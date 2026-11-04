package com.dearded.spektralab

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A 3D colour table: [size]^3 RGB entries, red changing fastest, then green,
 * blue slowest (the .cube and HaldCLUT order, and what the native Layer 2
 * entry reads). Inputs and outputs are 0..1.
 */
class Lut3(val size: Int, val data: FloatArray) {
    init {
        require(size in 2..MAX_SIDE) { "LUT side $size" }
        require(data.size == size * size * size * 3) { "LUT has ${data.size} values for side $size" }
    }

    /** [rgb] through the table, tetrahedral interpolation, in place. */
    fun sample(rgb: FloatArray) {
        val n1 = size - 1
        val fr = clamp01(rgb[0]) * n1; val fg = clamp01(rgb[1]) * n1; val fb = clamp01(rgb[2]) * n1
        val r0 = min(fr.toInt(), n1 - 1); val g0 = min(fg.toInt(), n1 - 1); val b0 = min(fb.toInt(), n1 - 1)
        val dr = fr - r0; val dg = fg - g0; val db = fb - b0
        fun at(r: Int, g: Int, b: Int) = ((b * size + g) * size + r) * 3
        val c000 = at(r0, g0, b0); val c111 = at(r0 + 1, g0 + 1, b0 + 1)
        // The cube's six tetrahedra, chosen by the order of the fractions.
        val (c1, c2, w0, w1, w2, w3) = when {
            dr >= dg && dg >= db -> Tet(at(r0 + 1, g0, b0), at(r0 + 1, g0 + 1, b0), 1 - dr, dr - dg, dg - db, db)
            dr >= db && db >= dg -> Tet(at(r0 + 1, g0, b0), at(r0 + 1, g0, b0 + 1), 1 - dr, dr - db, db - dg, dg)
            db >= dr && dr >= dg -> Tet(at(r0, g0, b0 + 1), at(r0 + 1, g0, b0 + 1), 1 - db, db - dr, dr - dg, dg)
            dg >= dr && dr >= db -> Tet(at(r0, g0 + 1, b0), at(r0 + 1, g0 + 1, b0), 1 - dg, dg - dr, dr - db, db)
            dg >= db && db >= dr -> Tet(at(r0, g0 + 1, b0), at(r0, g0 + 1, b0 + 1), 1 - dg, dg - db, db - dr, dr)
            else -> Tet(at(r0, g0, b0 + 1), at(r0, g0 + 1, b0 + 1), 1 - db, db - dg, dg - dr, dr)
        }
        for (k in 0 until 3)
            rgb[k] = w0 * data[c000 + k] + w1 * data[c1 + k] + w2 * data[c2 + k] + w3 * data[c111 + k]
    }

    /** This table sampled on a [side]^3 grid (a no-op when the side is already that). */
    fun resampled(side: Int): Lut3 {
        if (side == size) return this
        val out = FloatArray(side * side * side * 3)
        val px = FloatArray(3)
        val step = 1f / (side - 1)
        var i = 0
        for (b in 0 until side) for (g in 0 until side) for (r in 0 until side) {
            px[0] = r * step; px[1] = g * step; px[2] = b * step
            sample(px)
            out[i++] = px[0]; out[i++] = px[1]; out[i++] = px[2]
        }
        return Lut3(side, out)
    }

    private data class Tet(val c1: Int, val c2: Int, val w0: Float, val w1: Float, val w2: Float, val w3: Float)

    companion object {
        /** The largest table the native side takes; bigger ones are resampled to it. */
        const val NATIVE_SIDE = 65
        /** The largest table read at all (129^3 floats is 26 MB). */
        const val MAX_SIDE = 129
        /** A 1D-only .cube is expanded to this. */
        const val EXPAND_SIDE = 33

        fun identity(side: Int): Lut3 {
            val out = FloatArray(side * side * side * 3)
            val step = 1f / (side - 1)
            var i = 0
            for (b in 0 until side) for (g in 0 until side) for (r in 0 until side) {
                out[i++] = r * step; out[i++] = g * step; out[i++] = b * step
            }
            return Lut3(side, out)
        }
    }
}

