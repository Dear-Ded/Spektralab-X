package com.dearded.spektralab

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
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

    /** The previous process was killed for memory (lmkd or the memory limiter). */
    @Volatile var lastExitWasMemory = false
        private set
    /** Completes once [lastExitWasMemory] is known; the queue waits for it before its first job. */
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
    private var ready: Ready? = null
    // The stock pair of the first start, warmed up again by a recovered engine.
    private var stocks = "" to ""

    /** Application.onCreate: diagnostics of the last exit, then any jobs that process left. */
    fun startUp() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    lastExitWasMemory = runCatching { ExitDiagnostics.record(app, events) }.getOrDefault(false)
                }
            } finally {
                diagnosed.complete(Unit)
            }
            // Started even with nothing queued: the queue first clears what a
            // killed process left half-written, then waits.
            queue.start()
        }
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

/**
 * Why earlier processes ended (Android 11+), logged once each at start-up.
 * Android 17's per-app memory limiter says "MemoryLimiter:AnonSwap" in the
 * description; lmkd kills are REASON_LOW_MEMORY. Without this both look like
 * "the app vanished" in a report.
 */
object ExitDiagnostics {
    /** Logs the exits not seen before; true when the newest was a memory kill. */
    fun record(context: Context, events: EventLog): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val prefs = context.getSharedPreferences("diagnostics", 0)
        val seen = prefs.getLong("exit_seen", 0L)
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 8) }
            .getOrDefault(emptyList()).filter { it.timestamp > seen }.sortedBy { it.timestamp }
        if (exits.isEmpty()) return false
        val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
        for (e in exits) {
            events.add("exit ${clock.format(Date(e.timestamp))}: ${describe(e)}, importance ${e.importance}, " +
                "rss ${e.rss / 1024} MB, pss ${e.pss / 1024} MB")
        }
        prefs.edit().putLong("exit_seen", exits.last().timestamp).apply()
        return memoryKill(exits.last())
    }

    @RequiresApi(30)
    private fun memoryKill(e: ApplicationExitInfo): Boolean =
        e.reason == ApplicationExitInfo.REASON_LOW_MEMORY || e.description.orEmpty().contains("MemoryLimiter")

    @RequiresApi(30)
    private fun describe(e: ApplicationExitInfo): String {
        val d = e.description.orEmpty()
        val what = when {
            d.contains("MemoryLimiter") -> "memory limiter"
            e.reason == ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory killer"
            e.reason == ApplicationExitInfo.REASON_CRASH -> "crash"
            e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            e.reason == ApplicationExitInfo.REASON_ANR -> "ANR"
            e.reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
            e.reason == ApplicationExitInfo.REASON_SIGNALED -> "signal ${e.status}"
            e.reason == ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
            e.reason == ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
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
