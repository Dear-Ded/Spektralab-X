package com.dearded.spektralab

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** An import that cannot go on at all; the message is for the user. */
class ImportException(message: String) : Exception(message)

/**
 * What an import carried over and how, for the 未能转换的项目 screen:
 * mapped (same control, same scale), approximated (nearest control or scale)
 * or ignored (nothing here does it). redlamp's three outcomes.
 */
class ImportReport {
    enum class Kind { MAPPED, APPROXIMATED, IGNORED }
    data class Item(val kind: Kind, val what: String, val why: String = "")

    private val list = mutableListOf<Item>()
    val items: List<Item> get() = list
    /** Everything not carried over exactly. */
    val unconverted: List<Item> get() = list.filter { it.kind != Kind.MAPPED }

    fun mapped(what: String, why: String = "") { list += Item(Kind.MAPPED, what, why) }
    fun approximated(what: String, why: String = "") { list += Item(Kind.APPROXIMATED, what, why) }
    fun ignored(what: String, why: String = "") { list += Item(Kind.IGNORED, what, why) }
}

/** The engine side of a style. A null field keeps the open photo's own setting. */
data class SpkEngine(
    val film: String? = null,
    val paper: String? = null,
    val exposureEv: Double? = null,
    val printExposure: Double? = null,
    val yCc: Double? = null,
    val mCc: Double? = null,
    val formatMm: Double? = null,
    val grain: Double? = null,
    val halation: Double? = null,
    val glare: Double? = null,
) {
    val empty get() = this == SpkEngine()
}

/** The LUT beside look.json: always the `lut.cube` entry; [space] is "srgb" or "prophoto". */
data class SpkLut(val strength: Double = 1.0, val space: String = SPACE_SRGB) {
    companion object {
        const val SPACE_SRGB = "srgb"
        const val SPACE_PROPHOTO = "prophoto"
    }
}

/** Where an imported style came from; [note] doubles as its "灵感来自 …" line. */
data class SpkSource(val kind: String = "", val file: String = "", val note: String = "")

/**
 * `.spklook` version 1, this app's own style file (docs/STYLES.md section 7.1).
 * Fields go by name: missing ones take their defaults and unknown ones are
 * reported, so a file from a newer version still opens. Every number is clamped
 * to its slider's range. Plain Kotlin and org.json only.
 */
