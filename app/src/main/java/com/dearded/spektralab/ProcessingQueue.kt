package com.dearded.spektralab

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class JobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/**
 * One export: a snapshot of the photo and its whole edit, so the editor is
 * free to move on (another photo, more edits) while this one is developed.
 */
data class ProcessingJob(
    val id: Long,
    val uri: String,
    val name: String,
    val raw: Boolean,
    val editJson: String,
    val wbK: Double,
    val wbTint: Double,
    val format: ExportFormat,
    val size: ExportSize,
    val workingEdge: Int,
    val maxPixels: Long,
    /** The output's pixel count, for the time estimate. */
    val outPixels: Long,
    val state: JobState = JobState.QUEUED,
    val progress: Float = 0f,
    val phase: String = "",
    /** Seconds left for this job; -1 when unknown. */
    val etaSeconds: Int = -1,
    val message: String = "",
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
) {
    val active get() = state == JobState.QUEUED || state == JobState.RUNNING

    val label get() = when {
        format == ExportFormat.TIFF -> "16 位 TIFF"
        size == ExportSize.SHARE -> "JPEG · 分享尺寸"
        else -> "JPEG · 全尺寸"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("uri", uri).put("name", name).put("raw", raw).put("edit", editJson)
        .put("wbK", wbK).put("wbTint", wbTint).put("format", format.name).put("size", size.name)
        .put("edge", workingEdge).put("maxPixels", maxPixels).put("outPixels", outPixels)
        .put("state", state.name).put("message", message).put("attempts", attempts)
        .put("createdAt", createdAt).put("finishedAt", finishedAt)

    companion object {
        fun fromJson(o: JSONObject): ProcessingJob? = runCatching {
            ProcessingJob(
                id = o.getLong("id"), uri = o.getString("uri"), name = o.optString("name"),
                raw = o.optBoolean("raw"), editJson = o.getString("edit"),
                wbK = o.optDouble("wbK", 0.0), wbTint = o.optDouble("wbTint", 0.0),
                format = ExportFormat.valueOf(o.getString("format")), size = ExportSize.valueOf(o.getString("size")),
                workingEdge = o.optInt("edge"), maxPixels = o.optLong("maxPixels"), outPixels = o.optLong("outPixels"),
                state = JobState.valueOf(o.optString("state", "QUEUED")), message = o.optString("message"),
                attempts = o.optInt("attempts"), createdAt = o.optLong("createdAt"), finishedAt = o.optLong("finishedAt"),
            )
        }.getOrNull()
    }
}

/**
 * The 正在处理 list: exports run here, one at a time, in the process scope,
 * behind a foreground service that keeps HyperOS from killing the process.
 *
 * - Each job opens its own engine session from the source URI, so it never
 *   depends on what the editor has open.
 * - The list is persisted; a job the system killed resumes the next time the
 *   app starts, and a job killed twice is marked failed rather than retried
 *   forever (that is usually memory pressure).
 * - The editor comes first: before each engine call the worker waits until
 *   the user has not touched a control for a moment, so a drag is never stuck
 *   behind a full-size render.
 * - The time estimate is learned: seconds per output megapixel, per format,
 *   averaged over this phone's finished jobs.
 */
class ProcessingQueue(private val app: Application, private val host: EngineHost) {
    private val prefs = app.getSharedPreferences("processing", 0)
    private val loader = SourceLoader(app)
    // Declared before _jobs: load() fills it, and a later initializer would wipe it.
    private var noticed = HashSet<Long>()
    private val _jobs = MutableStateFlow(load())
    val jobs: StateFlow<List<ProcessingJob>> = _jobs

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var runner: kotlinx.coroutines.Deferred<String>? = null
    private val handleLock = Any()
    @Volatile private var runningHandle = 0L
    @Volatile private var cancelRequested = -1L
    private var notifiedAt = 0L

    /** Starts (or wakes) the worker. Call from the foreground: it may start the service. */
    fun start() {
        if (worker?.isActive != true) worker = host.scope.launch { loop() }
        wake.trySend(Unit)
    }

    fun enqueue(job: ProcessingJob) {
        _jobs.update { it + job }
        persist()
        start()
    }

    fun cancel(id: Long) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        when (job.state) {
            JobState.QUEUED -> { set(id) { it.copy(state = JobState.CANCELLED, finishedAt = now(), etaSeconds = 0) }; persist() }
            JobState.RUNNING -> {
                cancelRequested = id
                synchronized(handleLock) { if (runningHandle != 0L) NativeEngine.cancel(runningHandle) }
                runner?.cancel()
            }
            else -> Unit
        }
        refreshNotification(force = true)
    }

    fun retry(id: Long) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.active) return
        enqueue(job.copy(id = nextId(), state = JobState.QUEUED, progress = 0f, phase = "", message = "",
            attempts = 0, etaSeconds = -1, createdAt = now(), finishedAt = 0))
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.active } }
        persist()
    }

    fun nextId(): Long = max(System.currentTimeMillis(), (_jobs.value.maxOfOrNull { it.id } ?: 0L) + 1)

    // --- worker ---------------------------------------------------------------

    private suspend fun loop() {
        var serviceOn = false
        while (true) {
            val next = _jobs.value.firstOrNull { it.state == JobState.QUEUED }
            if (next == null) {
                if (serviceOn) { ExportService.stop(app); serviceOn = false }
                finishedNotice()
                wake.receive()
                continue
            }
            // One foreground period per busy stretch, however many jobs it holds.
            if (!serviceOn) { ExportService.start(app); serviceOn = true }
            runOne(next)
        }
    }

    private suspend fun runOne(queued: ProcessingJob) {
        val id = queued.id
        if (queued.attempts >= 2) {
            set(id) { it.copy(state = JobState.FAILED, finishedAt = now(), etaSeconds = 0,
                message = "处理时两次被系统中断，多半是内存不足；请调小工作尺寸后重试") }
            persist()
            return
        }
        val started = SystemClock.elapsedRealtime()
        val estimate = estimateSeconds(queued)
        set(id) { it.copy(state = JobState.RUNNING, attempts = it.attempts + 1, progress = 0.02f, phase = "准备", etaSeconds = estimate) }
        persist()   // a kill from here on counts as an attempt
        val ticker = host.scope.launch {
            while (isActive) {
                tick(id, started, estimate)
                delay(1000)
            }
        }
        cancelRequested = -1L
        try {
            val deferred = host.scope.async { process(queued) { phase, frac -> set(id) { it.copy(phase = phase, progress = max(it.progress, frac)) } } }
            runner = deferred
            val saved = deferred.await()
            learn(queued, (SystemClock.elapsedRealtime() - started) / 1000.0)
            set(id) { it.copy(state = JobState.DONE, progress = 1f, phase = "完成", etaSeconds = 0, finishedAt = now(), message = saved) }
            host.events.add("job $id done in ${(SystemClock.elapsedRealtime() - started) / 1000}s")
        } catch (e: CancellationException) {
            if (cancelRequested != id) throw e
            set(id) { it.copy(state = JobState.CANCELLED, etaSeconds = 0, finishedAt = now(), message = "已取消") }
        } catch (e: OutOfMemoryError) {
            set(id) { it.copy(state = JobState.FAILED, etaSeconds = 0, finishedAt = now(), message = "内存不足；请调小工作尺寸后重试") }
            host.events.add("job $id failed: out of memory")
        } catch (e: Throwable) {
            set(id) { it.copy(state = JobState.FAILED, etaSeconds = 0, finishedAt = now(), message = e.message ?: e.toString()) }
            host.events.add("job $id failed: ${e.message ?: e}")
        } finally {
            runner = null
            ticker.cancel()
            persist()
            refreshNotification(force = true)
        }
    }

    /** The editor's live renders go first; nothing here starts while a finger is moving. */
    private suspend fun yieldToEditor() {
        while (SystemClock.uptimeMillis() - host.lastInteraction < EDITOR_GRACE_MS) delay(150)
    }

    private suspend fun process(job: ProcessingJob, step: (String, Float) -> Unit): String {
        val edit = Edit.fromParams(JSONObject(job.editJson), Edit())
        val ready = host.start(edit.film, edit.paper)
        val uri = Uri.parse(job.uri)
        step("读取照片", 0.05f)
        val exif = withContext(Dispatchers.IO) { ExifCopy.read(app, uri) }
        // The film edge lays the frame on a canvas up to 1.6x its area, and that
        // canvas is one GPU buffer (128 MiB on Adreno 7xx): decode a little smaller.
        val maxPixels = if (edit.filmEdge && job.maxPixels > 0) job.maxPixels * 10 / 16 else job.maxPixels
        yieldToEditor()
        val handle: Long = if (job.raw) {
            val file = withContext(Dispatchers.IO) { loader.copyToCache(uri, job.name, "job") }
            step("解码 RAW", 0.12f)
            try {
                withContext(EngineThread.dispatcher) {
                    NativeEngine.trimMemory(ready.engine)
                    val params = Edit.openingParams(edit, inputSpace = "ProPhoto RGB", linear = true).toString()
                    val h = NativeEngine.openRaw(ready.engine, file.absolutePath, job.workingEdge, maxPixels, params, job.wbK, job.wbTint)
                    if (h == 0L) throw EngineException("打开失败：${NativeEngine.lastError()}")
                    synchronized(handleLock) { runningHandle = h }
                    h
                }
            } finally {
                file.delete()
            }
        } else {
            step("解码照片", 0.10f)
            val (bitmap, space) = withContext(Dispatchers.IO) { loader.decodePhoto(uri, job.workingEdge, maxPixels) }
            withContext(EngineThread.dispatcher) {
                NativeEngine.trimMemory(ready.engine)
                val params = Edit.openingParams(edit, inputSpace = space, linear = false).toString()
                val h = NativeEngine.openBitmap(ready.engine, bitmap, params)
                bitmap.recycle()
                if (h == 0L) throw EngineException("打开失败：${NativeEngine.lastError()}")
                synchronized(handleLock) { runningHandle = h }
                h
            }
        }
        try {
            val stem = "SpektraLab_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + "_" + (job.id % 1000)
            yieldToEditor()
            step("冲洗", 0.25f)
            val icc = File(ready.dir, "io/sRGB.icc").absolutePath
            when (job.format) {
                ExportFormat.TIFF -> {
                    val tmp = File(app.cacheDir, "$stem.tif")
                    try {
                        withContext(EngineThread.dispatcher) {
                            if (!NativeEngine.exportTiff(handle, tmp.absolutePath, icc, job.size.tier)) {
                                if (NativeEngine.lastWasCancelled()) throw CancellationException("cancelled")
                                throw EngineException("导出失败：${NativeEngine.lastError()}")
                            }
                        }
                        step("写入相册", 0.92f)
                        withContext(Dispatchers.IO) {
                            MediaSaver.save(app, "$stem.tif", "image/tiff") { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 20) } }
                        }
                    } finally {
                        tmp.delete()
                    }
                    return "$stem.tif"
                }
                ExportFormat.JPEG -> {
                    val bitmap: Bitmap = withContext(EngineThread.dispatcher) {
                        NativeEngine.renderBitmap(handle, job.size.tier) ?: run {
                            if (NativeEngine.lastWasCancelled()) throw CancellationException("cancelled")
                            throw EngineException("导出失败：${NativeEngine.lastError()}")
                        }
                    }
                    step("写入相册", 0.92f)
                    try {
                        withContext(Dispatchers.IO) {
                            // Compressed to a file first: EXIF is written in place, then copied out.
                            val tmp = File(app.cacheDir, "$stem.jpg")
                            try {
                                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                                exif.applyTo(tmp, bitmap.width, bitmap.height)
                                MediaSaver.save(app, "$stem.jpg", "image/jpeg") { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 20) } }
                            } finally {
                                tmp.delete()
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    return "$stem.jpg"
                }
            }
        } finally {
            withContext(NonCancellable + EngineThread.dispatcher) {
                synchronized(handleLock) {
                    NativeEngine.release(handle)
                    runningHandle = 0L
                }
            }
        }
    }

    // --- estimate, progress, notification -----------------------------------

    private fun tick(id: Long, startedAt: Long, estimate: Int) {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        val guess = (0.05 + 0.88 * (elapsed / max(1.0, estimate.toDouble()))).coerceAtMost(0.95)
        set(id) {
            if (it.state != JobState.RUNNING) it
            else it.copy(progress = max(it.progress, guess.toFloat()), etaSeconds = max(1, (estimate - elapsed).roundToInt()))
        }
        refreshNotification()
    }

    private fun estimateSeconds(job: ProcessingJob): Int {
        val mp = max(0.5, job.outPixels / 1e6)
        return (BASE_SECONDS + secondsPerMp(job.format) * mp).roundToInt()
    }

    private fun secondsPerMp(format: ExportFormat): Double =
        prefs.getFloat("spmp_${format.name}", if (format == ExportFormat.TIFF) 1.6f else 1.2f).toDouble()

    private fun learn(job: ProcessingJob, seconds: Double) {
        val mp = max(0.5, job.outPixels / 1e6)
        val measured = max(0.05, (seconds - BASE_SECONDS) / mp)
        val next = 0.6 * secondsPerMp(job.format) + 0.4 * measured
        prefs.edit().putFloat("spmp_${job.format.name}", next.toFloat()).apply()
    }

    private fun refreshNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - notifiedAt < 900) return
        notifiedAt = now
        val list = _jobs.value
        if (list.none { it.active }) return
        JobNotifier.post(app, JobNotifier.ID, JobNotifier.build(app, list))
    }


    /** One short "done" notice per batch, after the ongoing one goes away. */
    private fun finishedNotice() {
        val fresh = _jobs.value.filter { !it.active && it.id !in noticed && it.finishedAt > 0 }
        if (fresh.isEmpty()) return
        noticed.addAll(fresh.map { it.id })
        val done = fresh.count { it.state == JobState.DONE }
        val failed = fresh.count { it.state == JobState.FAILED }
        if (done == 0 && failed == 0) return
        JobNotifier.post(app, JobNotifier.DONE_ID, JobNotifier.finished(app, done, failed))
    }

    // --- state ------------------------------------------------------------------

    private fun set(id: Long, f: (ProcessingJob) -> ProcessingJob) =
        _jobs.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun persist() {
        // Finished jobs are kept for the list, newest 30.
        val list = _jobs.value
        val keep = list.filter { it.active } + list.filter { !it.active }.sortedByDescending { it.finishedAt }.take(30)
        val arr = JSONArray()
        keep.sortedBy { it.id }.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("jobs", arr.toString()).apply()
    }

    private fun load(): List<ProcessingJob> = runCatching {
        val arr = JSONArray(prefs.getString("jobs", "[]"))
        (0 until arr.length()).mapNotNull { ProcessingJob.fromJson(arr.getJSONObject(it)) }
            // A job that was running when the process died goes back in line.
            .map { if (it.state == JobState.RUNNING) it.copy(state = JobState.QUEUED) else it }
            .also { list -> noticedInit(list) }
    }.getOrDefault(emptyList())

    private fun noticedInit(list: List<ProcessingJob>) {
        noticed = HashSet(list.filter { !it.active }.map { it.id })
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val BASE_SECONDS = 2.0
        private const val EDITOR_GRACE_MS = 1200L

        /** Seconds until every active job is done: the running one's own estimate plus each queued one's. */
        fun remainingSeconds(jobs: List<ProcessingJob>, perMpJpeg: Double = 1.2): Int {
            var total = 0
            for (j in jobs) when (j.state) {
                JobState.RUNNING -> total += max(0, j.etaSeconds)
                JobState.QUEUED -> total += (BASE_SECONDS + perMpJpeg * max(0.5, j.outPixels / 1e6)).roundToInt()
                else -> Unit
            }
            return total
        }

        fun formatEta(seconds: Int): String = when {
            seconds <= 0 -> "即将完成"
            seconds < 60 -> "$seconds 秒"
            else -> "${seconds / 60} 分 ${min(59, seconds % 60)} 秒"
        }
    }
}
