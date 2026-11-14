package com.dearded.spektralab

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONObject
import kotlin.math.floor

/**
 * 135 sprocket strips along the long edges of the print: the look of a film
 * strip on any photo and any film, with no crop and nothing sent to the
 * engine. The engine's own film edge (overscan) needs the frame cropped to a
 * gate and re-develops the negative; this is drawn on the card after the
 * engine, like the paper frames, so it never changes a parameter.
 *
 * Geometry is real 135 in millimetres, scaled so the print's short edge is the
 * 24 mm frame height: a 5.5 mm rebate, holes 2.794 x 1.98 mm (KS) on a
 * 4.75 mm pitch, 2.0 mm in from the outer edge, edge print in that outer band.
 */
data class Perforation(
    /** "both", "top" or "bottom", in the print's landscape orientation. */
    val where: String = "both",
    val base: Int = BASE_BLACK,
    val holes: Int = HOLE_LIGHT,
    /** "ks" (Kodak standard, square-ish) or "bh" (Bell & Howell, rounded). */
    val shape: String = "ks",
    val edgeText: Boolean = true,
    /** Printed on the rebate; "" prints the film's own name. */
    val text: String = "",
    val numbers: Boolean = true,
    val frame: Int = 12,
) {
    val top get() = where != "bottom"
    val bottom get() = where != "top"

    fun toJson(): JSONObject = JSONObject().put("where", where).put("base", base).put("holes", holes)
        .put("shape", shape).put("edgeText", edgeText).put("text", text).put("numbers", numbers).put("frame", frame)

    /** Extra card margins, in pixels, around a [w] x [h] print whose short edge is [s]. */
    fun margins(w: Int, h: Int, s: Float): Margins {
        val strip = s * REBATE / FRAME
        return if (w >= h) Margins(0f, if (top) strip else 0f, 0f, if (bottom) strip else 0f, if (bottom) strip else 0f)
        // Portrait: the strip runs down the long edges; "top" is the right-hand one.
        else Margins(if (bottom) strip else 0f, 0f, if (top) strip else 0f, 0f, 0f)
    }

    /** Draws the strips around the print at ([x], [y]) of size [w] x [h] on [canvas]. */
    fun draw(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, s: Float, film: String) {
        val mm = s / FRAME
        canvas.save()
        val long: Float
        if (w >= h) {
            canvas.translate(x, y); long = w
        } else {
            // Draw in landscape and turn it: local (u, v) lands at (x + w - v, y + u).
            canvas.translate(x + w, y); canvas.rotate(90f); long = h
        }
        val short = s
        val strip = REBATE * mm
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = base }
        val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = holes }
        // v0 is the strip's outer edge, `down` points from it towards the print.
        fun strip(v0: Float, down: Float) {
            val a = minOf(v0, v0 + down * strip)
            canvas.drawRect(0f, a, long, a + strip, basePaint)
            val hw = HOLE_W * mm
            val hh = (if (shape == "bh") 1.854f else HOLE_H) * mm
            val r = (if (shape == "bh") 0.85f else 0.5f) * mm
            val lengthMm = long / mm
            val n = maxOf(1, floor((lengthMm - HOLE_W) / PITCH).toInt() + 1)
            val start = (lengthMm - ((n - 1) * PITCH + HOLE_W)) / 2f * mm
            val hv = if (down > 0) v0 + HOLE_IN * mm else v0 - HOLE_IN * mm - hh
            for (i in 0 until n) {
                val u = start + i * PITCH * mm
                canvas.drawRoundRect(RectF(u, hv, u + hw, hv + hh), r, r, holePaint)
            }
        }
        if (top) strip(-strip, 1f)
        if (bottom) strip(short + strip, -1f)
        // Edge print in the outer band of one strip: the film's name and frame numbers.
        val label = text.ifBlank { film }.uppercase()
        if ((edgeText && label.isNotBlank()) || numbers) {
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor(base)
                textSize = 1.25f * mm
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                letterSpacing = 0.08f
            }
            val baseline = if (bottom) short + strip - 0.55f * mm else -strip + 1.6f * mm
            if (edgeText && label.isNotBlank()) canvas.drawText(label, 4f * mm, baseline, ink)
            if (numbers) {
                val n = frame.coerceIn(0, 99)
                canvas.drawText("$n", long * 0.46f, baseline, ink)
                ink.textAlign = Paint.Align.RIGHT
                canvas.drawText("▸${n}A", long - 4f * mm, baseline, ink)
            }
        }
        canvas.restore()
    }

    data class Margins(val left: Float, val top: Float, val right: Float, val bottom: Float, val belowPrint: Float)

    companion object {
        private const val FRAME = 24f
        private const val REBATE = 5.5f
        private const val HOLE_W = 2.794f
        private const val HOLE_H = 1.98f
        private const val HOLE_IN = 2.0f
        private const val PITCH = 4.75f

        const val BASE_BLACK = 0xFF0E0D0C.toInt()      // a positive scan: the rebate prints black
        const val BASE_C41 = 0xFFB5733F.toInt()        // colour negative's orange mask
        const val BASE_E6 = 0xFFD9DDE1.toInt()         // slide film's clear base
        const val BASE_BW = 0xFF5F5F5F.toInt()         // black-and-white grey base
        const val HOLE_LIGHT = 0xFFF3F1EB.toInt()      // light through the holes
        const val HOLE_DARK = 0xFF0E0D0C.toInt()

        val BASES get() = listOf(BASE_BLACK to tr("黑 · 扫描"), BASE_C41 to tr("橙 · 负片"), BASE_E6 to tr("透明 · 反转"), BASE_BW to tr("灰 · 黑白"))

        /** Holes that show against [base]: light on a dark base, dark on a light one. */
        fun holesFor(base: Int) = if (luma(base) < 0.5f) HOLE_LIGHT else HOLE_DARK

        fun textColor(base: Int): Int = when {
            base == BASE_C41 -> 0xFF4A2A12.toInt()
            luma(base) < 0.5f -> 0xFFE0A050.toInt()    // the amber edge print of a scanned strip
            else -> 0xFF3A3530.toInt()
        }

        private fun luma(c: Int): Float =
            (0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)) / 255f

        fun fromJson(o: JSONObject?): Perforation? = o?.let {
            runCatching {
                val d = Perforation()
                Perforation(
                    where = it.optString("where", d.where).takeIf { w -> w in setOf("both", "top", "bottom") } ?: d.where,
                    base = it.optInt("base", d.base), holes = it.optInt("holes", d.holes),
                    shape = if (it.optString("shape") == "bh") "bh" else "ks",
                    edgeText = it.optBoolean("edgeText", d.edgeText), text = it.optString("text", "").take(40),
                    numbers = it.optBoolean("numbers", d.numbers), frame = it.optInt("frame", d.frame).coerceIn(0, 99),
                )
            }.getOrNull()
        }
    }
}
