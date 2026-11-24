package com.dearded.spektralab

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The launch intro (ui-polish; preview/ui-polish.html playIntro). The system splash shows the
 * launcher's film strip with its spectrum wiping on; this picks up from that exact frame, so there
 * is no flash, and then makes one exposure:
 *
 *   the camera moves in on the strip, which runs on beyond the light on a light box;
 *   the advance lever winds three frames on, one stroke each (Cue.Ratchet), the counter steps;
 *   the shutter fires (Cue.Shutter): the new frame flashes, then develops as reversal film does,
 *   cream to a silver negative, to black, to a hazed positive, to a clear slide (FilmDevelop),
 *   a red halation blooming round it as it clears; the tank turns over with each bath (Cue.Agitate);
 *   the name lands on split-flap tiles under the strip;
 *   the developed frame opens out over the screen and the editor is under it, the glow on the
 *   same phase.
 *
 * About 3.2 s. It plays once, on a launcher start with system animations on; it has no setting
 * of its own. A tap after 150 ms skips it. The engine warms up underneath; if it is still warming
 * when the intro ends, its waiting screen shows.
 */

/** The name the intro flaps in, and when it starts (ms after the first frame). */
private const val NAME = "Spektralab-X"

/** Where the system splash drew its icon, and how far its wipe had got, handed over by MainActivity. */
internal object LaunchIcon {
    /** Window coordinates in px, or null when the splash did not report it (then the strip is centred). */
    var rect: android.graphics.Rect? = null
    /** 0..1 of the spectrum wipe the splash had shown (1 below API 31, where the icon is still). */
    var wipe: Float = 1f
}

/**
 * Whether this start gets the intro: a launcher start with system animations on. There is no
 * setting of its own: it plays once per start, and the system's animation switch turns it off.
 */
@Suppress("UNUSED_PARAMETER")
internal fun launchIntroWanted(context: Context, intent: Intent?): Boolean =
    intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && motionAllowed()

// The launcher foreground's geometry (ic_launcher_foreground.xml, a 108-unit viewport).
private const val PITCH = 10.56f
private val SPECTRUM = AuraColors.spectrum

/** Where the intro is, read in the draw phase only. */
private class IntroState {
    val wipe = Animatable(LaunchIcon.wipe.coerceIn(0f, 1f))
    val dolly = Animatable(0f)
    val pull = Animatable(0f)
    val flash = Animatable(0f)
    val dev = Animatable(0f)
    val open = Animatable(0f)
    val shown = Animatable(1f)
    val name = Animatable(0f)
}

/**
 * The intro over the first screen. [ready] is the engine's readiness (the intro does not wait
 * for it); [onDone] runs once it has gone, and the caller then drops it.
 */
