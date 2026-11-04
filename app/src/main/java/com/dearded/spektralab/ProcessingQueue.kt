package com.dearded.spektralab

import android.app.Application
import android.content.Intent
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

/** The pre-save check found a frame no photo produces ([what]: 全黑, 全白, 纯色); nothing was saved. */
class BlankFrameException(val what: String) : Exception("导出画面异常（$what），未保存")

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
    /** The paper frame (FrameSpec JSON, "" = none) and the film's name for its caption. */
    val frameJson: String = "",
    val filmLabel: String = "",
    /** A JPEG rendered and tagged in Display P3 rather than sRGB. */
    val wideGamut: Boolean = false,
    /** Highlight recovery the editor opened the phone photo with. */
    val highlightStops: Float = 0f,
    /** Layer 2 (Look JSON, "" = none), applied while the export is packed. */
    val lookJson: String = "",
    val state: JobState = JobState.QUEUED,
    val progress: Float = 0f,
    val phase: String = "",
    /** Seconds left for this job; -1 when unknown. */
    val etaSeconds: Int = -1,
    val message: String = "",
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    /** The source copied into app storage because [uri]'s grant could not be kept ("" = read [uri]). */
    val sourcePath: String = "",
    /** A persistable read grant on [uri] is held for this job. */
    val persisted: Boolean = false,
    /** False while the grant or copy is being made; the worker skips the job until then. */
    val secured: Boolean = true,
    /** The pending gallery entry being written, so a kill mid-save is cleaned up or found saved. */
    val pendingUri: String = "",
    /** The source's GPS tags ([ExifLocation.encode]), read at enqueue when 导出保留拍摄位置 is on; "" = none. */
    val location: String = "",
    /**
     * 极致渲染: the source's own resolution (workingEdge 0, only the device's
     * pixel cap applies), the whole-frame full tier, JPEG at quality 98, and
     * pauses while the phone is near throttling. Device loss and the frame
     * check step it down as any job.
     */
    val extreme: Boolean = false,
) {
    val active get() = state == JobState.QUEUED || state == JobState.RUNNING

    val label get() = when {
        extreme -> "极致渲染 · 全分辨率"
        format == ExportFormat.TIFF -> "16 位 TIFF"
        size == ExportSize.SHARE -> "JPEG · 分享尺寸"
        else -> "JPEG · 全尺寸"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("uri", uri).put("name", name).put("raw", raw).put("edit", editJson)
        .put("wbK", wbK).put("wbTint", wbTint).put("format", format.name).put("size", size.name)
        .put("edge", workingEdge).put("maxPixels", maxPixels).put("outPixels", outPixels)
        .put("frame", frameJson).put("filmLabel", filmLabel).put("wide", wideGamut).put("hl", highlightStops.toDouble()).put("look", lookJson)
        .put("state", state.name).put("message", message).put("attempts", attempts)
        .put("createdAt", createdAt).put("finishedAt", finishedAt)
        .put("src", sourcePath).put("persisted", persisted).put("secured", secured).put("pending", pendingUri)
        .put("location", location).put("extreme", extreme)

    companion object {
        fun fromJson(o: JSONObject): ProcessingJob? = runCatching {
            ProcessingJob(
                id = o.getLong("id"), uri = o.getString("uri"), name = o.optString("name"),
                raw = o.optBoolean("raw"), editJson = o.getString("edit"),
                wbK = o.optDouble("wbK", 0.0), wbTint = o.optDouble("wbTint", 0.0),
                format = ExportFormat.valueOf(o.getString("format")), size = ExportSize.valueOf(o.getString("size")),
                workingEdge = o.optInt("edge"), maxPixels = o.optLong("maxPixels"), outPixels = o.optLong("outPixels"),
                frameJson = o.optString("frame"), filmLabel = o.optString("filmLabel"),
                wideGamut = o.optBoolean("wide"),
                highlightStops = o.optDouble("hl", 0.0).toFloat(),
                lookJson = o.optString("look"),
                state = JobState.valueOf(o.optString("state", "QUEUED")), message = o.optString("message"),
                attempts = o.optInt("attempts"), createdAt = o.optLong("createdAt"), finishedAt = o.optLong("finishedAt"),
                sourcePath = o.optString("src"), persisted = o.optBoolean("persisted"),
                secured = o.optBoolean("secured", true), pendingUri = o.optString("pending"),
                location = o.optString("location"), extreme = o.optBoolean("extreme"),
            )
        }.getOrNull()
    }
}

/**
 * The 正在处理 list: exports run here, one at a time, in the process scope,
 * behind a foreground service that keeps HyperOS from killing the process.
 *
 * - Each job opens its own engine session from the source, so it never
 *   depends on what the editor has open. At enqueue the source is secured:
 *   a persistable grant where the URI allows one, else a copy in app storage,
 *   so a job resumed in a new process can still read it.
 * - The list is persisted; a job the system killed resumes the next time the
 *   app starts, and a job killed twice is marked failed rather than retried
 *   forever (that is usually memory pressure). After a memory kill the retry
 *   decodes one size smaller.
 * - A lost GPU device (VkResult -4) gets a new engine and one retry a size down.
 * - Files reach the gallery only checked and whole (MediaSaver); what a kill
 *   left half-written is removed when the queue next starts.
 * - The foreground service's time limit (Android 15+) and a refused start
 *   pause the queue; it continues when one of our activities is visible.
 * - The editor comes first: before each engine call the worker waits until
 *   the user has not touched a control for a moment, so a drag is never stuck
 *   behind a full-size render.
 * - The time estimate is learned: seconds per output megapixel, per format,
 *   averaged over this phone's finished jobs.
 */
class ProcessingQueue(private val app: Application, private val host: EngineHost) {
    private val prefs = app.getSharedPreferences("processing", 0)
    private val loader = SourceLoader(app)
    // Declared before _jobs: load() fills them, and a later initializer would wipe them.
    private var noticed = HashSet<Long>()
    private var interrupted = emptySet<Long>()
    private val _jobs = MutableStateFlow(load())
    val jobs: StateFlow<List<ProcessingJob>> = _jobs

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var runner: kotlinx.coroutines.Deferred<String>? = null
    private val handleLock = Any()
    private val grantLock = Any()
    @Volatile private var runningHandle = 0L
    @Volatile private var cancelRequested = -1L
    @Volatile private var pauseRequested = -1L
    private var notifiedAt = 0L
    // Main thread, like the loop that reads them.
    private var serviceOn = false
    private var paused = false
    private var swept = false

    private val sourcesDir = File(app.filesDir, "job-sources")
    private val exportDir = File(app.cacheDir, "export")

    /** Starts (or wakes) the worker. */
    fun start() {
        if (worker?.isActive != true) worker = host.scope.launch { loop() }
        wake.trySend(Unit)
    }

    fun enqueue(job: ProcessingJob) {
        _jobs.update { it + job.copy(secured = false) }
        persist()
        host.scope.launch {
            val secured = withContext(Dispatchers.IO) {
                // From the URI as the user picked it, while its grant is fresh: a
                // copy in app storage would carry the GPS tags MediaStore redacted.
                val location = if (LocationSetting.enabled(app)) ExifLocation.encode(ExifCopy.readLocation(app, Uri.parse(job.uri))) else ""
                secure(job).copy(location = location)
            }
            set(job.id) { it.copy(sourcePath = secured.sourcePath, persisted = secured.persisted, location = secured.location, secured = true) }
            persist()
            start()
        }
    }

    fun cancel(id: Long) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        when (job.state) {
            JobState.QUEUED -> { set(id) { it.copy(state = JobState.CANCELLED, finishedAt = now(), etaSeconds = 0) }; persist(); releaseSourcesLater() }
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
            attempts = 0, etaSeconds = -1, createdAt = now(), finishedAt = 0, pendingUri = ""))
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.active } }
        persist()
        releaseSourcesLater()
    }

    fun nextId(): Long = max(System.currentTimeMillis(), (_jobs.value.maxOfOrNull { it.id } ?: 0L) + 1)

    /**
     * Main thread. The foreground service hit its time limit or could not
     * become foreground: the running job goes back in line (not counted as an
     * attempt), the list is written synchronously, and nothing more runs until
     * [onAppForeground]. [serviceStopped]: the service stops itself (a timeout).
     */
    fun pause(why: String, serviceStopped: Boolean) {
        if (serviceStopped) serviceOn = false
        if (paused) return
        paused = true
        host.events.add("queue paused: $why")
        _jobs.value.firstOrNull { it.state == JobState.RUNNING }?.let { r ->
            pauseRequested = r.id
            set(r.id) { requeued(it) }
            synchronized(handleLock) { if (runningHandle != 0L) NativeEngine.cancel(runningHandle) }
            runner?.cancel()
        }
        persist(sync = true)
        JobNotifier.cancel(app, JobNotifier.ID)
        val waiting = _jobs.value.count { it.active }
        if (waiting > 0) JobNotifier.post(app, JobNotifier.PAUSED_ID, JobNotifier.paused(app, waiting))
        wake.trySend(Unit)
    }

    /** Main thread: one of our activities is visible, so a foreground service may start again. */
    fun onAppForeground() {
        if (paused) {
            paused = false
            JobNotifier.cancel(app, JobNotifier.PAUSED_ID)
            host.events.add("queue resumed")
        }
        if (_jobs.value.any { it.active }) start()
    }

    // --- worker ---------------------------------------------------------------

    private suspend fun loop() {
        if (!swept) {
            swept = true
            host.diagnosed.await()
            withContext(Dispatchers.IO) { recoverInterrupted(); sweepFiles() }
        }
        while (true) {
            val next = if (paused) null else _jobs.value.firstOrNull { it.state == JobState.QUEUED && it.secured }
            if (next == null) {
                if (serviceOn) { ExportService.stop(app); serviceOn = false }
                if (!paused) finishedNotice()
                wake.receive()
                continue
            }
            // One foreground period per busy stretch, however many jobs it holds.
            if (!serviceOn) {
                // Android 12+ refuses one while the app is in the background (a
                // process the system started for something else): wait for an
                // activity (onAppForeground) rather than run unprotected.
                if (!ExportService.start(app)) { pause("foreground service refused", serviceStopped = true); continue }
                serviceOn = true
            }
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
        if (queued.pendingUri.isNotEmpty() && withContext(Dispatchers.IO) { settle(queued) }) { persist(); return }
        val started = SystemClock.elapsedRealtime()
        val estimate = estimateSeconds(queued)
        set(id) { it.copy(state = JobState.RUNNING, attempts = it.attempts + 1, progress = 0.02f, phase = "准备", etaSeconds = estimate) }
        persist(sync = true)   // a kill from here on counts as an attempt
        val ticker = host.scope.launch {
            while (isActive) {
                tick(id, started, estimate)
                delay(1000)
            }
        }
        cancelRequested = -1L
        pauseRequested = -1L
        val rssBefore = ProcMemory.statusKb("VmRSS")
        val peakReset = ProcMemory.resetPeak()
        try {
            val deferred = host.scope.async { runRecovering(queued) { phase, frac -> set(id) { it.copy(phase = phase, progress = max(it.progress, frac)) } } }
            runner = deferred
            val saved = deferred.await()
            learn(queued, (SystemClock.elapsedRealtime() - started) / 1000.0)
            set(id) { it.copy(state = JobState.DONE, progress = 1f, phase = "完成", etaSeconds = 0, finishedAt = now(), message = saved, pendingUri = "") }
            host.events.add("job $id done in ${(SystemClock.elapsedRealtime() - started) / 1000}s, ${peakText(rssBefore, peakReset)}")
        } catch (e: CancellationException) {
            when {
                // pause() already put it back in line and wrote the list.
                pauseRequested == id -> Unit
                cancelRequested == id -> set(id) { it.copy(state = JobState.CANCELLED, etaSeconds = 0, finishedAt = now(), message = "已取消", pendingUri = "") }
                else -> throw e
            }
        } catch (e: OutOfMemoryError) {
            set(id) { it.copy(state = JobState.FAILED, etaSeconds = 0, finishedAt = now(), message = "内存不足；请调小工作尺寸后重试", pendingUri = "") }
            host.events.add("job $id failed: out of memory, ${peakText(rssBefore, peakReset)}")
        } catch (e: Throwable) {
            set(id) { it.copy(state = JobState.FAILED, etaSeconds = 0, finishedAt = now(), message = failureMessage(e), pendingUri = "") }
            host.events.add("job $id failed: ${e.message ?: e}, ${peakText(rssBefore, peakReset)}")
        } finally {
            runner = null
            ticker.cancel()
            persist()
            releaseSourcesLater()
            refreshNotification(force = true)
        }
    }

    /**
     * A paused job back in line: the interruption was the system's, not the
     * job's. Its pending entry stays recorded: a pause that landed just after
     * publishing is found saved by the next run ([settle]).
     */
    private fun requeued(job: ProcessingJob) = job.copy(state = JobState.QUEUED, attempts = max(0, job.attempts - 1),
        progress = 0f, phase = "", etaSeconds = -1, message = "")

    private fun failureMessage(e: Throwable): String = when (e) {
        is SecurityException -> "无法再读取原图（访问权限已失效）；请重新打开照片后导出"
        else -> e.message ?: e.toString()
    }

    /**
     * zhaoye's recoverEngine(): nothing on a lost VkDevice works again, so the
     * engine is replaced and the job tried once more a size down (the GPU
     * watchdog fires on the longest submits, which a smaller frame shortens).
     */
    private suspend fun runRecovering(job: ProcessingJob, step: (String, Float) -> Unit): String {
        try {
            return process(job, step)
        } catch (e: Exception) {
            if (e !is DeviceLostException && e !is BlankFrameException) throw e
            host.events.add("job ${job.id}: ${e.message}")
            if (e is DeviceLostException) {
                step("GPU 被重置，正在重试", 0.05f)
                host.recover(e.engine)
            } else {
                // A blank frame is a render that went wrong without saying so;
                // the same smaller retry, on the same engine.
                step("画面异常，正在重试", 0.05f)
            }
            val smaller = smaller(job)
            set(job.id) { it.copy(workingEdge = smaller.workingEdge, maxPixels = smaller.maxPixels) }
            persist()
            host.events.add("job ${job.id}: retrying at edge ${smaller.workingEdge}, ${smaller.maxPixels / 1_000_000} MP")
            try {
                return process(smaller, step)
            } catch (again: DeviceLostException) {
                host.events.add("job ${job.id}: device lost again: ${again.message}")
                host.recover(again.engine)   // the editor still needs a working engine
                throw EngineException("GPU 连续两次被系统重置；请调小工作尺寸后重试")
            } catch (again: BlankFrameException) {
                host.events.add("job ${job.id}: blank again: ${again.message}")
                throw EngineException("导出画面两次异常（${again.what}），未保存；请调小工作尺寸后重试")
            }
        }
    }

    /** One working size down, and 60 % of the pixel cap. */
    private fun smaller(job: ProcessingJob): ProcessingJob {
        val edge = DeviceProfile.EDGES.filter { job.workingEdge <= 0 || it < job.workingEdge }.maxOrNull()
            ?: DeviceProfile.EDGES.first()
        return job.copy(workingEdge = edge, maxPixels = if (job.maxPixels > 0) job.maxPixels * 6 / 10 else 0L)
    }

    /**
     * The editor's live renders go first; nothing here starts while a finger is
     * moving. [cool]: 极致渲染 also waits, up to [THERMAL_WAIT_MS], while the
     * phone is near throttling, so its long renders do not start hot.
     */
    private suspend fun yieldToEditor(cool: Boolean = false) {
        while (SystemClock.uptimeMillis() - host.lastInteraction < EDITOR_GRACE_MS) delay(150)
        if (!cool) return
        val until = SystemClock.uptimeMillis() + THERMAL_WAIT_MS
        while (host.thermal.headroom() > HOT_HEADROOM && SystemClock.uptimeMillis() < until) delay(1000)
    }

    /** Engine thread, straight after a failed call: what the failure means for the job. */
    private fun engineFailure(engine: Long, what: String): Nothing {
        if (NativeEngine.lastWasCancelled()) throw CancellationException("cancelled")
        if (NativeEngine.lastWasDeviceLost()) throw DeviceLostException(engine, NativeEngine.lastError())
        val error = NativeEngine.lastError()
        // The pre-save check (blank_frame): nothing was written to the gallery.
        if (error.startsWith("export check:")) throw BlankFrameException(blankReason(error))
        throw EngineException("$what：$error")
    }

    private fun blankReason(error: String): String = when {
        "black" in error -> "全黑"
        "white" in error -> "全白"
        "solid colour" in error -> "纯色"
        else -> "无像素"
    }

    /** Records the pending gallery entry before a byte is written. Any thread. */
    private fun markPending(id: Long, uri: Uri) {
        set(id) { it.copy(pendingUri = uri.toString()) }
        persist(sync = true)
    }

    private suspend fun process(job: ProcessingJob, step: (String, Float) -> Unit): String {
        val parsed = Edit.fromParams(JSONObject(job.editJson), Edit())
        val ready = host.start(parsed.film, parsed.paper)
        val engine = ready.engine
        val edit = ready.catalog.normalize(parsed)
        // The copy made at enqueue when the URI's grant could not be kept.
        val local = job.sourcePath.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
        val uri = if (local != null) Uri.fromFile(local) else Uri.parse(job.uri)
        step("读取照片", 0.05f)
        val exif = withContext(Dispatchers.IO) { ExifCopy.read(app, uri) }.withLocation(ExifLocation.decode(job.location))
        // The film edge lays the frame on a canvas up to 1.6x its area, and that
        // canvas is one GPU buffer (128 MiB on Adreno 7xx): decode a little smaller.
        val maxPixels = if (edit.filmEdge && job.maxPixels > 0) job.maxPixels * 10 / 16 else job.maxPixels
        // TIFF carries the bundled sRGB profile, so only a JPEG goes wide.
        val wide = job.wideGamut && job.format == ExportFormat.JPEG
        val outSpace = if (wide) "Display P3" else "sRGB"
        yieldToEditor()
        val handle: Long = if (job.raw) {
            // LibRaw reads a path: the kept copy itself, or the URI's descriptor
            // (/proc/self/fd), falling back to a cache copy (SourceLoader.rawInput).
            // Assigned inside the IO hop, so a cancel as it returns still closes it.
            var input: RawInput? = null
            try {
                if (local == null) withContext(Dispatchers.IO) { input = loader.rawInput(uri, job.name, "job") }
                val path = local?.absolutePath ?: input!!.path
                step("解码 RAW", 0.12f)
                // trimMemory is engine-wide (EngineThread); the session is the export's own.
                withContext(EngineThread.dispatcher) { NativeEngine.trimMemory(engine) }
                withContext(ExportThread.dispatcher) {
                    val params = Edit.openingParams(edit, inputSpace = "ProPhoto RGB", linear = true).toString()
                    val h = NativeEngine.openRaw(engine, path, job.workingEdge, maxPixels, params, job.wbK, job.wbTint)
                    if (h == 0L) engineFailure(engine, "打开失败")
                    synchronized(handleLock) { runningHandle = h }
                    h
                }
            } finally {
                input?.close()
            }
        } else {
            step("解码照片", 0.10f)
            val photo = withContext(Dispatchers.IO) { loader.decodePhoto(uri, job.workingEdge, maxPixels) }
            withContext(EngineThread.dispatcher) { NativeEngine.trimMemory(engine) }
            withContext(ExportThread.dispatcher) {
                val h = openPhotoSession(engine, photo, job.highlightStops) { space, linear ->
                    Edit.openingParams(edit, inputSpace = space, linear = linear).toString()
                }.handle
                if (h == 0L) engineFailure(engine, "打开失败")
                synchronized(handleLock) { runningHandle = h }
                h
            }
        }
        try {
            // The file's own space, through the CAM16 export path (upstream's export
            // transform, lightness shoulder off). TIFF carries the sRGB profile.
            withContext(ExportThread.dispatcher) {
                if (!NativeEngine.setOutput(handle, outSpace, true)) engineFailure(engine, "导出失败")
            }
            // Layer 2 rides on the job's own session: every pack below goes through it.
            Look.fromJson(job.lookJson.takeIf { it.isNotEmpty() }?.let { JSONObject(it) }).let { look ->
                if (!look.neutral) withContext(ExportThread.dispatcher) { NativeEngine.setLook(handle, look.values()) }
            }
            val stem = "Spektralab-X_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + "_" + (job.id % 1000)
            yieldToEditor(cool = job.extreme)
            step(if (job.extreme) "极致冲洗（全分辨率）" else "冲洗", 0.25f)
            val icc = File(ready.dir, "io/sRGB.icc").absolutePath
            when (job.format) {
                ExportFormat.TIFF -> {
                    // Rendered straight into a pending entry (the gallery hides it), so
                    // a 100+ MB file is written once; checked, then published.
                    val name = "$stem.tif"
                    val target = withContext(Dispatchers.IO) { MediaSaver.create(app, name, "image/tiff") }
                    markPending(job.id, target)
                    try {
                        withContext(Dispatchers.IO) {
                            val pfd = app.contentResolver.openFileDescriptor(target, "w") ?: throw EngineException("无法写入文件")
                            pfd.use {
                                // engineFailure reads this thread's error, so it stays inside.
                                withContext(ExportThread.dispatcher) {
                                    if (!NativeEngine.exportTiffFd(handle, it.fd, icc, job.size.tier)) engineFailure(engine, "导出失败")
                                }
                            }
                        }
                        step("校验", 0.94f)
                        withContext(Dispatchers.IO) {
                            MediaSaver.checkTiff(app, target)
                            MediaSaver.publish(app, target)
                        }
                    } catch (e: Throwable) {
                        withContext(NonCancellable + Dispatchers.IO) { MediaSaver.discardIfPending(app, target) }
                        throw e
                    }
                    return name
                }
                ExportFormat.JPEG -> {
                    val bitmap: Bitmap = withContext(ExportThread.dispatcher) {
                        NativeEngine.exportBitmap(handle, job.size.tier) ?: engineFailure(engine, "导出失败")
                    }
                    // The pixels are P3 already; tag them so compress embeds the profile.
                    if (wide) runCatching { bitmap.setColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)) }
                    step("编码", 0.88f)
                    val name = "$stem.jpg"
                    try {
                        // Encoded to a .part first: EXIF is written in place, and the
                        // file is checked whole before the gallery sees any of it.
                        val part = withContext(Dispatchers.IO) {
                            exportDir.mkdirs()
                            val part = File(exportDir, "$name.part")
                            try {
                                // The paper frame is drawn around the print here, after the engine.
                                val frame = FrameSpec.fromJson(job.frameJson.takeIf { it.isNotEmpty() }?.let { JSONObject(it) })
                                val out = if (frame.isNone) bitmap else FrameSpec.render(bitmap, frame, Caption.of(exif, job.filmLabel))
                                try {
                                    val encoded = part.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, if (job.extreme) 98 else 95, it) }
                                    if (!encoded) throw EngineException("JPEG 编码失败")
                                    exif.applyTo(part, out.width, out.height, wide = wide)
                                    MediaSaver.checkJpeg(part, out.width, out.height)
                                } finally {
                                    if (out !== bitmap) out.recycle()
                                }
                                part
                            } catch (e: Throwable) {
                                part.delete()
                                throw e
                            }
                        }
                        step("写入相册", 0.92f)
                        try {
                            withContext(Dispatchers.IO) {
                                MediaSaver.saveFile(app, part, name, "image/jpeg") { markPending(job.id, it) }
                            }
                        } finally {
                            part.delete()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    return name
                }
            }
        } finally {
            withContext(NonCancellable + ExportThread.dispatcher) {
                synchronized(handleLock) {
                    NativeEngine.release(handle)
                    runningHandle = 0L
                }
            }
        }
    }

    // --- sources, leftovers -------------------------------------------------------

    /**
     * IO. A share or view intent's grant ends with its activity, and a
     * resumed job runs in a new process: take a persistable grant where the
     * URI offers one (the photo picker and the document picker do), else copy
     * the file into app storage. A failure leaves the job on its URI, which
     * still works while this process lives.
     */
    private fun secure(job: ProcessingJob): ProcessingJob {
        if (job.sourcePath.isNotEmpty() && File(job.sourcePath).isFile) return job
        val uri = Uri.parse(job.uri)
        if (uri.scheme == "file") return job
        val resolver = app.contentResolver
        val held = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (held || runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess) {
            synchronized(grantLock) {
                prefs.edit().putStringSet("grants", HashSet(prefs.getStringSet("grants", emptySet()).orEmpty()) + job.uri).apply()
            }
            return job.copy(persisted = true)
        }
        return runCatching {
            sourcesDir.mkdirs()
            val safe = job.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifEmpty { "source" }
            val file = File(sourcesDir, "${job.id}_$safe")
            val part = File(sourcesDir, "${job.id}_$safe.part")
            try {
                (resolver.openInputStream(uri) ?: throw EngineException("无法读取该文件")).use { i ->
                    part.outputStream().use { o -> i.copyTo(o, 1 shl 20) }
                }
                if (!part.renameTo(file)) throw EngineException("cannot keep a copy of the source")
            } finally {
                part.delete()
            }
            host.events.add("job ${job.id}: source copied (${file.length() shr 20} MB)")
            job.copy(sourcePath = file.absolutePath)
        }.getOrElse { e ->
            host.events.add("job ${job.id}: source not secured: ${e.message ?: e}")
            job
        }
    }

    /**
     * IO, before the first job of a process. A job the last process died in
     * may have a pending gallery entry: a finished photo when the kill came
     * after publishing, otherwise a partial file to delete.
     */
    private fun recoverInterrupted() {
        for (job in _jobs.value) if (job.pendingUri.isNotEmpty()) settle(job)
        // Killed for memory mid-export: the retry decodes one size smaller.
        if (host.lastExitWasMemory) for (id in interrupted) {
            set(id) { if (it.state == JobState.QUEUED) smaller(it) else it }
            host.events.add("job $id: memory kill, retrying a size down")
        }
        val stale = MediaSaver.sweepPending(app)
        if (stale > 0) host.events.add("removed $stale unfinished gallery entries")
        persist()
    }

    /**
     * IO. A job's recorded gallery entry: published means the job is done
     * (true), otherwise the partial file is deleted and the job runs again.
     */
    private fun settle(job: ProcessingJob): Boolean {
        val uri = Uri.parse(job.pendingUri)
        val (state, name) = runCatching { MediaSaver.state(app, uri) }.getOrDefault(MediaSaver.State.GONE to "")
        if (state == MediaSaver.State.PUBLISHED) {
            set(job.id) { it.copy(state = JobState.DONE, progress = 1f, phase = "完成", etaSeconds = 0,
                finishedAt = now(), message = name.ifEmpty { it.message }, pendingUri = "") }
            host.events.add("job ${job.id}: already saved as $name")
            return true
        }
        MediaSaver.discardIfPending(app, uri)
        set(job.id) { it.copy(pendingUri = "") }
        return false
    }

    /** IO. Encodes a killed export left in cache, and sources no job needs any more. */
    private fun sweepFiles() {
        exportDir.listFiles()?.forEach { it.delete() }
        File(app.cacheDir, "job").listFiles()?.forEach { it.delete() }
        // Older builds encoded into the cache root.
        app.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.startsWith("Spektralab-X_") || f.name.startsWith("SpektraLab_"))) f.delete()
        }
        releaseSources()
    }

    private fun releaseSourcesLater() {
        host.scope.launch(Dispatchers.IO) { releaseSources() }
    }

    /**
     * IO. Deletes copied sources and releases grants that only finished jobs
     * referred to. A failed or cancelled job keeps its source for 重试, until
     * it leaves the list; a job still being secured keeps whatever it is making.
     */
    private fun releaseSources() = synchronized(grantLock) {
        val needed = _jobs.value.filter { it.state != JobState.DONE }
        val paths = needed.map { it.sourcePath }.filter { it.isNotEmpty() }.toSet()
        val securing = needed.filter { !it.secured }.map { "${it.id}_" }
        sourcesDir.listFiles()?.forEach { f ->
            if (f.absolutePath !in paths && securing.none { f.name.startsWith(it) }) f.delete()
        }
        val uris = needed.filter { it.persisted || !it.secured }.map { it.uri }.toSet()
        val held = prefs.getStringSet("grants", emptySet()).orEmpty().toSet()
        val keep = held.filter { it in uris }.toSet()
        for (u in held - keep) {
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (keep.size != held.size) prefs.edit().putStringSet("grants", keep).apply()
    }

    /** VmHWM after a job: the peak the memory limiter and lmkd judge us by. */
    private fun peakText(rssBeforeKb: Long, reset: Boolean): String {
        val kb = ProcMemory.statusKb("VmHWM")
        if (kb < 0) return "peak RSS unknown"
        return "RSS ${rssBeforeKb / 1024} MB before, peak ${kb / 1024} MB" + if (reset) "" else " (process peak)"
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
        return (BASE_SECONDS + secondsPerMp(job) * mp).roundToInt()
    }

    // Learned per format, and separately for 极致渲染: the whole-frame full
    // tier at the source's size runs slower per megapixel than a working-size
    // export (larger blurs, more pool traffic), and would skew the others.
    private fun rateKey(job: ProcessingJob) = "spmp_${job.format.name}" + if (job.extreme) "_extreme" else ""

    private fun secondsPerMp(job: ProcessingJob): Double {
        val base = if (job.format == ExportFormat.TIFF) 1.6f else 1.2f
        return prefs.getFloat(rateKey(job), if (job.extreme) base * 1.5f else base).toDouble()
    }

    private fun learn(job: ProcessingJob, seconds: Double) {
        val mp = max(0.5, job.outPixels / 1e6)
        val measured = max(0.05, (seconds - BASE_SECONDS) / mp)
        val next = 0.6 * secondsPerMp(job) + 0.4 * measured
        prefs.edit().putFloat(rateKey(job), next.toFloat()).apply()
    }

    private fun refreshNotification(force: Boolean = false) {
        // Paused: the service is gone and the paused notice says why.
        if (paused) return
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

    /** [sync] writes before returning: for state a kill must not lose (an attempt, a pending entry). */
    private fun persist(sync: Boolean = false) {
        // Finished jobs are kept for the list, newest 30.
        val list = _jobs.value
        val keep = list.filter { it.active } + list.filter { !it.active }.sortedByDescending { it.finishedAt }.take(30)
        val arr = JSONArray()
        keep.sortedBy { it.id }.forEach { arr.put(it.toJson()) }
        val edit = prefs.edit().putString("jobs", arr.toString())
        if (sync) edit.commit() else edit.apply()
    }

    private fun load(): List<ProcessingJob> = runCatching {
        val arr = JSONArray(prefs.getString("jobs", "[]"))
        val list = (0 until arr.length()).mapNotNull { ProcessingJob.fromJson(arr.getJSONObject(it)) }
        interrupted = list.filter { it.state == JobState.RUNNING }.map { it.id }.toSet()
        // A job that was running when the process died goes back in line; one
        // still being secured runs on its URI as it is.
        list.map { if (it.state == JobState.RUNNING) it.copy(state = JobState.QUEUED) else it }
            .map { if (!it.secured) it.copy(secured = true) else it }
            .also { noticedInit(it) }
    }.getOrDefault(emptyList())

    private fun noticedInit(list: List<ProcessingJob>) {
        noticed = HashSet(list.filter { !it.active }.map { it.id })
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val BASE_SECONDS = 2.0
        private const val EDITOR_GRACE_MS = 1200L
        // The editor's own "hot" line (EditorViewModel.kHotHeadroom), and at
        // most two minutes of waiting for it: a phone that never cools still exports.
        private const val HOT_HEADROOM = 0.85f
        private const val THERMAL_WAIT_MS = 120_000L

        /** Seconds until every active job is done: the running one's own estimate plus each queued one's. */
        fun remainingSeconds(jobs: List<ProcessingJob>, perMpJpeg: Double = 1.2): Int {
            var total = 0
            for (j in jobs) when (j.state) {
                JobState.RUNNING -> total += max(0, j.etaSeconds)
                JobState.QUEUED -> total += (BASE_SECONDS + perMpJpeg * (if (j.extreme) 1.5 else 1.0) * max(0.5, j.outPixels / 1e6)).roundToInt()
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
