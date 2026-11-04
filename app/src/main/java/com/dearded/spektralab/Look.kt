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
) {
    val neutral get() = copy(vignetteMid = 50.0) == NEUTRAL

    /** The array LookIndex in layer2.hpp reads, in that order. */
    fun values(): FloatArray {
        val v = FloatArray(50)
        val scalars = doubleArrayOf(temperature, tint, exposure, contrast, brightness, saturation, vibrance,
            highlights, shadows, blackPoint, whitePoint, fade, vignette, vignetteMid)
        for (i in scalars.indices) v[i] = scalars[i].toFloat()
        listOf(master, shadowZone, midZone, highlightZone).forEachIndexed { z, zone ->
            v[14 + 3 * z] = zone.hue.toFloat(); v[15 + 3 * z] = zone.sat.toFloat(); v[16 + 3 * z] = zone.lum.toFloat()
        }
        for (b in 0 until 8) {
            val band = hsl.getOrElse(b) { Band() }
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
    }

    companion object {
        val NEUTRAL = Look()

        /** The eight HSL bands, as layer2.cpp's kBandHue. */
        val BANDS = listOf("红" to 0xFFE0524A, "橙" to 0xFFE8913A, "黄" to 0xFFE8D24A, "绿" to 0xFF5EB65A,
            "青" to 0xFF4AC0C8, "蓝" to 0xFF4A74E0, "紫" to 0xFF8A5AD8, "品红" to 0xFFD25AB0)

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
