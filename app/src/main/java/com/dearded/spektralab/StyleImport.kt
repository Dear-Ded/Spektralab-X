package com.dearded.spektralab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

enum class ImportKind { STYLE, FILM_RECIPE, FILM_DATA }

/**
 * A file read and converted but not yet kept: shown on the import sheet with
 * its report. A colour table is already stored under [lutId] (so the preview
 * can use it) and is dropped again when the import is cancelled.
 */
class PendingImport(
    val fileName: String,
    val kind: ImportKind,
    val look: SpkLook,
    val lutId: String,
    val report: ImportReport,
    /** Film imports: the user_ stock id, its profile JSON and its stage (filming / printing). */
    val profileId: String = "",
    val profileJson: JSONObject? = null,
    val stage: String = "",
    /** Film recipes: the bundled stock it is an alias of. */
    val base: String = "",
)

/** What the import sheet shows: reading, a converted file, or why a file could not be read. */
data class ImportUi(
    val pending: PendingImport? = null,
    val left: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
)

/** The process's user library, shared by the editor and the style row. */
object UserLibrary {
    @Volatile private var instance: StyleImports? = null

    fun get(context: Context): StyleImports = instance ?: synchronized(this) {
        instance ?: StyleImports(context.applicationContext).also { instance = it }
    }
}

/**
 * Reading, storing and exporting the user's styles and films (docs/STYLES.md
 * section 7). Everything here runs off the main thread except [applyLut],
 * which runs on the engine thread.
 */
class StyleImports(private val context: Context) {
    private val root = File(context.filesDir, "user")
    val luts = LutStore(File(root, "luts"))
    val styles = UserStyles(File(root, "styles"))
    val profiles = UserProfiles(File(root, "profiles"))
    /** EngineAssets.install's directory: the engine reads profiles from here. */
    val engineDir: File get() = File(context.filesDir, "engine")

    // --- reading -------------------------------------------------------------

    /** [uri] read and converted against [catalog] (the bundled and user stocks). Throws [ImportException]. */
    fun read(uri: Uri, catalog: Catalog): PendingImport {
        val (name, size) = nameAndSize(uri)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val limit = when (ext) {
            "cube", "png" -> MAX_LUT_FILE
            "spklook", "zip" -> SpkPackage.MAX_FILE
            "json" -> FilmRecipe.MAX_DATA_BYTES.toLong()
            else -> PresetImport.MAX_XMP_BYTES.toLong()
        }
        if (size > limit) throw ImportException("文件太大（超过 ${limit shr 20} MB）")
        val bytes = context.contentResolver.openInputStream(uri)?.use { SpkPackage.readBounded(it, limit, name) }
            ?: throw ImportException("打不开这个文件")
        if (bytes.isEmpty()) throw ImportException("文件是空的")
        val report = ImportReport()
        val stem = PresetImport.stem(name).ifEmpty { "导入的风格" }
        val latinHead = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        return when {
            SpkPackage.isZip(bytes) -> fromPackage(SpkPackage.read(bytes, report, stem), name, catalog, report)
            isPng(bytes) -> fromHald(bytes, name, stem, catalog, report)
            ext == "cube" || CUBE_HEAD.containsMatchIn(latinHead) -> fromCube(bytes, name, stem, catalog, report)
            ext == "xmp" || ext == "dng" || latinHead.contains("<x:xmpmeta") || latinHead.contains("crs:") ->
                style(PresetImport.fromXmp(bytes, name, report), name, catalog, report)
            else -> fromText(bytes, name, stem, catalog, report)
        }
    }

