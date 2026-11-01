package com.dearded.spektralab

import org.json.JSONObject
import java.io.File

data class Stock(
    val id: String,
    val label: String,
    val positive: Boolean,
    val bw: Boolean,
    val targetPrint: String?,
)

/** Film and paper stocks, read from the bundled profiles the way the desktop host reads them. */
data class Catalog(val films: List<Stock>, val papers: List<Stock>) {
    fun film(id: String) = films.firstOrNull { it.id == id }
    fun paper(id: String) = papers.firstOrNull { it.id == id }

    companion object {
        val EMPTY = Catalog(emptyList(), emptyList())

        fun load(engineDir: File): Catalog {
            val files = File(engineDir, "profiles").listFiles { f -> f.extension == "json" }.orEmpty()
            val roots = files.associate { it.nameWithoutExtension to JSONObject(it.readText()) }
            val films = mutableListOf<Stock>()
            val papers = mutableListOf<Stock>()
            for ((id, root) in roots) {
                var info = root.optJSONObject("info") ?: JSONObject()
                // An alias (CineStill 800T) takes its base's info, then its own keys over it.
                val base = root.optString("derived_from", "")
                if (base.isNotEmpty()) {
                    val baseInfo = roots[base]?.optJSONObject("info") ?: continue
                    val merged = JSONObject(baseInfo.toString())
                    for (key in info.keys()) merged.put(key, info.get(key))
                    info = merged
                }
                val stock = Stock(
                    id = id,
                    label = info.optString("name", id),
                    positive = info.optString("type") == "positive",
                    bw = info.optString("channel_model") == "bw",
                    targetPrint = if (info.isNull("target_print")) null else info.optString("target_print"),
                )
                when (info.optString("stage")) {
                    "filming" -> films += stock
                    "printing" -> papers += stock
                }
            }
            return Catalog(films.sortedBy { it.label }, papers.sortedBy { it.label })
        }
    }
}

