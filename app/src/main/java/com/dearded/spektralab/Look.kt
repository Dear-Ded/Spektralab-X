package com.dearded.spektralab

import org.json.JSONArray
import org.json.JSONObject

/** One colour wheel: hue in degrees, saturation 0..100, luminance -100..100. */
data class Zone(val hue: Double = 0.0, val sat: Double = 0.0, val lum: Double = 0.0) {
    val neutral get() = sat == 0.0 && lum == 0.0
}

/** One HSL band: hue shift, saturation and luminance, each -100..100. */
data class Band(val hue: Double = 0.0, val sat: Double = 0.0, val lum: Double = 0.0) {
    val neutral get() = hue == 0.0 && sat == 0.0 && lum == 0.0
}

/**
 * Layer 2: adjustments to the finished print, applied natively while its
 * pixels are packed (cpp/layer2.cpp). Never sent to the engine, so moving one
 * re-packs the last render instead of developing again. Upstream SpektraLab's
 * Adjustments (Model/Adjustments.swift) with the same units; vibrance, fade
 * and the eight HSL bands are this port's.
 *
 * Every field at 0 is "nothing applied" (vignetteMid is a shape, not an amount).
 */
data class Look(
    val temperature: Double = 0.0,   // -100..100, blue <-> amber
    val tint: Double = 0.0,          // -100..100, green <-> magenta
    val exposure: Double = 0.0,      // stops, -3..3
    val contrast: Double = 0.0,      // -50..50
    val brightness: Double = 0.0,    // -50..50
    val saturation: Double = 0.0,    // -100..100
    val vibrance: Double = 0.0,      // -100..100
    val highlights: Double = 0.0,    // -100..100
    val shadows: Double = 0.0,       // -100..100
    val blackPoint: Double = 0.0,    // 0..50
    val whitePoint: Double = 0.0,    // 0..50
    val fade: Double = 0.0,          // 0..100
    val vignette: Double = 0.0,      // -100..100, negative darkens the corners
    val vignetteMid: Double = 50.0,  // 0..100
    val master: Zone = Zone(),
    val shadowZone: Zone = Zone(),
    val midZone: Zone = Zone(),
    val highlightZone: Zone = Zone(),
    val hsl: List<Band> = List(8) { Band() },
    /** Upstream's tone curves (rgb, luma, red, green, blue), applied after the colour balance as upstream does. */
    val curves: CurveSet = CurveSet.IDENTITY,
    // The palette pad (docs/UI.md 2.8, PalettePad.kt), -100..100 on each axis. Tone is not exposure:
    // up lifts the shadows, holds the highlights and flattens; down deepens the shadows and the
    // black and adds contrast. Colour runs from muted to rich through saturation and vibrance.
    // [resolved] folds both into the fields above; layer2.cpp never sees them.
    val tone: Double = 0.0,
    val colour: Double = 0.0,
    /** Where the style put the pad: a double tap returns here, not to the centre. A place, not an amount. */
    val toneHome: Double = 0.0,
    val colourHome: Double = 0.0,
    /** The palette slider: the finish (everything but the pad) at this percentage, 0..100. */
    val palette: Double = 100.0,
    /**
     * A user colour table (styles line: LutStore, filesDir/user/luts/<id>.lut), applied
     * before every slider here at [lutStrength] 0..1. Not in [values]; the editor sends
     * it with NativeEngine.setLut. Empty is none.
     */
    val lut: String = "",
    val lutStrength: Double = 1.0,
) {
    val neutral get() = resolved().copy(vignetteMid = 50.0) == NEUTRAL

    /**
     * What Layer 2 applies: the finish at [palette] percent, then the pad on top,
     * with tone, colour, the home point and palette folded away. A look with the
     * pad at the centre and palette at 100 resolves to itself, so every field
     * upstream also has keeps upstream's arithmetic exactly.
     */
    fun resolved(): Look {
        val k = (palette / 100).coerceIn(0.0, 1.0)
        val base = (if (k < 1.0) finishAt(k) else this).let {
            it.copy(tone = 0.0, colour = 0.0, toneHome = 0.0, colourHome = 0.0, palette = 100.0,
                    lutStrength = if (lut.isEmpty()) 1.0 else it.lutStrength)
        }
        // Relative to the home point: a style's home is where its finish already
        // sits, so at home it renders exactly as designed (Styles.kt PAD_HOMES).
        val t = ((tone - toneHome) / 100).coerceIn(-1.0, 1.0)
        val c = ((colour - colourHome) / 100).coerceIn(-1.0, 1.0)
        if (t == 0.0 && c == 0.0) return base
        val up = maxOf(t, 0.0)
        val down = maxOf(-t, 0.0)
        return base.copy(
            shadows = if (t == 0.0) base.shadows else (base.shadows + PAD_SHADOWS * t).coerceIn(-100.0, 100.0),
            highlights = if (up == 0.0) base.highlights else (base.highlights - PAD_HIGHLIGHTS * up).coerceIn(-100.0, 100.0),
            contrast = if (t == 0.0) base.contrast
                       else (base.contrast - PAD_FLATTEN * up + PAD_DEEPEN * down).coerceIn(-50.0, 50.0),
            blackPoint = if (down == 0.0) base.blackPoint else (base.blackPoint + PAD_BLACK * down).coerceIn(0.0, 50.0),
            saturation = if (c == 0.0) base.saturation else (base.saturation + PAD_SATURATION * c).coerceIn(-100.0, 100.0),
            vibrance = if (c == 0.0) base.vibrance else (base.vibrance + PAD_VIBRANCE * c).coerceIn(-100.0, 100.0),
        )
    }

    /**
     * Every amount times [k], from neutral: the wheels keep their hues (a
     * direction) and vignetteMid its shape; each curve point moves toward the
     * diagonal, which keeps x increasing as layer2.cpp requires.
     */
    fun finishAt(k: Double): Look {
        fun zone(z: Zone) = z.copy(sat = z.sat * k, lum = z.lum * k)
        fun curve(c: Curve) = if (c.isIdentity) c else Curve(c.points.map { CurvePoint(it.x, it.x + k * (it.y - it.x)) })
        return copy(
            temperature = temperature * k, tint = tint * k, exposure = exposure * k, contrast = contrast * k,
            brightness = brightness * k, saturation = saturation * k, vibrance = vibrance * k,
            highlights = highlights * k, shadows = shadows * k, blackPoint = blackPoint * k, whitePoint = whitePoint * k,
            fade = fade * k, vignette = vignette * k, lutStrength = lutStrength * k,
            master = zone(master), shadowZone = zone(shadowZone), midZone = zone(midZone), highlightZone = zone(highlightZone),
            hsl = hsl.map { Band(it.hue * k, it.sat * k, it.lum * k) },
            curves = CurveSet(curve(curves.rgb), curve(curves.luma), curve(curves.red), curve(curves.green), curve(curves.blue)),
        )
    }

    /** The array LookIndex in layer2.hpp reads, in that order, of [resolved]; the curves follow as the kCurves tail. */
    fun values(): FloatArray {
        val r = resolved()
        val tail = r.curves.tail()
        val v = FloatArray(50 + tail.size)
        tail.copyInto(v, 50)
        val scalars = doubleArrayOf(r.temperature, r.tint, r.exposure, r.contrast, r.brightness, r.saturation, r.vibrance,
            r.highlights, r.shadows, r.blackPoint, r.whitePoint, r.fade, r.vignette, r.vignetteMid)
        for (i in scalars.indices) v[i] = scalars[i].toFloat()
        listOf(r.master, r.shadowZone, r.midZone, r.highlightZone).forEachIndexed { z, zone ->
            v[14 + 3 * z] = zone.hue.toFloat(); v[15 + 3 * z] = zone.sat.toFloat(); v[16 + 3 * z] = zone.lum.toFloat()
        }
        for (b in 0 until 8) {
            val band = r.hsl.getOrElse(b) { Band() }
            v[26 + 3 * b] = band.hue.toFloat(); v[27 + 3 * b] = band.sat.toFloat(); v[28 + 3 * b] = band.lum.toFloat()
        }
        return v
    }

    fun withBand(i: Int, band: Band) = copy(hsl = hsl.toMutableList().also { it[i] = band })

    /**
     * By field name, so a field added later (or reordered in [values]) never
     * shifts the others. Untouched wheels and bands are left out; [fromJson]
     * reads a missing key as its default.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        for ((key, get) in SCALARS) put(key, get(this@Look))
        for ((key, get) in ZONES) get(this@Look).takeIf { it != Zone() }?.let { z ->
            put(key, JSONObject().put("hue", z.hue).put("sat", z.sat).put("lum", z.lum))
        }
        if (hsl.any { !it.neutral }) put("hsl", JSONArray().also { a ->
            for (b in 0 until 8) hsl.getOrElse(b) { Band() }.let { a.put(JSONArray().put(it.hue).put(it.sat).put(it.lum)) }
        })
        if (!curves.isIdentity) put("curves", curves.toJson())
        if (lut.isNotEmpty()) put("lut", lut).put("lutStrength", lutStrength)
    }

    companion object {
        val NEUTRAL = Look()

        /** The eight HSL bands, as layer2.cpp's kBandHue. */
        val BANDS = listOf("红" to 0xFFE0524A, "橙" to 0xFFE8913A, "黄" to 0xFFE8D24A, "绿" to 0xFF5EB65A,
            "青" to 0xFF4AC0C8, "蓝" to 0xFF4A74E0, "紫" to 0xFF8A5AD8, "品红" to 0xFFD25AB0)

        // The pad's reach at a full throw (|tone| or |colour| = 100), in the units of the
        // fields it moves. Up lifts the shadows, holds the highlights and lowers contrast;
        // down pulls the shadows and the black point and raises contrast. A full corner is
        // a strong look but stays inside every field's own range.
        const val PAD_SHADOWS = 40.0
        const val PAD_HIGHLIGHTS = 30.0
        const val PAD_FLATTEN = 15.0
        const val PAD_DEEPEN = 20.0
        const val PAD_BLACK = 6.0
        const val PAD_SATURATION = 35.0
        const val PAD_VIBRANCE = 35.0

        /** Version of [toJson]'s layout; 1 was the positional "v" array. */
        private const val FORMAT = 2

        private val SCALARS: List<Pair<String, (Look) -> Double>> = listOf(
            "temperature" to { l: Look -> l.temperature }, "tint" to { l: Look -> l.tint },
            "exposure" to { l: Look -> l.exposure }, "contrast" to { l: Look -> l.contrast },
            "brightness" to { l: Look -> l.brightness }, "saturation" to { l: Look -> l.saturation },
            "vibrance" to { l: Look -> l.vibrance }, "highlights" to { l: Look -> l.highlights },
            "shadows" to { l: Look -> l.shadows }, "blackPoint" to { l: Look -> l.blackPoint },
            "whitePoint" to { l: Look -> l.whitePoint }, "fade" to { l: Look -> l.fade },
            "vignette" to { l: Look -> l.vignette }, "vignetteMid" to { l: Look -> l.vignetteMid },
            "tone" to { l: Look -> l.tone }, "colour" to { l: Look -> l.colour },
            "toneHome" to { l: Look -> l.toneHome }, "colourHome" to { l: Look -> l.colourHome },
            "palette" to { l: Look -> l.palette },
        )
        private val ZONES: List<Pair<String, (Look) -> Zone>> = listOf(
            "master" to { l: Look -> l.master }, "shadowZone" to { l: Look -> l.shadowZone },
            "midZone" to { l: Look -> l.midZone }, "highlightZone" to { l: Look -> l.highlightZone },
        )

        /** Either layout: named keys (format 2) or the old positional "v" array. */
        fun fromJson(o: JSONObject?): Look {
            if (o == null) return NEUTRAL
            o.optJSONArray("v")?.let { if (!o.has("format")) return fromArray(it) }
            fun num(key: String, default: Double) = o.optDouble(key, default).takeIf { it.isFinite() } ?: default
            fun zone(key: String) = o.optJSONObject(key)?.let {
                Zone(it.optDouble("hue", 0.0), it.optDouble("sat", 0.0), it.optDouble("lum", 0.0))
            } ?: Zone()
            val bands = o.optJSONArray("hsl")
            return Look(
                temperature = num("temperature", 0.0), tint = num("tint", 0.0), exposure = num("exposure", 0.0),
                contrast = num("contrast", 0.0), brightness = num("brightness", 0.0),
                saturation = num("saturation", 0.0), vibrance = num("vibrance", 0.0),
                highlights = num("highlights", 0.0), shadows = num("shadows", 0.0),
                blackPoint = num("blackPoint", 0.0), whitePoint = num("whitePoint", 0.0), fade = num("fade", 0.0),
                vignette = num("vignette", 0.0), vignetteMid = num("vignetteMid", 50.0),
                master = zone("master"), shadowZone = zone("shadowZone"), midZone = zone("midZone"),
                highlightZone = zone("highlightZone"),
                hsl = List(8) { b ->
                    bands?.optJSONArray(b)?.let { Band(it.optDouble(0, 0.0), it.optDouble(1, 0.0), it.optDouble(2, 0.0)) } ?: Band()
                },
                curves = CurveSet.fromJson(o.optJSONObject("curves")),
                lut = o.optString("lut", ""),
                lutStrength = num("lutStrength", 1.0).coerceIn(0.0, 1.0),
                tone = num("tone", 0.0).coerceIn(-100.0, 100.0), colour = num("colour", 0.0).coerceIn(-100.0, 100.0),
                toneHome = num("toneHome", 0.0).coerceIn(-100.0, 100.0),
                colourHome = num("colourHome", 0.0).coerceIn(-100.0, 100.0),
                palette = num("palette", 100.0).coerceIn(0.0, 100.0),
            )
        }

        /** Format 1 (0.1.18 and before): [values]' 50 floats in LookIndex order. */
        private fun fromArray(a: JSONArray): Look {
            val v = DoubleArray(50) { if (it < a.length()) a.optDouble(it, 0.0) else if (it == 13) 50.0 else 0.0 }
            fun zone(z: Int) = Zone(v[14 + 3 * z], v[15 + 3 * z], v[16 + 3 * z])
            return Look(
                temperature = v[0], tint = v[1], exposure = v[2], contrast = v[3], brightness = v[4],
                saturation = v[5], vibrance = v[6], highlights = v[7], shadows = v[8], blackPoint = v[9],
                whitePoint = v[10], fade = v[11], vignette = v[12], vignetteMid = v[13],
                master = zone(0), shadowZone = zone(1), midZone = zone(2), highlightZone = zone(3),
                hsl = List(8) { b -> Band(v[26 + 3 * b], v[27 + 3 * b], v[28 + 3 * b]) },
            )
        }
    }
}
