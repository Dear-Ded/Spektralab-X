package com.dearded.spektralab

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The process. Owns the one [EngineHost]. */
class SpektraLabApp : Application() {
    val host: EngineHost by lazy { EngineHost(this) }

    override fun onCreate() {
        super.onCreate()
        host.startUp()
        // A queue paused by the foreground-service limit continues once an
        // activity is visible again (a foreground service may start then).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = host.queue.onAppForeground()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

/** The GPU device behind [engine] is lost (VkResult -4): only a new engine renders again. */
class DeviceLostException(val engine: Long, detail: String) : Exception("GPU 被系统重置：$detail")

/**
 * Process-lifetime owner of the native engine: its resources, setup caches and
 * compiled Vulkan pipelines. It outlives any one editor, so leaving and
 * re-entering the app while the process lives reuses a warm engine, and an
 * export started in an editor finishes after that editor is gone.
 *
 * Sessions (one open photo each) still belong to the editor that opened them.
 */
class EngineHost(private val app: Application) {
    /** For work that must outlive an editor, such as an export. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val events = EventLog()
    val thermal = ThermalMonitor(app)
    /** The 正在处理 list. Process-wide, so it outlives every editor. */
    val queue: ProcessingQueue by lazy { ProcessingQueue(app, this) }
    /** Uptime of the last slider move or edit; background jobs wait for a quiet moment. */
    @Volatile var lastInteraction = 0L

    /**
     * Pixels of the export render in flight on ExportThread, 0 when none.
     * Two large renders at once double the GPU pool's peak (upstream issue
     * #62: a thumbnail render on top of a full one), so the editor's sharp
     * preview waits for a large one ([yieldToExport]); its live tier does not.
     */
    @Volatile var exportRenderPixels = 0L

    /**
     * Editor, before its 3400 px preview render: waits while an export of
     * more than [LARGE_EXPORT_PIXELS] is rendering, for at most [maxMs] (the
     * preview then renders anyway). Returns at once when none is.
     */
    suspend fun yieldToExport(maxMs: Long = 20_000L) {
        val until = android.os.SystemClock.uptimeMillis() + maxMs
        while (exportRenderPixels > LARGE_EXPORT_PIXELS && android.os.SystemClock.uptimeMillis() < until) {
            kotlinx.coroutines.delay(100)
        }
    }

    /**
     * Bumped each time [recover] replaces the engine. A photo open on the old
     * engine cannot render again: its editor releases it, calls [start] for
     * the new engine and reopens the photo.
     */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation

    /** How the previous process ended, as [ExitDiagnostics] read it. */
    @Volatile var lastExit = ExitKind.NONE
        private set
    /** The previous process was killed for memory (lmkd or the memory limiter). */
    val lastExitWasMemory: Boolean get() = lastExit == ExitKind.MEMORY
    /** Completes once [lastExit] is known; the queue waits for it before its first job. */
    val diagnosed = CompletableDeferred<Unit>()

    class Ready(
        val engine: Long,
        val dir: File,
        val catalog: Catalog,
        val deviceJson: String,
        val capabilitiesJson: String,
        /** True when an earlier editor already started it. */
        val reused: Boolean,
    ) {
        fun again() = Ready(engine, dir, catalog, deviceJson, capabilitiesJson, reused = true)
    }

    private val mutex = Mutex()
    @Volatile private var ready: Ready? = null
    // The stock pair of the first start, warmed up again by a recovered engine.
    private var stocks = "" to ""

    /** Application.onCreate: diagnostics of the last exit, then any jobs that process left. */
    fun startUp() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    lastExit = runCatching { ExitDiagnostics.record(app, events) }.getOrDefault(ExitKind.NONE)
                    // Where the processing notice can show (Live Updates, Xiaomi focus), for the report.
                    JobNotifier.logStatus(app, events)
                }
            } finally {
                diagnosed.complete(Unit)
            }
            // Started even with nothing queued: the queue first clears what a
            // killed process left half-written, then waits.
            queue.start()
        }
        app.registerComponentCallbacks(trimCallbacks)
    }

    /**
     * Process-wide onTrimMemory. The editor trims when its activity hides
     * (EditorViewModel.trimMemory), but a process the queue keeps alive with
     * no editor open had nobody doing it: here the engine's idle GPU planes
     * go back as soon as the app is out of sight or memory runs low. Only
     * idle buffers are freed, so a running export is not touched, and a trim
     * right after the editor's frees nothing.
     */
    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
            val engine = ready?.engine ?: return
            scope.launch {
                // Under the mutex: a recovery cannot destroy this engine mid-call.
                val freed = mutex.withLock {
                    if (ready?.engine != engine) 0L
                    else withContext(EngineThread.dispatcher) { NativeEngine.trimMemory(engine) }
                }
                if (freed > 0) events.add("trim memory level $level (app): ${freed shr 20} MB freed")
            }
        }
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    /** Starts the engine once per process; later calls return the same one. */
    suspend fun start(film: String, paper: String): Ready = mutex.withLock {
        ready?.let { r -> return@withLock r.again() }
        stocks = film to paper
        // NonCancellable: leaving during the first start must not drop a created
        // engine (it leaked, and the next editor created a second one).
        withContext(NonCancellable + EngineThread.dispatcher) { build(film, paper) }
    }

    /**
     * Replaces [failed] after its GPU device was lost (zhaoye's recoverEngine()):
     * nothing on a lost VkDevice works again, so the engine is destroyed and a
     * new one created. When another caller already replaced it, returns the
     * current one. The old engine is only retired while a session is still
     * open on it; that session's release frees it.
     */
    suspend fun recover(failed: Long): Ready = mutex.withLock {
        ready?.let { r -> if (r.engine != failed) return@withLock r.again() }
        val old = ready
        ready = null
        events.add("GPU device lost: replacing the engine")
        val next = withContext(NonCancellable + EngineThread.dispatcher) {
            if (old != null) NativeEngine.destroy(old.engine)
            build(stocks.first, stocks.second)
        }
        _generation.value += 1
        next
    }

    // Engine thread, under the mutex.
    private fun build(film: String, paper: String): Ready {
        val dir = EngineAssets.install(app)
        // Before create: the probe runs on a VkDevice of its own.
        val device = StorageProbe.annotate(app, NativeEngine.deviceInfo(), events)
        val catalog = Catalog.load(dir)
        val tmp = File(app.cacheDir, "engine-tmp").apply { mkdirs() }
        val handle = NativeEngine.create(dir.absolutePath, tmp.absolutePath)
        if (handle == 0L) {
            throw EngineException(
                "引擎启动失败：${NativeEngine.lastError()}\n\nGPU：${DeviceProfile.summary(DeviceProfile.gpu(device))}"
            )
        }
        // The default stock pair's tables and every compute pipeline now, so
        // the first photo's first preview does not pay for them. Not fatal:
        // open would do the same work.
        if (film.isNotEmpty() && !NativeEngine.warmUp(handle, film, paper)) events.add("warm up failed: ${NativeEngine.lastError()}")
        return Ready(handle, dir, catalog, device, NativeEngine.capabilities(handle), reused = false).also { ready = it }
    }

    companion object {
        /** An export this large makes the editor's sharp preview wait ([yieldToExport]): 12 MP and up. */
        const val LARGE_EXPORT_PIXELS = 12_000_000L
    }
}