    private fun fromText(bytes: ByteArray, file: String, stem: String, catalog: Catalog, report: ImportReport): PendingImport {
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        if (text.trimStart().startsWith("{")) {
            val o = SpkPackage.parseJson(bytes, file)
            return when (PresetImport.classifyJson(o)) {
                PresetImport.JsonKind.SPKLOOK -> fromPackage(SpkPackage.read(bytes, report, stem), file, catalog, report)
                PresetImport.JsonKind.SPEKTRAFILM -> style(PresetImport.fromSpektrafilm(o, file, report), file, catalog, report)
                PresetImport.JsonKind.FILM_RECIPE -> recipe(o, bytes.size, file, catalog, report)
                PresetImport.JsonKind.FILM_DATA -> filmData(o, bytes.size, file, stem, catalog, report)
                PresetImport.JsonKind.UNKNOWN -> throw ImportException("认不出这个 JSON：不是 .spklook、spektrafilm 预设、胶片方案或胶片数据")
            }
        }
        if (PresetImport.looksLikeFujiRecipe(text)) {
            val look = PresetImport.fromFujiRecipe(text, stem, { id -> Style.ALL.firstOrNull { it.id == id }?.toSpkLook() }, report)
            return style(look, file, catalog, report)
        }
        throw ImportException("不支持这种文件。可以导入 .spklook、.cube、HaldCLUT PNG、Lightroom XMP / DNG 预设、spektrafilm 预设、胶片方案、胶片数据和富士配方文本")
    }

    private fun fromPackage(pkg: SpkPackage, file: String, catalog: Catalog, report: ImportReport): PendingImport {
        var look = pkg.look
        var lutId = ""
        val cube = pkg.lutCube
        if (look.lut != null && cube != null) {
            try {
                lutId = storeLut(CubeFile.parse(String(cube, Charsets.UTF_8)).toLut(report), look.lut!!.space)
            } catch (e: ImportException) {
                report.ignored("lut.cube", e.message ?: "读不了")
                look = look.copy(lut = null)
            }
        }
        return style(look, file, catalog, report, lutId)
    }

    private fun fromCube(bytes: ByteArray, file: String, stem: String, catalog: Catalog, report: ImportReport): PendingImport {
        val cube = CubeFile.parse(String(bytes, Charsets.UTF_8))
        val lut = cube.toLut(report)
        report.mapped("LUT", "${lut.size}³ 色表，放在冲印之后、调整之前")
        val look = SpkLook(name = cube.title.ifBlank { stem }.take(60), lut = SpkLut(),
            source = SpkSource("cube", file.take(120), "导入的 LUT"))
        return style(look, file, catalog, report, storeLut(lut, SpkLut.SPACE_SRGB))
    }

