package com.dearded.spektralab

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a mask does inside its region: upstream SpektraLab's MaskAdjustments
 * (Model/Mask.swift), in Look's units and ranges. Curves, colour balance and
 * the vignette stay global, as upstream and Lightroom keep them.
 */
data class MaskTone(
    val exposure: Double = 0.0,     // stops, -3..3
    val contrast: Double = 0.0,     // -50..50
    val brightness: Double = 0.0,   // -50..50
    val saturation: Double = 0.0,   // -100..100
    val highlights: Double = 0.0,   // -100..100
    val shadows: Double = 0.0,      // -100..100
    val blackPoint: Double = 0.0,   // 0..50
    val whitePoint: Double = 0.0,   // 0..50
    val temperature: Double = 0.0,  // -100..100
    val tint: Double = 0.0,         // -100..100
) {
    val neutral get() = this == MaskTone()

    /** layer2_local.hpp's order. */
    fun values() = doubleArrayOf(exposure, contrast, brightness, saturation, highlights, shadows,
        blackPoint, whitePoint, temperature, tint)

    fun toJson(): JSONObject = JSONObject().also { o -> values().forEachIndexed { i, x -> if (x != 0.0) o.put(KEYS[i], x) } }

    companion object {
        val KEYS = listOf("exposure", "contrast", "brightness", "saturation", "highlights", "shadows",
            "blackPoint", "whitePoint", "temperature", "tint")
        private val RANGES = listOf(-3.0 to 3.0, -50.0 to 50.0, -50.0 to 50.0, -100.0 to 100.0, -100.0 to 100.0,
            -100.0 to 100.0, 0.0 to 50.0, 0.0 to 50.0, -100.0 to 100.0, -100.0 to 100.0)

        fun fromJson(o: JSONObject?): MaskTone {
            if (o == null) return MaskTone()
            val v = DoubleArray(10) { i ->
                val (lo, hi) = RANGES[i]
                o.optDouble(KEYS[i], 0.0).takeIf { it.isFinite() }?.coerceIn(lo, hi) ?: 0.0
            }
            return MaskTone(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9])
        }
    }
}

enum class MaskKind(val key: String, private val message: String) {
    LINEAR("linear", "线性渐变"), RADIAL("radial", "径向渐变"), BRUSH("brush", "画笔");

    val label get() = tr(message)

    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
}

/**
 * One dab of the brush (upstream's BrushStamp): its centre in the frame (0..1),
 * its radius as a fraction of the long edge, how soft its edge is and how much
 * it lays down, 0..1. An erasing dab takes coverage away.
 */
data class BrushStamp(val x: Double, val y: Double, val radius: Double, val softness: Double, val flow: Double,
                      val erase: Boolean = false)

/**
 * A local adjustment (蒙版): one region and its own [tone]. Geometry is stored,
 * never rasterised, in the frame the print came out at (0..1 across and down),
 * so the preview, every tier and the export read the same shape.
 *
 * Linear: coverage 0 at [ax],[ay], rising to 1 at [bx],[by]. Radial: centred
 * on [ax],[ay], semi-axes [rx],[ry] as fractions of the long edge, turned by
 * [angle] degrees; [feather] is the share of the radius the falloff takes.
 */
