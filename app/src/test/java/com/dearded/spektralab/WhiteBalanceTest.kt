package com.dearded.spektralab

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class WhiteBalanceTest {
    @Test fun solvesTheActualNativeGainsAcrossPaletteStrengths() {
        for (strength in listOf(25.0, 50.0, 100.0)) for (temperature in listOf(-90.0, -20.0, 0.0, 40.0, 90.0)) {
            for (tint in listOf(-90.0, -25.0, 0.0, 35.0, 90.0)) {
                val k = strength / 100
                val r = 0.4 / (1 + 0.0018 * temperature * k)
                val g = 0.4 / (1 - 0.0012 * tint * k)
                val b = 0.4 / (1 - 0.0018 * temperature * k)
                val pick = solveWhiteBalance(r, g, b, strength).pick!!
                assertEquals(temperature, pick.temperature, 1e-9)
                assertEquals(tint, pick.tint, 1e-9)
                assertFalse(pick.limited)
                assertEquals(r * (1 + 0.0018 * pick.temperature * k), g * (1 - 0.0012 * pick.tint * k), 1e-12)
                assertEquals(r * (1 + 0.0018 * pick.temperature * k), b * (1 - 0.0018 * pick.temperature * k), 1e-12)
            }
        }
    }

    @Test fun neutralPatchResetsBothAxesAndLargeCastsAreExplicitlyLimited() {
        val neutral = solveWhiteBalance(0.4, 0.4, 0.4, 100.0).pick!!
        assertEquals(0.0, neutral.temperature, 0.0); assertEquals(0.0, neutral.tint, 0.0)
        val extreme = solveWhiteBalance(0.1, 0.4, 0.8, 100.0).pick!!
        assertTrue(extreme.limited); assertEquals(100.0, extreme.temperature, 0.0)
        assertTrue(extreme.tint in -100.0..100.0)
    }

    @Test fun rejectsUnusableSamplesInsteadOfInventingAnAdjustment() {
        assertEquals(WhiteBalanceSampleError.DARK, solveWhiteBalance(.001, .002, .003, 100.0).error)
        assertEquals(WhiteBalanceSampleError.CLIPPED, solveWhiteBalance(1.0, .8, .8, 100.0).error)
        assertEquals(WhiteBalanceSampleError.INVALID, solveWhiteBalance(Double.NaN, .4, .4, 100.0).error)
        assertEquals(WhiteBalanceSampleError.INVALID, solveWhiteBalance(-.2, .4, .4, 100.0).error)
        assertEquals(WhiteBalanceSampleError.PALETTE_OFF, solveWhiteBalance(.4, .4, .4, 0.0).error)
    }

    private fun reference(): FloatArray = FloatArray(2 + 9 * 9 * 6).also { values ->
        values[0] = 9f; values[1] = 9f
        repeat(9 * 9) { i -> repeat(6) { k -> values[2 + i * 6 + k] = .4f } }
    }

    @Test fun averagesThePatchAndHandlesCornersWithoutReadingOutsideIt() {
        val values = reference()
        values[2 + (4 * 9 + 4) * 6] = .5f
        val ref = WhiteBalanceReference.fromNative(values)!!
        val pick = ref.sample(.5f, .5f, Look.NEUTRAL).pick!!
        assertTrue(pick.temperature < 0); assertTrue(pick.temperature > -2)
        assertEquals(0.0, ref.sample(0f, 0f, Look.NEUTRAL).pick!!.temperature, 0.0)
        assertNotNull(ref.sample(1f, 1f, Look.NEUTRAL).pick)
        assertEquals(WhiteBalanceSampleError.INVALID, ref.sample(-.1f, .5f, Look.NEUTRAL).error)
    }

    @Test fun validatesNativeShapeAndRefusesMostlyClippedPatches() {
        assertNull(WhiteBalanceReference.fromNative(floatArrayOf(512f, 512f)))
        assertNull(WhiteBalanceReference.fromNative(reference().also { it[0] = 9.5f }))
        assertNull(WhiteBalanceReference.fromNative(reference().also { it[4] = Float.NaN }))
        val values = reference()
        repeat(9 * 9) { values[2 + it * 6] = 1f }
        assertEquals(WhiteBalanceSampleError.CLIPPED,
            WhiteBalanceReference.fromNative(values)!!.sample(.5f, .5f, Look.NEUTRAL).error)
    }

    @Test fun fittedImageCoordinatesExcludeLetterboxing() {
        val r = whiteBalanceImageRect(400f, 600f, 800, 400)
        assertEquals(0f, r.left, 0f); assertEquals(200f, r.top, 0f)
        assertEquals(400f, r.width, 0f); assertEquals(200f, r.height, 0f)
        assertFalse(r.contains(Offset(200f, 100f)))
        assertTrue(r.contains(Offset(200f, 300f)))
    }
}