/** A parsed .cube: an optional 1D curve (a shaper when a 3D table follows), an optional 3D table, and their domains. */
class CubeFile(
    val title: String,
    val oneD: FloatArray?, val oneDSize: Int, val oneDMin: FloatArray, val oneDMax: FloatArray,
    val threeD: Lut3?, val threeDMin: FloatArray, val threeDMax: FloatArray,
) {
    /**
     * The whole file as one table with a 0..1 domain, at most [Lut3.NATIVE_SIDE]
     * a side. A shaper and a non-unit domain are folded in by resampling.
     */
    fun toLut(report: ImportReport): Lut3 {
        val plainDomain = threeDMin.all { it == 0f } && threeDMax.all { it == 1f }
        if (threeD != null && oneD == null && plainDomain && threeD.size <= Lut3.NATIVE_SIDE) return threeD
        val side = when {
            threeD == null -> Lut3.EXPAND_SIDE.also { report.mapped("1D LUT", "展开成 ${it}³ 的 3D 表") }
            threeD.size > Lut3.NATIVE_SIDE -> Lut3.NATIVE_SIDE.also { report.approximated("3D LUT", "边长 ${threeD.size} 重采样到 $it") }
            else -> threeD.size
        }
        if (oneD != null && threeD != null) report.mapped("1D 整形曲线", "并入 3D 表")
        val out = FloatArray(side * side * side * 3)
        val px = FloatArray(3)
        val step = 1f / (side - 1)
        var i = 0
        for (b in 0 until side) for (g in 0 until side) for (r in 0 until side) {
            px[0] = r * step; px[1] = g * step; px[2] = b * step
            apply(px)
            out[i++] = px[0]; out[i++] = px[1]; out[i++] = px[2]
        }
        return Lut3(side, out)
    }

    /**
     * One colour through the file as written: the 1D curve, then the 3D table.
     * Each table's domain says which input values its first and last entries
     * stand for; inputs outside it clamp to the edge entries.
     */
    private fun apply(px: FloatArray) {
        if (oneD != null) {
            val n1 = oneDSize - 1
            for (k in 0 until 3) {
                val t = clamp01((px[k] - oneDMin[k]) / (oneDMax[k] - oneDMin[k])) * n1
                val i0 = min(t.toInt(), n1 - 1)
                val f = t - i0
                px[k] = oneD[i0 * 3 + k] * (1 - f) + oneD[(i0 + 1) * 3 + k] * f
            }
        }
        if (threeD != null) {
            for (k in 0 until 3) px[k] = (px[k] - threeDMin[k]) / (threeDMax[k] - threeDMin[k])
            threeD.sample(px)
        }
    }

    companion object {
        const val MAX_1D = 65536

        /**
         * Reads an Adobe or Resolve .cube. Throws [ImportException] on anything
         * malformed; never allocates more than the declared, bounded sizes.
         */
        fun parse(text: String): CubeFile {
            var title = ""
            var size1 = 0; var size3 = 0
            val min1 = floatArrayOf(0f, 0f, 0f); val max1 = floatArrayOf(1f, 1f, 1f)
            val min3 = floatArrayOf(0f, 0f, 0f); val max3 = floatArrayOf(1f, 1f, 1f)
            var oneD: FloatArray? = null; var threeD: FloatArray? = null
            var filled1 = 0; var filled3 = 0
            var line = 0
            fun fail(why: String): Nothing = throw ImportException(".cube 第 $line 行：$why")
            fun triple(parts: List<String>, from: Int): FloatArray {
                if (parts.size < from + 3) fail("需要三个数")
                return FloatArray(3) { parts[from + it].toFloatOrNull()?.takeIf(Float::isFinite) ?: fail("“${parts[from + it]}”不是数") }
            }
            fun range(parts: List<String>): Pair<Float, Float> {
                if (parts.size < 3) fail("需要两个数")
                fun num(s: String) = s.toFloatOrNull()?.takeIf(Float::isFinite) ?: fail("“${s.take(20)}”不是数")
                return num(parts[1]) to num(parts[2])
            }
            var domainMin: FloatArray? = null; var domainMax: FloatArray? = null
            var range1 = false; var range3 = false
            fun size(parts: List<String>, lo: Int, hi: Int): Int {
                val n = parts.getOrNull(1)?.toIntOrNull() ?: fail("缺少尺寸")
                if (n !in lo..hi) fail("尺寸 $n 不在 $lo…$hi")
                return n
            }
            for (raw in text.lineSequence()) {
                line++
                val s = raw.substringBefore('#').trim()
                if (s.isEmpty()) continue
                val first = s[0]
                if (first.isDigit() || first == '-' || first == '+' || first == '.') {
                    val parts = s.split(WS)
                    val v = triple(parts, 0)
                    // The 1D curve comes first when a file carries both.
                    when {
                        oneD != null && filled1 < size1 -> { v.copyInto(oneD, filled1 * 3); filled1++ }
                        threeD != null && filled3 < size3 * size3 * size3 -> { v.copyInto(threeD, filled3 * 3); filled3++ }
                        else -> fail("数据比声明的多")
                    }
                    continue
                }
                val parts = s.split(WS)
                when (parts[0].uppercase()) {
                    "TITLE" -> title = s.substringAfter(parts[0]).trim().trim('"').take(120)
                    "LUT_1D_SIZE" -> { if (oneD != null) fail("重复的 LUT_1D_SIZE"); size1 = size(parts, 2, MAX_1D); oneD = FloatArray(size1 * 3) }
                    "LUT_3D_SIZE" -> {
                        if (threeD != null) fail("重复的 LUT_3D_SIZE")
                        size3 = size(parts, 2, Lut3.MAX_SIDE); threeD = FloatArray(size3 * size3 * size3 * 3)
                    }
                    // Adobe's per-channel domain: an Adobe file has one table, so it goes to
                    // whichever table the file turns out to hold (resolved after the loop).
                    "DOMAIN_MIN" -> domainMin = triple(parts, 1)
                    "DOMAIN_MAX" -> domainMax = triple(parts, 1)
                    // Resolve's: one min and one max for all three channels.
                    "LUT_1D_INPUT_RANGE" -> { range(parts).let { (a, b) -> min1.fill(a); max1.fill(b) }; range1 = true }
                    "LUT_3D_INPUT_RANGE" -> { range(parts).let { (a, b) -> min3.fill(a); max3.fill(b) }; range3 = true }
                    // Video-range flags: the data is still read; the report says the range is assumed full.
                    "LUT_IN_VIDEO_RANGE", "LUT_OUT_VIDEO_RANGE" -> Unit
                    else -> fail("不认识的关键字 ${parts[0].take(40)}")
                }
            }
            if (oneD == null && threeD == null) throw ImportException(".cube 里没有 LUT_1D_SIZE 或 LUT_3D_SIZE")
            if (oneD != null && filled1 != size1) throw ImportException(".cube 的 1D 表只有 $filled1 / $size1 行")
            if (threeD != null && filled3 != size3 * size3 * size3) throw ImportException(".cube 的 3D 表只有 $filled3 / ${size3 * size3 * size3} 行")
            // DOMAIN_* belongs to the 3D table when there is one (a shaper then has its own
            // range or none), otherwise to the 1D curve; an explicit *_INPUT_RANGE wins.
            val domainFor1 = threeD == null
            domainMin?.let { if (domainFor1) { if (!range1) it.copyInto(min1) } else if (!range3) it.copyInto(min3) }
            domainMax?.let { if (domainFor1) { if (!range1) it.copyInto(max1) } else if (!range3) it.copyInto(max3) }
            for (k in 0 until 3) {
                if (max1[k] <= min1[k] || max3[k] <= min3[k]) throw ImportException(".cube 的定义域上限不大于下限")
            }
            return CubeFile(title, oneD, size1, min1, max1, threeD?.let { Lut3(size3, it) }, min3, max3)
        }

        private val WS = Regex("\\s+")
    }
}

