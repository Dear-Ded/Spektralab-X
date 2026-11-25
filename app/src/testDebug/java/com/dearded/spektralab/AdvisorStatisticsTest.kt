package com.dearded.spektralab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import com.dearded.spektralab.advisor.domain.BrightSide
import com.dearded.spektralab.advisor.editor.AdvisorStatisticsSampler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdvisorStatisticsTest {
    @Test fun measuredDistributionMatchesKnownSrgbPixelsAndPreservesTheBitmap() = runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(Color.BLACK, Color.rgb(64, 64, 64), Color.rgb(128, 128, 128), Color.WHITE)
        try {
            bitmap.setPixels(colors, 0, 4, 0, 0, 4, 1)
            val sample = checkNotNull(AdvisorStatisticsSampler.sample(bitmap, "sRGB", 7, 19))
            assertEquals(7L, sample.sourceRevision); assertEquals(19L, sample.sampleRevision)
            assertEquals(4, sample.width); assertEquals(1, sample.height)
            val values = checkNotNull(sample.values)
            assertArrayEquals(doubleArrayOf(0.0, 64.0 / 255, 1.0), values.lumaQuantiles.toDoubleArray(), 1e-12)
            assertEquals(.25, values.nearBlack, 0.0); assertEquals(.25, values.nearWhite, 0.0)
            assertEquals(listOf(0, 4, 8, 15), values.red.indices.filter { values.red[it] > 0 })
            assertEquals(listOf(0, 21, 42, 63), values.coarseRgb.indices.filter { values.coarseRgb[it] > 0 })
            val after = IntArray(4); bitmap.getPixels(after, 0, 4, 0, 0, 4, 1); assertArrayEquals(colors, after)
        } finally { bitmap.recycle() }
    }

    @Test fun p3CodesAreConvertedToSrgbInsteadOfBeingRetaggedOrTreatedAsLinearLight() = runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        try {
            bitmap.setPixel(0, 0, Color.rgb(179, 51, 77))
            bitmap.setColorSpace(p3) // Fixture raw channel codes now denote P3, as native output does.
            val expected = ColorSpace.connect(p3, srgb).transform(179f / 255, 51f / 255, 77f / 255)
                .map { (it.coerceIn(0f, 1f) * 255).roundToInt() }
            val expectedLuma = (.2126 * expected[0] + .7152 * expected[1] + .0722 * expected[2]) / 255
            val rawLuma = (.2126 * 179 + .7152 * 51 + .0722 * 77) / 255
            assertTrue(abs(expectedLuma - rawLuma) > .005)
            val sample = checkNotNull(AdvisorStatisticsSampler.sample(bitmap, "Display P3", 1, 1))
            assertEquals(expectedLuma, sample.values!!.lumaQuantiles[1], 1.5 / 255)
            assertEquals(p3, bitmap.colorSpace)
            assertNull(AdvisorStatisticsSampler.sample(bitmap, "sRGB", 1, 1))
        } finally { bitmap.recycle() }
    }

    @Test fun uniformGridHasBoundedSizeAndTransparentOrUnknownSamplesAreUnavailable() = runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(513, 257, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(128, 128, 128))
            val sample = checkNotNull(AdvisorStatisticsSampler.sample(bitmap, "sRGB", 1, 1))
            assertEquals(128, sample.width); assertEquals(128, sample.height)
            assertEquals(128.0 / 255, sample.values!!.lumaQuantiles[1], 1e-12)
            assertNull(AdvisorStatisticsSampler.sample(bitmap, "ProPhoto", 1, 1))
            bitmap.eraseColor(Color.TRANSPARENT)
            assertNull(AdvisorStatisticsSampler.sample(bitmap, "sRGB", 1, 1))
        } finally { bitmap.recycle() }
        assertNull(AdvisorStatisticsSampler.sample(bitmap, "sRGB", 1, 1))
    }

    @Test fun splitCheckerPhasesHaveNoDirectionButKeepPointSampleStatistics() = runBlocking<Unit> {
        for ((w, h) in listOf(256 to 256, 257 to 385, 513 to 513)) {
            for (horizontal in listOf(false, true)) for (phase in 0..1) {
                val bitmap = fixture(w, h) { x, y ->
                    val far = if (horizontal) x >= w / 2 else y >= h / 2
                    val offset = if (far) 1 else 0
                    if ((x + y + phase + offset) % 2 == 0) 51 else 204
                }
                try {
                    val sample = checkNotNull(AdvisorStatisticsSampler.sample(bitmap, "sRGB", 1, 1))
                    assertEquals("${w}x$h horizontal=$horizontal phase=$phase", null, sample.measuredBrightSide)
                    assertEquals(128, sample.width); assertEquals(128, sample.height)
                    if (w == 256 && h == 256) {
                        val values = checkNotNull(sample.values)
                        assertArrayEquals(doubleArrayOf(.2, .2, .8), values.lumaQuantiles.toDoubleArray(), 1e-12)
                        val histogram = List(16) { if (it == 3 || it == 12) .5 else 0.0 }
                        assertEquals(histogram, values.red)
                        assertEquals(histogram, values.green)
                        assertEquals(histogram, values.blue)
                        assertEquals(List(64) { if (it == 0 || it == 63) .5 else 0.0 }, values.coarseRgb)
                        assertEquals(0.0, values.nearBlack, 0.0)
                        assertEquals(0.0, values.nearWhite, 0.0)
                    }
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun directionSurvivesSingleAxisReductionWithFineTexture() = runBlocking<Unit> {
        for ((w, h) in listOf(513 to 95, 95 to 513)) for (phase in 0..1) {
            val horizontal = w > h
            val length = if (horizontal) w else h
            val bitmap = fixture(w, h) { x, y ->
                val along = if (horizontal) x else y
                val base = 30 + along * 75 / (length - 1)
                val offset = if (along >= length / 2) 1 else 0
                if ((along + phase + offset) % 2 == 0) base else base + 140
            }
            try {
                val sample = checkNotNull(AdvisorStatisticsSampler.sample(bitmap, "sRGB", 1, 1))
                assertEquals(if (horizontal) BrightSide.RIGHT else BrightSide.BOTTOM, sample.measuredBrightSide)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun directionIsConfirmedForBroadLightInRealPhotoControls() = runBlocking<Unit> {
        val samples = File("../docs/images/samples")
        for ((name, expected) in listOf("compare_dunes" to BrightSide.TOP, "compare_lake" to BrightSide.TOP,
            "compare_skyline" to null)) {
            val photo = checkNotNull(BitmapFactory.decodeFile(samples.resolve("$name.jpg").path))
            try {
                // Use the right-hand photo, not the comparison layout.
                val panel = Bitmap.createBitmap(photo, photo.width / 2, 0, photo.width / 2, photo.height)
                try {
                    assertEquals(name, expected, checkNotNull(AdvisorStatisticsSampler.sample(panel, "sRGB", 1, 1))
                        .measuredBrightSide)
                    if (expected != null) {
                        val rotate = Matrix().apply { setRotate(90f) }
                        val portrait = Bitmap.createBitmap(panel, 0, 0, panel.width, panel.height, rotate, false)
                        try {
                            assertEquals(name, BrightSide.RIGHT,
                                checkNotNull(AdvisorStatisticsSampler.sample(portrait, "sRGB", 1, 1)).measuredBrightSide)
                        } finally { portrait.recycle() }
                    }
                } finally { panel.recycle() }
            } finally { photo.recycle() }
        }
    }

    @Test fun alphaOutsidePointGridOnlyInvalidatesDirection() = runBlocking<Unit> {
        val bitmap = fixture(256, 256) { x, _ -> if (x < 128) 51 else 204 }
        try {
            val opaque = checkNotNull(AdvisorStatisticsSampler.sample(bitmap, "sRGB", 1, 1))
            assertEquals(BrightSide.RIGHT, opaque.measuredBrightSide)
            bitmap.setPixel(0, 0, Color.TRANSPARENT)
            val offGrid = checkNotNull(AdvisorStatisticsSampler.sample(bitmap, "sRGB", 1, 1))
            assertEquals(opaque.values, offGrid.values)
            assertNull(offGrid.measuredBrightSide)
            bitmap.setPixel(1, 1, Color.TRANSPARENT)
            assertNull(AdvisorStatisticsSampler.sample(bitmap, "sRGB", 1, 1))
        } finally { bitmap.recycle() }
    }

    private fun fixture(w: Int, h: Int, gray: (Int, Int) -> Int) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(w * h) { i -> gray(i % w, i / w).let { Color.rgb(it, it, it) } }
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
}
