package com.dearded.spektralab

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * A frame being developed, drawn: the launch intro's frame and the save animation both use it,
 * with the same formula as the project site's opening print (docs/site/src/hero.js).
 *
 * The frame starts as its colour negative on a light box (the positive inverted, under the orange
 * mask) and the print comes up through it the way it does in the tray: a developer front that is
 * uneven (fbm noise), where the print's dark tones come up a little ahead of its light ones, a warm
 * edge along the front and grain only where it is still developing. At p = 1 every pixel is the
 * positive exactly (the front's threshold is past every point's noise, and the last 3 % lands on it).
 *
 * API 33+: one RuntimeShader with the positive as a child shader. Elsewhere (API 29-32, and software
 * canvases such as the JVM screenshot tests): the same formula on the CPU at a low resolution,
 * drawn scaled, handing over to the sharp positive as it finishes. Keep the two in step, and the
 * site's hero.js.
 */
internal object FilmDevelop {
    /**
     * The slide a frame develops into: [shader] draws it into the frame's rect (an image or a
     * gradient), [small] is a copy at most [SMALL] px wide for the CPU twin.
     */
    class Positive(val shader: (Rect) -> android.graphics.Shader, val small: android.graphics.Bitmap)

    /** The CPU twin's width. */
    const val SMALL = 160

    // --- the model, shared by the shader and the CPU twin -------------------------------------

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val v = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    private fun fract(x: Float) = x - floor(x)
    private fun hash(x: Float, y: Float) = fract(sin(x * 127.1f + y * 311.7f) * 43758.547f)
    private fun vnoise(x: Float, y: Float): Float {
        val ix = floor(x); val iy = floor(y)
        var fx = x - ix; var fy = y - iy
        fx = fx * fx * (3f - 2f * fx); fy = fy * fy * (3f - 2f * fy)
        val a = hash(ix, iy); val b = hash(ix + 1f, iy); val c = hash(ix, iy + 1f); val d = hash(ix + 1f, iy + 1f)
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy
    }

    private fun fbm(x: Float, y: Float): Float {
        var v = 0f; var amp = 0.5f; var px = x; var py = y
        repeat(5) { v += amp * vnoise(px, py); px *= 2.03f; py *= 2.03f; amp *= 0.5f }
        return v
    }

    /** One pixel of the CPU twin, as the shader's main(): [r],[g],[b] the positive there, [u],[v] 0..1 in the frame. */
    private fun pixel(r: Float, g: Float, b: Float, u: Float, v: Float, t: Float, seed: Float, out: FloatArray) {
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val n = fbm(u * 3.2f + seed * 7f, v * 2.2f + seed * 7f + t * 0.6f) * 0.55f + (l - 0.5f) * 0.22f
        val k = smooth(n - 0.1f, n + 0.1f, t * 1.55f - 0.32f)
        val wip = 1f - k
        val grain = (hash(u * 977f + seed, v * 613f + t) - 0.5f) * 0.06f * wip
        val land = smooth(0.97f, 1f, t)
        val src = floatArrayOf(r, g, b)
        for (i in 0..2) {
            val neg = (1f - src[i]) * Mask[i] + Base[i]
            var c = neg + (src[i] - neg) * k + grain + Edge[i] * k * wip * 0.55f
            c += (src[i] - c) * land
            out[i] = c.coerceIn(0f, 1f)
        }
    }

    /** The colour negative's orange mask and base, and the warm edge along the front. */
    private val Mask = floatArrayOf(0.96f, 0.60f, 0.36f)
    private val Base = floatArrayOf(0.03f, 0.012f, 0f)
    private val Edge = floatArrayOf(1f, 0.72f, 0.45f)

    // --- drawing ------------------------------------------------------------------------------

    /** One exposed frame at [p] (0..1) in [r]. [alpha] fades it as a whole. */
    fun DrawScope.frame(r: Rect, p: Float, positive: Positive, seed: Float = 0.37f, alpha: Float = 1f) {
        if (r.width < 1f || r.height < 1f) return
        val sh = if (Build.VERSION.SDK_INT >= 33 && drawContext.canvas.nativeCanvas.isHardwareAccelerated && !cpuOnly) Gpu.shader(seed) else null
        if (sh != null && Build.VERSION.SDK_INT >= 33) {
            Gpu.set(sh, r, p, seed, positive.shader(r))
            drawRect(ShaderBrush(sh), r.topLeft, r.size, alpha = alpha)
        } else {
            cpu(r, p, positive.small, seed, alpha)
            // The twin is soft; the sharp slide takes over as it finishes.
            val sharp = smooth(0.9f, 1f, p)
            if (sharp > 0f) drawRect(ShaderBrush(positive.shader(r)), r.topLeft, r.size, alpha = alpha * sharp)
        }
    }

    /** The rebate and the unexposed frames: the same chemistry over a slide that is all black. */
    fun DrawScope.rebate(box: Rect, p: Float, seed: Float = 0.61f, alpha: Float = 1f) =
        frame(box, p, BlackSlide, seed, alpha)