    private fun fromHald(bytes: ByteArray, file: String, stem: String, catalog: Catalog, report: ImportReport): PendingImport {
        // The size first: a picture that is not a HaldCLUT is never decoded.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth; val h = bounds.outHeight
        if (HaldClut.level(w, h) == null) throw ImportException("图片 ${w}×$h 不是 HaldCLUT（边长须为 8、27、64…4096 的正方形）")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false; inPremultiplied = false
        }) ?: throw ImportException("PNG 解码失败")
        val pixels = IntArray(w * h)
        try { bitmap.getPixels(pixels, 0, w, 0, 0, w, h) } finally { bitmap.recycle() }
        val lut = HaldClut.toLut(pixels, w, h, report)
        report.mapped("HaldCLUT", "${lut.size}³ 色表，放在冲印之后、调整之前")
        val look = SpkLook(name = stem.take(60), lut = SpkLut(), source = SpkSource("haldclut", file.take(120), "导入的 HaldCLUT"))
        return style(look, file, catalog, report, storeLut(lut, SpkLut.SPACE_SRGB))
    }

    private fun recipe(o: JSONObject, size: Int, file: String, catalog: Catalog, report: ImportReport): PendingImport {
        if (size > FilmRecipe.MAX_RECIPE_BYTES) throw ImportException("胶片方案超过 64 KB")
        val rec = FilmRecipe.parseRecipe(o, file, { id -> catalog.film(id) != null && !isAlias(id) }, { catalog.paper(it) != null }, report)
        val id = profiles.freeId(rec.id) { catalog.film(it) != null || catalog.paper(it) != null }
        val r = rec.copy(id = id)
        return PendingImport(file, ImportKind.FILM_RECIPE, r.toLook(), "", report, id, r.toJson(), "filming", r.base)
    }

    private fun filmData(o: JSONObject, size: Int, file: String, stem: String, catalog: Catalog, report: ImportReport): PendingImport {
        if (size > FilmRecipe.MAX_DATA_BYTES) throw ImportException("胶片数据超过 4 MB")
        val stage = FilmRecipe.validateData(o, report)
        val label = o.optJSONObject("info")?.optString("name").orEmpty().trim().ifEmpty { stem }.take(60)
        val id = profiles.freeId(FilmRecipe.stockId(label)) { catalog.film(it) != null || catalog.paper(it) != null }
        val engine = if (stage == "filming") SpkEngine(film = id) else SpkEngine(paper = id)
        val look = SpkLook(name = label, category = "胶片方案", engine = engine, source = SpkSource("film-data", file.take(120), "导入的胶片数据"))
        return PendingImport(file, ImportKind.FILM_DATA, look, "", report, id, FilmRecipe.storedData(o, id), stage)
    }

    /** A style whose stocks are checked against [catalog]: one this version lacks keeps the photo's own. */
    private fun style(look: SpkLook, file: String, catalog: Catalog, report: ImportReport, lutId: String = ""): PendingImport {
        var e = look.engine
        e.film?.let { if (catalog.film(it) == null) { report.ignored("engine.film", "胶片“$it”不在当前版本里，套用时沿用照片的胶片"); e = e.copy(film = null) } }
        e.paper?.let { if (catalog.paper(it) == null) { report.ignored("engine.paper", "相纸“$it”不在当前版本里，套用时沿用照片的相纸"); e = e.copy(paper = null) } }
        return PendingImport(file, ImportKind.STYLE, look.copy(engine = e, lut = if (lutId.isEmpty()) null else look.lut), lutId, report)
    }

    private fun storeLut(lut: Lut3, space: String): String = luts.newId().also { luts.save(it, lut, space) }

    /** Whether a bundled or user stock is itself an alias (the engine refuses an alias of an alias). */
    private fun isAlias(id: String): Boolean {
        val f = File(engineDir, "profiles/$id.json")
        if (!f.isFile || f.length() > FilmRecipe.MAX_DATA_BYTES) return true
        return runCatching { JSONObject(f.readText()).has("derived_from") }.getOrDefault(true)
    }

    private fun nameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifEmpty { "导入" }
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        return name.take(120) to size
    }

    // --- keeping -------------------------------------------------------------

    /** Keeps a style import; [strength] (0..1) becomes its strength in the style row. */
    fun saveStyle(p: PendingImport, name: String, category: String, strength: Double): UserStyle {
        val look = p.look.copy(name = name.trim().ifEmpty { p.look.name }.take(60),
            category = category.trim().ifEmpty { SpkLook.DEFAULT_CATEGORY }.take(20))
        val style = UserStyle(styles.newId(), look, p.lutId, System.currentTimeMillis())
        styles.save(style)
        context.getSharedPreferences(STYLE_STRENGTH_PREFS, 0).edit()
            .putFloat(style.id, strength.coerceIn(0.0, 1.0).toFloat()).apply()
        return style
    }

    /** Stores a film import and copies it into the engine directory. */
    fun saveFilm(p: PendingImport) {
        profiles.store(p.profileId, requireNotNull(p.profileJson))
        profiles.syncInto(engineDir)
    }

    fun dropFilm(id: String) = profiles.delete(id, engineDir)

    /** A cancelled import: its colour table goes unless a kept style uses it. */
    fun discard(p: PendingImport) {
        if (p.lutId.isNotEmpty() && !styles.usesLut(p.lutId)) luts.delete(p.lutId)
    }

    fun deleteStyle(id: String) {
        val gone = styles.delete(id) ?: return
        if (gone.lutId.isNotEmpty() && !styles.usesLut(gone.lutId)) luts.delete(gone.lutId)
    }

    // --- the native side ------------------------------------------------------

    /**
     * Engine thread: sends [look]'s colour table to [handle] when its table or
     * strength differs from [previous]'s (null: always). A missing table clears it.
     */
    fun applyLut(handle: Long, look: Look, previous: Look?) {
        if (handle == 0L) return
        val r = look.resolved()
        val p = previous?.resolved()
        if (p != null && p.lut == r.lut && (r.lut.isEmpty() || p.lutStrength == r.lutStrength)) return
        val table = r.lut.takeIf { it.isNotEmpty() && r.lutStrength > 0 }?.let(luts::load)
        if (table == null) NativeEngine.setLut(handle, null, 0, 0f)
        else NativeEngine.setLut(handle, table.data, table.size, r.lutStrength.toFloat())
    }

    // --- exporting -------------------------------------------------------------

    /**
     * [edit] as a .spklook in the cache's share/ directory: film, paper, print and
     * effects, Layer 2 with the palette folded in, the colour table as imported,
     * and [thumbJpeg]. Per-photo geometry, white balance and borders stay out.
     */
    fun exportLook(edit: Edit, catalog: Catalog, name: String, thumbJpeg: ByteArray?): File {
        val r = edit.look.resolved()
        val original = r.lut.takeIf { it.isNotEmpty() }?.let(luts::original)
        val title = name.trim().ifEmpty { "我的风格" }.take(60)
        val look = SpkLook(
            name = title,
            engine = SpkEngine(
                film = edit.film, paper = edit.paper, exposureEv = edit.exposureEv, printExposure = edit.printExposure,
                yCc = edit.yShift * Edit.FILTER_SHIFT_CC, mCc = edit.mShift * Edit.FILTER_SHIFT_CC,
                formatMm = edit.filmFormatMm,
                grain = if (edit.grainActive) edit.grainAmount else 0.0,
                halation = if (edit.halationActive) edit.halationAmount else 0.0,
                glare = if (edit.glareActive) edit.glareAmount else 0.0,
            ),
            layer2 = r.copy(lut = "", lutStrength = 1.0),
            lut = original?.let { SpkLut(strength = r.lutStrength, space = it.second) },
            source = SpkSource("spektralab-x", "", catalog.film(edit.film)?.let { "Spektralab-X 导出" } ?: ""),
        )
        val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, safeName(title) + ".spklook")
        file.outputStream().use { SpkPackage(look, original?.first, thumbJpeg).write(it) }
        return file
    }

    companion object {
        const val MAX_LUT_FILE = 64L shl 20
        /** At most this many files from one pick or share. */
        const val MAX_BATCH = 50
        private val CUBE_HEAD = Regex("(?m)^\\s*(LUT_3D_SIZE|LUT_1D_SIZE|TITLE\\s)")

        private fun isPng(b: ByteArray) = b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

        private fun safeName(name: String) =
            name.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_' || c == ' ') c else '_' }.joinToString("").trim().ifEmpty { "style" }

        /** A 512 px JPEG of [photo], made on the caller's thread; null when it cannot be read. */
        fun thumbnail(photo: Bitmap?): ByteArray? = runCatching {
            if (photo == null || photo.isRecycled) return null
            val s = 512.0 / max(photo.width, photo.height)
            val small = if (s >= 1) photo else Bitmap.createScaledBitmap(photo,
                max(1, (photo.width * s).roundToInt()), max(1, (photo.height * s).roundToInt()), true)
            val soft = if (small.config == Bitmap.Config.HARDWARE) small.copy(Bitmap.Config.ARGB_8888, false) else small
            ByteArrayOutputStream().also { soft.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        }.getOrNull()
    }
}

