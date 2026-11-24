package com.dearded.spektralab

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp

/*
 * The darkroom assistant's light (docs/FRONTEND_ITERATION_2026-11-24.md C5, C6): the same liquid
 * silver as the waiting screens (Aura.kt), much quieter. It only ever shows a real state:
 *
 *  - while the model works on parameters, a soft light flows slowly round the goal field and the
 *    button that sent it (EdgeLight);
 *  - while the engine previews the adjustment, the light moves round the photo's edges, outside
 *    it (PhotoRim).
 *
 * Both carry a faint refraction on their crests (a spectrum split close to grey, after React Bits
 * Prism, much quieter). Never over the photo, never in an export, no hard edge, no particles. It fades in over 400 ms and
 * out over 600 ms; its clock runs only while it is visible, at most 30 times a second; with system
 * animations off it is a still glow. Drawn in its own draw pass: nothing recomposes per frame.
 */

/** 0..1 of the light, following [active]: a slow rise, a slower fall, then nothing runs. */
@Composable
private fun rememberLightLevel(active: Boolean): Animatable<Float, *> {
    val level = remember { Animatable(0f) }
    LaunchedEffect(active) { level.animateTo(if (active) 1f else 0f, tween(if (active) 400 else 600)) }
    return level
}

/** Seconds, ticking 30 times a second while [running]; frozen otherwise (and with animations off). */
@Composable
private fun rememberLightClock(running: Boolean): androidx.compose.runtime.MutableFloatState {
    val clock = remember { mutableFloatStateOf(17f) }
    val moving = remember { motionAllowed() }
    LaunchedEffect(running && moving) {
        if (!(running && moving)) return@LaunchedEffect
        var last = 0L
        while (true) withFrameMillis { ms ->
            if (ms - last >= 33L) { last = ms; clock.floatValue = (android.os.SystemClock.uptimeMillis() % 3_600_000L) / 1000f }
        }
    }
    return clock
}

/**
 * [content] with a soft light flowing round its bounds while [active] (the model generating),
 * feathered [reach] outward. The light is drawn behind, outside the bounds; the content is untouched.
 */
@Composable
internal fun EdgeLit(
    active: Boolean, modifier: Modifier = Modifier, radius: androidx.compose.ui.unit.Dp = 12.dp,
    reach: androidx.compose.ui.unit.Dp = 22.dp, content: @Composable () -> Unit,
) {
    val level = rememberLightLevel(active)
    val lit by remember { androidx.compose.runtime.derivedStateOf { level.value > 0f } }
    val clock = rememberLightClock(lit || active)
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) EdgeShader.create() else null }
    Box(modifier.drawBehind {
        val k = level.value
        if (k <= 0.001f) return@drawBehind
        val r = radius.toPx()
        val f = reach.toPx()
        val t = clock.floatValue
        if (shader != null && Build.VERSION.SDK_INT >= 33 && drawContext.canvas.nativeCanvas.isHardwareAccelerated && !FilmDevelop.cpuOnly) {
            shader.setFloatUniform("uBox", 0f, 0f, size.width, size.height)
            shader.setFloatUniform("uRadius", r)
            shader.setFloatUniform("uFeather", f)
            shader.setFloatUniform("uTime", t)
            shader.setFloatUniform("uLevel", k)
            drawRect(ShaderBrush(shader), Offset(-f, -f), Size(size.width + 2 * f, size.height + 2 * f))
        } else edgeFallback(k, t, r, f)
    }) { content() }
}

/** Below API 33 and on software canvases: three soft rounded rings, breathing slowly, and a fine bright edge. */
private fun DrawScope.edgeFallback(k: Float, t: Float, r: Float, f: Float) {
    val breath = 0.75f + 0.25f * kotlin.math.sin(t * 1.6f)
    for (i in 3 downTo 1) {
        val grow = f * i / 3f
        drawRoundRect(AuraColors.Silver.copy(alpha = 0.07f * k * breath * (1.2f - i / 3f)), Offset(-grow, -grow),
            Size(size.width + 2 * grow, size.height + 2 * grow), CornerRadius(r + grow), style = Stroke(grow * 0.9f))
    }
    drawRoundRect(AuraColors.Ivory.copy(alpha = 0.22f * k * breath), Offset(-1f, -1f), Size(size.width + 2f, size.height + 2f),
        CornerRadius(r + 1f), style = Stroke(1.5f))
}