data class SpkLook(
    val name: String,
    val author: String = "",
    val license: String = "",
    val category: String = DEFAULT_CATEGORY,
    val engine: SpkEngine = SpkEngine(),
    val layer2: Look = Look.NEUTRAL,
    val lut: SpkLut? = null,
    val source: SpkSource? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("name", name)
        if (author.isNotEmpty()) put("author", author)
        if (license.isNotEmpty()) put("license", license)
        put("category", category)
        if (!engine.empty) put("engine", engineJson(engine))
        put("layer2", layer2Json(layer2))
        lut?.let { put("lut", JSONObject().put("file", LUT_ENTRY).put("strength", it.strength).put("space", it.space)) }
        source?.let { s ->
            put("source", JSONObject().apply {
                if (s.kind.isNotEmpty()) put("kind", s.kind)
                if (s.file.isNotEmpty()) put("file", s.file)
                if (s.note.isNotEmpty()) put("note", s.note)
            })
        }
    }

    companion object {
        const val FORMAT = "spklook"
        const val VERSION = 1
        const val DEFAULT_CATEGORY = "导入"
        const val LOOK_ENTRY = "look.json"
        const val LUT_ENTRY = "lut.cube"
        const val THUMB_ENTRY = "thumb.jpg"

        private val TOP = setOf("format", "version", "name", "author", "license", "category", "engine", "layer2", "lut", "source")
        private val ENGINE = setOf("film", "paper", "exposure_ev", "print_exposure", "y_cc", "m_cc", "format_mm", "grain", "halation", "glare")
        private val LAYER2 = setOf("temperature", "tint", "exposure", "contrast", "brightness", "saturation", "vibrance",
            "highlights", "shadows", "black_point", "white_point", "fade", "vignette", "vignette_mid", "wheels", "hsl")
        private val WHEELS = listOf("global", "shadows", "midtones", "highlights")
        /** Look.hsl order (layer2.cpp kBandHue), with Lightroom's band names. */
        val HSL_BANDS = listOf("red", "orange", "yellow", "green", "aqua", "blue", "purple", "magenta")
        private val HSV = setOf("hue", "sat", "lum")
        private val STOCK = Regex("[A-Za-z0-9_-]{1,96}")

        /** [o] read as look.json; [fallbackName] when it names nothing. */
        fun fromJson(o: JSONObject, report: ImportReport, fallbackName: String): SpkLook {
            val format = o.optString("format", FORMAT)
            if (format != FORMAT) throw ImportException("不是 .spklook 文件（format 为 “${format.take(40)}”）")
            val version = o.optInt("version", VERSION)
            if (version > VERSION) report.ignored("version", "文件是第 $version 版，本应用只认识第 $VERSION 版，新字段可能没读到")
            val r = Fields(report)
            r.unknown(o, "", TOP)
            val lut = o.optJSONObject("lut")?.let { l ->
                r.unknown(l, "lut", setOf("file", "strength", "space"))
                val file = l.optString("file", LUT_ENTRY)
                if (file != LUT_ENTRY) report.ignored("lut.file", "只认 $LUT_ENTRY，“${file.take(40)}”不会读")
                val space = l.optString("space", SpkLut.SPACE_SRGB).lowercase()
                SpkLut(
                    strength = r.num(l, "strength", "lut", 0.0, 1.0, 1.0),
                    space = if (space == SpkLut.SPACE_PROPHOTO) space else {
                        if (space != SpkLut.SPACE_SRGB) report.ignored("lut.space", "不认识“${space.take(20)}”，按 sRGB 处理")
                        SpkLut.SPACE_SRGB
                    },
                )
            }
            val source = o.optJSONObject("source")?.let { s ->
                r.unknown(s, "source", setOf("kind", "file", "note"))
                SpkSource(s.optString("kind").take(40), s.optString("file").take(200), s.optString("note").take(200))
            }
            return SpkLook(
                name = o.optString("name").trim().take(60).ifEmpty { fallbackName },
                author = o.optString("author").trim().take(100),
                license = o.optString("license").trim().take(60),
                category = o.optString("category").trim().take(20).ifEmpty { DEFAULT_CATEGORY },
                engine = r.obj(o, "engine")?.let { readEngine(it, r) } ?: SpkEngine(),
                layer2 = r.obj(o, "layer2")?.let { readLayer2(it, r) } ?: Look.NEUTRAL,
                lut = lut,
                source = source,
            )
        }

        internal fun readEngine(e: JSONObject, r: Fields): SpkEngine {
            r.unknown(e, "engine", ENGINE)
            fun stock(key: String): String? {
                if (!e.has(key) || e.isNull(key)) return null
                val id = e.optString(key)
                if (STOCK.matches(id)) return id
                r.report.ignored("engine.$key", "不是有效的胶片 id")
                return null
            }
            return SpkEngine(
                film = stock("film"), paper = stock("paper"),
                exposureEv = r.opt(e, "exposure_ev", "engine", -4.0, 4.0),
                printExposure = r.opt(e, "print_exposure", "engine", 0.2, 3.0),
                yCc = r.opt(e, "y_cc", "engine", -Edit.FILTER_SHIFT_CC, Edit.FILTER_SHIFT_CC),
                mCc = r.opt(e, "m_cc", "engine", -Edit.FILTER_SHIFT_CC, Edit.FILTER_SHIFT_CC),
                formatMm = r.opt(e, "format_mm", "engine", 4.0, 200.0),
                grain = r.opt(e, "grain", "engine", 0.0, 2.0),
                halation = r.opt(e, "halation", "engine", 0.0, 4.0),
                glare = r.opt(e, "glare", "engine", 0.0, 5.0),
            )
        }

        // Ranges as AdjustPanel.kt's sliders.
        private fun readLayer2(l: JSONObject, r: Fields): Look {
            r.unknown(l, "layer2", LAYER2)
            fun n(key: String, lo: Double, hi: Double, def: Double = 0.0) = r.num(l, key, "layer2", lo, hi, def)
            val wheels = r.obj(l, "wheels", "layer2")
            wheels?.let { r.unknown(it, "layer2.wheels", WHEELS.toSet()) }
            fun zone(i: Int): Zone {
                val key = WHEELS[i]
                val z = wheels?.let { r.obj(it, key, "layer2.wheels") } ?: return Zone()
                val path = "layer2.wheels.$key"
                r.unknown(z, path, HSV)
                return Zone(hue = r.hue(z, "hue", path), sat = r.num(z, "sat", path, 0.0, 100.0, 0.0),
                    lum = r.num(z, "lum", path, -100.0, 100.0, 0.0))
            }
            val hsl = r.obj(l, "hsl", "layer2")
            hsl?.let { r.unknown(it, "layer2.hsl", HSL_BANDS.toSet()) }
            fun band(i: Int): Band {
                val key = HSL_BANDS[i]
                val b = hsl?.let { r.obj(it, key, "layer2.hsl") } ?: return Band()
                val path = "layer2.hsl.$key"
                r.unknown(b, path, HSV)
                return Band(r.num(b, "hue", path, -100.0, 100.0, 0.0), r.num(b, "sat", path, -100.0, 100.0, 0.0),
                    r.num(b, "lum", path, -100.0, 100.0, 0.0))
            }
            return Look(
                temperature = n("temperature", -100.0, 100.0), tint = n("tint", -100.0, 100.0),
                exposure = n("exposure", -3.0, 3.0), contrast = n("contrast", -50.0, 50.0),
                brightness = n("brightness", -50.0, 50.0), saturation = n("saturation", -100.0, 100.0),
                vibrance = n("vibrance", -100.0, 100.0), highlights = n("highlights", -100.0, 100.0),
                shadows = n("shadows", -100.0, 100.0), blackPoint = n("black_point", 0.0, 50.0),
                whitePoint = n("white_point", 0.0, 50.0), fade = n("fade", 0.0, 100.0),
                vignette = n("vignette", -100.0, 100.0), vignetteMid = n("vignette_mid", 0.0, 100.0, 50.0),
                master = zone(0), shadowZone = zone(1), midZone = zone(2), highlightZone = zone(3),
                hsl = List(8) { band(it) },
            )
        }

        internal fun engineJson(e: SpkEngine) = JSONObject().apply {
            e.film?.let { put("film", it) }
            e.paper?.let { put("paper", it) }
            e.exposureEv?.let { put("exposure_ev", it) }
            e.printExposure?.let { put("print_exposure", it) }
            e.yCc?.let { put("y_cc", it) }
            e.mCc?.let { put("m_cc", it) }
            e.formatMm?.let { put("format_mm", it) }
            e.grain?.let { put("grain", it) }
            e.halation?.let { put("halation", it) }
            e.glare?.let { put("glare", it) }
        }

        private fun layer2Json(l: Look) = JSONObject().apply {
            put("temperature", l.temperature); put("tint", l.tint); put("exposure", l.exposure)
            put("contrast", l.contrast); put("brightness", l.brightness); put("saturation", l.saturation)
            put("vibrance", l.vibrance); put("highlights", l.highlights); put("shadows", l.shadows)
            put("black_point", l.blackPoint); put("white_point", l.whitePoint); put("fade", l.fade)
            put("vignette", l.vignette); put("vignette_mid", l.vignetteMid)
            put("wheels", JSONObject().apply {
                listOf(l.master, l.shadowZone, l.midZone, l.highlightZone).forEachIndexed { i, z ->
                    put(WHEELS[i], JSONObject().put("hue", z.hue).put("sat", z.sat).put("lum", z.lum))
                }
            })
            put("hsl", JSONObject().apply {
                HSL_BANDS.forEachIndexed { i, key ->
                    val b = l.hsl.getOrElse(i) { Band() }
                    put(key, JSONObject().put("hue", b.hue).put("sat", b.sat).put("lum", b.lum))
                }
            })
        }
    }
}