object HaldClut {
    /** Largest side accepted (level 16), checked before any pixel is decoded. */
    const val MAX_EDGE = 4096

    /** The level of a [width] x [height] HaldCLUT, or null when it is not one (side must be L^3, L in 2..16). */
    fun level(width: Int, height: Int): Int? {
        if (width != height || width > MAX_EDGE) return null
        for (l in 2..16) if (l * l * l == width) return l
        return null
    }

    /**
     * The table in ARGB [pixels] (row-major, top row first). Red fastest:
     * pixel i holds the entry r + g N + b N^2, N = L^2.
     */
    fun toLut(pixels: IntArray, width: Int, height: Int, report: ImportReport): Lut3 {
        val l = level(width, height) ?: throw ImportException("图片 ${width}×$height 不是 HaldCLUT（边长须为 8、27、64…4096）")
        val n = l * l
        require(pixels.size == width * height)
        val out = FloatArray(n * n * n * 3)
        for (i in 0 until n * n * n) {
            val p = pixels[i]
            out[i * 3] = ((p shr 16) and 0xFF) / 255f
            out[i * 3 + 1] = ((p shr 8) and 0xFF) / 255f
            out[i * 3 + 2] = (p and 0xFF) / 255f
        }
        val lut = Lut3(n, out)
        if (n <= Lut3.NATIVE_SIDE) return lut
        report.approximated("HaldCLUT", "${l} 级（${n}³）重采样到 ${Lut3.NATIVE_SIDE}³")
        return lut.resampled(Lut3.NATIVE_SIDE)
    }
}