/** How the previous process ended, for the queue and the keep-alive hint. */
enum class ExitKind {
    /** Nothing new recorded (first start, Android 10, or already seen). */
    NONE,
    /** The memory limiter (Android 17) or lmkd: a smaller retry helps, battery settings do not. */
    MEMORY,
    /** Ended by the system for another reason: the freezer, a signal, a vendor kill. */
    SYSTEM,
    /** The user removed it, a crash, an update: nothing to hint at. */
    OTHER,
}

/**
 * Why earlier processes ended (Android 11+), logged once each at start-up.
 * Android 17's per-app memory limiter reports REASON_OTHER with
 * "MemoryLimiter:AnonSwap" in the description; lmkd kills are
 * REASON_LOW_MEMORY; the app freezer's are REASON_FREEZER. HyperOS's own
 * background kills mostly arrive as REASON_SIGNALED or REASON_OTHER with a
 * vendor description. Without this all of them look like "the app vanished"
 * in a report.
 *
 * Counts per kind are kept across starts and pinned to the top of the
 * diagnostics report with the newest exit, so the rolling log cannot push
 * them out.
 */
object ExitDiagnostics {
    private val COUNTED = listOf(
        "memory_limiter" to "memory limiter", "low_memory" to "low memory", "freezer" to "freezer",
        "system_export" to "system kill during export",
    )

