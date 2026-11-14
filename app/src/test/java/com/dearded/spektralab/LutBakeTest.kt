package com.dearded.spektralab

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Synthetic external-look contracts. Co-created with GPT (OpenAI), 2026-11-13. */
class LutBakeTest {
    @get:Rule val temp = TemporaryFolder()

    // Compose the established RAW comparison's XYZ/Bradford reference in
    // Double, without reading production's composed Float matrices.
    private val toSrgb = product(arrayOf(
        doubleArrayOf(3.2404542, -1.5371385, -.4985314),
        doubleArrayOf(-.9692660, 1.8760108, .0415560),
        doubleArrayOf(.0556434, -.2040259, 1.0572252)), product(arrayOf(
        doubleArrayOf(.9555766, -.0230393, .0631636),
        doubleArrayOf(-.0282895, 1.0099416, .0210077),
        doubleArrayOf(.0122982, -.0204830, 1.3299098)), arrayOf(
        doubleArrayOf(.7976749, .1351917, .0313534),
        doubleArrayOf(.2880402, .7118741, .0000857),
        doubleArrayOf(0.0, 0.0, .8252100))))
    private val toPro = inverse(toSrgb)
    private fun product(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { r -> DoubleArray(3) { c -> (0..2).sumOf { a[r][it] * b[it][c] } } }
    private fun inverse(m: Array<DoubleArray>): Array<DoubleArray> {
        val cofactors = Array(3) { r -> DoubleArray(3) { c ->
            m[(r + 1) % 3][(c + 1) % 3] * m[(r + 2) % 3][(c + 2) % 3] -
                m[(r + 1) % 3][(c + 2) % 3] * m[(r + 2) % 3][(c + 1) % 3]
        } }
        val determinant = (0..2).sumOf { m[0][it] * cofactors[0][it] }
        return Array(3) { r -> DoubleArray(3) { c -> cofactors[c][r] / determinant } }
    }
    private fun matrix(m: Array<DoubleArray>, x: DoubleArray) = DoubleArray(3) { k -> (0..2).sumOf { m[k][it] * x[it] } }
    private fun clamp(x: Double) = x.coerceIn(0.0, 1.0)
    private fun decode(x: Double) = if (x < .03125) x / 16 else x.pow(1.8)
    private fun encode(x: Double) = if (x < .001953125) x * 16 else x.pow(1 / 1.8)
    private fun srgbIn(x: Double) = if (x <= .04045) x / 12.92 else ((x + .055) / 1.055).pow(2.4)
    private fun srgbOut(x: Double) = if (x <= .0031308) x * 12.92 else 1.055 * x.pow(1 / 2.4) - .055

    // Sort barycentric coordinates instead of copying production's six branches.
    private fun sample(table: Lut3, input: DoubleArray): DoubleArray {
        val p = DoubleArray(3) { clamp(input[it]) * (table.size - 1) }
        val base = IntArray(3) { min(p[it].toInt(), table.size - 2) }
        val f = DoubleArray(3) { p[it] - base[it] }
        val order = (0..2).sortedByDescending { f[it] }
        val weights = doubleArrayOf(1 - f[order[0]], f[order[0]] - f[order[1]], f[order[1]] - f[order[2]], f[order[2]])
        val out = DoubleArray(3)
        for (vertex in 0..3) {
            val at = ((base[2] * table.size + base[1]) * table.size + base[0]) * 3
            for (k in 0..2) out[k] += weights[vertex] * table.data[at + k]
            if (vertex < 3) base[order[vertex]]++
        }
        return out
    }

    private fun reference(source: Lut3, input: DoubleArray): DoubleArray {
        val srgb = matrix(toSrgb, DoubleArray(3) { decode(input[it]) })
        val looked = sample(source, DoubleArray(3) { srgbOut(clamp(srgb[it])) })
        val pro = matrix(toPro, DoubleArray(3) { srgbIn(clamp(looked[it])) })
        return DoubleArray(3) { encode(clamp(pro[it])) }
    }

    private fun look(side: Int): Lut3 {
        val table = Lut3.identity(side)
        for (i in table.data.indices step 3) {
            val r = table.data[i].toDouble(); val g = table.data[i + 1].toDouble(); val b = table.data[i + 2].toDouble()
            // Public synthetic cross-channel warm look, not a third-party preset.
            table.data[i] = clamp(.88 * r + .07 * g + .018).pow(.93).toFloat()
            table.data[i + 1] = clamp(.97 * g + .025 * b + .008).pow(1.04).toFloat()
            table.data[i + 2] = clamp(.90 * b + .045 * r).pow(1.08).toFloat()
        }
        return table
    }

    // Frozen alpha-6 algorithm, for a measured before/after comparison only.
    private fun legacy(source: Lut3): Lut3 {
        val base = if (source.size > 65) source.resampled(65) else source
        val side = base.size
        val table = Lut3(side, FloatArray(side * side * side * 3))
        val forward = floatArrayOf(2.0340758f, -.7273341f, -.3067418f,
            -.2288131f, 1.2317301f, -.0029169f, -.0085698f, -.1532866f, 1.1618564f)
        val inverse = floatArrayOf(.5293459f, .3300728f, .1405813f,
            .0983743f, .8734610f, .0281646f, .0168832f, .1176725f, .8654443f)
        fun mul(m: FloatArray, x: FloatArray, out: FloatArray) {
            for (k in 0..2) out[k] = m[k * 3] * x[0] + m[k * 3 + 1] * x[1] + m[k * 3 + 2] * x[2]
        }
        val px = FloatArray(3); val lin = FloatArray(3)
        val step = 1f / (side - 1)
        var i = 0
        for (b in 0 until side) for (g in 0 until side) for (r in 0 until side) {
            lin[0] = (r * step).pow(1.8f); lin[1] = (g * step).pow(1.8f); lin[2] = (b * step).pow(1.8f)
            mul(forward, lin, px)
            for (k in 0..2) {
                val x = px[k].coerceIn(0f, 1f)
                px[k] = if (x <= .0031308f) 12.92f * x else 1.055f * x.pow(1 / 2.4f) - .055f
            }
            base.sample(px)
            for (k in 0..2) {
                val x = px[k].coerceIn(0f, 1f)
                lin[k] = if (x <= .04045f) x / 12.92f else ((x + .055f) / 1.055f).pow(2.4f)
            }
            mul(inverse, lin, px)
            for (k in 0..2) table.data[i++] = px[k].coerceIn(0f, 1f).pow(1 / 1.8f)
        }
        return table
    }

    @Test fun prophotoTablesRetainTheirExactOriginalSamples() {
        for (side in listOf(2, 17, 65)) {
            val original = look(side)
            assertSame(original, LutBake.forLayer2(original, SpkLut.SPACE_PROPHOTO))
        }
        val large = look(129)
        assertArrayEquals(large.resampled(65).data, LutBake.forLayer2(large, SpkLut.SPACE_PROPHOTO).data, 0f)
    }

    @Test fun smallSrgbTablesGetAFullResolutionComposedTransform() {
        val baked = LutBake.forLayer2(Lut3.identity(2), SpkLut.SPACE_SRGB)
        assertEquals(65, baked.size)
        // A coarse 2^3 bake is not an identity even for in-gamut greys.
        val grey = floatArrayOf(.3857f, .3857f, .3857f)
        baked.sample(grey)
        for (x in grey) assertEquals(.3857f, x, .00001f)
    }

    @Test fun toeAndGamutBoundaryVerticesMatchIndependentReference() {
        val source = look(17)
        val baked = LutBake.forLayer2(source, SpkLut.SPACE_SRGB)
        for (r in listOf(0, 1, 2, 3, 12, 32, 64)) for (g in listOf(0, 1, 2, 3, 25, 64)) for (b in listOf(0, 1, 2, 3, 45, 64)) {
            val x = doubleArrayOf(r / 64.0, g / 64.0, b / 64.0)
            val expected = reference(source, x)
            val actual = FloatArray(3) { x[it].toFloat() }.also(baked::sample)
            for (k in 0..2) assertEquals("vertex $r/$g/$b channel $k", expected[k], actual[k].toDouble(), .000002)
        }
        // Literal black-offset fixture exercises output toe, independent of a round trip.
        val constant = Lut3(2, FloatArray(24) { .01f })
        val black = LutBake.forLayer2(constant, SpkLut.SPACE_SRGB).data
        for (x in black) assertEquals(.01 / 12.92 * 16, x.toDouble(), .00000002)
    }

    @Test fun denseOffGridSamplesReduceCompositionError() {
        val csv = StringBuilder("source_side,group,pixels,old_mean_abs,new_mean_abs,old_max_abs,new_max_abs,old_srgb8_mean_codes,new_srgb8_mean_codes,old_srgb8_max_codes,new_srgb8_max_codes\n")
        for (side in listOf(2, 17, 33, 65, 129)) {
            val source = look(side)
            val old = legacy(source)
            val baked = LutBake.forLayer2(source, SpkLut.SPACE_SRGB)
            val records = ArrayList<Float>()
            for (group in listOf("cube", "shadows", "neutrals", "saturated")) {
                var oldSum = 0.0; var newSum = 0.0; var oldMax = 0.0; var newMax = 0.0
                var oldDisplaySum = 0L; var newDisplaySum = 0L; var oldDisplayMax = 0; var newDisplayMax = 0
                val count = 4096
                for (n in 0 until count) {
                    val u = doubleArrayOf(((n * 1567 + 73) % 4093) / 4093.0,
                        ((n * 2411 + 177) % 4091) / 4091.0, ((n * 3541 + 341) % 4079) / 4079.0)
                    val x = when (group) {
                        "shadows" -> DoubleArray(3) { u[it] * .0625 }
                        "neutrals" -> DoubleArray(3) { u[0] }
                        "saturated" -> u.also { it[n % 3] = if (n % 2 == 0) 0.0 else 1.0 }
                        else -> u
                    }
                    // Native input is Float, so reference exactly that quantization.
                    val input = FloatArray(3) { x[it].toFloat() }
                    val expected = reference(source, DoubleArray(3) { input[it].toDouble() })
                    val before = input.copyOf().also(old::sample)
                    val after = input.copyOf().also(baked::sample)
                    fun display(x: DoubleArray): IntArray {
                        val rgb = matrix(toSrgb, DoubleArray(3) { decode(x[it]) })
                        return IntArray(3) { (srgbOut(clamp(rgb[it])) * 255).roundToInt() }
                    }
                    val displayReference = display(expected)
                    val displayBefore = display(DoubleArray(3) { before[it].toDouble() })
                    val displayAfter = display(DoubleArray(3) { after[it].toDouble() })
                    for (k in 0..2) {
                        val a = abs(before[k] - expected[k]); val b = abs(after[k] - expected[k])
                        oldSum += a; newSum += b; oldMax = max(oldMax, a); newMax = max(newMax, b)
                        val a8 = abs(displayBefore[k] - displayReference[k]); val b8 = abs(displayAfter[k] - displayReference[k])
                        oldDisplaySum += a8; newDisplaySum += b8; oldDisplayMax = max(oldDisplayMax, a8); newDisplayMax = max(newDisplayMax, b8)
                    }
                    if (side == 17) { for (xv in input) records += xv; for (xv in after) records += xv }
                }
                csv.append("$side,$group,$count,${oldSum / (count * 3)},${newSum / (count * 3)},$oldMax,$newMax,${oldDisplaySum.toDouble() / (count * 3)},${newDisplaySum.toDouble() / (count * 3)},$oldDisplayMax,$newDisplayMax\n")
                assertTrue("mean regression: side=$side group=$group old=$oldSum new=$newSum", newSum <= oldSum + .0002)
                if (side <= 33) assertTrue("coarse composition not improved", newSum < oldSum * .8)
            }
            System.getenv("SPK_LUT_EVIDENCE_DIR")?.let { path ->
                val dir = File(path).also { it.mkdirs() }
                if (side == 17) {
                    val buf = ByteBuffer.allocate(8 + baked.data.size * 4 + records.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    buf.putInt(baked.size).putInt(records.size / 6)
                    for (x in baked.data) buf.putFloat(x)
                    for (x in records) buf.putFloat(x)
                    File(dir, "native-parity.bin").writeBytes(buf.array())
                }
            }
        }
        println(csv)
        System.getenv("SPK_LUT_EVIDENCE_DIR")?.let { File(it, "quality.csv").writeText(csv.toString()) }
    }

    @Test fun largeSourceIsSampledDirectlyAtTheFinalGrid() {
        // Narrow alternating bands disappear if 129^3 is first shrunk to 65^3.
        val source = Lut3.identity(129)
        for (b in 0..128) for (g in 0..128) for (r in 0..128) {
            val at = ((b * 129 + g) * 129 + r) * 3
            for (k in 0..2) source.data[at + k] = .5f + .15f * (r % 2)
        }
        val baked = LutBake.forLayer2(source, SpkLut.SPACE_SRGB)
        for (i in 1..63) {
            val x = doubleArrayOf(i / 64.0, (64 - i) / 64.0, 17 / 64.0)
            val expected = reference(source, x)
            val actual = FloatArray(3) { x[it].toFloat() }.also(baked::sample)
            // A 129-point alternating band amplifies Float input coordinates;
            // keep this stress bound within one 16-bit working-space code.
            for (k in 0..2) assertEquals(expected[k], actual[k].toDouble(), 1.0 / 65535)
        }
    }

    @Test fun optionalCloudBakeCostRecord() {
        val path = System.getenv("SPK_LUT_EVIDENCE_DIR") ?: return
        val csv = StringBuilder("source_side,algorithm,best_of_3_ms,table_bytes\n")
        for (side in listOf(2, 33, 129)) {
            val source = look(side)
            for (old in listOf(true, false)) {
                var best = Long.MAX_VALUE
                var bytes = 0
                repeat(4) { run ->
                    val start = System.nanoTime()
                    val table = if (old) legacy(source) else LutBake.forLayer2(source, SpkLut.SPACE_SRGB)
                    val elapsed = System.nanoTime() - start
                    bytes = table.data.size * 4
                    assertTrue(table.data.all { it.isFinite() })
                    if (run > 0) best = min(best, elapsed)
                }
                csv.append("$side,${if (old) "legacy" else "piecewise65"},${best / 1e6},$bytes\n")
            }
        }
        File(path).mkdirs()
        File(path, "bake-cost.csv").writeText(csv.toString())
    }

    @Test fun newCachesRoundTripAndLegacyCachesRemainFrozen() {
        val dir = temp.newFolder("luts")
        val id = "lut_new"
        val source = look(2)
        val baked = LutStore(dir).save(id, source, SpkLut.SPACE_SRGB)
        assertArrayEquals(baked.data, LutStore(dir).load(id)!!.data, 0f)
        assertEquals(0x334B5053, ByteBuffer.wrap(File(dir, "$id.lut").readBytes()).order(ByteOrder.LITTLE_ENDIAN).int)
        val original = LutStore(dir).original(id)!!
        assertEquals(SpkLut.SPACE_SRGB, original.second)
        val restored = CubeFile.parse(String(original.first, Charsets.US_ASCII)).threeD!!
        assertEquals(source.size, restored.size)
        assertArrayEquals(source.data, restored.data, .000001f)

        val frozen = legacy(source)
        val buf = ByteBuffer.allocate(12 + frozen.data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x4C4B5053).putInt(frozen.size).putInt(0)
        for (x in frozen.data) buf.putFloat(x)
        File(dir, "lut_legacy.lut").writeBytes(buf.array())
        File(dir, "lut_legacy.cube").writeBytes(source.toCube())
        assertArrayEquals(frozen.data, LutStore(dir).load("lut_legacy")!!.data, 0f)
        assertEquals(SpkLut.SPACE_SRGB, LutStore(dir).original("lut_legacy")!!.second)
        buf.putInt(0, 0x324B5053)
        File(dir, "lut_piecewise.lut").writeBytes(buf.array())
        File(dir, "lut_piecewise.cube").writeBytes(source.toCube())
        assertArrayEquals(frozen.data, LutStore(dir).load("lut_piecewise")!!.data, 0f)
        assertEquals(SpkLut.SPACE_SRGB, LutStore(dir).original("lut_piecewise")!!.second)
    }
}