/**
 * Turns a table made for display colours into one on the values Layer 2 sees:
 * the engine's print, ProPhoto RGB encoded with gamma 1.8 (layer2.hpp's
 * mid-grey 0.3857 is 0.18^(1/1.8)). For an sRGB table each grid point goes
 * ProPhoto -> linear -> sRGB linear (clipped to 0..1) -> sRGB encoded ->
 * the table -> back. Colours outside sRGB are clipped going in.
 */
object LutBake {
    // ProPhoto (D50) <-> linear sRGB (D65), Bradford adaptation. Rows sum to 1.
    private val PRO_TO_SRGB = floatArrayOf(
        2.0340758f, -0.7273341f, -0.3067418f,
        -0.2288131f, 1.2317301f, -0.0029169f,
        -0.0085698f, -0.1532866f, 1.1618564f,
    )
    private val SRGB_TO_PRO = floatArrayOf(
        0.5293459f, 0.3300728f, 0.1405813f,
        0.0983743f, 0.8734610f, 0.0281646f,
        0.0168832f, 0.1176725f, 0.8654443f,
    )
    private const val PRO_GAMMA = 1.8f

    /** The table Layer 2 gets for [lut] made in [space] (SpkLut.SPACE_*), at most [Lut3.NATIVE_SIDE] a side. */
    fun forLayer2(lut: Lut3, space: String): Lut3 {
        val base = if (lut.size > Lut3.NATIVE_SIDE) lut.resampled(Lut3.NATIVE_SIDE) else lut
        if (space == SpkLut.SPACE_PROPHOTO) return base
        val side = base.size
        val out = FloatArray(side * side * side * 3)
        val px = FloatArray(3); val lin = FloatArray(3)
        val step = 1f / (side - 1)
        var i = 0
        for (b in 0 until side) for (g in 0 until side) for (r in 0 until side) {
            lin[0] = (r * step).pow(PRO_GAMMA); lin[1] = (g * step).pow(PRO_GAMMA); lin[2] = (b * step).pow(PRO_GAMMA)
            mul(PRO_TO_SRGB, lin, px)
            for (k in 0 until 3) px[k] = srgbEncode(clamp01(px[k]))
            base.sample(px)
            for (k in 0 until 3) lin[k] = srgbDecode(clamp01(px[k]))
            mul(SRGB_TO_PRO, lin, px)
            for (k in 0 until 3) out[i++] = clamp01(px[k]).pow(1 / PRO_GAMMA)
        }
        return Lut3(side, out)
    }

    private fun mul(m: FloatArray, v: FloatArray, out: FloatArray) {
        for (row in 0 until 3) out[row] = m[row * 3] * v[0] + m[row * 3 + 1] * v[1] + m[row * 3 + 2] * v[2]
    }

    private fun srgbEncode(x: Float) = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1 / 2.4f) - 0.055f
    private fun srgbDecode(x: Float) = if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
}

private fun clamp01(x: Float) = max(0f, min(1f, if (x.isNaN()) 0f else x))
