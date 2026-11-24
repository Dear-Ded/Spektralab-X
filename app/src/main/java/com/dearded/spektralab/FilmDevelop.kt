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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.min
import kotlin.math.sin

/*
 * A frame of reversal film (E-6) being developed, drawn: the launch intro's frame and the save
 * animation both use it. After how the film looks at each bath (Wikipedia, "E-6 process";
 * Photrio, "opacity of undeveloped vs developed film"; preview/ui-polish.html):
 *
 *   undeveloped        milky grey cream, nearly opaque, no image
 *   a  first developer a grey-black silver negative builds up in the cream; the edge print shows dark
 *   b  colour developer all the silver is out: the film goes almost black
 *   c  bleach and fix  the positive rises out of the black through a milky veil that clears to a slide;
 *                      the rebate stays dense black, the edge print comes up light
 *   wash and dry       one soft sheen runs off
 *
 * p runs 0..1 over all of it. The brightness goes down once (cream to black) and up once (black to
 * the slide), never back and forth, and each bath starts before the last one ends, so nothing
 * jumps. A bath soaks down the film from the top as a soft front a third of the frame deep, rocked
 * a little as the tank is; [box] is the area a front runs over (the whole strip, so frame and
 * rebate change together). The grain is the film's own and moves only with it.
 */
internal object FilmDevelop {
    private val CreamA = Color(0xFFB9B4A2)
    private val CreamB = Color(0xFFA29D8A)
    private val Silver = Color(0xFF0D0C0B)
    private val Haze = Color(0xFFB4AE9C)
    /** The bleach's veil at its lightest: milky, but never brighter than a slide, so the frame only brightens. */
    private val Veil = Color(0xFF55514A)

    /** How deep the soft front is, as a share of the box's height. */
    private const val FEATHER = 0.42f

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val v = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    /** How far each bath has soaked down the film, 0..1, and the veil over the clearing slide. */
    class Fronts(p: Float) {
        val first = smooth(0.0f, 0.34f, p)
        val colour = smooth(0.26f, 0.54f, p)
        val clear = smooth(0.50f, 0.92f, p)
        /** The veil's colour, black to milky, and how much of it is left. */
        val veilLight = smooth(0.54f, 0.78f, p)
        val veil = 1f - smooth(0.66f, 0.96f, p)
        val dry = smooth(0.88f, 1f, p)
    }

    /**
     * Draws [content] where a bath has soaked to [k]: fully above the front, fading out over the
     * front's depth, nothing below. [blend] and [alpha] are how the soaked layer lies on what is under.
     */
    private fun DrawScope.soaked(
        box: Rect, bounds: Rect, k: Float, wave: Float, seed: Float,
        blend: BlendMode = BlendMode.SrcOver, alpha: Float = 1f, content: DrawScope.() -> Unit,
    ) {
        if (k <= 0f || alpha <= 0f) return
        drawIntoCanvas { c ->
            c.saveLayer(bounds, Paint().apply { blendMode = blend; this.alpha = alpha })
            content()
            if (k < 1f) {
                val f = box.height * FEATHER
                val y = box.top - f + (box.height + 2f * f) * k
                // The liquid's surface rocks slowly with the tank: a small, slow tilt of the front.
                val tilt = sin(wave * 1.3f + seed) * box.width * 0.03f
                drawRect(Brush.linearGradient(0f to Color.Black, 1f to Color.Transparent,
                    start = Offset(box.center.x + tilt, y - f), end = Offset(box.center.x - tilt, y + f)),
                    bounds.topLeft, bounds.size, blendMode = BlendMode.DstIn)
            }
            c.restore()
        }
    }

    private fun DrawScope.cream(r: Rect) =
        drawRect(Brush.linearGradient(listOf(CreamA, CreamB), r.topLeft, r.bottomRight), r.topLeft, r.size)

    /** Film grain over [r]: a noise tile, overlaid; [shift] moves it with the film. */
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
     * negative (grey, inverted). [wave] is a clock in seconds, for the tank's slow rocking.
     */
    fun DrawScope.frame(
        r: Rect, p: Float, box: Rect, wave: Float, tile: ImageBitmap?, shift: Offset,
        positive: DrawScope.() -> Unit, negative: DrawScope.() -> Unit,
    ) {
        val f = Fronts(p)
        clipRect(r.left, r.top, r.right, r.bottom) {
            cream(r)
            // The negative builds up in the cream (multiplied into it), denser the longer it soaks.
            soaked(box, r, f.first, wave, 0.7f, BlendMode.Multiply, 0.9f * (0.4f + 0.6f * f.first)) { negative() }
            soaked(box, r, f.colour, wave, 1.7f) {
                drawRect(Silver.copy(alpha = 0.95f), r.topLeft, r.size)
            }
            // The positive rises out of the black under a veil that lightens to milky as it thins away.
            soaked(box, r, f.clear, wave, 2.7f) {
                positive()
                val v = lerpColor(Silver, Veil, f.veilLight)
                drawRect(v.copy(alpha = (0.85f - 0.25f * f.veilLight) * f.veil), r.topLeft, r.size)
            }
            if (f.dry > 0f && f.dry < 1f) {
                // Wash and dry: one soft sheen slides off.
                val sx = r.left + r.width * (f.dry * 1.6f - 0.3f)
                drawRect(Brush.linearGradient(0f to Color.Transparent, 0.5f to Color(0xFFFFFCF4).copy(alpha = 0.10f * sin(f.dry * PI_F)), 1f to Color.Transparent,
                    start = Offset(sx - r.width * 0.3f, r.top), end = Offset(sx + r.width * 0.3f, r.bottom)), r.topLeft, r.size, blendMode = BlendMode.Screen)
            }
        }
        // Grain: plain in the cream, buried in the black, fine in the finished slide.
        if (tile != null) grain(r, 0.2f * (1f - 0.85f * f.clear) * (1f - 0.5f * f.colour * (1f - f.clear)) + 0.04f, tile, shift)
    }

    /** The rebate and the unexposed frames: cream, black from the colour developer on, a faint haze while it clears. */
    fun DrawScope.rebate(box: Rect, p: Float, wave: Float, tile: ImageBitmap?, shift: Offset) {
        val f = Fronts(p)
        cream(box)
        if (tile != null) grain(box, 0.16f * (1f - f.colour), tile, shift)
        val black = Brush.verticalGradient(0f to Color(0xFF0A0908), 0.12f to Color(0xFF191714), 0.5f to Color(0xFF110F0D),
            0.88f to Color(0xFF171512), 1f to Color(0xFF090807), startY = box.top, endY = box.bottom)
        soaked(box, box, f.colour, wave, 1.7f) { drawRect(black, box.topLeft, box.size) }
        soaked(box, box, f.clear, wave, 2.7f, alpha = 0.16f * f.veilLight * f.veil) { drawRect(Haze, box.topLeft, box.size) }
    }

    /** The pre-exposed edge print: dark in the first developer, lost in the black, coming up light as it clears. */
    fun edgeInk(p: Float): Color {
        val f = Fronts(p)
        val light = 0.85f * smooth(0.72f, 0.95f, p)
        val dark = 0.7f * f.first * (1f - f.colour)
        return if (light >= dark) Color(0xFFECCE96).copy(alpha = light) else Color(0xFF342C22).copy(alpha = dark)
    }

    private const val PI_F = Math.PI.toFloat()

    private fun lerpColor(a: Color, b: Color, t: Float) =
        Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

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