@RequiresApi(33)
private object EdgeShader {
    fun create(): RuntimeShader? = runCatching {
        RuntimeShader(AGSL).apply {
            fun set(name: String, c: Color) = setFloatUniform(name, c.red, c.green, c.blue)
            set("uSilver", AuraColors.Silver); set("uIvory", AuraColors.Ivory); set("uAmber", AuraColors.Amber)
        }
    }.onFailure { android.util.Log.w("EdgeLight", "shader unavailable", it) }.getOrNull()

    private const val AGSL = """
uniform float4 uBox;
uniform float uRadius;
uniform float uFeather;
uniform float uTime;
uniform float uLevel;
uniform float3 uSilver;
uniform float3 uIvory;
uniform float3 uAmber;

float hash(float2 q) { return fract(sin(dot(q, float2(127.1, 311.7))) * 43758.5453); }
float vnoise(float x) { float i = floor(x); float f = fract(x); f = f * f * (3.0 - 2.0 * f);
    return mix(hash(float2(i, 0.0)), hash(float2(i + 1.0, 0.0)), f); }
float sdRound(float2 p, float2 b, float r) { float2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }

half4 main(float2 p) {
    float2 c = uBox.xy + uBox.zw * 0.5;
    float d = sdRound(p - c, uBox.zw * 0.5, uRadius);
    // Only outside the box and on its edge: the field and its text stay untouched.
    if (d < -1.0) return half4(0.0);
    float2 q = p - c;
    float ang = atan(q.y, q.x) / 6.2832;
    // Light flowing round the edge: two slow crests and a drifting unevenness, never a spinning dot.
    float flow = 0.5 + 0.5 * sin(6.2832 * (2.0 * ang - 0.07 * uTime) + 1.6 * vnoise(ang * 9.0 + uTime * 0.15));
    float core = exp(-abs(d) / 1.6);
    float bloom = exp(-max(d, 0.0) / (uFeather * 0.45));
    float a = (0.30 * core + 0.55 * bloom) * (0.35 + 0.65 * flow) * uLevel * 0.42;
    float3 col = mix(uSilver, uIvory, flow);
    // Refraction on the crest: a faint spectrum split along the edge, kept close to grey.
    float3 spectrum = 0.5 + 0.5 * cos(6.2832 * (ang * 2.0 - uTime * 0.035 + max(d, 0.0) / uFeather * 0.7 + float3(0.0, 0.33, 0.67)));
    col = mix(col, spectrum, 0.14 * flow * bloom);
    col = mix(col, uAmber, 0.25 * (1.0 - flow) * bloom);
    a *= 1.0 - smoothstep(uFeather * 0.7, uFeather, d);
    a = clamp(a + (hash(p) - 0.5) / 255.0, 0.0, 1.0);
    return half4(half3(col * a), half(a));
}
"""
}

/**
 * The light round the photo while the adjustment previews (C6): a soft refracted glow that hugs the
 * photo's four edges from outside and travels slowly round them, as light through the edge of a prism.
 * [photo] is the photo's rect in this box's coordinates; nothing is drawn inside it, so the photo looks
 * exactly as it will. It waits [patience] before it shows, so a quick render (a strength step) never
 * flickers it; it leaves in 600 ms when the draft arrives, in 200 ms when the work stopped ([quick]).
 */
@Composable
internal fun PhotoRim(photo: Rect?, active: Boolean, quick: Boolean = false, patience: Long = 250) {
    val shown by androidx.compose.runtime.produceState(false, active) {
        value = false
        if (active) { kotlinx.coroutines.delay(patience); value = true }
    }
    val level = remember { Animatable(0f) }
    LaunchedEffect(shown, quick) { level.animateTo(if (shown) 1f else 0f, tween(if (shown) 420 else if (quick) 200 else 600)) }
    val lit by remember { androidx.compose.runtime.derivedStateOf { level.value > 0f } }
    val clock = rememberLightClock(lit)
    if (photo == null || !lit) return
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) RimShader.create() else null }
    val reach = 28.dp
    Canvas(Modifier.fillMaxSize()) {
        val k = level.value
        val f = reach.toPx()
        val t = clock.floatValue
        val outer = Rect(photo.left - f, photo.top - f, photo.right + f, photo.bottom + f)
        if (shader != null && Build.VERSION.SDK_INT >= 33 && drawContext.canvas.nativeCanvas.isHardwareAccelerated && !FilmDevelop.cpuOnly) {
            shader.setFloatUniform("uRect", photo.left, photo.top, photo.right, photo.bottom)
            shader.setFloatUniform("uFeather", f)
            shader.setFloatUniform("uTime", t)
            shader.setFloatUniform("uLevel", k)
            drawRect(ShaderBrush(shader), outer.topLeft, outer.size)
        } else rimFallback(photo, k, t, f)
    }
}

