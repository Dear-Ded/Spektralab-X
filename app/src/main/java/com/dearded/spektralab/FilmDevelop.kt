package com.dearded.spektralab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * A frame of reversal film (E-6) being developed, drawn: the launch intro's frame and the save
 * animation both use it. After how the film looks at each bath (Wikipedia, "E-6 process";
 * Photrio, "opacity of undeveloped vs developed film"; preview/ui-polish.html):
 *
 *   undeveloped        milky grey cream, nearly opaque, no image
 *   a  first developer a grey-black silver negative comes up in the cream; the edge print shows dark
 *   b  colour developer all the silver is out: the film turns almost black and opaque
 *   c  bleach          the black lifts to a milky haze; the colour positive shows faintly through it
 *   d  fix             the haze clears: a positive slide, the rebate dense black, the edge print light
 *   wash and dry       a sheen runs off and a few drops dry away
 *
 * p runs 0..1 over all of it. Each bath runs down the film from the top as a wavy wet front, the
 * way liquid fills a tank; [box] is the area a front runs over (the whole strip, so frame and
 * rebate change together).
 */
internal object FilmDevelop {
    private val CreamA = Color(0xFFB9B4A2)
    private val CreamB = Color(0xFFA29D8A)
    private val Silver = Color(0xFF0D0C0B)
    private val Haze = Color(0xFFC4BEAC)

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val v = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    /** How far each bath has run down the film, 0..1. */
    class Fronts(p: Float) {
        val first = smooth(0.03f, 0.24f, p)
        val colour = smooth(0.28f, 0.44f, p)
        val bleach = smooth(0.50f, 0.66f, p)
        val fix = smooth(0.70f, 0.88f, p)
        val dry = smooth(0.86f, 1f, p)
    }

    /** The region a bath has reached: from the top down to its wavy front. */
    private fun front(box: Rect, k: Float, wave: Float, seed: Float): Path = Path().apply {
        if (k >= 1f) { addRect(box.inflate(4f)); return@apply }
        val fy = box.top - box.height * 0.05f + box.height * 1.1f * k
        moveTo(box.left - 4f, box.top - 4f)
        for (i in 0..28) {
            val x = box.left + box.width * i / 28f
            lineTo(x, fy + sin(i * 0.8f + wave * 5f + seed) * box.height * 0.022f + sin(i * 2.1f - wave * 7f + seed * 2f) * box.height * 0.009f)
        }
        lineTo(box.right + 4f, box.top - 4f)
        close()
    }

