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
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

// --- the aurora band (API 33+), tuned here --------------------------------------
/** How far up from the bottom edge the band reaches, as a fraction of the height (the lower third). */
internal const val AURORA_REACH = 0.34f
/** How much the band's top edge rises and falls, as a fraction of its reach. */
private const val AURORA_AMPLITUDE = 0.35f
/** Time scale of the drift: 1 = the folds cross the screen in about half a minute. */
private const val AURORA_SPEED = 1f
/** Overall brightness at strength 1: about half the old radial glow on the empty editor. */
internal const val AURORA_BRIGHTNESS = 0.55f
/** Plain over the ground; no additive blend, so the colours never sum towards white or violet. */
private val AURORA_BLEND = BlendMode.SrcOver
/** The band moves at most 30 times a second. */
private const val AURORA_FRAME_MS = 33L
/** The frame shown when animations are off. */
private const val AURORA_STILL_TIME = 40f

/*
 * The colour-drifting glow behind waiting screens, after the Gemini app as it
 * looks since the 2025 rebrand (tools/work/ui-motion_gemini_glow.md): a blue
 * haze rising from the bottom edge, with teal, green and a little yellow as
 * small accents low down that drift through it, as Gemini's thinking glow
 * goes blue -> cyan -> green. No purple in any frame: red never touches blue
 * (blue and red mix to violet whatever the blend mode), so red stays out of
 * the glow and sits next to yellow only, in the spectrum band.
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
    val Teal = Color(0xFF24A2CA)
    val Red = Color(0xFFFC413D)
    val Yellow = Color(0xFFFBBC04)
    val Green = Color(0xFF00B95C)

    /**
     * The accents' loop: teal to green to yellow and back the same way, so it
     * never passes through red or a blue-red mix.
     */
    private val stops = listOf(
        0.00f to Teal, 0.40f to Green, 0.65f to Yellow, 0.85f to Green, 1.00f to Teal,
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

    /** The launcher strip's spectrum and the splash's, in rainbow order so red only meets yellow. */
    val spectrum = listOf(
        0.00f to Blue, 0.25f to Teal, 0.50f to Green, 0.72f to Yellow, 1.00f to Red,
    )

    /** The working bar: the spectrum there and back, so its repeat never joins red to blue. */
    val bar = listOf(Blue, Teal, Green, Yellow, Red, Yellow, Green, Teal, Blue)
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

/**
 * [Aura] whose [strength] is read in the draw phase, so an animated strength
 * recomposes nothing. On API 33+ it is the aurora band ([reach] of the height
 * up from the bottom edge); below that, the radial glow.
 */
@Composable
fun Aura(modifier: Modifier, strength: () -> Float, ground: Color = Palette.Background, reach: Float = AURORA_REACH) {
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) Aurora.create() else null }
    if (shader != null && Build.VERSION.SDK_INT >= 33) AuroraBand(modifier, shader, strength, ground, reach) else RadialAura(modifier, strength, ground)
}

/**
 * The aurora band (after React Bits' Aurora, rebuilt from how it looks, never
 * from its source: MIT + Commons Clause does not mix with GPL-3.0,
 * ui2_reactbits.md 8.1). A wide band along the bottom edge whose top edge rises
 * and falls slowly, with wide soft folds swelling through it like a
 * curtain, fading into the near-black ground. Colour runs across it blue ->
 * teal -> green -> yellow, mostly blue; red never enters it.
 *
 * Time moves at most 30 times a second, only while the screen is started
 * (nothing runs in the background), and stands still with animations off.
 */