/** Below API 33 and on software canvases: soft rings outside the photo, breathing, with a fine edge. */
private fun DrawScope.rimFallback(photo: Rect, k: Float, t: Float, f: Float) {
    val breath = 0.75f + 0.25f * kotlin.math.sin(t * 1.2f)
    for (i in 3 downTo 1) {
        val grow = f * i / 3f
        drawRect(AuraColors.Silver.copy(alpha = 0.06f * k * breath * (1.2f - i / 3f)),
            Offset(photo.left - grow + grow * 0.45f, photo.top - grow + grow * 0.45f),
            Size(photo.width + 2 * grow - grow * 0.9f, photo.height + 2 * grow - grow * 0.9f), style = Stroke(grow * 0.9f))
    }
    drawRect(AuraColors.Ivory.copy(alpha = 0.18f * k * breath), Offset(photo.left - 1f, photo.top - 1f),
        Size(photo.width + 2f, photo.height + 2f), style = Stroke(1.5f))
}

@RequiresApi(33)
private object RimShader {
    fun create(): RuntimeShader? = runCatching {
        RuntimeShader(AGSL).apply {
            fun set(name: String, c: Color) = setFloatUniform(name, c.red, c.green, c.blue)
            set("uSilver", AuraColors.Silver); set("uIvory", AuraColors.Ivory); set("uAmber", AuraColors.Amber)
        }
    }.onFailure { android.util.Log.w("PhotoRim", "shader unavailable", it) }.getOrNull()

    private const val AGSL = """
uniform float4 uRect;
uniform float uFeather;
uniform float uTime;
uniform float uLevel;
uniform float3 uSilver;
uniform float3 uIvory;
uniform float3 uAmber;

float hash(float2 q) { return fract(sin(dot(q, float2(127.1, 311.7))) * 43758.5453); }

half4 main(float2 p) {
    float2 c = (uRect.xy + uRect.zw) * 0.5;
    float2 h = (uRect.zw - uRect.xy) * 0.5;
    float2 q = p - c;
    float2 e = abs(q) - h;
    float d = length(max(e, 0.0)) + min(max(e.x, e.y), 0.0);
    // Outside only: the photo itself is never lit.
    if (d <= 0.0) return half4(0.0);
    // Position round the perimeter, 0..1, aspect-true so the sweep moves at one speed on every edge.
    float2 n = q / h;
    float ang = atan(n.y, n.x) / 6.2832;
    // One slow crest travelling round (about 9 s a lap) and a fainter one opposite; never a spinning dot.
    float s1 = pow(0.5 + 0.5 * cos(6.2832 * (ang - uTime * 0.11)), 6.0);
    float s2 = pow(0.5 + 0.5 * cos(6.2832 * (ang - uTime * 0.11 + 0.5)), 10.0) * 0.45;
    float sweep = 0.28 + 0.72 * max(s1, s2);
    float core = exp(-d / 1.4);
    float bloom = exp(-d / (uFeather * 0.38));
    float a = (0.26 * core + 0.50 * bloom) * sweep * uLevel * 0.55;
    a *= 1.0 - smoothstep(uFeather * 0.75, uFeather, d);
    // Refraction: the crest splits into a faint spectrum, kept close to grey.
    float k = ang * 3.0 - uTime * 0.04 + d / uFeather * 0.6;
    float3 spectrum = 0.5 + 0.5 * cos(6.2832 * (k + float3(0.0, 0.33, 0.67)));
    float3 col = mix(uSilver, uIvory, s1);
    col = mix(col, spectrum, 0.16 * s1);
    col = mix(col, uAmber, 0.18 * bloom * (1.0 - s1));
    a = clamp(a + (hash(p) - 0.5) / 255.0, 0.0, 1.0);
    return half4(half3(col * a), half(a));
}
"""
}
