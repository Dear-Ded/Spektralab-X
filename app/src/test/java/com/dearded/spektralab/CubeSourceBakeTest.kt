package com.dearded.spektralab

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Analytic source-chain oracle, co-created with GPT (OpenAI). */
class CubeSourceBakeTest {
    @get:Rule val temp = TemporaryFolder()
    private val curve = Array(257) { i ->
        val x = i / 256.0
        floatArrayOf((2 * x * x - .1).toFloat(), (x * x * x + .1).toFloat(), (1.5 * sqrt(x)).toFloat())
    }
    private val low = doubleArrayOf(-.2, .05, 0.0)
    private val high = doubleArrayOf(1.2, 1.05, 2.0)
    private val forward = arrayOf(
        doubleArrayOf(2.0340758, -.7273341, -.3067418),
        doubleArrayOf(-.2288131, 1.2317301, -.0029169),
        doubleArrayOf(-.0085698, -.1532866, 1.1618564))
    private val inverse = arrayOf(
        doubleArrayOf(.5293459, .3300728, .1405813),
        doubleArrayOf(.0983743, .8734610, .0281646),
        doubleArrayOf(.0168832, .1176725, .8654443))
    private fun matrix(m: Array<DoubleArray>, x: DoubleArray) = DoubleArray(3) { r -> (0..2).sumOf { m[r][it] * x[it] } }
    private fun decode(x: Double) = if (x < .03125) x / 16 else x.pow(1.8)
    private fun encode(x: Double) = if (x < .001953125) x * 16 else x.pow(1 / 1.8)
    private fun srgbIn(x: Double) = if (x <= .04045) x / 12.92 else ((x + .055) / 1.055).pow(2.4)
    private fun srgbOut(x: Double) = if (x <= .0031308) x * 12.92 else 1.055 * x.pow(1 / 2.4) - .055
    private fun affine(x: DoubleArray) = doubleArrayOf(.88 * x[0] + .08 * x[1] + .01,
        .92 * x[1] + .04 * x[2] + .02, .90 * x[2] + .06 * x[0])

    private fun text() = buildString {
        append("# Preserve source precision and both domains\r\nTITLE \"analytic shaper\"\r\n")
        append("LUT_1D_SIZE 257\r\nLUT_1D_INPUT_RANGE 0 1\r\n")
        append("LUT_3D_SIZE 2\r\nDOMAIN_MIN -0.2 0.05 0\r\nDOMAIN_MAX 1.2 1.05 2\r\n")
        for (row in curve) append(row.joinToString(" ")).append("\r\n")
        for (b in 0..1) for (g in 0..1) for (r in 0..1)
            append(affine(doubleArrayOf(r.toDouble(), g.toDouble(), b.toDouble())).joinToString(" ")).append("\r\n")
    }

    private fun source(x: DoubleArray): DoubleArray {
        val shaped = DoubleArray(3) { k ->
            val at = x[k].coerceIn(0.0, 1.0) * 256
            val i = min(at.toInt(), 255)
            val f = at - i
            val value = curve[i][k].toDouble() * (1 - f) + curve[i + 1][k].toDouble() * f
            ((value - low[k]) / (high[k] - low[k])).coerceIn(0.0, 1.0)
        }
        return affine(shaped)
    }

    private fun reference(input: DoubleArray, space: String): DoubleArray {
        if (space == SpkLut.SPACE_PROPHOTO) return source(input)
        val linear = matrix(forward, DoubleArray(3) { decode(input[it]) })
        val out = source(DoubleArray(3) { srgbOut(linear[it].coerceIn(0.0, 1.0)) })
        return matrix(inverse, DoubleArray(3) { srgbIn(out[it].coerceIn(0.0, 1.0)) })
            .map { encode(it.coerceIn(0.0, 1.0)) }.toDoubleArray()
    }