@RequiresApi(33)
@Composable
private fun AuroraBand(modifier: Modifier, shader: RuntimeShader, strength: () -> Float, ground: Color, reach: Float) {
    val moving = remember { motionAllowed() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = moving && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val time = remember { mutableFloatStateOf(if (moving) auroraSeconds() else AURORA_STILL_TIME) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = 0L
        while (true) withFrameMillis { ms ->
            if (ms - last >= AURORA_FRAME_MS) { last = ms; time.floatValue = auroraSeconds() }
        }
    }
    val brush = remember(shader) { ShaderBrush(shader) }
    val cpu = LocalCpuGlow.current
    val frame = remember { CpuAurora() }
    Canvas(modifier) {
        if (ground.alpha > 0f) drawRect(ground)
        // A software canvas (a view drawn into a bitmap, Robolectric's screenshots) cannot run a
        // RuntimeShader and would draw nothing: it gets the same band computed on the CPU.
        if (cpu || !drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            frame.draw(this, time.floatValue * AURORA_SPEED, (strength() * AURORA_BRIGHTNESS).coerceIn(0f, 1f),
                reach.coerceIn(0.05f, 1f))
            return@Canvas
        }
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uTime", time.floatValue * AURORA_SPEED)
        shader.setFloatUniform("uStrength", (strength() * AURORA_BRIGHTNESS).coerceIn(0f, 1f))
        shader.setFloatUniform("uReach", reach.coerceIn(0.05f, 1f))
        shader.setFloatUniform("uAmp", AURORA_AMPLITUDE)
        drawRect(brush, blendMode = AURORA_BLEND)
    }
}

/** True draws the aurora on the CPU even on a hardware canvas: the JVM screenshot tests set it. */
internal val LocalCpuGlow = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The aurora band evaluated on the CPU at an eighth of the size and drawn
 * scaled up (the band is soft, so nothing is lost). The same formula as
 * [Aurora]'s SKSL, line for line: keep the two in step.
 */
private class CpuAurora {
    private var cache: androidx.compose.ui.graphics.ImageBitmap? = null
    private var key: List<Any>? = null

    fun draw(scope: DrawScope, t: Float, strength: Float, reach: Float) = with(scope) {
        // Half resolution, scaled up bilinearly: close enough to the shader for screenshots to judge.
        val bw = (size.width / 2f).toInt().coerceAtLeast(1)
        val bh = (size.height / 2f).toInt().coerceAtLeast(1)
        val k = listOf(bw, bh, (t * 30f).toInt(), strength, reach)
        if (k != key) { cache = render(bw, bh, t, strength, reach); key = k }
        drawImage(cache!!, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
    }

    private fun render(bw: Int, bh: Int, t: Float, strength: Float, reach: Float): androidx.compose.ui.graphics.ImageBitmap {
        val px = IntArray(bw * bh)
        val blue = AuraColors.Blue; val teal = AuraColors.Teal; val green = AuraColors.Green; val yellow = AuraColors.Yellow
        val shift = 0.08f * snoise(t * 0.02f, 7.3f)
        for (y in 0 until bh) {
            val d = 1f - (y + 0.5f) / bh
            if (d > reach) continue   // above the band's highest edge: nothing to draw
            for (x in 0 until bw) {
                val u = (x + 0.5f) / bw
                val lift = 0.5f + 0.5f * snoise(u * 1.3f + t * 0.045f, t * 0.03f)
                val edge = reach * (1f - AURORA_AMPLITUDE + AURORA_AMPLITUDE * lift)
                var body = 1f - smooth(0.2f * edge, edge, d)
                body *= body
                if (body <= 0f) continue
                val folds = 0.86f + 0.14f * snoise(u * 1.6f - t * 0.03f, d * 2.2f + t * 0.02f)
                val s = (u + shift).coerceIn(0f, 1f)
                val g = smooth(0.80f, 0.95f, s)
                var c = lerp(blue, teal, smooth(0.45f, 0.72f, s))
                c = lerp(c, green, 0.6f * g)
                c = lerp(c, yellow, 0.25f * smooth(0.92f, 1f, s))
                val a = (body * folds * strength * (1f - 0.5f * g)).coerceIn(0f, 1f)
                px[y * bw + x] = c.copy(alpha = a).toArgb()
            }
        }
        return android.graphics.Bitmap.createBitmap(px, bw, bh, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val v = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    // webgl-noise 2D simplex (Ian McEwan, Ashima Arts; Stefan Gustavson), MIT, as in the SKSL.
    private fun mod289(x: Float) = x - kotlin.math.floor(x / 289f) * 289f
    private fun permute(x: Float) = mod289((x * 34f + 10f) * x)
    private fun fract(x: Float) = x - kotlin.math.floor(x)

    private fun snoise(vx: Float, vy: Float): Float {
        val cx = 0.211324865405187f; val cy = 0.366025403784439f; val cz = -0.577350269189626f; val cw = 0.024390243902439f
        val s = (vx + vy) * cy
        var ix = kotlin.math.floor(vx + s); var iy = kotlin.math.floor(vy + s)
        val tt = (ix + iy) * cx
        val x0x = vx - ix + tt; val x0y = vy - iy + tt
        val i1x = if (x0x > x0y) 1f else 0f; val i1y = 1f - i1x
        val x1x = x0x + cx - i1x; val x1y = x0y + cx - i1y
        val x2x = x0x + cz; val x2y = x0y + cz
        ix = mod289(ix); iy = mod289(iy)
        val p0 = permute(permute(iy) + ix)
        val p1 = permute(permute(iy + i1y) + ix + i1x)
        val p2 = permute(permute(iy + 1f) + ix + 1f)
        var total = 0f
        for ((p, gx, gy) in listOf(Triple(p0, x0x, x0y), Triple(p1, x1x, x1y), Triple(p2, x2x, x2y))) {
            var m = maxOf(0.5f - (gx * gx + gy * gy), 0f)
            m *= m; m *= m
            val xx = 2f * fract(p * cw) - 1f
            val h = kotlin.math.abs(xx) - 0.5f
            val a0 = xx - kotlin.math.floor(xx + 0.5f)
            m *= 1.79284291400159f - 0.85373472095314f * (a0 * a0 + h * h)
            total += m * (a0 * gx + h * gy)
        }
        return 130f * total
    }
}

/** Seconds of process uptime, so the intro's band and the empty editor's are the same frame at the hand-off. */
private fun auroraSeconds(): Float = (android.os.SystemClock.uptimeMillis() % 3_600_000L) / 1000f

@RequiresApi(33)
private object Aurora {
    /** The shader with the palette set, or null if this GPU will not compile it (then the radial glow). */
    fun create(): RuntimeShader? = runCatching {
        RuntimeShader(SKSL).apply {
            fun set(name: String, c: Color) = setFloatUniform(name, c.red, c.green, c.blue)
            set("uBlue", AuraColors.Blue); set("uTeal", AuraColors.Teal)
            set("uGreen", AuraColors.Green); set("uYellow", AuraColors.Yellow)
        }
    }.onFailure { android.util.Log.w("Aurora", "shader unavailable, radial glow instead", it) }.getOrNull()

    // snoise() below is the 2D simplex noise of webgl-noise (Ian McEwan, Ashima Arts;
    // Stefan Gustavson), MIT licence, https://github.com/ashima/webgl-noise (NOTICE).
    // Everything after it -- the band, its folds, the colour ramp -- is written here.
    private const val SKSL = """
uniform float2 uSize;
uniform float uTime;
uniform float uStrength;
uniform float uReach;
uniform float uAmp;
uniform float3 uBlue;
uniform float3 uTeal;
uniform float3 uGreen;
uniform float3 uYellow;

float3 mod289(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float2 mod289(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float3 permute(float3 x) { return mod289(((x * 34.0) + 10.0) * x); }

float snoise(float2 v) {
    const float4 C = float4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
    float2 i = floor(v + dot(v, C.yy));
    float2 x0 = v - i + dot(i, C.xx);
    float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod289(i);
    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));
    float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m;
    m = m * m;
    float3 x = 2.0 * fract(p * C.www) - 1.0;
    float3 h = abs(x) - 0.5;
    float3 ox = floor(x + 0.5);
    float3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    float3 g = float3(a0.x * x0.x + h.x * x0.y, a0.y * x12.x + h.y * x12.y, a0.z * x12.z + h.z * x12.w);
    return 130.0 * dot(m, g);
}

half4 main(float2 p) {
    float2 uv = p / uSize;
    float d = 1.0 - uv.y;
    float t = uTime;
    // The band's top edge rises and falls along its length.
    float lift = 0.5 + 0.5 * snoise(float2(uv.x * 1.3 + t * 0.045, t * 0.03));
    float edge = uReach * (1.0 - uAmp + uAmp * lift);
    // Full at the bottom, fading over the upper 80 % of the band: one long soft edge, no step.
    float body = 1.0 - smoothstep(0.2 * edge, edge, d);
    body = body * body;
    // Wide horizontal folds, slow and low: the curtain swells instead of standing in columns.
    float folds = 0.86 + 0.14 * snoise(float2(uv.x * 1.6 - t * 0.03, d * 2.2 + t * 0.02));
    // Blue and teal carry the band; green and a touch of yellow only at the far right, dimmer.
    float x = clamp(uv.x + 0.08 * snoise(float2(t * 0.02, 7.3)), 0.0, 1.0);
    float g = smoothstep(0.80, 0.95, x);
    float3 c = mix(uBlue, uTeal, smoothstep(0.45, 0.72, x));
    c = mix(c, uGreen, 0.6 * g);
    c = mix(c, uYellow, 0.25 * smoothstep(0.92, 1.0, x));
    float k = clamp(body * folds * uStrength * (1.0 - 0.5 * g), 0.0, 1.0);
    // One step of dither, so the dark tail does not band.
    k = clamp(k + (fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0, 0.0, 1.0);
    return half4(float4(c * k, k));
}
"""
}

/** The radial glow: the aurora's stand-in on API 29-32, where there is no RuntimeShader. */
@Composable
private fun RadialAura(modifier: Modifier, strength: () -> Float, ground: Color) {
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
        drawRadial(t, strength())
    }
}

/** The radial glow's circles at phase [t] (0..1 of its loop), [strength] scaling their opacity. */
private fun DrawScope.drawRadial(t: Float, strength: Float) {
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
        val alpha = 0.55f * b.weight * strength
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
                    AuraColors.bar,
                    start = Offset(x - span, 0f), end = Offset(x + span, 0f),
                    tileMode = TileMode.Repeated,
                ),
            )
        }
    }
}

private const val PERIOD_MS = 24_000
