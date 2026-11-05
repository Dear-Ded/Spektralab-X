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
        // The edge prints the film's own name for its gauge (UI.md E.1).
        val edge = Edit.edgeTextFor(film(e.film), e.edgeFormat)
        return if (mono == e.monoPrint && edge == e.edgeText) e else e.copy(monoPrint = mono, edgeText = edge)
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
    /** In-emulsion scatter, a mix weight 0..1 (engine halation_scatter_amount; upstream EffectStrengths.scatter). */
    val scatterAmount: Double = 1.0,
    /** Inter-layer DIR couplers, the stock's colour separation, 0..1.5 (upstream stops short of the wire's 4). */
    val couplersAmount: Double = 1.0,
    /** Grain drawn per sub-layer (fine + coarse) rather than as one layer. */
    val grainLayered: Boolean = true,
    /** Grain particle area, um^2 (patch 0007 grain_particle_area_um2): larger is coarser grain. */
    val grainArea: Double = 0.2,
    /** The scanner's unsharp mask (patch 0007): radius in full-size pixels, and gain. */
    val unsharpSigma: Double = 0.7,
    val unsharpAmount: Double = 0.7,
    // engine_deepdive 1.1-1.2: upstream fields the app did not send. Defaults are
    // the engine's own; at the default nothing is sent at open (EXTRA_FIELDS).
    /** Enlarger preflash, 0..0.3 of the engine's 0..1 (print side, live): greys the paper's highlights. */
    val preflash: Double = 0.0,
    /** density_curve_gamma: the negative's contrast, 0.7..1.4 of the engine's 0.2..4. */
    val negativeGamma: Double = 1.0,
    /** halation_boost_ev: extra highlight energy above protect_ev, 0..3 EV of 0..8. */
    val highlightBoostEv: Double = 0.0,
    /** lens_blur_um: a soft lens in front of the film, 0..30 um of 0..200. */
    val lensBlurUm: Double = 0.0,
    /** antihalation_removed: the film without its anti-halation layer (a red halo, like CineStill). */
    val noAntihalation: Boolean = false,
    /** camera_filter for black-and-white film: "" or w8 w11 w12 w15 w21 w25 w47 w58. */
    val cameraFilter: String = "",
    /** scanner_white_correction + scanner_black_correction: the lab scan's own black and white points. */
    val labLevels: Boolean = false,
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
    /** The stock's edge print ("KODAK GB 200-7"); resolved by Catalog.normalize from the film and gauge. */
    val edgeText: String = "",
    /** Print the film's real name on the edge (UI.md E.1); off leaves the edge print blank. App-side. */
    val edgeRealName: Boolean = true,
    /** The frame's number on the edge, 1..99; 0 is the frame seed's draw. */
    val edgeFrameNumber: Int = 0,
    /** frame (in the picture) or rebate (between frames, 135 with a film edge only). */
    val datePlacement: String = "frame",
    /** The shooting data printed beside the date ("1/250 F5.6 24mm"), already resolved; "" = none. */
    val dateDataText: String = "",
    val dateSize: Double = 1.0,
    val dateEv: Double = 3.5,
    val dateInsetX: Double = 3.0,
    val dateInsetY: Double = 2.4,
    /** japan ('26 11 4) or us (11 4 '26). App-side: the text is formatted here (withShooting). */
    val dateOrder: String = "japan",
    /** Print the shooting data as well as the date. App-side; resolved into [dateDataText]. */
    val dateWithData: Boolean = false,
    /** The paper around the print. App-side only: drawn after the engine, never sent. */
    val frame: FrameSpec = FrameSpec(),
    /** The border preset the user picked, for the chip row. App-side only. */
    val borderId: String = "none",
    /** Black-and-white film on black-and-white paper; set from the catalog by the view model. */
    val monoPrint: Boolean = false,
    /** Layer 2, the adjustments to the finished print (Look.kt). App-side: re-packed, never developed. */
    val look: Look = Look.NEUTRAL,
) {
    /** A date between frames needs a 135 film edge to print on (upstream's `between`, the wire's `rebate`). */
    val rebateDateFits: Boolean get() = filmEdge && (edgeFormat == "135" || edgeFormat == "135_half")

    /** A quarter turn by [by]: the same crop region, so its output aspect flips on odd turns. */
    fun turned(by: Int) = copy(
        quarterTurns = (quarterTurns + by) % 4,
        cropAspect = if (by % 2 == 1 && cropAspect > 0) 1.0 / cropAspect else cropAspect,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        for ((key, get) in FIELDS) put(key, get(this@Edit))
        // Only off their defaults, so a frame that never touches them opens on
        // exactly the wire it always did.
        val d = DEFAULT
        for ((key, get) in EXTRA_FIELDS) get(this@Edit).let { if (it != get(d)) put(key, it) }
        if (monoPrint) putMonoPack(this)
    }

    /**
     * Only changed fields: an unchanged shoot-side field would invalidate the
     * cached negative. `scan_film` goes with every film change, otherwise a
     * slide's direct-scan mode would survive switching back to a negative.
     */
    fun delta(previous: Edit): JSONObject = JSONObject().apply {
        for ((key, get) in FIELDS + EXTRA_FIELDS) {
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

        private val DEFAULT = Edit()

        /** The lens filters camera_filter accepts (engine camera_filters.hpp), "" for none. */
        val CAMERA_FILTERS = listOf("", "w8", "w11", "w12", "w15", "w21", "w25", "w47", "w58")

        /**
         * engine_deepdive 1.1-1.2. Clamped to params.cpp's ranges (one value out of
         * range and the engine refuses the whole delta). The lens filter only
         * reaches black-and-white film, and the lab's black/white points never a
         * direct scan, which the engine refuses for a slide.
         */
        private val EXTRA_FIELDS: List<Pair<String, (Edit) -> Any>> = listOf(
            "preflash_exposure" to { e: Edit -> e.preflash.coerceIn(0.0, 1.0) },
            "density_curve_gamma" to { e: Edit -> e.negativeGamma.coerceIn(0.2, 4.0) },
            "halation_boost_ev" to { e: Edit -> e.highlightBoostEv.coerceIn(0.0, 8.0) },
            "lens_blur_um" to { e: Edit -> e.lensBlurUm.coerceIn(0.0, 200.0) },
            "antihalation_removed" to { e: Edit -> e.noAntihalation },
            "camera_filter" to { e: Edit -> if (e.monoPrint && e.cameraFilter in CAMERA_FILTERS) e.cameraFilter else "" },
            "scanner_white_correction" to { e: Edit -> e.labLevels && !e.scanFilm },
            "scanner_black_correction" to { e: Edit -> e.labLevels && !e.scanFilm },
        )

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
            "halation_scatter_amount" to { e: Edit -> e.scatterAmount },
            "dir_couplers_amount" to { e: Edit -> e.couplersAmount },
            "grain_sublayers_active" to { e: Edit -> e.grainLayered },
            "grain_particle_area_um2" to { e: Edit -> e.grainArea },
            "scanner_unsharp_sigma" to { e: Edit -> e.unsharpSigma },
            "scanner_unsharp_amount" to { e: Edit -> e.unsharpAmount },
            "geometry_quarter_turns" to { e: Edit -> e.quarterTurns },
            "geometry_flip_h" to { e: Edit -> e.flipH },
            "geometry_crop_x" to { e: Edit -> e.cropX },
            "geometry_crop_y" to { e: Edit -> e.cropY },
            "geometry_crop_w" to { e: Edit -> e.cropW },
            "geometry_crop_h" to { e: Edit -> e.cropH },
            "overscan_active" to { e: Edit -> e.filmEdge },
            "overscan_format" to { e: Edit -> e.edgeFormat },
            "date_imprint_active" to { e: Edit -> e.dateImprint },
            // The data face prints the shooting data in place of the date.
            "date_imprint_text" to { e: Edit -> if (e.dateStyle == "data" && e.filmEdge) e.dateDataText else e.dateText },
            "overscan_mode" to { e: Edit -> e.edgeMode },
            "overscan_holes" to { e: Edit -> e.edgeHoles },
            "overscan_gate" to { e: Edit -> e.edgeGate },
            "overscan_carrier" to { e: Edit -> e.edgeCarrier },
            "overscan_fog" to { e: Edit -> e.edgeFog },
            "overscan_leaks" to { e: Edit -> e.edgeLeaks },
            "overscan_camera_seed" to { e: Edit -> e.edgeCameraSeed },
            "overscan_frame_seed" to { e: Edit -> e.edgeFrameSeed },
            "overscan_f_number" to { e: Edit -> e.edgeFNumber },
            // The data face goes between frames or in the margin: without a film edge it has nowhere to print.
            "date_imprint_style" to { e: Edit -> if (e.dateStyle == "data" && !e.filmEdge) "lcd" else e.dateStyle },
            "date_imprint_corner" to { e: Edit -> e.dateCorner },
            // Clamped to params.cpp's ranges: one value out of range and the engine refuses the whole delta.
            "overscan_edge_text" to { e: Edit -> if (e.edgeRealName) e.edgeText else "" },
            "overscan_frame_number" to { e: Edit -> e.edgeFrameNumber.coerceIn(0, 99) },
            "date_imprint_placement" to { e: Edit -> if (e.rebateDateFits) e.datePlacement else "frame" },
            "date_imprint_data_text" to { e: Edit -> if (e.filmEdge && e.dateWithData && e.dateStyle != "data") e.dateDataText else "" },
            "date_imprint_size" to { e: Edit -> e.dateSize.coerceIn(0.4, if (e.dateStyle == "data" && e.edgeFormat == "135_half") 1.0 else 3.0) },
            "date_imprint_ev" to { e: Edit -> e.dateEv.coerceIn(-2.0, 8.0) },
            "date_imprint_inset_x" to { e: Edit -> e.dateInsetX.coerceIn(0.0, 30.0) },
            "date_imprint_inset_y" to { e: Edit -> e.dateInsetY.coerceIn(0.0, 30.0) },
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
            scatterAmount = p.optDouble("halation_scatter_amount", fallback.scatterAmount),
            couplersAmount = p.optDouble("dir_couplers_amount", fallback.couplersAmount),
            grainLayered = p.optBoolean("grain_sublayers_active", fallback.grainLayered),
            grainArea = p.optDouble("grain_particle_area_um2", fallback.grainArea),
            unsharpSigma = p.optDouble("scanner_unsharp_sigma", fallback.unsharpSigma),
            unsharpAmount = p.optDouble("scanner_unsharp_amount", fallback.unsharpAmount),
            // toJson leaves these out at their defaults, so a missing key is the default.
            preflash = p.optDouble("preflash_exposure", DEFAULT.preflash),
            negativeGamma = p.optDouble("density_curve_gamma", DEFAULT.negativeGamma),
            highlightBoostEv = p.optDouble("halation_boost_ev", DEFAULT.highlightBoostEv),
            lensBlurUm = p.optDouble("lens_blur_um", DEFAULT.lensBlurUm),
            noAntihalation = p.optBoolean("antihalation_removed", DEFAULT.noAntihalation),
            cameraFilter = p.optString("camera_filter", DEFAULT.cameraFilter),
            labLevels = p.optBoolean("scanner_white_correction", DEFAULT.labLevels) && p.optBoolean("scanner_black_correction", DEFAULT.labLevels),
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
            edgeText = p.optString("overscan_edge_text", fallback.edgeText),
            edgeRealName = fallback.edgeRealName,   // app-side only
            edgeFrameNumber = p.optInt("overscan_frame_number", fallback.edgeFrameNumber),
            datePlacement = p.optString("date_imprint_placement", fallback.datePlacement),
            dateDataText = p.optString("date_imprint_data_text", fallback.dateDataText),
            dateSize = p.optDouble("date_imprint_size", fallback.dateSize),
            dateEv = p.optDouble("date_imprint_ev", fallback.dateEv),
            dateInsetX = p.optDouble("date_imprint_inset_x", fallback.dateInsetX),
            dateInsetY = p.optDouble("date_imprint_inset_y", fallback.dateInsetY),
            dateOrder = fallback.dateOrder,         // app-side only
            dateWithData = fallback.dateWithData,   // app-side only
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
            // A date back is only switched on when the photo has a date to print.
            preset.date?.let { d ->
                next = next.copy(dateImprint = next.dateText.isNotBlank(), dateStyle = d.style, dateCorner = d.corner,
                    dateEv = d.ev.coerceIn(-2.0, 8.0), dateWithData = d.withData, datePlacement = "frame")
            }
            return if (edge != null) {
                next = next.copy(edgeMode = edge.mode, edgeHoles = edge.holes, edgeGate = edge.gate,
                    edgeCarrier = edge.carrier, edgeFog = edge.fog, edgeLeaks = edge.leaks, edgeRealName = edge.realName,
                    edgeFrameNumber = edge.frameNumber?.coerceIn(0, 99) ?: next.edgeFrameNumber)
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

        /**
         * The stock's edge print, as upstream's Session.edgeText: the film's own
         * words where its strips print something other than its name (read off
         * real strips upstream), else the name in capitals without "(...)".
         */
        fun edgeTextFor(stock: Stock?, format: String): String {
            stock ?: return ""
            EDGE_TEXT[stock.id]?.let { (on135, on120) -> return if (gauge(format) == "120") on120 else on135 }
            return stock.label.substringBefore('(').trim().uppercase()
        }

        /** The film a format runs on: 135 (with half frame and XPan) or 120. */
        fun gauge(format: String) = if (format.startsWith("135")) "135" else "120"

        // (135, 120), from upstream Session+FilmEdge.swift at a46627d.
        private val EDGE_TEXT = mapOf(
            "kodak_gold_200" to ("KODAK GB 200-7" to "KODAK 200"),
            "kodak_portra_800" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
            "kodak_portra_800_push1" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
            "kodak_portra_800_push2" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
            "kodak_ultramax_400" to ("KODAK GC 400" to "KODAK GC 400"),
            "kodak_ektachrome_100" to ("KODAK E100" to "KODAK E100"),
            "fujifilm_c200" to ("FUJI 200" to "FUJI 200"),
            "fujifilm_xtra_400" to ("S-400" to "FUJI S-400"),
            "fujifilm_pro_400h" to ("PRO400H" to "FUJI PRO400H"),
            "fujifilm_provia_100f" to ("FUJI RDPIII" to "FUJI RDPIII"),
            "fujifilm_velvia_100" to ("RVP100" to "FUJI RVP100"),
            "kodak_vision3_50d" to ("EASTMAN 5203" to "EASTMAN 5203"),
            "kodak_vision3_250d" to ("EASTMAN 5207" to "EASTMAN 5207"),
            "kodak_vision3_200t" to ("EASTMAN 5213" to "EASTMAN 5213"),
            "kodak_vision3_500t" to ("EASTMAN 5219" to "EASTMAN 5219"),
            "cinestill_800t" to ("CINESTILL 800T" to "CINESTILL 800T"),
            "kodak_tri_x_400" to ("KODAK 400TX" to "KODAK 400TX"),
            "kodak_tmax_100" to ("KODAK 100TMX" to "KODAK 100TMX"),
            "fujifilm_neopan_acros_100_ii" to ("FUJI 100 ACROS II" to "FUJI 100 ACROS II"),
            "ilford_hp5_plus_400" to ("ILFORD HP5 PLUS" to "ILFORD HP5 PLUS"),
        )

        /**
         * [edit] with what the photo's EXIF decides on the film: the date in
         * the chosen order, the shooting-data line and the aperture that sets
         * the gate's penumbra. A field the file does not carry is left out.
         */
        fun withShooting(edit: Edit, exif: ExifCopy): Edit = edit.copy(
            dateText = dateBackText(exif.dateTimeOriginal, edit.dateOrder),
            dateDataText = dataLine(exif),
            edgeFNumber = exif.fNumber()?.coerceIn(0.0, 64.0) ?: 0.0,
        )

        /**
         * The data face's line, after upstream's ShootingData.dataText:
         * shutter, aperture, 135-equivalent focal length ("1/250 F5.6 24mm"),
         * fields dropped from the right until it is 22 characters or fewer.
         * Only characters the 5x7 face draws.
         */
        fun dataLine(exif: ExifCopy): String {
            val fields = listOfNotNull(
                exif.shutter()?.let { if (it.startsWith("1/")) it.removeSuffix("s") else it },
                exif.fNumber()?.let { "F" + "%.1f".format(java.util.Locale.ROOT, it).removeSuffix(".0") },
                exif.focal35()?.let { "${it}mm" },
            ).toMutableList()
            while (fields.isNotEmpty() && fields.joinToString(" ").length > 22) fields.removeAt(fields.size - 1)
            return fields.joinToString(" ")
        }

        /** [text] (a date back's date in either order) written in [order]; anything else unchanged. */
        fun dateInOrder(text: String, order: String): String {
            val t = text.trim().split(' ')
            if (t.size != 3) return text
            val y = t.indexOfFirst { it.startsWith("'") }
            val (mo, d) = when (y) { 0 -> t[1] to t[2]; 2 -> t[0] to t[1]; else -> return text }
            return if (order == "us") "$mo $d ${t[y]}" else "${t[y]} $mo $d"
        }

        /** EXIF `yyyy:MM:dd ...` as a date back prints it: japan '26 11 4, us 11 4 '26, no leading zeros. */
        fun dateBackText(exifDate: String?, order: String): String = dateInOrder(dateBackText(exifDate), order)

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
