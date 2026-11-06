package com.dearded.spektralab

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The colour-drifting glow behind waiting screens, after the Gemini app as it
 * looks since the 2025 rebrand (tools/work/ui-motion_gemini_glow.md): a blue
 * haze rising from the bottom edge, with Google's red, yellow and green only
 * as small accents low down that drift through it and settle back to blue.
 * No violet field: the old blue-violet-rose read as "AI purple".
 *
 * Only where there is no print to judge -- engine start, opening a photo,
 * the empty editor. Never behind a photo being edited: a moving colour
 * field next to a print skews how its colour reads.
 *
 * Plain radial gradients on a Canvas, so it runs on API 29 without a
 * RuntimeShader; a handful of circles a frame cost next to nothing. With
 * system animations off it holds still.
 */
object AuraColors {
    // Gemini's 2025 spark stops (the Commons SVG), sampled against frames of the app.
    val Blue = Color(0xFF3186FF)
    val LightBlue = Color(0xFF749BFF)
    val Teal = Color(0xFF24A2CA)
    val Red = Color(0xFFFC413D)
    val Yellow = Color(0xFFFBBC04)
    val Green = Color(0xFF00B95C)

    /**
     * One loop, weighted as Gemini shows it: blue about half the way round,
     * the three accents a short stretch each, back to blue through teal.
     */
    private val stops = listOf(
        0.00f to Blue, 0.30f to Blue, 0.40f to LightBlue, 0.50f to Red,
        0.60f to Yellow, 0.72f to Green, 0.84f to Teal, 1.00f to Blue,
    )

    /** Position [t] (any real) along the loop above. */
    fun at(t: Float): Color {
        val x = ((t % 1f) + 1f) % 1f
        for (i in 0 until stops.size - 1) {
            val (a, ca) = stops[i]
            val (b, cb) = stops[i + 1]
            if (x <= b) return lerp(ca, cb, if (b > a) (x - a) / (b - a) else 0f)
        }
        return Blue
    }

    /** The launcher strip's spectrum and the splash's: the same colours as a left-to-right band. */
    val spectrum = listOf(
        0.00f to Blue, 0.30f to LightBlue, 0.48f to Red, 0.66f to Yellow, 0.84f to Green, 1.00f to Teal,
    )
}

/** One circle: where it rests (fractions of the box), how far and how fast it drifts. */
private class Blob(
    val x: Float, val y: Float, val r: Float,
    val dx: Float, val dy: Float, val kx: Int, val ky: Int, val phase: Float,
    /** Where on the colour loop it starts; null keeps it blue for good. */
    val hue: Float?, val weight: Float = 1f,
)

// Gemini's rest state is one blue haze from the bottom edge; the accents are
// smaller and dimmer, low down, and only pass through on their way round.
private val Blobs = listOf(
    Blob(0.50f, 1.02f, 0.80f, 0.10f, 0.04f, 1, 1, 0.0f, null),
    Blob(0.15f, 0.92f, 0.60f, 0.12f, 0.05f, 1, 2, 1.7f, null, 0.8f),
    Blob(0.85f, 0.90f, 0.55f, 0.12f, 0.06f, 2, 1, 3.1f, null, 0.8f),
    Blob(0.30f, 0.96f, 0.32f, 0.18f, 0.04f, 1, 1, 4.4f, 0.45f, 0.55f),
    Blob(0.72f, 0.98f, 0.30f, 0.16f, 0.05f, 1, 2, 2.2f, 0.65f, 0.55f),
)

/** True unless the user turned animations off (developer options or accessibility). */
internal fun motionAllowed() = ValueAnimator.areAnimatorsEnabled()

/**
 * Where every Aura is in its loop: process uptime, not a per-call animation
 * from zero, so the launch intro's glow and the editor's empty-state glow are
 * the same frame when one replaces the other (ui-motion_splash.md 5).
 */
private fun auraPhase(): Float = (android.os.SystemClock.uptimeMillis() % PERIOD_MS) / PERIOD_MS.toFloat()

/**
 * The glow, filling [modifier]'s bounds. [strength] scales its opacity:
 * 1 for the launch screen, about 0.5 under text on a waiting overlay.
 */
@Composable
fun Aura(modifier: Modifier, strength: Float = 1f, ground: Color = Palette.Background) =
    Aura(modifier, { strength }, ground)

/** [Aura] whose [strength] is read in the draw phase, so an animated strength recomposes nothing. */
@Composable
fun Aura(modifier: Modifier, strength: () -> Float, ground: Color = Palette.Background) {
    val moving = remember { motionAllowed() }
    // One clock for everything; every motion is a whole number of cycles per
    // period, so the loop has no seam.
    // Read only inside the draw lambda, so each frame redraws without recomposing.
    val clock = if (moving) {
        rememberInfiniteTransition(label = "aura").animateFloat(
            0f, 1f, infiniteRepeatable(tween(PERIOD_MS, easing = LinearEasing), RepeatMode.Restart), label = "t",
        )
    } else null
    Canvas(modifier) {
        // Reading the clock asks for the next frame; the phase itself is shared.
        val t = if (clock != null) { clock.value; auraPhase() } else 0f
        if (ground.alpha > 0f) drawRect(ground)
        val w = size.width
        val h = size.height
        val span = maxOf(w, h)
        val a = 2f * PI.toFloat() * t
        for (b in Blobs) {
            val c = Offset(
                w * (b.x + b.dx * sin(a * b.kx + b.phase)),
                h * (b.y + b.dy * cos(a * b.ky + b.phase)),
            )
            // Breathe: the radius swells and settles twice a period.
            val r = span * b.r * (0.92f + 0.08f * sin(2f * a + b.phase))
            val colour = b.hue?.let { AuraColors.at(it + t) } ?: AuraColors.Blue
            val alpha = 0.55f * b.weight * strength()
            drawCircle(
                brush = Brush.radialGradient(
                    0f to colour.copy(alpha = alpha),
                    0.35f to colour.copy(alpha = alpha * 0.55f),
                    0.7f to colour.copy(alpha = alpha * 0.15f),
                    1f to Color.Transparent,
                    center = c, radius = r,
                ),
                radius = r, center = c,
                blendMode = BlendMode.Screen,
            )
        }
    }
}

/**
 * The thin working bar along the top of the preview: the glow's colours
 * sliding past, in place of an indeterminate progress line.
 */
@Composable
fun AuraBar(modifier: Modifier = Modifier) {
    val moving = remember { motionAllowed() }
    val clock = if (moving) {
        rememberInfiniteTransition(label = "aura-bar").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "shift",
        )
    } else null
    Box(modifier.fillMaxWidth().height(3.dp)) {
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            val span = size.width
            val x = span * 2f * (clock?.value ?: 0f)
            drawRect(
                Brush.linearGradient(
                    listOf(AuraColors.Blue, AuraColors.LightBlue, AuraColors.Red, AuraColors.Yellow,
                        AuraColors.Green, AuraColors.Teal, AuraColors.Blue),
                    start = Offset(x - span, 0f), end = Offset(x + span, 0f),
                    tileMode = TileMode.Repeated,
                ),
            )
        }
    }
}

private const val PERIOD_MS = 24_000