    private fun DrawScope.sheen(box: Rect, k: Float) {
        if (k <= 0f || k >= 1f) return
        val fy = box.top - box.height * 0.05f + box.height * 1.1f * k
        val top = fy - box.height * 0.07f
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.85f to Color(0x47FFFAEC), 1f to Color.Transparent, startY = top, endY = fy + 3f),
            Offset(box.left, top), Size(box.width, fy + 3f - top), blendMode = BlendMode.Screen)
    }

    private fun DrawScope.cream(r: Rect) =
        drawRect(Brush.linearGradient(listOf(CreamA, CreamB), r.topLeft, r.bottomRight), r.topLeft, r.size)

    /** Film grain over [r]: a noise tile, overlaid, moved each frame. */
    fun DrawScope.grain(r: Rect, alpha: Float, tile: ImageBitmap, shift: Offset) {
        if (alpha <= 0.005f) return
        clipRect(r.left, r.top, r.right, r.bottom) {
            translate(shift.x, shift.y) {
                drawRect(ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)),
                    Offset(r.left - shift.x, r.top - shift.y), r.size, alpha = alpha, blendMode = BlendMode.Overlay)
            }
        }
    }

    /**
     * One exposed frame at [p]. [positive] draws the finished frame into [r], [negative] its silver
     * negative (grey, inverted).
     */
    fun DrawScope.frame(
        r: Rect, p: Float, box: Rect, wave: Float, tile: ImageBitmap?, shift: Offset,
        positive: DrawScope.() -> Unit, negative: DrawScope.() -> Unit,
    ) {
        val f = Fronts(p)
        clipRect(r.left, r.top, r.right, r.bottom) {
            cream(r)
            if (f.first > 0f) clipPath(front(box, f.first, wave, 0.7f)) {
                // The negative comes up inside the cream: multiplied into it.
                drawIntoCanvas { c ->
                    c.saveLayer(r, Paint().apply { blendMode = BlendMode.Multiply; this.alpha = 0.92f })
                    negative()
                    c.restore()
                }
            }
            if (f.colour > 0f) clipPath(front(box, f.colour, wave, 1.7f)) {
                drawRect(Silver, r.topLeft, r.size)
                drawIntoCanvas { c -> c.saveLayer(r, Paint().apply { this.alpha = 0.06f }); positive(); c.restore() }
            }
            if (f.bleach > 0f) clipPath(front(box, f.bleach, wave, 2.7f)) {
                drawRect(Silver, r.topLeft, r.size)
                drawIntoCanvas { c -> c.saveLayer(r, Paint().apply { this.alpha = 0.55f }); positive(); c.restore() }
                drawRect(Haze.copy(alpha = 0.5f), r.topLeft, r.size)
            }
            if (f.fix > 0f) clipPath(front(box, f.fix, wave, 3.7f)) { positive() }
            for (k in floatArrayOf(f.first, f.colour, f.bleach, f.fix)) sheen(box, k)
            if (f.fix >= 1f && f.dry < 1f) {
                // Wash and dry: a sheen slides off, drops shrink away.
                val sx = r.left + r.width * (f.dry * 1.6f - 0.3f)
                drawRect(Brush.linearGradient(0f to Color.Transparent, 0.5f to Color(0xFFFFFCF4).copy(alpha = 0.16f * (1 - f.dry)), 1f to Color.Transparent,
                    start = Offset(sx - r.width * 0.25f, r.top), end = Offset(sx + r.width * 0.25f, r.bottom)), r.topLeft, r.size, blendMode = BlendMode.Screen)
                val scale = max(1f, r.width / 260f)
                for (i in 0 until 7) {
                    val dx = r.left + r.width * ((i * 0.618f + 0.13f) % 1f)
                    val dy = r.top + r.height * ((i * 0.377f + 0.21f) % 1f)
                    val rad = max(0f, (2.2f + (i % 3)) * (1 - f.dry * (1 + i * 0.12f))) * scale * density
                    if (rad <= 0.2f) continue
                    drawOval(Color.Black.copy(alpha = 0.22f), Offset(dx - rad + 0.6f, dy - rad * 0.8f + 0.8f), Size(rad * 2, rad * 1.6f))
                    drawOval(Color(0x80FFFCF4), Offset(dx - rad * 0.65f, dy - rad * 0.55f), Size(rad * 0.7f, rad * 0.5f))
                }
            }
        }
        if (tile != null) grain(r, 0.34f * (1 - f.fix) * (1 - 0.6f * f.colour * (1 - f.bleach)) + 0.05f, tile, shift)
    }

    /** The rebate and the unexposed frames: cream, black from the colour developer on, hazed while bleaching. */
    fun DrawScope.rebate(box: Rect, p: Float, wave: Float, tile: ImageBitmap?, shift: Offset) {
        val f = Fronts(p)
        cream(box)
        if (tile != null) grain(box, 0.25f * (1 - f.colour), tile, shift)
        val black = Brush.verticalGradient(0f to Color(0xFF0A0908), 0.12f to Color(0xFF191714), 0.5f to Color(0xFF110F0D),
            0.88f to Color(0xFF171512), 1f to Color(0xFF090807), startY = box.top, endY = box.bottom)
        if (f.colour > 0f) clipPath(front(box, f.colour, wave, 1.7f)) { drawRect(black, box.topLeft, box.size) }
        if (f.bleach > 0f) clipPath(front(box, f.bleach, wave, 2.7f)) { drawRect(Color(0xFF96917F).copy(alpha = 0.42f * (1 - f.fix)), box.topLeft, box.size) }
        if (f.fix > 0f) clipPath(front(box, f.fix, wave, 3.7f)) { drawRect(black, box.topLeft, box.size) }
    }

    /** The pre-exposed edge print: dark in the first developer, lost in the black, light once fixed. */
    fun edgeInk(p: Float): Color {
        val f = Fronts(p)
        return when {
            f.fix > 0.05f -> Color(0xFFECCE96).copy(alpha = 0.85f * f.fix)
            f.colour > 0.4f -> Color.Transparent
            else -> Color(0xFF342C22).copy(alpha = (0.75f * f.first * (1 - f.colour * 2)).coerceIn(0f, 1f))
        }
    }

    /** A 160 px tile of film grain (a soft, two-sample noise), made once. */
    val grainTile: ImageBitmap by lazy {
        val n = 160
        val rnd = java.util.Random(26_11_23)
        val px = IntArray(n * n) {
            val v = (128 + (rnd.nextFloat() + rnd.nextFloat() - 1f) * 120f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** A KS perforation: a rounded rectangle. */
    fun perforation(topLeft: Offset, size: Size): androidx.compose.ui.geometry.RoundRect =
        androidx.compose.ui.geometry.RoundRect(Rect(topLeft, size), androidx.compose.ui.geometry.CornerRadius(min(size.width, size.height) * 0.28f))
}