data class EditMask(
    val kind: MaskKind = MaskKind.LINEAR,
    val inverted: Boolean = false,
    val amount: Double = 100.0,      // 0..100, the whole mask's opacity
    val tone: MaskTone = MaskTone(),
    val ax: Double = 0.5, val ay: Double = 0.55,
    val bx: Double = 0.5, val by: Double = 0.15,
    val rx: Double = 0.3, val ry: Double = 0.3,
    val angle: Double = 0.0,
    val feather: Double = 50.0,      // 0..100
    val stamps: List<BrushStamp> = emptyList(),
) {
    /** Changes the picture: something to apply, somewhere to apply it. */
    val active get() = !tone.neutral && amount > 0 && (kind != MaskKind.BRUSH || stamps.isNotEmpty())

    fun toJson(): JSONObject = JSONObject().put("kind", kind.key).put("inverted", inverted).put("amount", amount)
        .put("tone", tone.toJson())
        .put("a", JSONArray().put(ax).put(ay)).put("b", JSONArray().put(bx).put(by))
        .put("radii", JSONArray().put(rx).put(ry)).put("angle", angle).put("feather", feather)
        .apply {
            // Flat: x, y, radius, softness, flow per dab; an erasing dab's flow is negative.
            if (stamps.isNotEmpty()) put("stamps", JSONArray().also { a ->
                for (s in stamps) a.put(s.x).put(s.y).put(s.radius).put(s.softness).put(if (s.erase) -s.flow else s.flow)
            })
        }

    companion object {
        fun make(kind: MaskKind) = when (kind) {
            // Sensible starting shapes, as upstream's: the sky from the middle up, a centred circle.
            MaskKind.LINEAR -> EditMask(kind)
            MaskKind.RADIAL -> EditMask(kind, ax = 0.5, ay = 0.5)
            MaskKind.BRUSH -> EditMask(kind)
        }

        fun fromJson(o: JSONObject): EditMask? {
            val kind = MaskKind.of(o.optString("kind")) ?: return null
            fun num(key: String, def: Double) = o.optDouble(key, def).takeIf { it.isFinite() } ?: def
            fun pair(key: String, d0: Double, d1: Double): Pair<Double, Double> {
                val a = o.optJSONArray(key) ?: return d0 to d1
                return (a.optDouble(0, d0).takeIf { it.isFinite() } ?: d0).coerceIn(-2.0, 3.0) to
                    (a.optDouble(1, d1).takeIf { it.isFinite() } ?: d1).coerceIn(-2.0, 3.0)
            }
            val d = EditMask()
            val (ax, ay) = pair("a", d.ax, d.ay)
            val (bx, by) = pair("b", d.bx, d.by)
            val (rx, ry) = pair("radii", d.rx, d.ry)
            val stamps = ArrayList<BrushStamp>()
            o.optJSONArray("stamps")?.let { a ->
                var i = 0
                while (i + 4 < a.length() && stamps.size < LocalEdits.MAX_STAMPS) {
                    val f = a.optDouble(i + 4, 0.0)
                    val s = BrushStamp(a.optDouble(i, 0.0), a.optDouble(i + 1, 0.0),
                        a.optDouble(i + 2, 0.05).coerceIn(1e-4, 1.0), a.optDouble(i + 3, 0.5).coerceIn(0.0, 1.0),
                        kotlin.math.abs(f).coerceIn(0.0, 1.0), erase = f < 0)
                    if (listOf(s.x, s.y, s.radius, s.softness, s.flow).all { it.isFinite() })
                        stamps += s.copy(x = s.x.coerceIn(-2.0, 3.0), y = s.y.coerceIn(-2.0, 3.0))
                    i += 5
                }
            }
            return EditMask(kind, o.optBoolean("inverted", false), num("amount", 100.0).coerceIn(0.0, 100.0),
                MaskTone.fromJson(o.optJSONObject("tone")), ax, ay, bx, by,
                rx.coerceIn(0.005, 3.0), ry.coerceIn(0.005, 3.0), num("angle", 0.0),
                num("feather", 50.0).coerceIn(0.0, 100.0), stamps)
        }
    }
}

/**
 * Scan dust (灰尘 / 划痕), all 0..100 but [seed]. Procedural from [seed]: the
 * same photo and the same values give the same specks in every preview and
 * every export (layer2_local.hpp). [strength] 0 is off.
 */
data class Dust(
    val strength: Double = 0.0,
    val count: Double = 35.0,
    val size: Double = 50.0,
    val dark: Double = 20.0,
    val scratches: Double = 0.0,
    val seed: Int = 1,
) {
    val active get() = strength > 0 && (count > 0 || scratches > 0)

    fun toJson(): JSONObject = JSONObject().put("strength", strength).put("count", count).put("size", size)
        .put("dark", dark).put("scratches", scratches).put("seed", seed)

    companion object {
        const val MAX_SEED = 16_777_215   // exact as a float on the way to layer2

        fun fromJson(o: JSONObject?): Dust {
            if (o == null) return Dust()
            val d = Dust()
            fun num(key: String, def: Double) = (o.optDouble(key, def).takeIf { it.isFinite() } ?: def).coerceIn(0.0, 100.0)
            return Dust(num("strength", d.strength), num("count", d.count), num("size", d.size), num("dark", d.dark),
                num("scratches", d.scratches), o.optInt("seed", d.seed).coerceIn(0, MAX_SEED))
        }
    }
}

/** The masks and dust block of Look.values() (layer2_local.hpp), and the coverage the overlay draws. */
object LocalEdits {
    const val MAX_MASKS = 8
    const val MAX_STAMPS = 4000
    private const val MAGIC = 9157f
    private const val VERSION = 1f

