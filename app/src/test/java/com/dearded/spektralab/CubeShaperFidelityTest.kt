package com.dearded.spektralab

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Independent analytic shapers, co-created with GPT (OpenAI). No third-party looks. */
class CubeShaperFidelityTest {
    private fun shaped(domain: Boolean): CubeFile {
        val curve = FloatArray(257 * 3) { i ->
            val x = (i / 3) / 256.0
            (x * x * if (domain) 2 else 1).toFloat()
        }
        return CubeFile("quadratic", curve, 257, FloatArray(3), FloatArray(3) { 1f },
            Lut3.identity(2), FloatArray(3), FloatArray(3) { if (domain) 2f else 1f })
    }

    @Test fun shaperIsNotReducedToTheTwoPointThreeDGrid() {
        for (domain in listOf(false, true)) {
            val report = ImportReport()
            val table = shaped(domain).toLut(report)
            val input = floatArrayOf(.25f, .5f, .75f)
            table.sample(input)
            assertArrayEquals(floatArrayOf(.0625f, .25f, .5625f), input, .000001f)
            assertEquals(65, table.size)
            assertTrue(report.unconverted.any { it.kind == ImportReport.Kind.APPROXIMATED })
        }
    }

    @Test fun nonUnitDomainKeepsItsInternalClampingKnees() {
        val cube = CubeFile("domain", null, 0, FloatArray(3), FloatArray(3) { 1f },
            Lut3.identity(2), FloatArray(3) { .25f }, FloatArray(3) { .75f })
        val table = cube.toLut(ImportReport())
        for (i in 0..64) {
            val x = i / 64f
            val p = floatArrayOf(x, x, x)
            table.sample(p)
            val expected = ((x - .25f) * 2).coerceIn(0f, 1f)
            assertTrue("domain $x -> ${p[0]}, expected $expected", abs(p[0] - expected) < .000001f)
        }
    }
}