/**
 * This look as a one-tap [Style] on [edit]: every engine field the file leaves
 * out keeps the photo's own value, so a Layer 2-only file (XMP, .cube) never
 * changes the film. A file that names a film but no paper lets the film pick
 * its own print (Style.applyTo falls back to the film's target print).
 */
fun SpkLook.toStyle(id: String, edit: Edit, lutId: String): Style {
    val e = engine
    return Style(
        id = id, name = name, group = category.ifBlank { SpkLook.DEFAULT_CATEGORY },
        film = e.film ?: edit.film,
        paper = e.paper ?: if (e.film != null) "" else edit.paper,
        exposureEv = e.exposureEv ?: edit.exposureEv,
        printExposure = e.printExposure ?: edit.printExposure,
        yCc = e.yCc ?: edit.yShift * Edit.FILTER_SHIFT_CC,
        mCc = e.mCc ?: edit.mShift * Edit.FILTER_SHIFT_CC,
        formatMm = e.formatMm ?: edit.filmFormatMm,
        grain = e.grain ?: if (edit.grainActive) edit.grainAmount else 0.0,
        halation = e.halation ?: if (edit.halationActive) edit.halationAmount else 0.0,
        glare = e.glare ?: if (edit.glareActive) edit.glareAmount else 0.0,
        look = layer2.copy(lut = lutId, lutStrength = if (lutId.isEmpty()) 1.0 else lut?.strength ?: 1.0),
        inspiredBy = source?.note?.takeIf { it.isNotBlank() }
            ?: author.takeIf { it.isNotBlank() }?.let { "作者 $it" } ?: "导入的风格",
    )
}