/** The user-facing subset of the engine's parameters (names as on the wire). */
data class Edit(
    val film: String = "kodak_portra_400",
    val paper: String = "kodak_portra_endura",
    val scanFilm: Boolean = false,
    val autoExposure: Boolean = true,
    val exposureEv: Double = 0.0,
    val filmFormatMm: Double = 35.0,
    val printExposure: Double = 1.0,
    val yShift: Double = 0.0,
    val mShift: Double = 0.0,
    val grainActive: Boolean = true,
    val grainAmount: Double = 1.0,
    val halationActive: Boolean = true,
    val halationAmount: Double = 1.0,
    val glareActive: Boolean = true,
    val glareAmount: Double = 1.0,
    // Geometry, applied by the engine before the film (shoot layer). Crop
    // fractions are of the source frame before the quarter turns.
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val cropX: Double = 0.0,
    val cropY: Double = 0.0,
    val cropW: Double = 1.0,
    val cropH: Double = 1.0,
    /** The crop's chosen output aspect; app-side only, never sent (0 = whole frame). */
    val cropAspect: Double = 0.0,
    // RFC-032 film edge and RFC-031 date back. With the edge on the frame must
    // already be the gate's shape (the engine refuses anything 5 % off), so
    // turning it on also crops to the gate; see withFilmEdge.
    val filmEdge: Boolean = false,
    val edgeFormat: String = "135",
    val dateImprint: Boolean = false,
    /** Already formatted the way a date back writes it, e.g. `'26 11 1`. */
    val dateText: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply { for ((key, get) in FIELDS) put(key, get(this@Edit)) }

    /**
     * Only changed fields: an unchanged shoot-side field would invalidate the
     * cached negative. `scan_film` goes with every film change, otherwise a
     * slide's direct-scan mode would survive switching back to a negative.
     */
    fun delta(previous: Edit): JSONObject = JSONObject().apply {
        for ((key, get) in FIELDS) {
            val value = get(this@Edit)
            if (value != get(previous)) put(key, value)
        }
        if (film != previous.film) put("scan_film", scanFilm)
    }

    companion object {
        private val FIELDS: List<Pair<String, (Edit) -> Any>> = listOf(
            "film_stock" to { e: Edit -> e.film },
            "print_stock" to { e: Edit -> e.paper },
            "scan_film" to { e: Edit -> e.scanFilm },
            "auto_exposure" to { e: Edit -> e.autoExposure },
            "exposure_compensation_ev" to { e: Edit -> e.exposureEv },
            "film_format_mm" to { e: Edit -> e.filmFormatMm },
            "print_exposure" to { e: Edit -> e.printExposure },
            "y_filter_shift" to { e: Edit -> e.yShift },
            "m_filter_shift" to { e: Edit -> e.mShift },
            "grain_active" to { e: Edit -> e.grainActive },
            "grain_amount" to { e: Edit -> e.grainAmount },
            "halation_active" to { e: Edit -> e.halationActive },
            "halation_amount" to { e: Edit -> e.halationAmount },
            "glare_active" to { e: Edit -> e.glareActive },
            "glare_amount" to { e: Edit -> e.glareAmount },
            "geometry_quarter_turns" to { e: Edit -> e.quarterTurns },
            "geometry_flip_h" to { e: Edit -> e.flipH },
            "geometry_crop_x" to { e: Edit -> e.cropX },
            "geometry_crop_y" to { e: Edit -> e.cropY },
            "geometry_crop_w" to { e: Edit -> e.cropW },
            "geometry_crop_h" to { e: Edit -> e.cropH },
            "overscan_active" to { e: Edit -> e.filmEdge },
            "overscan_format" to { e: Edit -> e.edgeFormat },
            "date_imprint_active" to { e: Edit -> e.dateImprint },
            "date_imprint_text" to { e: Edit -> e.dateText },
        )

        /** The session's resolved values, as `spk_get_params` reports them. */
        fun fromParams(p: JSONObject, fallback: Edit) = Edit(
            film = p.optString("film_stock", fallback.film),
            paper = p.optString("print_stock", fallback.paper),
            scanFilm = p.optBoolean("scan_film", fallback.scanFilm),
            autoExposure = p.optBoolean("auto_exposure", fallback.autoExposure),
            exposureEv = p.optDouble("exposure_compensation_ev", fallback.exposureEv),
            filmFormatMm = p.optDouble("film_format_mm", fallback.filmFormatMm),
            printExposure = p.optDouble("print_exposure", fallback.printExposure),
            yShift = p.optDouble("y_filter_shift", fallback.yShift),
            mShift = p.optDouble("m_filter_shift", fallback.mShift),
            grainActive = p.optBoolean("grain_active", fallback.grainActive),
            grainAmount = p.optDouble("grain_amount", fallback.grainAmount),
            halationActive = p.optBoolean("halation_active", fallback.halationActive),
            halationAmount = p.optDouble("halation_amount", fallback.halationAmount),
            glareActive = p.optBoolean("glare_active", fallback.glareActive),
            glareAmount = p.optDouble("glare_amount", fallback.glareAmount),
            quarterTurns = p.optInt("geometry_quarter_turns", fallback.quarterTurns),
            flipH = p.optBoolean("geometry_flip_h", fallback.flipH),
            cropX = p.optDouble("geometry_crop_x", fallback.cropX),
            cropY = p.optDouble("geometry_crop_y", fallback.cropY),
            cropW = p.optDouble("geometry_crop_w", fallback.cropW),
            cropH = p.optDouble("geometry_crop_h", fallback.cropH),
            filmEdge = p.optBoolean("overscan_active", fallback.filmEdge),
            edgeFormat = p.optString("overscan_format", fallback.edgeFormat),
            dateImprint = p.optBoolean("date_imprint_active", fallback.dateImprint),
            dateText = p.optString("date_imprint_text", fallback.dateText),
        )

        /** Gate long / short edge, mm, as engine/src/pipeline/overscan.cpp's kFormats. */
        val EDGE_FORMATS = listOf(
            Triple("135", "135", 36.25 / 24.3),
            Triple("135_half", "半格", 24.0 / 18.0),
            Triple("135_xpan", "XPan", 65.0 / 24.0),
            Triple("120_645", "645", 56.0 / 41.5),
            Triple("120_6x6", "6×6", 1.0),
            Triple("120_6x7", "6×7", 69.5 / 56.0),
            Triple("120_6x9", "6×9", 84.0 / 56.0),
            Triple("120_6x12", "6×12", 112.0 / 56.0),
            Triple("120_6x17", "6×17", 168.0 / 56.0),
        )

        /** The date back exists on 135 and 120 only (no panoramic formats, RFC-031). */
        fun dateBackFits(format: String) = format != "135_xpan" && format != "120_6x12" && format != "120_6x17"

        /**
         * The film edge on [format]: the crop becomes the gate's shape (kept in
         * the frame's current orientation), because the engine lays the picture
         * into the gate and refuses a frame more than 5 % off its shape.
         */
        fun withFilmEdge(edit: Edit, w: Int, h: Int, on: Boolean, format: String = edit.edgeFormat): Edit {
            if (!on) return edit.copy(filmEdge = false, edgeFormat = format)
            val gate = EDGE_FORMATS.firstOrNull { it.first == format }?.third ?: 1.5
            val outW = if (edit.quarterTurns % 2 == 1) h * edit.cropH else w * edit.cropW
            val outH = if (edit.quarterTurns % 2 == 1) w * edit.cropW else h * edit.cropH
            val aspect = if (outW >= outH) gate else 1.0 / gate
            return centredCrop(edit.copy(filmEdge = true, edgeFormat = format, cropAspect = aspect), w, h, aspect)
        }

        /** EXIF `yyyy:MM:dd ...` as a date back prints it (year first, no leading zeros). */
        fun dateBackText(exifDate: String?): String {
            val m = Regex("""^(\d{4}):(\d{2}):(\d{2})""").find(exifDate ?: "") ?: return ""
            val (y, mo, d) = m.destructured
            return "'${y.takeLast(2)} ${mo.toInt()} ${d.toInt()}"
        }

        /**
         * A centred crop of [aspect] (width / height of the *output*, after the
         * quarter turns) on a w x h source; 0 means the whole frame.
         */
        fun centredCrop(edit: Edit, w: Int, h: Int, aspect: Double): Edit {
            if (aspect <= 0.0 || w <= 0 || h <= 0) return edit.copy(cropX = 0.0, cropY = 0.0, cropW = 1.0, cropH = 1.0)
            // In source orientation an odd number of turns inverts the target aspect.
            val a = if (edit.quarterTurns % 2 == 1) 1.0 / aspect else aspect
            val src = w.toDouble() / h
            return if (src > a) {
                val cw = a / src
                edit.copy(cropX = (1 - cw) / 2, cropY = 0.0, cropW = cw, cropH = 1.0)
            } else {
                val ch = src / a
                edit.copy(cropX = 0.0, cropY = (1 - ch) / 2, cropW = 1.0, cropH = ch)
            }
        }

        /**
         * LibRaw decodes to linear ProPhoto ("ProPhoto RGB", linear). Gallery
         * images arrive encoded, in "sRGB" or "Display P3" (the engine's names).
         */
        fun openingParams(edit: Edit, inputSpace: String, linear: Boolean): JSONObject = edit.toJson().apply {
            put("input_color_space", inputSpace)
            put("input_cctf_decoding", !linear)
            put("output_color_space", "sRGB")
            put("output_cctf_encoding", true)
            put("extended_dynamic_range", false)
            put("product_defaults", true)
        }
    }
}

data class SourceInfo(
    val name: String,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val kind: String,
    val camera: String,
    /** RAW only: the camera's as-shot illuminant, and the chosen one (0 = as shot). */
    val asShotK: Double = 0.0,
    val asShotTint: Double = 0.0,
    val wbK: Double = 0.0,
    val wbTint: Double = 0.0,
)