/** Typed, clamped reads that note every surprise in [report]. */
internal class Fields(val report: ImportReport) {
    fun unknown(o: JSONObject, path: String, known: Set<String>) {
        for (k in o.keys()) if (k !in known) report.ignored(join(path, k), "不认识的字段")
    }

    fun obj(o: JSONObject, key: String, path: String = ""): JSONObject? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optJSONObject(key) ?: run { report.ignored(join(path, key), "应为对象"); null }
    }

    /** [key] clamped to lo..hi, or null when absent. */
    fun opt(o: JSONObject, key: String, path: String, lo: Double, hi: Double): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        val raw = o.optDouble(key, Double.NaN)
        if (!raw.isFinite()) { report.ignored(join(path, key), "不是数字"); return null }
        val v = raw.coerceIn(lo, hi)
        if (v != raw) report.approximated(join(path, key), "${fmt(raw)} 超出范围，已夹到 ${fmt(lo)}…${fmt(hi)}")
        return v
    }

    fun num(o: JSONObject, key: String, path: String, lo: Double, hi: Double, def: Double) = opt(o, key, path, lo, hi) ?: def

    /** A wheel's hue, taken modulo 360 rather than clamped. */
    fun hue(o: JSONObject, key: String, path: String): Double {
        val raw = opt(o, key, path, -1e6, 1e6) ?: return 0.0
        return ((raw % 360.0) + 360.0) % 360.0
    }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    companion object {
        fun fmt(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString() else "%.3g".format(java.util.Locale.ROOT, v)
    }
}