    /**
     * Everything after the 50 scalars when there are active masks or dust: all
     * five curve rows (identity rows too, which layer2.cpp still reads as no
     * curves), then the block. Null when there is nothing local to apply, so
     * the array stays exactly what it was before masks existed.
     */
    fun tail(curves: CurveSet, masks: List<EditMask>, dust: Dust): FloatArray? {
        val live = masks.filter { it.active }.take(MAX_MASKS)
        if (live.isEmpty() && !dust.active) return null
        val out = ArrayList<Float>()
        for (c in curves.rows) c.appendWire(out)
        out += MAGIC; out += VERSION
        if (dust.active) {
            out += dust.strength.toFloat(); out += dust.count.toFloat(); out += dust.size.toFloat()
            out += dust.dark.toFloat(); out += dust.scratches.toFloat(); out += dust.seed.coerceIn(0, Dust.MAX_SEED).toFloat()
        } else repeat(6) { out += 0f }
        out += live.size.toFloat()
        for (m in live) {
            out += m.kind.ordinal.toFloat(); out += if (m.inverted) 1f else 0f; out += (m.amount / 100).toFloat()
            for (x in m.tone.values()) out += x.toFloat()
            out += m.ax.toFloat(); out += m.ay.toFloat(); out += m.bx.toFloat(); out += m.by.toFloat()
            out += m.rx.toFloat(); out += m.ry.toFloat(); out += m.angle.toFloat(); out += (m.feather / 100).toFloat()
            val stamps = if (m.kind == MaskKind.BRUSH) m.stamps.take(MAX_STAMPS) else emptyList()
            out += stamps.size.toFloat()
            for (s in stamps) {
                out += s.x.toFloat(); out += s.y.toFloat(); out += s.radius.toFloat(); out += s.softness.toFloat()
                out += (if (s.erase) -s.flow else s.flow).toFloat()
            }
        }
        return out.toFloatArray()
    }

    // --- coverage, as layer2_local.hpp computes it (the overlay draws this) ---

    /** Frame (0..1) to long-edge units, so an ellipse is an ellipse on a 3:2 frame. */
    fun toLongEdge(u: Double, v: Double, aspect: Double) =
        if (aspect >= 1) u to v / aspect else u * aspect to v

    fun fromLongEdge(x: Double, y: Double, aspect: Double) =
        if (aspect >= 1) x to y * aspect else x / aspect to y

    private fun smooth(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** [m]'s coverage at frame position ([u], [v]), 0..1, before [EditMask.amount]. */
    fun coverage(m: EditMask, u: Double, v: Double, aspect: Double): Double {
        val (x, y) = toLongEdge(u, v, aspect)
        val (lax, lay) = toLongEdge(m.ax, m.ay, aspect)
        var c = when (m.kind) {
            MaskKind.LINEAR -> {
                val (lbx, lby) = toLongEdge(m.bx, m.by, aspect)
                val ex = lbx - lax
                val ey = lby - lay
                val t = ((x - lax) * ex + (y - lay) * ey) / max(ex * ex + ey * ey, 1e-9)
                smooth(0.0, 1.0, t.coerceIn(0.0, 1.0))
            }
            MaskKind.RADIAL -> {
                val a = Math.toRadians(m.angle)
                val cs = cos(a)
                val sn = sin(a)
                val dx = x - lax
                val dy = y - lay
                val ex = (dx * cs + dy * sn) / max(m.rx, 1e-4)
                val ey = (-dx * sn + dy * cs) / max(m.ry, 1e-4)
                1 - smooth(1 - (m.feather / 100).coerceIn(0.001, 1.0), 1.0, sqrt(ex * ex + ey * ey))
            }
            MaskKind.BRUSH -> {
                var acc = 0.0
                for (s in m.stamps) {
                    val (sx, sy) = toLongEdge(s.x, s.y, aspect)
                    val r = s.radius.coerceIn(1e-4, 1.0)
                    val d2 = (x - sx) * (x - sx) + (y - sy) * (y - sy)
                    if (d2 >= r * r) continue
                    val f = s.flow.coerceIn(0.0, 1.0) * (1 - smooth(1 - s.softness.coerceIn(0.001, 1.0), 1.0, sqrt(d2) / r))
                    acc = if (s.erase) acc * (1 - f) else acc + f * (1 - acc)
                }
                acc
            }
        }
        if (m.inverted) c = 1 - c
        return min(1.0, max(0.0, c))
    }
}
