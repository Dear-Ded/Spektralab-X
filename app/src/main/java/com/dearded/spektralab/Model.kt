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

    /** [e] with the app-side facts the wire needs that only the catalog knows. */
    fun normalize(e: Edit): Edit {
        val mono = film(e.film)?.bw == true && paper(e.paper)?.bw == true
        return if (mono == e.monoPrint) e else e.copy(monoPrint = mono)
    }

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
    // SpektraLab's own default pair for a new frame (Params.swift, params.ts):
    // Supra Endura, not the reference's Portra Endura.
    val paper: String = "kodak_supra_endura",
    val scanFilm: Boolean = false,
    val autoExposure: Boolean = true,
    /** The meter's intent. Upstream sends "balanced" (a log average) for every new frame;
     *  left unsent the engine meters "center_weighted", an arithmetic mean a bright sky drags up. */
    val aeMethod: String = "balanced",
    val exposureEv: Double = 0.0,
    val filmFormatMm: Double = 36.0,
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
    // The rest of the film edge (engine wire names in FIELDS).
    val edgeMode: String = "strip",
    val edgeHoles: String = "white",
    val edgeGate: String = "auto",
    val edgeCarrier: String = "none",
    val edgeFog: Double = 1.0,
    val edgeLeaks: Double = 0.0,
    /** "The camera" and "this frame": gate centring, weave, leak positions. A die roll re-draws them. */
    val edgeCameraSeed: Int = 1,
    val edgeFrameSeed: Int = 1,
    /** EXIF aperture; sets the gate's penumbra (0 = f/5.6 assumed). */
    val edgeFNumber: Double = 0.0,
    val dateStyle: String = "lcd",
    val dateCorner: String = "br",
    /** The paper around the print. App-side only: drawn after the engine, never sent. */
    val frame: FrameSpec = FrameSpec(),
    /** The border preset the user picked, for the chip row. App-side only. */
    val borderId: String = "none",
    /** Black-and-white film on black-and-white paper; set from the catalog by the view model. */
    val monoPrint: Boolean = false,
    /** Layer 2, the adjustments to the finished print (Look.kt). App-side: re-packed, never developed. */
    val look: Look = Look.NEUTRAL,
) {
    /** A quarter turn by [by]: the same crop region, so its output aspect flips on odd turns. */
    fun turned(by: Int) = copy(
        quarterTurns = (quarterTurns + by) % 4,
        cropAspect = if (by % 2 == 1 && cropAspect > 0) 1.0 / cropAspect else cropAspect,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        for ((key, get) in FIELDS) put(key, get(this@Edit))
        if (monoPrint) putMonoPack(this)
    }

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
        // The engine's neutral-filter table has no row for a silver paper, so a
        // session coming from a colour pair keeps that pair's pack (~55/65 CC).
        // Going back to colour, the engine takes the colour row again by itself.
        if (monoPrint && (!previous.monoPrint || film != previous.film || paper != previous.paper)) putMonoPack(this)
    }

    companion object {
        /** Multigrade filter 2 as the enlarger's C/M/Y (paper_grades.json grade "2"), as upstream sends it. */
        private fun putMonoPack(o: JSONObject) {
            o.put("c_filter_neutral", 0.0).put("m_filter_neutral", 0.0).put("y_filter_neutral", 68.0)
        }

        /** What the Y/M sliders' ends are, in CC. Unsent, the engine's 1 makes the whole slider one CC. */
        const val FILTER_SHIFT_CC = 40.0

        private val FIELDS: List<Pair<String, (Edit) -> Any>> = listOf(
            "film_stock" to { e: Edit -> e.film },
            "print_stock" to { e: Edit -> e.paper },
            "scan_film" to { e: Edit -> e.scanFilm },
            "auto_exposure" to { e: Edit -> e.autoExposure },
            "auto_exposure_method" to { e: Edit -> e.aeMethod },
            "exposure_compensation_ev" to { e: Edit -> e.exposureEv },
            "film_format_mm" to { e: Edit -> e.filmFormatMm },
            "print_exposure" to { e: Edit -> e.printExposure },
            "y_filter_shift" to { e: Edit -> e.yShift },
            "m_filter_shift" to { e: Edit -> e.mShift },
            "filter_shift_scale" to { _: Edit -> FILTER_SHIFT_CC },
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
            "overscan_mode" to { e: Edit -> e.edgeMode },
            "overscan_holes" to { e: Edit -> e.edgeHoles },
            "overscan_gate" to { e: Edit -> e.edgeGate },
            "overscan_carrier" to { e: Edit -> e.edgeCarrier },
            "overscan_fog" to { e: Edit -> e.edgeFog },
            "overscan_leaks" to { e: Edit -> e.edgeLeaks },
            "overscan_camera_seed" to { e: Edit -> e.edgeCameraSeed },
            "overscan_frame_seed" to { e: Edit -> e.edgeFrameSeed },
            "overscan_f_number" to { e: Edit -> e.edgeFNumber },
            "date_imprint_style" to { e: Edit -> e.dateStyle },
            "date_imprint_corner" to { e: Edit -> e.dateCorner },
        )

        /** The session's resolved values, as `spk_get_params` reports them. */
        fun fromParams(p: JSONObject, fallback: Edit) = Edit(
            film = p.optString("film_stock", fallback.film),
            paper = p.optString("print_stock", fallback.paper),
            scanFilm = p.optBoolean("scan_film", fallback.scanFilm),
            autoExposure = p.optBoolean("auto_exposure", fallback.autoExposure),
            aeMethod = p.optString("auto_exposure_method", fallback.aeMethod),
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
            cropAspect = fallback.cropAspect,   // app-side only; the engine does not know it
            filmEdge = p.optBoolean("overscan_active", fallback.filmEdge),
            edgeFormat = p.optString("overscan_format", fallback.edgeFormat),
            dateImprint = p.optBoolean("date_imprint_active", fallback.dateImprint),
            dateText = p.optString("date_imprint_text", fallback.dateText),
            edgeMode = p.optString("overscan_mode", fallback.edgeMode),
            edgeHoles = p.optString("overscan_holes", fallback.edgeHoles),
            edgeGate = p.optString("overscan_gate", fallback.edgeGate),
            edgeCarrier = p.optString("overscan_carrier", fallback.edgeCarrier),
            edgeFog = p.optDouble("overscan_fog", fallback.edgeFog),
            edgeLeaks = p.optDouble("overscan_leaks", fallback.edgeLeaks),
            edgeCameraSeed = p.optInt("overscan_camera_seed", fallback.edgeCameraSeed),
            edgeFrameSeed = p.optInt("overscan_frame_seed", fallback.edgeFrameSeed),
            edgeFNumber = p.optDouble("overscan_f_number", fallback.edgeFNumber),
            dateStyle = p.optString("date_imprint_style", fallback.dateStyle),
            dateCorner = p.optString("date_imprint_corner", fallback.dateCorner),
            frame = fallback.frame,          // app-side only
            borderId = fallback.borderId,    // app-side only
            monoPrint = fallback.monoPrint,  // app-side only
            look = fallback.look,            // app-side only
        )

        /**
         * [preset] applied to [edit] on a [w] x [h] source: the film edge (with
         * its gate crop) or, without one, the preset's own centred crop.
         */
        fun withBorder(edit: Edit, preset: BorderPreset, w: Int, h: Int): Edit {
            val edge = preset.edge
            var next = edit.copy(frame = preset.frame.copy(perf = if (preset.edge != null) null else edit.frame.perf), borderId = preset.id)
            return if (edge != null) {
                next = next.copy(edgeMode = edge.mode, edgeHoles = edge.holes, edgeGate = edge.gate,
                    edgeCarrier = edge.carrier, edgeFog = edge.fog, edgeLeaks = edge.leaks)
                val e = withFilmEdge(next.copy(cropX = 0.0, cropY = 0.0, cropW = 1.0, cropH = 1.0), w, h, true, edge.format)
                if (!dateBackFits(edge.format)) e.copy(dateImprint = false) else e
            } else {
                next = next.copy(filmEdge = false)
                if (preset.crop > 0) centredCrop(next.copy(cropAspect = preset.crop), w, h, preset.crop)
                else if (edit.filmEdge) centredCrop(next.copy(cropAspect = 0.0), w, h, 0.0)
                else next
            }
        }

        /** Gate long / short edge, mm, as engine/src/pipeline/overscan.cpp's kFormats. */
        val EDGE_FORMATS = listOf(
            Triple("135", "135", 36.25 / 24.3),
            Triple("135_half", "半格", 24.0 / 18.0),
            Triple("135_xpan", "XPan", 65.0 / 24.0),
            Triple("120_645", "645", 56.0 / 41.5),
            Triple("120_6x6", "6×6", 1.0),
            Triple("120_6x7", "6×7", 69.5 / 56.0),
            Triple("120_6x8", "6×8", 76.0 / 56.0),
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
            // An unset camera clock writes 0000:00:00.
            if (y == "0000" || mo.toInt() !in 1..12 || d.toInt() !in 1..31) return ""
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
         * LibRaw decodes to linear ProPhoto ("ProPhoto RGB", linear), and gallery
         * photos are converted to it natively before the engine (openBitmap), as
         * upstream's host does: every session opens with ("ProPhoto RGB", true).
         */
        fun openingParams(edit: Edit, inputSpace: String, linear: Boolean): JSONObject = edit.toJson().apply {
            put("input_color_space", inputSpace)
            put("input_cctf_decoding", !linear)
            // The engine's working space, upstream's convention (RFC-018): the
            // print stays in ProPhoto and leaves it once per destination, through
            // OutputTransform (display: matrix and clip; file: CAM16 into the
            // file's space). Rendering straight into sRGB clipped the dyes there
            // and compressed them differently from the desktop.
            put("output_color_space", "ProPhoto RGB")
            put("output_cctf_encoding", true)
            put("extended_dynamic_range", false)
            put("product_defaults", true)
            // The live tier is what a slider drag renders. Upstream's 1600 px is
            // sized for a desktop GPU; 1024 is 2.4x fewer pixels on a phone, and
            // the 3400 px preview follows as soon as the finger lifts.
            put("preview_long_edge", LIVE_EDGE)
        }

        const val LIVE_EDGE = 1024
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
