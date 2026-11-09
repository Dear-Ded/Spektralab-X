package com.dearded.spektralab

import org.json.JSONObject

/** Film-input stops, separate from the finished print's Layer 2 curves. */
data class SceneCurve private constructor(val controls: CurveSet?, private val sampled: String?) {
    val isIdentity get() = sampled == null && controls?.isIdentity == true
    val isSampled get() = sampled != null

    /** Empty is an exact native bypass; an explicit diagonal LUT is not bit-identical. */
    val wire: String by lazy {
        sampled ?: if (isIdentity) "" else requireNotNull(controls).rows.joinToString(",") { curve ->
            (0 until UI_SAMPLES).joinToString(",") { i ->
                curve.evaluate(i.toDouble() / (UI_SAMPLES - 1)).toFloat().toString()
            }
        }
    }

    /** Imported tables retain linear interpolation and every sample; they are view-only. */
    val display: CurveSet by lazy {
        controls ?: wire.split(',').map { it.toDouble().toFloat().toDouble() }.let { values ->
            val n = values.size / 5
            val rows = List(5) { row -> Curve(List(n) { i -> CurvePoint(i.toDouble() / (n - 1), values[row * n + i]) }, linear = true) }
            CurveSet(rows[0], rows[1], rows[2], rows[3], rows[4])
        }
    }
    val samplesPerRow get() = if (isSampled) display.rgb.points.size else UI_SAMPLES

    fun toJson(): JSONObject = if (sampled != null) JSONObject().put("samples", sampled)
        else JSONObject().put("points", requireNotNull(controls).toJson())

    companion object {
        const val UI_SAMPLES = 129
        const val MAX_TEXT = 1024 * 1024
        val IDENTITY = SceneCurve(CurveSet.IDENTITY, null)
        private val NUMBER = Regex("[\\t\\n\\r ]*[+-]?(?:(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?|0[xX](?:[0-9a-fA-F]+(?:\\.[0-9a-fA-F]*)?|\\.[0-9a-fA-F]+)[pP][+-]?[0-9]+)")

        fun fromControls(curves: CurveSet): SceneCurve {
            for (curve in curves.rows) {
                require(!curve.linear && curve.points.size in 2..256) { "Invalid scene-curve control points" }
                require(curve.points.first().x == 0.0 && curve.points.last().x == 1.0)
                require(curve.points.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
                require(curve.points.zipWithNext().all { (a, b) -> a.x < b.x })
            }
            return if (curves.isIdentity) IDENTITY else SceneCurve(curves, null)
        }

        fun fromWire(text: String, fallback: SceneCurve = IDENTITY): SceneCurve {
            if (text == fallback.wire) return fallback
            if (text.isEmpty()) return IDENTITY
            require(text.length <= MAX_TEXT && '\u0000' !in text) { "Scene curve exceeds the native text limit" }
            val values = text.split(',')
            require(values.size % 5 == 0 && values.size / 5 in 2..4096) { "Scene curve needs five rows of 2..4096 samples" }
            require(values.all { NUMBER.matches(it) && it.toDoubleOrNull()?.let { v -> v.isFinite() && v in 0.0..1.0 } == true }) {
                "Scene curve contains an invalid sample"
            }
            return SceneCurve(null, text)
        }

        /** The structured app representation, or a native CSV from another host. */
        fun fromJson(value: Any?): SceneCurve = when (value) {
            null, JSONObject.NULL -> IDENTITY
            is String -> fromWire(value)
            is JSONObject -> {
                require(value.has("points") != value.has("samples")) { "Scene curve needs either points or samples" }
                if (value.has("samples")) {
                    require(value.get("samples") is String)
                    fromWire(value.getString("samples"))
                } else {
                    val points = value.getJSONObject("points")
                    require(points.keys().asSequence().all { it in CurveSet.KEYS }) { "Unknown scene-curve channel" }
                    val rows = CurveSet.KEYS.map { key ->
                        if (!points.has(key)) Curve.IDENTITY else {
                            val array = points.getJSONArray(key)
                            val curve = Curve.fromJson(array)
                            require(array.length() in 2..256 && curve.points.size == array.length()) { "Invalid scene-curve points" }
                            for (i in 0 until array.length()) {
                                val point = array.getJSONArray(i)
                                require(point.length() == 2 && point.getDouble(0) == curve.points[i].x && point.getDouble(1) == curve.points[i].y)
                            }
                            curve
                        }
                    }
                    fromControls(CurveSet(rows[0], rows[1], rows[2], rows[3], rows[4]))
                }
            }
            else -> throw IllegalArgumentException("Invalid scene-curve representation")
        }
    }
}
