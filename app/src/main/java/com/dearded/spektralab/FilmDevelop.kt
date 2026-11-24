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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * A frame of reversal film (E-6) being developed, drawn: the launch intro's frame and the save
 * animation both use it (docs/FRONTEND_ITERATION_2026-11-24.md, B).
 *
 * Chemistry does not sweep a front down the film: every point changes at once, along smooth
 * exponential curves, and a point's tone decides when it starts and how fast it goes. So each
 * pixel here runs its own clock:
 *
 *   undeveloped        milky cream, no image
 *   first developer    a silver negative builds up in the cream, the most exposed parts (the
 *                      slide's highlights) first
 *   colour developer   what is left goes dark too, shadows first: the film nearly black, never a
 *                      cut to black (it stays a dim grey-brown)
 *   bleach and fix     the slide clears out of the dark through a dim veil, thin (bright) parts
 *                      first, its colour coming up as it clears
 *
 * Uneven development (agitation, bromide drag) is two low-frequency fields that shift each
 * point's clock a little; they never threshold a mask, so no edge or blob can form, and they are
 * gone by the end. Grain is the film's own, fixed to it. At p = 1 the frame is exactly the slide.
 *
 * API 33+: one RuntimeShader with the slide as a child shader. Elsewhere (API 29-32, and software
 * canvases such as the JVM screenshot tests): the same formula on the CPU at a low resolution,
 * drawn scaled, handing over to the sharp slide as it finishes. Keep the two in step, and the
 * preview's (preview/ui-polish.html developPixel).
 */
internal object FilmDevelop {
    /** The undeveloped stock on a light box: milky, warm grey. */
    private val Cream = floatArrayOf(0.70f, 0.68f, 0.60f)
    /** The deepest it goes in the colour developer: dim, warm, not black. */
    private val Dark = floatArrayOf(0.045f, 0.042f, 0.040f)
    /** The bleach's veil. */
    private val Veil = floatArrayOf(0.05f, 0.048f, 0.045f)

    /**
     * The slide a frame develops into: [shader] draws it into the frame's rect (an image or a
     * gradient), [small] is a copy at most [SMALL] px wide for the CPU twin.
     */
    class Positive(val shader: (Rect) -> android.graphics.Shader, val small: android.graphics.Bitmap)

    /** The CPU twin's width. */
    const val SMALL = 160

    // --- the model, shared by the shader and the CPU twin -------------------------------------

    private fun ramp(x: Float, a: Float, b: Float, k: Float): Float {
        val u = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return (1f - exp(-k * u)) / (1f - exp(-k))
    }

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

    /** One pixel of the CPU twin, as the shader's main(): [r],[g],[b] the slide there, [u],[v] 0..1 in the frame. */
    private fun pixel(r: Float, g: Float, b: Float, u: Float, v: Float, t: Float, seed: Float, out: FloatArray) {
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val n = vnoise(u * 2.5f + seed * 7f + t * 0.6f, v * 1.6f + seed * 7f) - 0.5f
        val s = vnoise(u * 7f + seed * 3f, v * 0.9f + seed * 3f) - 0.5f
        val ti = (t + (0.06f * n + 0.02f * s) * (1f - t) * smooth(0f, 0.08f, t)).coerceIn(0f, 1f)
        val a = ramp(ti, 0.02f + 0.14f * (1f - l), 0.42f, 3f)
        val dk = ramp(ti, 0.28f + 0.08f * l, 0.62f, 2.5f)
        val c = ramp(ti, 0.50f + 0.12f * (1f - l), 0.95f, 3.2f)
        val veil = 1f - ramp(ti, 0.62f, 0.98f, 3f)
        val sat = 0.35f + 0.65f * ramp(ti, 0.55f, 1f, 2.5f)
        val land = smooth(0.97f, 1f, t)
        val sr = r * (1f - 0.5f * veil) + Veil[0] * veil
        val sg = g * (1f - 0.5f * veil) + Veil[1] * veil
        val sb = b * (1f - 0.5f * veil) + Veil[2] * veil
        val sl = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb
        out[0] = channel(0, l, a, dk, c, sl + (sr - sl) * sat, r, land)
        out[1] = channel(1, l, a, dk, c, sl + (sg - sl) * sat, g, land)
        out[2] = channel(2, l, a, dk, c, sl + (sb - sl) * sat, b, land)
    }

    private fun channel(i: Int, l: Float, a: Float, dk: Float, c: Float, slide: Float, src: Float, land: Float): Float {
        var col = Cream[i] * (1f - 0.62f * a * l)
        col += (Dark[i] - col) * dk
        col += (slide - col) * c
        return (col + (src - col) * land).coerceIn(0f, 1f)
    }

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

    /** The pre-exposed edge print: dark in the first developer, lost in the dark, coming up light as it clears. */
    fun edgeInk(p: Float): Color {
        val light = 0.85f * smooth(0.74f, 0.96f, p)
        val dark = 0.7f * ramp(p, 0.04f, 0.4f, 3f) * (1f - ramp(p, 0.3f, 0.6f, 2.5f))
        return if (light >= dark) Color(0xFFECCE96).copy(alpha = light) else Color(0xFF342C22).copy(alpha = dark)
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
float ramp(float x, float a, float b, float k) {
    float u = clamp((x - a) / (b - a), 0.0, 1.0);
    return (1.0 - exp(-k * u)) / (1.0 - exp(-k));
}

half4 main(float2 p) {
    float2 uv = (p - uRect.xy) / uRect.zw;
    float3 pos = float3(img.eval(p).rgb);
    float l = dot(pos, float3(0.2126, 0.7152, 0.0722));
    float t = uT;
    // Uneven development: two low-frequency fields shift each point's clock, never a mask.
    float n = vnoise(float2(uv.x * 2.5 + uSeed * 7.0 + t * 0.6, uv.y * 1.6 + uSeed * 7.0)) - 0.5;
    float s = vnoise(float2(uv.x * 7.0 + uSeed * 3.0, uv.y * 0.9 + uSeed * 3.0)) - 0.5;
    float ti = clamp(t + (0.06 * n + 0.02 * s) * (1.0 - t) * smoothstep(0.0, 0.08, t), 0.0, 1.0);
    // First developer: the negative, most exposed first. Colour developer: the rest goes dark,
    // shadows first. Bleach and fix: the slide clears, thin parts first, through a veil.
    float a = ramp(ti, 0.02 + 0.14 * (1.0 - l), 0.42, 3.0);
    float dk = ramp(ti, 0.28 + 0.08 * l, 0.62, 2.5);
    float c = ramp(ti, 0.50 + 0.12 * (1.0 - l), 0.95, 3.2);
    float veil = 1.0 - ramp(ti, 0.62, 0.98, 3.0);
    float sat = 0.35 + 0.65 * ramp(ti, 0.55, 1.0, 2.5);
    float3 slide = pos * (1.0 - 0.5 * veil) + float3(0.05, 0.048, 0.045) * veil;
    float sl = dot(slide, float3(0.2126, 0.7152, 0.0722));
    slide = mix(float3(sl), slide, sat);
    float3 col = float3(0.70, 0.68, 0.60) * (1.0 - 0.62 * a * l);
    col = mix(col, float3(0.045, 0.042, 0.040), dk);
    col = mix(col, slide, c);
    // Grain: the film's own, fixed to it, finer once it has cleared.
    float g = hash(floor(p * 0.75) + uSeed * 13.0) - 0.5;
    col += g * (0.05 * (1.0 - c) + 0.012) * (1.0 - smoothstep(0.97, 1.0, t));
    col = mix(col, pos, smoothstep(0.97, 1.0, t));
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""
    }
}
