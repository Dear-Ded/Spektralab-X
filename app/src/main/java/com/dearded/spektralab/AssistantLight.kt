package com.dearded.spektralab

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * The darkroom assistant's light (docs/FRONTEND_ITERATION_2026-11-24.md C5, C6): the same liquid
 * silver as the waiting screens (Aura.kt), much quieter. It only ever shows a real state:
 *
 *  - while the model works on parameters, a soft light flows slowly round the goal field and the
 *    button that sent it (EdgeLight);
 *  - while the engine renders the draft, the light moves to the neutral strip just under the photo,
 *    outside it (UnderLight).
 *
 * Never over the photo, never in an export, no hard edge, no particles. It fades in over 400 ms and
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

/** Below API 33 and on software canvases: three soft rounded strokes and a slowly turning sheen. */
private fun DrawScope.edgeFallback(k: Float, t: Float, r: Float, f: Float) {
    for (i in 3 downTo 1) {
        val grow = f * i / 3f
        drawRoundRect(AuraColors.Silver.copy(alpha = 0.07f * k * (1.2f - i / 3f)), Offset(-grow, -grow),
            Size(size.width + 2 * grow, size.height + 2 * grow), CornerRadius(r + grow), style = Stroke(grow * 0.9f))
    }
    rotate(t * 24f % 360f, center) {
        drawRoundRect(Brush.sweepGradient(listOf(Color.Transparent, AuraColors.Ivory.copy(alpha = 0.28f * k), Color.Transparent,
            AuraColors.Amber.copy(alpha = 0.12f * k), Color.Transparent), center), Offset(-2f, -2f),
            Size(size.width + 4f, size.height + 4f), CornerRadius(r + 2f), style = Stroke(2f))
    }
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
    col = mix(col, uAmber, 0.25 * (1.0 - flow) * bloom);
    a *= 1.0 - smoothstep(uFeather * 0.7, uFeather, d);
    a = clamp(a + (hash(p) - 0.5) / 255.0, 0.0, 1.0);
    return half4(half3(col * a), half(a));
}
"""
}

/**
 * The light under the photo while a draft renders: the liquid-silver band (Aura.kt), turned to hug
 * the photo's lower edge from outside and fade downward. [photo] is the photo's rect in this box's
 * coordinates; nothing is drawn inside it.
 */
@Composable
internal fun UnderLight(photo: Rect?, active: Boolean) {
    val level = rememberLightLevel(active)
    val lit by remember { androidx.compose.runtime.derivedStateOf { level.value > 0f } }
    if (photo == null || (!active && !lit)) return
    val density = LocalDensity.current
    val h = 36.dp
    with(density) {
        Box(
            Modifier.offset { IntOffset(photo.left.roundToInt(), photo.bottom.roundToInt()) }
                .width(photo.width.toDp()).height(h)
                // The band rises from its box's lower edge; flipped, it falls from the photo's.
                .graphicsLayer { scaleY = -1f; alpha = level.value },
        ) {
            Aura(Modifier.fillMaxWidth().height(h), strength = { 0.75f }, ground = Color.Transparent, reach = 1f)
        }
    }
}