    private fun sample(table: Lut3, input: DoubleArray): DoubleArray {
        val p = DoubleArray(3) { input[it].coerceIn(0.0, 1.0) * (table.size - 1) }
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

    @Test fun finalVerticesUseTheOriginalShaperAndDomainChain() {
        val cube = CubeFile.parse(text())
        for (space in listOf(SpkLut.SPACE_SRGB, SpkLut.SPACE_PROPHOTO)) {
            val baked = LutBake.forLayer2(cube, space)
            assertEquals(65, baked.size)
            for (r in listOf(0, 1, 2, 3, 8, 16, 32, 64)) for (g in listOf(0, 1, 7, 31, 64)) for (b in listOf(0, 2, 19, 64)) {
                val x = doubleArrayOf(r / 64.0, g / 64.0, b / 64.0)
                val actual = FloatArray(3) { x[it].toFloat() }.also(baked::sample)
                val expected = reference(x, space)
                for (k in 0..2) assertEquals("$space $r/$g/$b/$k", expected[k], actual[k].toDouble(), .000002)
            }
        }
    }

    @Test fun offGridErrorAndNativeFixturesAreMeasuredAgainstTheSourceChain() {
        val csv = StringBuilder("space,pixels,old_mean_abs,new_mean_abs,old_max_abs,new_max_abs\n")
        val path = System.getenv("SPK_FIDELITY_EVIDENCE_DIR")?.let { File(it).apply { mkdirs() } }
        val collapsed = Lut3.identity(2)
        for (i in collapsed.data.indices step 3) {
            val result = source(DoubleArray(3) { collapsed.data[i + it].toDouble() })
            for (k in 0..2) collapsed.data[i + k] = result[k].toFloat()
        }
        for (space in listOf(SpkLut.SPACE_SRGB, SpkLut.SPACE_PROPHOTO)) {
            val baked = LutBake.forLayer2(CubeFile.parse(text()), space)
            val old = LutBake.forLayer2(collapsed, space)
            val fixture = ByteBuffer.allocate(8 + baked.data.size * 4 + 16384 * 6 * 4).order(ByteOrder.LITTLE_ENDIAN)
            fixture.putInt(65).putInt(16384)
            for (v in baked.data) fixture.putFloat(v)
            val cube = CubeFile.parse(text())
            val direct = ByteBuffer.allocate(16 + 12 * 4 + 257 * 3 * 4 + 24 * 4 + 16384 * 6 * 4).order(ByteOrder.LITTLE_ENDIAN)
            direct.putInt(2).putInt(257).putInt(if (space == SpkLut.SPACE_SRGB) 1 else 0).putInt(16384)
            for (row in listOf(cube.oneDMin, cube.oneDMax, cube.threeDMin, cube.threeDMax)) for (v in row) direct.putFloat(v)
            for (v in cube.oneD!!) direct.putFloat(v)
            for (v in cube.threeD!!.data) direct.putFloat(v)
            var oldSum = 0.0; var newSum = 0.0; var oldMax = 0.0; var newMax = 0.0
            repeat(16384) { n ->
                val input = doubleArrayOf(((n * 1567 + 73) % 16381) / 16381.0,
                    ((n * 2411 + 177) % 16369) / 16369.0, ((n * 3541 + 341) % 16363) / 16363.0)
                // The native fixture receives Float inputs, as JNI does.
                val x = DoubleArray(3) { input[it].toFloat().toDouble() }
                val expected = reference(x, space)
                val actual = sample(baked, x)
                val previous = sample(old, x)
                for (k in 0..2) {
                    val a = abs(actual[k] - expected[k]); val b = abs(previous[k] - expected[k])
                    newSum += a; oldSum += b; newMax = max(newMax, a); oldMax = max(oldMax, b)
                }
                for (v in x) fixture.putFloat(v.toFloat())
                for (v in actual) fixture.putFloat(v.toFloat())
                for (v in x) direct.putFloat(v.toFloat())
                for (v in expected) direct.putFloat(v.toFloat())
            }
            assertTrue("$space mean $newSum vs $oldSum", newSum < oldSum * .3)
            assertTrue("$space max $newMax vs $oldMax", newMax < oldMax)
            csv.append("$space,16384,${oldSum / 49152},${newSum / 49152},$oldMax,$newMax\n")
            path?.let { File(it, "native-$space.bin").writeBytes(fixture.array()) }
            path?.let { File(it, "source-$space.bin").writeBytes(direct.array()) }
        }
        path?.let { File(it, "source-chain-error.csv").writeText(csv.toString()) }
    }

    @Test fun originalBytesAndBakedFloatsSurviveRepeatedSharing() {
        val original = text().toByteArray()
        val cube = CubeFile.parse(text())
        val store = LutStore(temp.newFolder("luts"))
        val first = store.saveCube("lut_first", cube, original, SpkLut.SPACE_SRGB, ImportReport())
        assertEquals(257, store.source("lut_first")!!.first.oneDSize)
        assertNotNull(LutStore(File(temp.root, "luts")).source("lut_first"))
        repeat(5) { n ->
            val saved = store.original(if (n == 0) "lut_first" else "lut_round${n - 1}")!!
            assertArrayEquals(original, saved.first)
            assertEquals(SpkLut.SPACE_SRGB, saved.second)
            val packageBytes = java.io.ByteArrayOutputStream().also {
                SpkPackage(SpkLook("source", lut = SpkLut(space = saved.second)), saved.first).write(it)
            }.toByteArray()
            val parsed = SpkPackage.read(packageBytes, ImportReport(), "round")
            val nextBytes = requireNotNull(parsed.lutCube)
            val next = store.saveCube("lut_round$n", CubeFile.parse(String(nextBytes)),
                nextBytes, parsed.look.lut!!.space, ImportReport())
            assertArrayEquals(first.data, next.data, 0f)
        }
    }

    @Test fun oversizedSourceSharesAnExactNativeFloatTableWithinThePackageLimit() {
        val source = text()
        val original = ("#" + "x".repeat(SpkPackage.MAX_LUT.toInt()) + "\n" + source).toByteArray()
        val report = ImportReport()
        val store = LutStore(temp.newFolder("large"))
        val baked = store.saveCube("lut_large", CubeFile.parse(String(original)), original, SpkLut.SPACE_SRGB, report)
        val saved = store.original("lut_large")!!
        assertTrue(saved.first.size <= SpkPackage.MAX_LUT)
        assertEquals(SpkLut.SPACE_PROPHOTO, saved.second)
        assertNull(store.source("lut_large"))
        val restored = LutBake.forLayer2(CubeFile.parse(String(saved.first)), saved.second)
        assertArrayEquals(baked.data, restored.data, 0f)
        assertTrue(report.unconverted.any { it.what == "lut.cube" })
    }
}
