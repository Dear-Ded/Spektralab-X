package com.dearded.spektralab

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * What 保存 looks like (ui-polish; preview/ui-polish.html playSave): the print on screen shrinks
 * back onto a strip of slide film as an undeveloped frame, develops through the E-6 baths
 * (FilmDevelop, Cue.Agitate at each), is held up to the light box for a moment, and flies into
 * the 保存 button. It is ceremony only: the export is already queued when it starts and runs in
 * the background whatever happens here. A tap anywhere skips it.
 */

/** One save to animate: the print as shown, where it is, and where 保存 is (window px). */
internal class SaveShot(val print: ImageBitmap, val from: Rect, val button: Rect, val area: Rect)

@Composable
internal fun SaveDevelop(shot: SaveShot, onDone: () -> Unit) {
    val view = LocalView.current
    val done by rememberUpdatedState(onDone)
    val start = remember { SystemClock.uptimeMillis() }
    val grow = remember { Animatable(0f) }
    val dev = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val fly = remember { Animatable(0f) }
    var skip by remember { mutableStateOf(false) }
    // The silver negative of the print, made off the main thread while it shrinks.
    var negative by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(shot) { negative = withContext(Dispatchers.Default) { negativeOf(shot.print) } }

    LaunchedEffect(shot) {
        // A tap jumps each step to its end (snapTo interrupts the running animation); the flight
        // into the button always plays, so the eye follows the frame to where it went.
        suspend fun step(block: suspend () -> Unit) {
            try { block() } catch (e: kotlinx.coroutines.CancellationException) {
                if (!skip || !kotlinx.coroutines.currentCoroutineContext().isActive) throw e
            }
        }
        step { grow.animateTo(1f, Motion.defaultSpatial()) }
        if (!skip) delay(260)
        val baths = launch { for (ms in longArrayOf(0, 950, 750, 680)) { delay(ms); if (!skip) Feedback.play(view, Cue.Agitate) } }
        step { dev.animateTo(1f, tween(3400, easing = LinearEasing)) }
        baths.cancel()
        step { glow.animateTo(1f, Motion.slowEffects()) }
        if (!skip) delay(560)
        fly.animateTo(1f, spring(dampingRatio = 0.88f, stiffness = 110f, visibilityThreshold = 0.003f))
        Feedback.play(view, Cue.Saved)
        done()
    }
    LaunchedEffect(skip) {
        if (!skip) return@LaunchedEffect
        grow.snapTo(1f); dev.snapTo(1f); glow.snapTo(1f)
    }

    // The shot is in window coordinates; this canvas may not start at the window's corner.
    var origin by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val edgeStyle = remember { TextStyle(fontFamily = UiMono, fontWeight = FontWeight.SemiBold) }
    Canvas(
        Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .onGloballyPositioned { origin = it.positionInWindow() }
            .clearAndSetSemantics { }
            .pointerInput(Unit) { detectTapGestures { skip = true } },
    ) {
        val w = size.width
        val h = size.height
        val from = shot.from.translate(-origin)
        val area = shot.area.translate(-origin)
        // The slide: up to 78 % of the print, centred in the photo area, with room for the rebate.
        val k = minOf(0.78f, (area.height - 90.dp.toPx()) / from.height, (w - 56.dp.toPx()) / from.width)
        val sw = from.width * k
        val sh = from.height * k
        val slide = Rect(Offset((w - sw) / 2f, area.top + (area.height - sh) / 2f), Size(sw, sh))
        val rebate = max(22.dp.toPx(), sh * 0.13f)
        val strip = Rect(-40f, slide.top - rebate, w + 40f, slide.bottom + rebate)
        val g = grow.value
        val f = fly.value
        val stripIn = (g * 1.4f).coerceIn(0f, 1f)
        val wave = (SystemClock.uptimeMillis() - start) / 1000f
        val shift = Offset((wave * 977f) % 160f, (wave * 613f) % 160f)
        val r = Rect(lerp(from.left, slide.left, g), lerp(from.top, slide.top, g), lerp(from.right, slide.right, g), lerp(from.bottom, slide.bottom, g))

        drawRect(Color(0xFF090908).copy(alpha = 0.86f * stripIn * (1f - f)))
        // The flight to 保存: across with ease-out, down with ease-in, small, turning a little.
        val target = shot.button.center - origin
        val easeF = 1f - (1f - f) * (1f - f) * (1f - f)
        val dx = (target.x - slide.center.x) * easeF
        val dy = (target.y - slide.center.y) * f * f
        val fs = lerp(1f, 0.06f, easeF)
        val alpha = 1f - smoothstep(0.75f, 1f, f)
        translate(dx, dy) {
            scale(fs, fs, slide.center) {
                rotate(-10.3f * f, slide.center) {
                    if (stripIn > 0f) {
                        // The light box under the strip, then the strip, its holes punched through.
                        drawCircle(Brush.radialGradient(0f to Color(0xFFFFF6E8).copy(alpha = (0.62f + 0.25f * glow.value) * stripIn * alpha),
                            0.6f to Color(0xFFFFF4E2).copy(alpha = 0.3f * stripIn * alpha), 1f to Color.Transparent,
                            center = slide.center, radius = max(sw, sh) * 0.95f), radius = max(sw, sh) * 0.95f, center = slide.center)
                        clipRect(strip.left, strip.top, strip.right, strip.bottom) {
                            with(FilmDevelop) { rebate(strip, max(dev.value, 0.0001f), wave, FilmDevelop.grainTile, shift) }
                            val ink = FilmDevelop.edgeInk(dev.value).let { it.copy(alpha = it.alpha * stripIn * alpha) }
                            val small = edgeStyle.copy(fontSize = max(7.dp.toPx(), rebate * 0.2f).toSp(), color = ink)
                            for ((d, n) in listOf(-1 to "23", 0 to "24", 1 to "25")) {
                                val x0 = slide.left + d * sw * 1.05f
                                drawText(measurer.measure("▸ $n", small), topLeft = Offset(x0, strip.top + rebate * 0.62f))
                                drawText(measurer.measure("${n}A", small), topLeft = Offset(x0 + sw - 22.dp.toPx(), strip.top + rebate * 0.62f))
                            }
                            drawText(measurer.measure("SPEKTRALAB 100   E-6", small), topLeft = Offset(slide.left + sw * 0.3f, strip.bottom - rebate * 0.95f))
                            val ph = rebate * 0.34f
                            val pw = ph * 1.45f
                            val pitch = pw * 2.03f
                            var x = strip.left + ((slide.left - strip.left) % pitch)
                            while (x < strip.right) {
                                for (yy in floatArrayOf(strip.top + rebate * 0.2f, strip.bottom - rebate * 0.2f - ph)) {
                                    drawPath(Path().apply { addRoundRect(FilmDevelop.perforation(Offset(x, yy), Size(pw, ph))) }, Color.Black, blendMode = BlendMode.Clear)
                                }
                                x += pitch
                            }
                        }
                    }
                    if (glow.value > 0f) for (n in 0..5) {
                        val gr = n * 8.dp.toPx()
                        drawRect(Color(0xFFFFF6E4).copy(alpha = glow.value * alpha * (if (n == 0) 0.9f else 0.1f)), r.topLeft - Offset(gr, gr), Size(r.width + 2 * gr, r.height + 2 * gr))
                    }
                    val neg = negative
                    if (dev.value <= 0f || neg == null) drawPrint(shot.print, r, alpha)
                    else with(FilmDevelop) {
                        frame(r, dev.value, strip, wave, FilmDevelop.grainTile, shift,
                            positive = { drawPrint(shot.print, r, alpha) },
                            negative = { drawPrint(neg, r, alpha) })
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPrint(img: ImageBitmap, r: Rect, alpha: Float) =
    drawImage(img, srcSize = IntSize(img.width, img.height), dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
        dstSize = IntSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)), alpha = alpha)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun smoothstep(e0: Float, e1: Float, x: Float): Float { val v = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return v * v * (3f - 2f * v) }

/** The silver negative of a print: grey, inverted, a little warm; at most 720 px wide. */
private fun negativeOf(print: ImageBitmap): ImageBitmap {
    val src = print.asAndroidBitmapCompat()
    val scale = min(1f, 720f / src.width)
    val b = android.graphics.Bitmap.createScaledBitmap(src, max(1, (src.width * scale).roundToInt()), max(1, (src.height * scale).roundToInt()), true)
    val px = IntArray(b.width * b.height)
    b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
    for (i in px.indices) {
        val c = px[i]
        val l = (((c shr 16) and 0xFF) * 0.3f + ((c shr 8) and 0xFF) * 0.59f + (c and 0xFF) * 0.11f) / 255f
        val v = 255f - Math.pow(l.toDouble(), 0.85).toFloat() * 210f
        val rr = v.roundToInt().coerceIn(0, 255)
        val gg = (v * 0.98f).roundToInt().coerceIn(0, 255)
        val bb = (v * 0.94f).roundToInt().coerceIn(0, 255)
        px[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
    return android.graphics.Bitmap.createBitmap(px, b.width, b.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun ImageBitmap.asAndroidBitmapCompat(): android.graphics.Bitmap {
    val b = this.asAndroidBitmap()
    return if (b.config == android.graphics.Bitmap.Config.HARDWARE) b.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else b
}