/** A .spklook read from, or about to be written to, a file: the look and the entries beside it. */
class SpkPackage(val look: SpkLook, val lutCube: ByteArray? = null, val thumbJpeg: ByteArray? = null) {
    /** As a zip. [out] is finished, not closed. */
    fun write(out: OutputStream) {
        val zip = ZipOutputStream(out)
        fun put(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        }
        put(SpkLook.LOOK_ENTRY, look.toJson().toString(2).toByteArray(Charsets.UTF_8))
        if (look.lut != null) lutCube?.let { put(SpkLook.LUT_ENTRY, it) }
        thumbJpeg?.let { put(SpkLook.THUMB_ENTRY, it) }
        zip.finish()
    }

    companion object {
        const val MAX_FILE = 16L shl 20
        const val MAX_LOOK = 256L shl 10
        const val MAX_LUT = 12L shl 20
        const val MAX_THUMB = 2L shl 20
        const val MAX_ENTRIES = 16

        fun isZip(head: ByteArray) = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 3.toByte() && head[3] == 4.toByte()

        /**
         * A .spklook zip or a bare look.json, already read into [bytes] (at most
         * [MAX_FILE]). Entries are only ever read by their name into memory, never
         * written out by it, so a hostile entry name cannot reach the file system.
         */
        fun read(bytes: ByteArray, report: ImportReport, fallbackName: String): SpkPackage {
            if (bytes.size > MAX_FILE) throw ImportException("文件太大（超过 ${MAX_FILE shr 20} MB）")
            if (!isZip(bytes)) {
                if (bytes.size > MAX_LOOK) throw ImportException("look.json 太大（超过 ${MAX_LOOK shr 10} KB）")
                val look = SpkLook.fromJson(parseJson(bytes, SpkLook.LOOK_ENTRY), report, fallbackName)
                if (look.lut != null) report.ignored("lut", "单独的 look.json 带不了 LUT，需要用 .spklook 压缩包")
                return SpkPackage(look.copy(lut = null))
            }
            val entries = HashMap<String, ByteArray>()
            var count = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    // Android 14+ refuses names with ".." or a leading "/" here.
                    val e = try { zip.nextEntry } catch (x: ZipException) {
                        throw ImportException("压缩包损坏或含有不安全的路径")
                    } catch (x: IllegalArgumentException) {
                        throw ImportException("压缩包里的文件名无法识别")
                    } ?: break
                    if (++count > MAX_ENTRIES) throw ImportException("压缩包条目太多（超过 $MAX_ENTRIES 个）")
                    val name = e.name
                    val limit = when (name) {
                        SpkLook.LOOK_ENTRY -> MAX_LOOK
                        SpkLook.LUT_ENTRY -> MAX_LUT
                        SpkLook.THUMB_ENTRY -> MAX_THUMB
                        else -> -1L
                    }
                    when {
                        e.isDirectory -> {}
                        limit < 0 -> report.ignored(name.take(80), "不是 .spklook 的条目，未读取")
                        name in entries -> report.ignored(name, "重复的条目，只用第一个")
                        else -> entries[name] = readBounded(zip, limit, name)
                    }
                }
            }
            val lookBytes = entries[SpkLook.LOOK_ENTRY] ?: throw ImportException("压缩包里没有 look.json")
            var look = SpkLook.fromJson(parseJson(lookBytes, SpkLook.LOOK_ENTRY), report, fallbackName)
            val lut = entries[SpkLook.LUT_ENTRY]
            if (look.lut != null && lut == null) {
                report.ignored("lut", "压缩包里没有 ${SpkLook.LUT_ENTRY}")
                look = look.copy(lut = null)
            } else if (look.lut == null && lut != null) {
                look = look.copy(lut = SpkLut())
            }
            return SpkPackage(look, lut, entries[SpkLook.THUMB_ENTRY])
        }

        /** All of [input], or an ImportException once it passes [limit] bytes. */
        fun readBounded(input: InputStream, limit: Long, what: String): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw ImportException("$what 太大（超过 ${sizeText(limit)}）")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        fun parseJson(bytes: ByteArray, what: String): JSONObject {
            var text = String(bytes, Charsets.UTF_8)
            if (text.startsWith("﻿")) text = text.substring(1)
            return try { JSONObject(text) } catch (x: JSONException) { throw ImportException("$what 不是有效的 JSON") }
        }

        private fun sizeText(bytes: Long) = if (bytes >= 1L shl 20) "${bytes shr 20} MB" else "${bytes shr 10} KB"
    }
}