    /** The pre-exposed edge print: dark on the orange negative, coming up light as the rebate goes black. */
    fun edgeInk(p: Float): Color {
        val light = 0.85f * smooth(0.5f, 0.85f, p)
        val dark = 0.7f * (1f - smooth(0.3f, 0.6f, p))
        return if (light >= dark) Color(0xFFECCE96).copy(alpha = light) else Color(0xFF3A2414).copy(alpha = dark)
    }

    /** A KS perforation: a rounded rectangle. */
    fun perforation(topLeft: Offset, size: Size): androidx.compose.ui.geometry.RoundRect =
        androidx.compose.ui.geometry.RoundRect(Rect(topLeft, size), androidx.compose.ui.geometry.CornerRadius(min(size.width, size.height) * 0.28f))

    /** A slide from a bitmap: drawn to fill the frame, and its small copy for the CPU twin. */
    fun positiveOf(image: android.graphics.Bitmap): Positive {
        val small = android.graphics.Bitmap.createScaledBitmap(image, SMALL,
            (SMALL * image.height / image.width.toFloat()).roundToInt().coerceAtLeast(1), true)
        return Positive({ r ->
            android.graphics.BitmapShader(image, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply {
                    setScale(r.width / image.width, r.height / image.height)
                    postTranslate(r.left, r.top)
                })
            }
        }, small)
    }

    /** A slide that is a horizontal gradient (the intro's spectrum). */
    fun positiveOf(stops: List<Pair<Float, Color>>): Positive {
        val colours = stops.map { android.graphics.Color.argb(255, (it.second.red * 255).roundToInt(), (it.second.green * 255).roundToInt(), (it.second.blue * 255).roundToInt()) }.toIntArray()
        val at = stops.map { it.first }.toFloatArray()
        val small = android.graphics.Bitmap.createBitmap(SMALL, 8, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(small).drawRect(0f, 0f, SMALL.toFloat(), 8f, android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, SMALL.toFloat(), 0f, colours, at, android.graphics.Shader.TileMode.CLAMP)
        })
        return Positive({ r -> android.graphics.LinearGradient(r.left, 0f, r.right, 0f, colours, at, android.graphics.Shader.TileMode.CLAMP) }, small)
    }

    private val BlackSlide: Positive by lazy {
        val small = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        Positive({ r -> android.graphics.LinearGradient(r.left, 0f, r.right, 0f, android.graphics.Color.BLACK, android.graphics.Color.BLACK, android.graphics.Shader.TileMode.CLAMP) }, small)
    }

    /**
     * A new look coming up over the old one (EditorScreen's photo): a RenderEffect for the new
     * print's layer, [t] 0..1. The same developer front as [frame], the new print's dark tones a
     * little ahead, a warm edge along it; where it has not reached, the layer is transparent and the
     * old print under it shows. At t = 1 the layer is the new print exactly. Null below API 33 or
     * where the shader will not compile (the caller cross-fades instead).
     */
    fun swapEffect(size: Size, t: Float, seed: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (Build.VERSION.SDK_INT < 33 || cpuOnly || size.width < 1f) return null
        val sh = Swap.shader ?: return null
        sh.setFloatUniform("uSize", size.width, size.height)
        sh.setFloatUniform("uT", t)
        sh.setFloatUniform("uSeed", seed)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "content").asComposeRenderEffect()
    }

    @RequiresApi(33)
    private object Swap {
        val shader: RuntimeShader? by lazy {
            runCatching { RuntimeShader(AGSL) }
                .onFailure { android.util.Log.w("FilmDevelop", "swap shader unavailable, cross-fade instead", it) }.getOrNull()
        }
        private const val AGSL = """
uniform shader content;
uniform float2 uSize;
uniform float uT;
uniform float uSeed;

float hash(float2 q) { return fract(sin(q.x * 127.1 + q.y * 311.7) * 43758.547); }
float vnoise(float2 q) {
    float2 i = floor(q);
    float2 f = fract(q);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), f.x), f.y);
}
float fbm(float2 q) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * vnoise(q); q *= 2.03; a *= 0.5; }
    return v;
}

half4 main(float2 p) {
    half4 c = content.eval(p);
    if (c.a <= 0.0) return c;
    float3 rgb = float3(c.rgb) / float(c.a);
    float2 uv = p / uSize;
    float l = dot(rgb, float3(0.2126, 0.7152, 0.0722));
    float n = fbm(float2(uv.x * 3.2 + uSeed * 7.0, uv.y * 2.2 + uSeed * 7.0 + uT * 0.6)) * 0.55 + (l - 0.5) * 0.22;
    float k = smoothstep(n - 0.10, n + 0.10, uT * 1.55 - 0.32);
    k = mix(k, 1.0, smoothstep(0.97, 1.0, uT));
    rgb = clamp(rgb + float3(1.0, 0.72, 0.45) * k * (1.0 - k) * 0.35, 0.0, 1.0);
    float a = float(c.a) * k;
    return half4(half3(rgb * a), half(a));
}
"""
    }

    /** True draws the CPU twin even on a hardware canvas: the JVM screenshot tests set it. */
    @Volatile var cpuOnly = false

    // --- the CPU twin -------------------------------------------------------------------------

    /** A frame's last CPU render, kept until its time or size changes (frame and rebate each have one). */
    private class Twin(val bitmap: android.graphics.Bitmap, val px: IntArray, val src: IntArray, var t: Float = -1f)
    private val twins = object : LinkedHashMap<Any, Twin>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Twin>?) = size > 4
    }
    private val pix = FloatArray(3)

    private fun DrawScope.cpu(r: Rect, p: Float, small: android.graphics.Bitmap, seed: Float, alpha: Float) {
        val w = min(SMALL, r.width.roundToInt().coerceAtLeast(1))
        val h = (w * r.height / r.width).roundToInt().coerceIn(1, 256)
        val key = Triple(small, w * 1000 + h, seed)
        val tw = twins.getOrPut(key) {
            Twin(android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888), IntArray(w * h),
                IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) })
        }
        if (tw.t != p) {
            val src = tw.src
            for (y in 0 until h) for (x in 0 until w) {
                val u = (x + 0.5f) / w
                val v = (y + 0.5f) / h
                val c = src[(v * small.height).toInt().coerceIn(0, small.height - 1) * small.width + (u * small.width).toInt().coerceIn(0, small.width - 1)]
                pixel(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, u, v, p, seed, pix)
                tw.px[y * w + x] = (0xFF shl 24) or ((pix[0] * 255f).roundToInt() shl 16) or ((pix[1] * 255f).roundToInt() shl 8) or (pix[2] * 255f).roundToInt()
            }
            tw.bitmap.setPixels(tw.px, 0, w, 0, 0, w, h)
            tw.t = p
        }
        clipRect(r.left, r.top, r.right, r.bottom) {
            drawImage(tw.bitmap.asImageBitmap(), srcSize = IntSize(w, h), dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()), alpha = alpha, filterQuality = FilterQuality.Low)
        }
    }

    // --- the shader ---------------------------------------------------------------------------

    @RequiresApi(33)
    private object Gpu {
        private val pool = HashMap<Float, RuntimeShader?>()

        /**
         * The shader for one caller (keyed by its seed: a frame and its rebate draw in the same pass,
         * so each needs its own uniforms), or null if this GPU will not compile it (then the CPU twin).
         */
        fun shader(seed: Float): RuntimeShader? = pool.getOrPut(seed) {
            runCatching { RuntimeShader(AGSL) }
                .onFailure { android.util.Log.w("FilmDevelop", "shader unavailable, CPU twin instead", it) }.getOrNull()
        }

        fun set(s: RuntimeShader, r: Rect, p: Float, seed: Float, slide: android.graphics.Shader) {
            s.setInputShader("img", slide)
            s.setFloatUniform("uRect", r.left, r.top, r.width, r.height)
            s.setFloatUniform("uT", p)
            s.setFloatUniform("uSeed", seed)
        }

        private const val AGSL = """
uniform shader img;
uniform float4 uRect;
uniform float uT;
uniform float uSeed;

float hash(float2 q) { return fract(sin(q.x * 127.1 + q.y * 311.7) * 43758.547); }
float vnoise(float2 q) {
    float2 i = floor(q);
    float2 f = fract(q);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(float2 q) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * vnoise(q); q *= 2.03; a *= 0.5; }
    return v;
}

half4 main(float2 p) {
    float2 uv = (p - uRect.xy) / uRect.zw;
    float3 pos = float3(img.eval(p).rgb);
    float t = uT;
    // The colour negative on a light box: inverted, under the orange mask.
    float3 neg = (1.0 - pos) * float3(0.96, 0.60, 0.36) + float3(0.03, 0.012, 0.0);
    // The developer front: uneven, the print's dark tones a little ahead.
    float l = dot(pos, float3(0.2126, 0.7152, 0.0722));
    float n = fbm(float2(uv.x * 3.2 + uSeed * 7.0, uv.y * 2.2 + uSeed * 7.0 + t * 0.6)) * 0.55 + (l - 0.5) * 0.22;
    float k = smoothstep(n - 0.10, n + 0.10, t * 1.55 - 0.32);
    float wip = 1.0 - k;
    float3 col = mix(neg, pos, k);
    col += (hash(floor(p * 0.75) + uSeed * 13.0 + floor(t * 30.0)) - 0.5) * 0.06 * wip;   // grain while it develops
    col += float3(1.0, 0.72, 0.45) * k * wip * 0.55;                                      // a warm edge along the front
    col = mix(col, pos, smoothstep(0.97, 1.0, t));
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""
    }
}
