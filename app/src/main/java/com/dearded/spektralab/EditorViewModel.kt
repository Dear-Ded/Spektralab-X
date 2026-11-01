package com.dearded.spektralab

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ExportFormat { JPEG, TIFF }

/** "full" is the working size; "preview" is the engine's 3400 px tier, for sharing. */
enum class ExportSize(val tier: String) { FULL("full"), SHARE("preview") }

data class UiState(
    val ready: Boolean = false,
    val busy: String? = "正在准备引擎…",
    val error: String? = null,
    val message: String? = null,
    val catalog: Catalog = Catalog.EMPTY,
    val edit: Edit = Edit(),
    val image: Bitmap? = null,
    val imageTier: String = "",
    /** The frame before any film, shown while the preview is held down. */
    val before: Bitmap? = null,
    /** Film id -> a 384 px preview of the open photo through that film. Filled in the background. */
    val filmCards: Map<String, Bitmap> = emptyMap(),
    val source: SourceInfo? = null,
    val workingEdge: Int = 4096,
    val maxPixels: Long = 0,
    val deviceSummary: String = "",
    val engineSummary: String = "",
    val rendering: Boolean = false,
    val exporting: Boolean = false,
) {
    val hasImage get() = source != null
}

class EngineException(message: String) : Exception(message)

class EditorViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", 0)
    private val _ui = MutableStateFlow(UiState(workingEdge = prefs.getInt("working_edge", 4096)))
    val ui: StateFlow<UiState> = _ui

    // Touched only on EngineThread, except where marked volatile.
    private var engine = 0L
    private var session = 0L
    private var applied = Edit()
    private var iccPath = ""
    @Volatile private var wanted = Edit()

    private val renders = Channel<Unit>(Channel.CONFLATED)
    // Diagnostics: the raw probe replies and the last events, for the copyable report.
    private var deviceJson = ""
    private var capabilitiesJson = ""
    private val events = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    private fun log(event: String) = synchronized(events) {
        events.addLast("${clock.format(Date())} $event")
        while (events.size > 20) events.removeFirst()
    }
    private var lastUri: Uri? = null
    private var sourceExif: ExifCopy = ExifCopy.NONE
    private var pendingOpen: Uri? = null
    // The open photo's input space, so a film card opens on it the same way.
    private var openSpace = "sRGB"
    private var openLinear = false
    private var cardsJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch { startEngine() }
        viewModelScope.launch { renderLoop() }
    }

    private suspend fun startEngine() {
        try {
            val started = withContext(EngineThread.dispatcher) {
                val dir = EngineAssets.install(app)
                val device = NativeEngine.deviceInfo()
                val catalog = Catalog.load(dir)
                val tmp = File(app.cacheDir, "engine-tmp").apply { mkdirs() }
                val handle = NativeEngine.create(dir.absolutePath, tmp.absolutePath)
                if (handle == 0L) {
                    throw EngineException("引擎启动失败：${NativeEngine.lastError()}\n\nGPU：${summarizeDevice(device)}")
                }
                engine = handle
                iccPath = File(dir, "io/sRGB.icc").absolutePath
                Triple(catalog, device, NativeEngine.capabilities(handle))
            }
            val (catalog, device, caps) = started
            deviceJson = device
            capabilitiesJson = caps
            log("engine ready")
            _ui.update {
                it.copy(
                    ready = true, busy = null, catalog = catalog,
                    deviceSummary = summarizeDevice(device),
                    engineSummary = summarizeCapabilities(caps),
                    maxPixels = maxPixelsFor(device, availableMemory()),
                )
            }
            pendingOpen?.let { pendingOpen = null; open(it) }
        } catch (e: Throwable) {
            _ui.update { it.copy(busy = null, error = e.message ?: e.toString()) }
        }
    }

    // --- opening -------------------------------------------------------------

    fun open(uri: Uri) {
        if (!_ui.value.ready) { pendingOpen = uri; return }
        lastUri = uri
        viewModelScope.launch { doOpen(uri) }
    }

    private suspend fun doOpen(uri: Uri) {
        _ui.update { it.copy(busy = "正在读取…", error = null) }
        try {
            val resolver = app.contentResolver
            val name = displayName(uri)
            val raw = isRaw(name, resolver.getType(uri))
            val edge = _ui.value.workingEdge
            val maxPixels = _ui.value.maxPixels
            // A phone photo was already exposed by the camera; metering it again
            // double-counts (a bright sky in the middle printed ~1.7 stops dark in
            // another port). RAW is scene-linear and wants the meter.
            // Rotation and crop belong to one photo; the look (film, paper, effects) carries over.
            val look = wanted.copy(
                autoExposure = raw,
                quarterTurns = 0, flipH = false,
                cropX = 0.0, cropY = 0.0, cropW = 1.0, cropH = 1.0, cropAspect = 0.0,
            )
            // Free the previous frame before decoding the next, so the two never
            // coexist in memory (a 20 MP open ran out of memory that way elsewhere).
            cardsJob?.cancel()
            withContext(EngineThread.dispatcher) { releaseSession() }
            _ui.update { it.copy(image = null, before = null, source = null, filmCards = emptyMap()) }
            sourceExif = withContext(Dispatchers.IO) { ExifCopy.read { resolver.openInputStream(uri) } }
            val handle: Long
            if (raw) {
                val file = withContext(Dispatchers.IO) { copyToCache(uri, name) }
                _ui.update { it.copy(busy = "正在解码 RAW…") }
                handle = withContext(EngineThread.dispatcher) {
                    releaseSession()
                    openSpace = "ProPhoto RGB"; openLinear = true
                    val params = Edit.openingParams(look, inputSpace = openSpace, linear = openLinear).toString()
                    val h = NativeEngine.openRaw(engine, file.absolutePath, edge, maxPixels, params)
                    file.delete()
                    if (h == 0L) throw EngineException("打开失败：${NativeEngine.lastError()}")
                    h
                }
            } else {
                val (bitmap, space) = withContext(Dispatchers.IO) { decodePhoto(uri, edge, maxPixels) }
                _ui.update { it.copy(busy = "正在打开…") }
                handle = withContext(EngineThread.dispatcher) {
                    releaseSession()
                    openSpace = space; openLinear = false
                    val params = Edit.openingParams(look, inputSpace = openSpace, linear = openLinear).toString()
                    val h = NativeEngine.openBitmap(engine, bitmap, params)
                    bitmap.recycle()
                    if (h == 0L) throw EngineException("打开失败：${NativeEngine.lastError()}")
                    h
                }
            }
            val (edit, info, before) = withContext(EngineThread.dispatcher) {
                session = handle
                val params = NativeEngine.getParams(handle)?.let(::JSONObject) ?: JSONObject()
                val edit = Edit.fromParams(params, look)
                applied = edit
                wanted = edit
                Triple(edit, JSONObject(NativeEngine.sourceInfo(handle)), NativeEngine.beforeBitmap(handle))
            }
            _ui.update {
                it.copy(
                    busy = null, edit = edit, image = null, imageTier = "", before = before,
                    source = SourceInfo(
                        name = name,
                        width = info.optInt("width"), height = info.optInt("height"),
                        sourceWidth = info.optInt("sourceWidth"), sourceHeight = info.optInt("sourceHeight"),
                        kind = info.optString("kind"),
                        camera = listOf(info.optString("make"), info.optString("model"))
                            .filter { s -> s.isNotBlank() }.joinToString(" "),
                    ),
                )
            }
            log("open ${if (raw) "raw" else "photo"} ${info.optInt("sourceWidth")}x${info.optInt("sourceHeight")} -> ${info.optInt("width")}x${info.optInt("height")}")
            renders.trySend(Unit)
            startFilmCards(handle)
        } catch (e: OutOfMemoryError) {
            log("open failed: out of memory")
            _ui.update { it.copy(busy = null, error = "内存不足，请在“设置”里把工作尺寸调小后重试") }
        } catch (e: Throwable) {
            log("open failed: ${e.message ?: e}")
            _ui.update { it.copy(busy = null, error = e.message ?: e.toString()) }
        }
    }

    private fun availableMemory(): Long {
        val am = app.getSystemService(android.app.ActivityManager::class.java) ?: return 0L
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    private fun releaseSession() {
        if (session != 0L) NativeEngine.release(session)
        session = 0L
    }

    private fun displayName(uri: Uri): String {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "image"
    }

    private fun isRaw(name: String, mime: String?): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in RAW_EXTENSIONS) return true
        val m = mime?.lowercase(Locale.ROOT) ?: return false
        return m.contains("dng") || m.contains("raw") || m.startsWith("image/x-") && m !in DECODABLE_X
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val dir = File(app.cacheDir, "open").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifEmpty { "image.raw" }
        val file = File(dir, safe)
        val input = app.contentResolver.openInputStream(uri) ?: throw EngineException("无法读取该文件")
        input.use { i -> file.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
        return file
    }

    /**
     * Phone photos (JPEG, HEIC/HEIF, Ultra HDR JPEG, PNG, WebP). ImageDecoder
     * applies the EXIF rotation. Display P3 sources (what many phones shoot)
     * stay in P3 so their wide gamut reaches the engine; everything else is
     * converted to sRGB. Returns the bitmap and the engine's name for its space.
     */
    private fun decodePhoto(uri: Uri, edge: Int, maxPixels: Long): Pair<Bitmap, String> {
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        var space = "sRGB"
        val bitmap = decodeBitmap(uri, edge, maxPixels) { info ->
            if (info.colorSpace == p3) { space = "Display P3"; p3 } else ColorSpace.get(ColorSpace.Named.SRGB)
        }
        return bitmap to space
    }

    private fun decodeBitmap(
        uri: Uri, edge: Int, maxPixels: Long,
        target: (ImageDecoder.ImageInfo) -> ColorSpace,
    ): Bitmap {
        val source = ImageDecoder.createSource(app.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(target(info))
            val w = info.size.width
            val h = info.size.height
            var s = 1.0
            if (edge > 0 && max(w, h) > edge) s = edge.toDouble() / max(w, h)
            if (maxPixels > 0 && w.toDouble() * h * s * s > maxPixels) s = sqrt(maxPixels.toDouble() / (w.toDouble() * h))
            if (s < 1.0) decoder.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
        }
        if (decoded.config == Bitmap.Config.ARGB_8888) return decoded
        val copy = decoded.copy(Bitmap.Config.ARGB_8888, false)
        decoded.recycle()
        return copy ?: throw EngineException("无法转换图像格式")
    }

    // --- editing and rendering -------------------------------------------------

    fun update(edit: Edit) {
        wanted = edit
        _ui.update { it.copy(edit = edit) }
        renders.trySend(Unit)
    }

    fun pickFilm(id: String) {
        val state = _ui.value
        update(withFilm(state.edit, state.catalog, id) ?: return)
    }

    /**
     * One card per film, rendered after the photo opens: the open photo's small
     * copy through that film, with the user's current paper and effects. One
     * card per engine-thread hop, so the user's own renders get in between.
     * Cancelled by the next open; a card that fails is simply left out.
     */
    private fun startFilmCards(handle: Long) {
        cardsJob?.cancel()
        cardsJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1500)   // let the first preview land first
            val catalog = _ui.value.catalog
            for (film in catalog.films) {
                val base = withFilm(wanted, catalog, film.id) ?: continue
                val params = Edit.openingParams(base, inputSpace = openSpace, linear = openLinear).toString()
                val card = runCatching {
                    withContext(EngineThread.dispatcher) {
                        if (session != handle) null else NativeEngine.filmPreview(handle, params)
                    }
                }.getOrNull() ?: continue
                _ui.update { it.copy(filmCards = it.filmCards + (film.id to card)) }
            }
            log("film cards ${_ui.value.filmCards.size}/${catalog.films.size}")
        }
    }

    /** Sends whatever the user changed since the last call. Engine thread only. */
    private fun applyWanted(): String? {
        val target = wanted
        if (target == applied) return null
        val delta = target.delta(applied)
        if (delta.length() == 0) { applied = target; return null }
        if (NativeEngine.setParams(session, delta.toString()) == null) return NativeEngine.lastError()
        applied = target
        return null
    }

    private suspend fun renderLoop() {
        for (request in renders) {
            if (!renderOnce("live")) continue
            // A full preview once the user has stopped moving a slider.
            val again = withTimeoutOrNull(600) { renders.receive() }
            if (again != null) { renders.trySend(Unit); continue }
            renderOnce("preview")
        }
    }

    // An exception escaping here would end renderLoop for good (and leave the
    // progress bar up), so everything but cancellation is turned into a message,
    // and the busy flag is cleared in finally.
    private suspend fun renderOnce(tier: String): Boolean {
        _ui.update { it.copy(rendering = true) }
        var failure: String? = null
        var reverted: Edit? = null
        var bitmap: Bitmap? = null
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            bitmap = withContext(EngineThread.dispatcher) {
                if (session == 0L) return@withContext null
                applyWanted()?.let { error ->
                    failure = "参数无效：$error"
                    wanted = applied
                    reverted = applied
                    return@withContext null
                }
                NativeEngine.renderBitmap(session, tier) ?: run {
                    failure = "渲染失败：${NativeEngine.lastError()}"
                    null
                }
            }
            val ms = android.os.SystemClock.elapsedRealtime() - started
            bitmap?.let { log("render $tier ${it.width}x${it.height} ${ms} ms") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            failure = "内存不足，渲染失败；请在“设置”里调小工作尺寸"
        } catch (e: Throwable) {
            failure = "渲染失败：${e.message ?: e}"
        } finally {
            failure?.let { log("render $tier failed: $it") }
            val shown = bitmap
            _ui.update {
                var next = it.copy(rendering = false)
                if (shown != null) next = next.copy(image = shown, imageTier = tier)
                reverted?.let { e -> next = next.copy(edit = e) }
                failure?.let { f -> next = next.copy(message = f) }
                next
            }
        }
        return bitmap != null
    }

    // --- export --------------------------------------------------------------

    fun export(format: ExportFormat, size: ExportSize = ExportSize.FULL) {
        if (_ui.value.exporting || !_ui.value.hasImage) return
        viewModelScope.launch {
            _ui.update { it.copy(exporting = true, busy = if (size == ExportSize.FULL) "正在渲染全尺寸…" else "正在渲染…") }
            try {
                val stem = "SpektraLab_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                when (format) {
                    ExportFormat.TIFF -> {
                        val tmp = File(app.cacheDir, "$stem.tif")
                        withContext(EngineThread.dispatcher) {
                            applyWanted()?.let { throw EngineException("参数无效：$it") }
                            if (!NativeEngine.exportTiff(session, tmp.absolutePath, iccPath, size.tier))
                                throw EngineException("导出失败：${NativeEngine.lastError()}")
                        }
                        withContext(Dispatchers.IO) {
                            save("$stem.tif", "image/tiff") { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 20) } }
                            tmp.delete()
                        }
                    }
                    ExportFormat.JPEG -> {
                        val bitmap = withContext(EngineThread.dispatcher) {
                            applyWanted()?.let { throw EngineException("参数无效：$it") }
                            NativeEngine.renderBitmap(session, size.tier)
                                ?: throw EngineException("导出失败：${NativeEngine.lastError()}")
                        }
                        withContext(Dispatchers.IO) {
                            // Compressed to a file first: EXIF is written in place, then copied out.
                            val tmp = File(app.cacheDir, "$stem.jpg")
                            try {
                                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                                sourceExif.applyTo(tmp, bitmap.width, bitmap.height)
                                save("$stem.jpg", "image/jpeg") { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 20) } }
                            } finally {
                                tmp.delete()
                            }
                        }
                        bitmap.recycle()
                    }
                }
                log("export $format ${size.tier} ok")
                _ui.update { it.copy(message = "已保存到 Pictures/SpektraLab") }
            } catch (e: OutOfMemoryError) {
                log("export $format ${size.tier} failed: out of memory")
                _ui.update { it.copy(message = "内存不足，无法导出全尺寸；请调小工作尺寸") }
            } catch (e: Throwable) {
                log("export $format ${size.tier} failed: ${e.message ?: e}")
                _ui.update { it.copy(message = e.message ?: e.toString()) }
            } finally {
                _ui.update { it.copy(exporting = false, busy = null) }
            }
        }
    }

    private fun save(name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SpektraLab")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        // Some builds refuse TIFF in the images collection; Downloads takes any type.
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        }.getOrNull() ?: run {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SpektraLab")
            resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } ?: throw EngineException("无法在相册中创建文件")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw EngineException("无法写入文件")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    // --- settings ------------------------------------------------------------

    /** Everything a bug report needs, as plain text. No file names or locations. */
    fun diagnostics(): String {
        val ui = _ui.value
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val src = ui.source
        return buildString {
            appendLine("SpektraLab Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE}) · " +
                "Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("SoC: ${if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "?"}")
            appendLine("RAM: total ${mem.totalMem shr 20} MB, available ${mem.availMem shr 20} MB, low=${mem.lowMemory}")
            appendLine("Java heap: max ${rt.maxMemory() shr 20} MB, used ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB")
            appendLine("Native heap: ${android.os.Debug.getNativeHeapAllocatedSize() shr 20} MB")
            appendLine("Working edge: ${ui.workingEdge} · pixel cap: ${ui.maxPixels}")
            appendLine("GPU: ${deviceJson.ifEmpty { "-" }}")
            appendLine("Engine: ${ui.engineSummary.ifEmpty { "-" }}")
            if (src != null) appendLine("Image: ${src.kind} ${src.sourceWidth}x${src.sourceHeight} -> ${src.width}x${src.height}" +
                (if (src.camera.isNotBlank()) " · ${src.camera}" else ""))
            appendLine("Edit: ${ui.edit.toJson()}")
            appendLine("Events:")
            synchronized(events) { events.forEach { appendLine("  $it") } }
        }
    }

    fun setWorkingEdge(edge: Int) {
        if (edge == _ui.value.workingEdge) return
        prefs.edit().putInt("working_edge", edge).apply()
        _ui.update { it.copy(workingEdge = edge) }
        lastUri?.let { open(it) }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }
    fun dismissError() = _ui.update { it.copy(error = null) }

    override fun onCleared() {
        val e = engine
        val s = session
        EngineThread.dispatcher.executor.execute {
            if (s != 0L) NativeEngine.release(s)
            if (e != 0L) NativeEngine.destroy(e)
        }
        session = 0L
        engine = 0L
    }

    companion object {
        private val RAW_EXTENSIONS = setOf(
            "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "pef",
            "srw", "x3f", "3fr", "iiq", "erf", "kdc", "mrw", "rwl", "raw", "mos", "mef", "dcr",
        )
        private val DECODABLE_X = setOf("image/x-ms-bmp", "image/x-icon")

        private fun firstDevice(device: String): JSONObject? =
            runCatching { JSONObject(device).optJSONArray("devices")?.optJSONObject(0) }.getOrNull()

        /**
         * One storage buffer holds a whole image plane, so the frame is capped by
         * maxStorageBufferRange. Sized for a four-channel float plane, the widest
         * the pipeline allocates.
         */
        fun maxPixelsFor(device: String, availMem: Long): Long {
            // An unreadable probe is treated as the Vulkan minimum (128 MiB), not as no limit.
            val range = firstDevice(device)?.optLong("maxStorageBufferRange")?.takeIf { it > 0 } ?: (128L shl 20)
            val gpu = range / 16
            // Measured on other ports: ~70-100 B/px live across the pipeline, plus host
            // copies. Keep 1.5 GiB for the system so lmkd does not start killing apps.
            val ram = max(4_000_000L, (availMem - (3L shl 29)) / 100)
            return minOf(gpu, ram, MAX_CANVAS_PIXELS)
        }

        /** 42.7 MP rendered and 44.8 MP lost the device on Adreno in another port. */
        private const val MAX_CANVAS_PIXELS = 42_000_000L

        fun summarizeDevice(device: String): String {
            val d = firstDevice(device) ?: return "未检测到 Vulkan 设备"
            val range = d.optLong("maxStorageBufferRange")
            return "${d.optString("name")} · Vulkan ${d.optString("api")} · 单缓冲上限 ${range shr 20} MB" +
                " · 约 ${(range / 16 / 1_000_000.0 * 10).roundToInt() / 10.0} MP"
        }

        fun summarizeCapabilities(caps: String): String = runCatching {
            val backend = JSONObject(caps).optJSONObject("backend") ?: return@runCatching caps
            "${backend.optString("render_core")} · ${backend.optString("math_mode")}"
        }.getOrDefault(caps)

        /** [edit] with film [id]; the paper changes only when it cannot print it (colour vs B&W). */
        fun withFilm(edit: Edit, catalog: Catalog, id: String): Edit? {
            val film = catalog.film(id) ?: return null
            var paper = edit.paper
            val paperBw = catalog.paper(paper)?.bw ?: false
            if (film.targetPrint != null && paperBw != film.bw) paper = film.targetPrint
            return edit.copy(film = id, scanFilm = film.positive, paper = paper)
        }

        @Suppress("unused")
        private fun clamp(v: Double, lo: Double, hi: Double) = min(hi, max(lo, v))
    }
}
