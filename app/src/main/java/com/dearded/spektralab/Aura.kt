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
 * The colour-drifting glow behind waiting screens, after the Gemini app
 * (design.google, "Gemini AI visual design"): soft circles of blue, violet
 * and rose that drift, breathe and trade colours on a near-black ground,
 * dense at the centre and dissolving at the tail, sharp nowhere.
 *
 * Only where there is no print to judge -- engine start, opening a photo,
 * the empty editor. Never behind a photo being edited: a moving colour
 * field next to a print skews how its colour reads.
 *
 * Plain radial gradients on a Canvas, so it runs on API 29 without a
 * RuntimeShader; four circles a frame cost next to nothing. With system
 * animations off it holds still.
 */
object AuraColors {
    // The three stops of the Gemini spark gradient.
    val Blue = Color(0xFF4796E3)
    val Violet = Color(0xFF9177C7)
    val Rose = Color(0xFFCA6673)
    val cycle = listOf(Blue, Violet, Rose)

    /** Position [t] (any real) along the blue -> violet -> rose -> blue loop. */
    fun at(t: Float): Color {
        val n = cycle.size
        val x = ((t % 1f) + 1f) % 1f * n
        val i = x.toInt() % n
        return lerp(cycle[i], cycle[(i + 1) % n], x - x.toInt())
    }
}

/** One circle: where it rests (fractions of the box), how far and how fast it drifts. */
private class Blob(
    val x: Float, val y: Float, val r: Float,
    val dx: Float, val dy: Float, val kx: Int, val ky: Int, val phase: Float, val hue: Float,
)

// Weighted to the lower half, as Gemini's glow rises from the bottom edge.
private val Blobs = listOf(
    Blob(0.20f, 0.78f, 0.75f, 0.16f, 0.06f, 1, 2, 0.0f, 0.00f),
    Blob(0.80f, 0.70f, 0.70f, 0.14f, 0.08f, 2, 1, 1.7f, 0.33f),
    Blob(0.50f, 0.95f, 0.85f, 0.20f, 0.04f, 1, 1, 3.1f, 0.66f),
    Blob(0.55f, 0.40f, 0.55f, 0.18f, 0.10f, 1, 2, 4.4f, 0.50f),
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
            val colour = AuraColors.at(b.hue + t)
            val alpha = 0.55f * strength()
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
 * The thin working bar along the top of the preview: the same three
 * colours sliding past, in place of an indeterminate progress line.
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
                    listOf(AuraColors.Blue, AuraColors.Violet, AuraColors.Rose, AuraColors.Blue),
                    start = Offset(x - span, 0f), end = Offset(x + span, 0f),
                    tileMode = TileMode.Repeated,
                ),
            )
        }
    }
}

private const val PERIOD_MS = 24_000
