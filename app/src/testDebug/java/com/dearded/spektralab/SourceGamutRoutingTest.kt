package com.dearded.spektralab

import android.app.Application
import android.graphics.ColorSpace
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Gamut routing only: no claim of preserving arbitrary ICC gamuts or HDR rendering. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class SourceGamutRoutingTest {
    private val srgb = floatArrayOf(.64f, .33f, .30f, .60f, .15f, .06f)
    private val d65 = (ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb).whitePoint

    // Independent barycentric coordinates, rather than production's edge-distance test.
    private fun oracleOutside(p: FloatArray): Boolean {
        val ax = .64; val ay = .33
        val bx = .30; val by = .60
        val cx = .15; val cy = .06
        val determinant = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
        return (0..2).any { i ->
            val x = p[2 * i].toDouble(); val y = p[2 * i + 1].toDouble()
            val a = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / determinant
            val b = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / determinant
            minOf(a, b, 1.0 - a - b) < -1e-6
        }
    }

    private fun profile(p: FloatArray, white: FloatArray = d65) =
        ColorSpace.Rgb("Routing fixture", p, white, 2.2)

    @Test fun establishedTargetsKeepTheirRoutes() {
        assertFalse(SourceLoader.wideSource(ColorSpace.get(ColorSpace.Named.SRGB)))
        assertFalse(SourceLoader.wideSource(ColorSpace.get(ColorSpace.Named.LINEAR_SRGB)))
        for (space in listOf(ColorSpace.Named.DISPLAY_P3, ColorSpace.Named.ADOBE_RGB,
            ColorSpace.Named.BT2020, ColorSpace.Named.DCI_P3)) {
            assertTrue("Lost wider target for $space", SourceLoader.wideSource(ColorSpace.get(space)))
        }
    }

    @Test fun smallTriangleWithRedOutsideSrgbUsesP3() {
        // Contains D65 and is less than half sRGB's area, but reaches the P3 red.
        val narrow = floatArrayOf(.68f, .32f, .34f, .38f, .18f, .12f)
        fun area(p: FloatArray) = abs(
            p[0].toDouble() * (p[3] - p[5]) + p[2].toDouble() * (p[5] - p[1]) +
                p[4].toDouble() * (p[1] - p[3])) / 2.0
        assertTrue(area(narrow) < area(srgb) / 2)
        assertTrue(oracleOutside(narrow))
        assertTrue(SourceLoader.primariesOutsideSrgb(narrow))
        assertTrue(SourceLoader.wideSource(profile(narrow)))
    }

    @Test fun insideAndBoundaryTrianglesStaySrgb() {
        val inside = FloatArray(6) { i -> d65[i % 2] + .6f * (srgb[i] - d65[i % 2]) }
        for (p in listOf(inside, srgb)) {
            assertFalse(oracleOutside(p))
            assertFalse(SourceLoader.primariesOutsideSrgb(p))
            assertFalse(SourceLoader.wideSource(profile(p)))
        }
    }

    @Test fun sourceVertexOrderDoesNotChangeContainment() {
        val reversed = floatArrayOf(srgb[4], srgb[5], srgb[2], srgb[3], srgb[0], srgb[1])
        assertFalse(SourceLoader.primariesOutsideSrgb(reversed))
        reversed[0] = .10f
        assertTrue(oracleOutside(reversed))
        assertTrue(SourceLoader.primariesOutsideSrgb(reversed))
    }

    @Test fun toleranceAcceptsRoundingButNotMeaningfulOverhang() {
        val rounded = srgb.copyOf().apply { this[0] += .00005f }
        val outside = srgb.copyOf().apply { this[0] += .001f }
        assertFalse(SourceLoader.primariesOutsideSrgb(rounded))
        assertTrue(SourceLoader.primariesOutsideSrgb(outside))
    }

    @Test fun customP3ProfileDoesNotDependOnNamedIdentity() {
        val p3 = floatArrayOf(.68f, .32f, .265f, .69f, .15f, .06f)
        assertTrue(oracleOutside(p3))
        assertTrue(SourceLoader.wideSource(profile(p3)))
    }

    @Test fun adaptedSrgbMatrixKeepsItsOriginalGamut() {
        val adapted = ColorSpace.adapt(ColorSpace.get(ColorSpace.Named.SRGB),
            floatArrayOf(.34567f, .35850f), ColorSpace.Adaptation.BRADFORD)
        assertFalse(SourceLoader.wideSource(adapted))
    }

    // Double Bradford reference. Input transform columns are RGB basis vectors in XYZ.
    private fun oracleAdaptedPrimaries(rgb: ColorSpace.Rgb): FloatArray {
        val bradford = doubleArrayOf(.8951, .2664, -.1614, -.7502, 1.7135, .0367, .0389, -.0685, 1.0296)
        val inverse = doubleArrayOf(.9869929, -.1470543, .1599627, .4323053, .5183603, .0492912,
            -.0085287, .0400428, .9684867)
        fun mul(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r ->
            (0..2).sumOf { c -> m[3 * r + c] * v[c] }
        }
        fun xyz(w: FloatArray) = doubleArrayOf(w[0].toDouble() / w[1], 1.0,
            (1.0 - w[0] - w[1]) / w[1])
        val sourceWhite = mul(bradford, xyz(rgb.whitePoint))
        val targetWhite = mul(bradford, xyz(d65))
        val matrix = rgb.transform
        val result = FloatArray(6)
        for (c in 0..2) {
            val lms = mul(bradford, DoubleArray(3) { r -> matrix[3 * c + r].toDouble() })
            val adapted = mul(inverse, DoubleArray(3) { i -> lms[i] * targetWhite[i] / sourceWhite[i] })
            val sum = adapted.sum()
            result[2 * c] = (adapted[0] / sum).toFloat()
            result[2 * c + 1] = (adapted[1] / sum).toFloat()
        }
        return result
    }

    @Test fun differentWhiteIsComparedAfterAdaptation() {
        val d50Profile = profile(srgb, floatArrayOf(.34567f, .35850f))
        val expected = oracleOutside(oracleAdaptedPrimaries(d50Profile))
        assertTrue("Fixture must distinguish raw primaries from adapted gamut", expected)
        assertEquals(expected, SourceLoader.wideSource(d50Profile))
    }

    @Test fun invalidAndNonRgbInputsHaveNoWiderGamutClaim() {
        for (p in listOf(floatArrayOf(), floatArrayOf(.1f, .2f),
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f),
            srgb.copyOf().apply { this[0] = Float.NaN },
            srgb.copyOf().apply { this[4] = Float.POSITIVE_INFINITY })) {
            assertFalse(SourceLoader.primariesOutsideSrgb(p))
        }
        assertFalse(SourceLoader.wideSource(null))
        assertFalse(SourceLoader.wideSource(ColorSpace.get(ColorSpace.Named.CIE_XYZ)))
    }
}