    /** Logs the exits not seen before; returns how the newest one ended. */
    fun record(context: Context, events: EventLog): ExitKind {
        if (Build.VERSION.SDK_INT < 30) return ExitKind.NONE
        val prefs = context.getSharedPreferences("diagnostics", 0)
        val am = context.getSystemService(ActivityManager::class.java) ?: return ExitKind.NONE
        val seen = prefs.getLong("exit_seen", 0L)
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 8) }
            .getOrDefault(emptyList()).filter { it.timestamp > seen }.sortedBy { it.timestamp }
        if (exits.isEmpty()) {
            prefs.getString("exit_last", null)?.let { events.pin("last exit", "last $it") }
            pinCounts(prefs, events)
            return ExitKind.NONE
        }
        val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
        val edit = prefs.edit()
        val counts = HashMap<String, Int>()
        var last = ""
        for (e in exits) {
            last = "exit ${clock.format(Date(e.timestamp))}: ${describe(e)}" +
                (if (duringExport(e)) " during export" else "") +
                ", importance ${e.importance}, rss ${e.rss / 1024} MB, pss ${e.pss / 1024} MB"
            events.add(last)
            counter(e)?.let { key -> counts[key] = (counts[key] ?: prefs.getInt("exit_count_$key", 0)) + 1 }
        }
        for ((key, n) in counts) edit.putInt("exit_count_$key", n)
        edit.putLong("exit_seen", exits.last().timestamp).putString("exit_last", last).commit()
        events.pin("last exit", "last $last")
        pinCounts(prefs, events)
        return kind(exits.last())
    }

    private fun pinCounts(prefs: android.content.SharedPreferences, events: EventLog) {
        val counts = COUNTED.joinToString(" · ") { (key, label) -> "$label ${prefs.getInt("exit_count_$key", 0)}" }
        events.pin("exit counts", "exits so far: $counts")
    }

    @RequiresApi(30)
    private fun memoryLimiter(e: ApplicationExitInfo) = e.description.orEmpty().contains("MemoryLimiter")

    /** ExportService was foreground, so an export was running. */
    @RequiresApi(30)
    private fun duringExport(e: ApplicationExitInfo) =
        e.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE

    @RequiresApi(30)
    private fun kind(e: ApplicationExitInfo): ExitKind = when {
        memoryLimiter(e) || e.reason == ApplicationExitInfo.REASON_LOW_MEMORY -> ExitKind.MEMORY
        e.reason == ApplicationExitInfo.REASON_FREEZER ||
            e.reason == ApplicationExitInfo.REASON_SIGNALED ||
            e.reason == ApplicationExitInfo.REASON_OTHER ||
            e.reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
            e.reason == ApplicationExitInfo.REASON_DEPENDENCY_DIED -> ExitKind.SYSTEM
        else -> ExitKind.OTHER
    }

    /** The count this exit adds to, if any. */
    @RequiresApi(30)
    private fun counter(e: ApplicationExitInfo): String? = when {
        memoryLimiter(e) -> "memory_limiter"
        e.reason == ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        e.reason == ApplicationExitInfo.REASON_FREEZER -> "freezer"
        kind(e) == ExitKind.SYSTEM && duringExport(e) -> "system_export"
        else -> null
    }

    @RequiresApi(30)
    private fun describe(e: ApplicationExitInfo): String {
        val d = e.description.orEmpty()
        val what = when {
            memoryLimiter(e) -> "memory limiter"
            e.reason == ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory killer"
            e.reason == ApplicationExitInfo.REASON_FREEZER -> "app freezer"
            e.reason == ApplicationExitInfo.REASON_CRASH -> "crash"
            e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            e.reason == ApplicationExitInfo.REASON_ANR -> "ANR"
            e.reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
            e.reason == ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
            e.reason == ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
            e.reason == ApplicationExitInfo.REASON_SIGNALED -> "signal ${e.status}"
            e.reason == ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
            e.reason == ApplicationExitInfo.REASON_USER_STOPPED -> "removed from recents"
            e.reason == ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
            e.reason == ApplicationExitInfo.REASON_OTHER -> "killed by the system"
            else -> "reason ${e.reason}"
        }
        return if (d.isEmpty()) what else "$what ($d)"
    }
}

/**
 * Peak memory as the kernel counts it for the memory limiter and lmkd:
 * VmHWM (peak RSS) from /proc/self/status, not PSS, which shares pages out
 * and reads low exactly when a large export peaks.
 */
object ProcMemory {
    /** Resets VmHWM to the current RSS (clear_refs 5); false where the kernel or SELinux refuses. */
    fun resetPeak(): Boolean = runCatching { File("/proc/self/clear_refs").writeText("5"); true }.getOrDefault(false)

    /** A /proc/self/status field in kB ("VmHWM", "VmRSS"), -1 when unreadable. */
    fun statusKb(key: String): Long = runCatching {
        File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("$key:") } }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: -1L
    }.getOrDefault(-1L)
}