@Composable
fun LaunchIntro(ready: Boolean, onDone: () -> Unit) {
    val start = remember { SystemClock.uptimeMillis() }
    val done by rememberUpdatedState(onDone)
    val view = androidx.compose.ui.platform.LocalView.current
    val st = remember { IntroState() }
    var leaving by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf(false) }
    var nameOn by remember { mutableStateOf(false) }
    val glow = remember { Animatable(0f) }
    val cue = { c: Cue -> if (!quick) Feedback.play(view, c) }

    LaunchedEffect(Unit) {
        launch { glow.animateTo(1f, Motion.slowEffects()) }
        launch { st.wipe.animateTo(1f, Motion.defaultEffects()) }
        launch { delay(200); st.dolly.animateTo(1f, spring(dampingRatio = 0.92f, stiffness = 34f, visibilityThreshold = 0.0005f)) }
        delay(340)
        // Three strokes of the lever: each lands before the next starts.
        for (i in 1..3) {
            cue(Cue.Ratchet)
            st.pull.animateTo(i.toFloat(), spring(dampingRatio = 0.7f, stiffness = 1100f, visibilityThreshold = 0.002f))
            delay(70)
        }
        cue(Cue.Counter)
        delay(160)
        cue(Cue.Shutter)
        launch { st.flash.snapTo(1f); st.flash.animateTo(0f, Motion.fastEffects()) }
        launch { delay(180); nameOn = true; st.name.animateTo(1f, Motion.defaultSpatial()) }
        // One turn of the tank as each bath goes in (FilmDevelop.Fronts: 0.02, 0.28, 0.50, mid-clear).
        launch { for (ms in longArrayOf(30, 390, 330, 315)) { delay(ms); cue(Cue.Agitate) } }
        // The bath: 1.5 s from cream to a clear slide; the halation blooms as it clears.
        st.dev.animateTo(1f, tween(1500, easing = LinearEasing))
        delay(380)
        leaving = true
    }
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        if (quick) st.shown.animateTo(0f, Motion.fastEffects())
        else {
            // The developed frame opens out over the screen; under it, the editor.
            st.open.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 150f, visibilityThreshold = 0.002f))
            st.shown.animateTo(0f, Motion.defaultEffects())
        }
        done()
    }

    val measurer = rememberTextMeasurer()
    val edgeStyle = remember { TextStyle(fontFamily = UiMono, fontWeight = FontWeight.SemiBold) }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = st.shown.value }
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                detectTapGestures {
                    // 150 ms guard: the launcher touch that started us is not a skip.
                    if (!leaving && SystemClock.uptimeMillis() - start > 150) { quick = true; leaving = true }
                }
            },
    ) {
        Aura(Modifier.matchParentSize(), strength = { glow.value })
        // The light box under the strip: a warm pool of light, brighter as the camera moves in.
        Canvas(Modifier.matchParentSize()) {
            val g = geometry(this)
            val a = (1f - (st.open.value * 2.4f).coerceIn(0f, 1f)) * (0.35f + 0.65f * st.dolly.value)
            if (a > 0f) drawCircle(Brush.radialGradient(0f to Color(0xFFFFF4E2).copy(alpha = 0.30f * a), 0.55f to Color(0xFFFFF0DC).copy(alpha = 0.12f * a),
                1f to Color.Transparent, center = g.centre, radius = 150f * g.u / 2.2f * (1 + 0.42f * st.dolly.value)),
                radius = 150f * g.u, center = g.centre)
        }
        // The strip, in its own layer so the perforations punch through to the light behind.
        Canvas(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val g = geometry(this)
            val u = g.u
            val stripAlpha = 1f - (st.open.value * 2.4f).coerceIn(0f, 1f)
            val s = 1f + 0.42f * st.dolly.value
            val ext = st.dolly.value * 70f * u
            val bx0 = g.x(22f) - ext; val bx1 = g.x(86f) + ext
            val by = g.y(36f); val bh = 36f * u
            val fy = g.y(43f); val fh = 22f * u; val fw = 64f * u; val frameStep = 6 * PITCH * u
            val off = st.pull.value * frameStep
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            // The grain is the film's: it moves with the strip and nothing else.
            val shift = Offset(((-off) % 160f + 160f) % 160f, 0f)
            withTransform({ scale(s, s, g.centre) }) {
                clipRect(bx0, by, bx1, by + bh) {
                    drawRect(Brush.verticalGradient(0f to Color(0xFF0D0C0B), 0.12f to Color(0xFF1F1D1A), 0.5f to Color(0xFF151412),
                        0.88f to Color(0xFF1C1A17), 1f to Color(0xFF0B0A09), startY = by, endY = by + bh), Offset(bx0, by), Size(bx1 - bx0, bh), alpha = stripAlpha)
                    for (k in -2..5) {
                        val fx0 = g.x(22f) + k * frameStep - off + 1.2f * u
                        val w = fw - 2.4f * u
                        if (fx0 > bx1 || fx0 + w < bx0) continue
                        val r = Rect(fx0, fy, fx0 + w, fy + fh)
                        val spectrum = Brush.horizontalGradient(*SPECTRUM.toTypedArray(), startX = r.left, endX = r.right)
                        when (k) {
                            0 -> drawRect(spectrum, r.topLeft, Size(r.width * st.wipe.value, r.height), alpha = stripAlpha)
                            3 -> with(FilmDevelop) {
                                if (st.dev.value <= 0f) drawRect(Color(0xFFA3A090), r.topLeft, r.size, alpha = 0.16f * stripAlpha)
                                else frame(r, st.dev.value, Rect(bx0, by, bx1, by + bh), t, FilmDevelop.grainTile, shift,
                                    positive = { drawRect(spectrum, r.topLeft, r.size, alpha = stripAlpha) },
                                    negative = { drawRect(Brush.horizontalGradient(listOf(Color(0xFF8A8478), Color(0xFF5D5850), Color(0xFF3E3A34), Color(0xFF6F6A60)),
                                        startX = r.left, endX = r.right), r.topLeft, r.size, alpha = stripAlpha) })
                            }
                            // Unexposed frames: a faint milky rectangle on the black.
                            else -> drawRect(Color(0xFFA3A090), r.topLeft, r.size, alpha = 0.16f * stripAlpha)
                        }
                        // The edge print, as a lab's printer leaves it.
                        val ink = Color(0xFFDEA046).copy(alpha = 0.7f * stripAlpha)
                        val small = edgeStyle.copy(fontSize = (2.1f * u).toSp(), color = ink)
                        drawText(measurer.measure("▸ ${12 + k}", small), topLeft = Offset(fx0 + 2 * u, g.y(40.9f)))
                        drawText(measurer.measure("${12 + k}A", small), topLeft = Offset(fx0 + w - 8 * u, g.y(40.9f)))
                        if (k % 2 == 0) drawText(measurer.measure("SPEKTRALAB 100", small), topLeft = Offset(fx0 + 18 * u, g.y(64.7f)))
                    }
                    // The perforations, punched through.
                    var i = -12
                    while (i <= 40) {
                        val xx = g.x(25f + i * PITCH) - off
                        if (xx > bx0 - 10f && xx < bx1 + 10f) for (hy in floatArrayOf(37.7f, 66.7f)) {
                            val hole = Path().apply { addRoundRect(FilmDevelop.perforation(Offset(xx, g.y(hy)), Size(5.2f * u, 3.6f * u))) }
                            drawPath(hole, Color.Black, blendMode = BlendMode.Clear)
                        }
                        i++
                    }
                    // The film's gloss, dragged across by the push-in.
                    val sx = 0.25f + st.dolly.value * 0.35f
                    drawRect(Brush.linearGradient(0f to Color.Transparent, (sx - 0.12f).coerceIn(0f, 1f) to Color.Transparent,
                        sx to Color(0x12FFFAF0), (sx + 0.12f).coerceIn(0f, 1f) to Color.Transparent, 1f to Color.Transparent,
                        start = Offset(bx0, by), end = Offset(bx1, by + bh)), Offset(bx0, by), Size(bx1 - bx0, bh), alpha = stripAlpha)
                }
                // The ends fade: the strip runs on beyond the light.
                if (ext > 0f) for ((from, to) in listOf(bx0 to bx0 + ext * 0.9f, bx1 to bx1 - ext * 0.9f)) {
                    drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = from, endX = to),
                        Offset(minOf(from, to), by - 2f), Size(kotlin.math.abs(to - from), bh + 4f), blendMode = BlendMode.DstOut)
                }
                val fr = Rect(g.x(22f) + 1.2f * u, fy, g.x(22f) + 1.2f * u + fw - 2.4f * u, fy + fh)
                // Halation: real film's red bloom round a bright frame, once it has cleared.
                val p = st.dev.value
                val halo = smooth(0.7f, 0.95f, p) * (1f - 0.5f * smooth(0.92f, 1f, p)) * stripAlpha
                if (halo > 0.01f) for (n in 1..7) {
                    val grow = n * 2.6f * density
                    drawRoundRect(Color(0xFFFF4A20).copy(alpha = 0.11f * halo * (1f - n / 8f)), fr.topLeft - Offset(grow, grow),
                        Size(fr.width + 2 * grow, fr.height + 2 * grow), CornerRadius(grow), style = Stroke(3.2f * density), blendMode = BlendMode.Plus)
                }
                val flash = st.flash.value
                if (flash > 0.01f) {
                    for (n in 0..5) {
                        val grow = n * 6f * density
                        drawRoundRect(Color(0xFFFFFDF6).copy(alpha = flash * (if (n == 0) 1f else 0.12f)), fr.topLeft - Offset(grow, grow),
                            Size(fr.width + 2 * grow, fr.height + 2 * grow), CornerRadius(grow))
                    }
                }
            }
            // The frame opening out over the screen.
            val o = st.open.value
            if (o > 0f) {
                val fr = Rect(g.x(22f) + 1.2f * u, fy, g.x(22f) + 1.2f * u + fw - 2.4f * u, fy + fh)
                val sc = 1f + 0.42f * st.dolly.value
                val a = Rect(g.centre + (fr.topLeft - g.centre) * sc, g.centre + (fr.bottomRight - g.centre) * sc)
                val r = Rect(lerp(a.left, 0f, o), lerp(a.top, 0f, o), lerp(a.right, size.width, o), lerp(a.bottom, size.height, o))
                drawRect(Brush.horizontalGradient(*SPECTRUM.toTypedArray(), startX = r.left, endX = r.right), r.topLeft, r.size,
                    alpha = (1f - o * 1.15f).coerceIn(0f, 1f))
            }
        }
        // The name under the strip on split-flap tiles (SplitFlap.kt), rising in as the frame develops.
        if (nameOn || quick) {
            val r = LaunchIcon.rect
            SplitFlapText(
                NAME,
                style = MaterialTheme.typography.headlineSmall,
                skip = quick,
                modifier = Modifier
                    .align(if (r == null) Alignment.Center else Alignment.TopCenter)
                    .graphicsLayer {
                        val n = if (quick) 1f else st.name.value
                        alpha = n.coerceIn(0f, 1f) * (1f - (st.open.value * 2.4f).coerceIn(0f, 1f))
                        translationY = (1f - n) * 10.dp.toPx()
                    }
                    .layout { m, c ->
                        val pl = m.measure(c.copy(minWidth = 0, minHeight = 0))
                        layout(pl.width, pl.height) {
                            // Under the strip as the push-in leaves it: 18 units below the icon's middle, scaled, plus 26 dp.
                            val side = if (r != null && r.width() > 0) r.width().toFloat() else 240.dp.toPx()
                            val mid = if (r != null && r.width() > 0) r.top + r.height() / 2f else 0f
                            val y = mid + side * 18f / 108f * (1f + 0.42f * st.dolly.value) + 26.dp.toPx()
                            pl.place(0, y.roundToInt())
                        }
                    }
            )
        }
    }
}

/** The splash icon's box in this canvas, and the 108-unit coordinates in it. */
private class Geometry(val box: Rect) {
    val u = box.width / 108f
    val centre = Offset(x(54f), y(54f))
    fun x(v: Float) = box.left + v * u
    fun y(v: Float) = box.top + v * u
}

private fun geometry(scope: DrawScope): Geometry {
    val r = LaunchIcon.rect
    // What the system reported, else 240 dp centred (API 31's icon size).
    val box = if (r != null && r.width() > 0) Rect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
    else with(scope) {
        val side = 240.dp.toPx()
        Rect(Offset((size.width - side) / 2f, (size.height - side) / 2f), Size(side, side))
    }
    return Geometry(box)
}

private fun smooth(e0: Float, e1: Float, x: Float): Float {
    val v = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
