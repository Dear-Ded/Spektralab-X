package com.dearded.spektralab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.util.Half
import com.dearded.spektralab.advisor.domain.BrightSide
import com.dearded.spektralab.advisor.editor.AdvisorStatisticsSampler
import com.dearded.spektralab.advisor.editor.CinematicRetryGoal
import com.dearded.spektralab.advisor.editor.CinematicWhites
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CinematicWhitesTest {
    @Test fun reducedDraftMustWidenContrastOrMoveBothEndsOfItsDeclaredAxis() = runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(257, 131, Bitmap.Config.ARGB_8888)
        suspend fun measure(goal: CinematicRetryGoal, luma: (Int, Int) -> Int): CinematicWhites {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val code = luma(x, y)
                bitmap.setPixel(x, y, Color.rgb(code, code, code))
            }
            return checkNotNull(CinematicWhites.measure(bitmap, "sRGB", goal))
        }
        try {
            val contrast = CinematicRetryGoal.Contrast
            val before = measure(contrast) { x, _ -> if (x < 128) 80 else 160 }
            val shifted = measure(contrast) { x, _ -> if (x < 128) 81 else 161 }
            val widened = measure(contrast) { x, _ -> if (x < 128) 79 else 161 }
            assertFalse(before.improves(shifted, contrast))
            assertTrue(before.improves(widened, contrast))
            for (side in BrightSide.entries) {
                val goal = CinematicRetryGoal.Direction(side)
                fun bright(x: Int, y: Int) = when (side) {
                    BrightSide.TOP -> y < 65
                    BrightSide.BOTTOM -> y >= 65
                    BrightSide.LEFT -> x < 128
                    BrightSide.RIGHT -> x >= 128
                }
                val base = measure(goal) { _, _ -> 120 }
                val oneSide = measure(goal) { x, y -> if (bright(x, y)) 121 else 120 }
                val both = measure(goal) { x, y -> if (bright(x, y)) 121 else 119 }
                assertFalse(base.improves(oneSide, goal))
                assertTrue(base.improves(both, goal))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun permitsOnlyNetGrowthWithinTheFlooredBudget() {
        val belowThousand = CinematicWhites(999, 1, "sRGB", 10, 20)
        assertTrue(belowThousand.permits(belowThousand))
        assertTrue(belowThousand.permits(belowThousand.copy(count = 9)))
        assertFalse(belowThousand.permits(belowThousand.copy(count = 11)))

        val exactThousand = belowThousand.copy(width = 1_000)
        assertTrue(exactThousand.permits(exactThousand.copy(count = 11)))
        assertFalse(exactThousand.permits(exactThousand.copy(count = 12)))

        val aboveThousand = exactThousand.copy(width = 1_999)
        assertTrue(aboveThousand.permits(aboveThousand.copy(count = 11)))
        assertFalse(aboveThousand.permits(aboveThousand.copy(count = 12)))

        val large = CinematicWhites(100_000, 100_000, "sRGB", 5_000_000_000L, 5_500_000_000L)
        assertTrue(large.permits(large.copy(count = 5_010_000_000L)))
        assertFalse(large.permits(large.copy(count = 5_010_000_001L)))
    }

    @Test fun comparisonsRequireMatchingDimensionsAndOutput() {
        val before = CinematicWhites(40, 25, "sRGB", 2, 2)
        assertFalse(before.permits(before.copy(width = 25, height = 40, count = 0)))
        assertFalse(before.permits(before.copy(width = 41, count = 0)))
        assertFalse(before.permits(before.copy(height = 26, count = 0)))
        assertFalse(before.permits(before.copy(output = "Display P3", count = 0)))
    }

    @Test fun coloredNearWhitesDoNotCountAsPureWhite() = runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        try {
            val pixels = intArrayOf(
                Color.WHITE, Color.rgb(254, 255, 255), Color.rgb(255, 254, 255),
                Color.rgb(255, 255, 254), Color.rgb(254, 254, 254), Color.BLACK,
            )
            bitmap.setPixels(pixels, 0, 3, 0, 0, 3, 2)
            assertEquals(CinematicWhites(3, 2, "sRGB", 1, 5), CinematicWhites.measure(bitmap, "sRGB"))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun countsEachClippedPixelOnceAndKeepsTheSrgbBoundaryInclusive() = runBlocking<Unit> {
        val image = Bitmap.createBitmap(40, 25, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.GRAY)
            image.setPixel(0, 0, Color.rgb(255, 0, 127))
            val before = checkNotNull(CinematicWhites.measure(image, "sRGB"))
            for (x in 1..5) image.setPixel(x, 0, Color.rgb(30, 255, 0))
            val atLimit = checkNotNull(CinematicWhites.measure(image, "sRGB"))
            assertEquals(6L, atLimit.channelEnds)
            assertTrue(before.permits(atLimit))
            image.setPixel(6, 0, Color.rgb(29, 80, 0))
            val above = checkNotNull(CinematicWhites.measure(image, "sRGB"))
            assertEquals(0L, above.count)
            assertFalse(before.permits(above))
            assertTrue(above.permits(before))
            assertFalse(before.copy(output = "Display P3").permits(above.copy(output = "Display P3")))
        } finally { image.recycle() }
    }

    @Test fun measuresDisplayP3FloatAndOpaqueRgb565SoftwareBitmaps() = runBlocking<Unit> {
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val wide = Bitmap.createBitmap(2, 3, Bitmap.Config.RGBA_F16, false, p3)
        val rgb565 = Bitmap.createBitmap(1, 2, Bitmap.Config.RGB_565)
        try {
            val data = ByteBuffer.allocate(wide.byteCount).order(ByteOrder.nativeOrder())
            for (y in 0 until wide.height) {
                for (x in 0 until wide.width) {
                    val white = x != 1 || y != 2
                    val code = Half.toHalf(if (white) 1.0f else 0.98f)
                    data.putShort(code).putShort(code).putShort(code).putShort(Half.toHalf(1.0f))
                }
                data.position((y + 1) * wide.rowBytes)
            }
            data.rewind()
            wide.copyPixelsFromBuffer(data)
            assertEquals(CinematicWhites(2, 3, "Display P3", 5, 5), CinematicWhites.measure(wide, "Display P3"))
            assertEquals(p3, wide.colorSpace)
            assertNull(CinematicWhites.measure(wide, "sRGB"))

            rgb565.eraseColor(Color.BLACK)
            rgb565.setPixel(0, 1, Color.WHITE)
            assertEquals(CinematicWhites(1, 2, "sRGB", 1, 2), CinematicWhites.measure(rgb565, "sRGB"))
            assertNull(CinematicWhites.measure(rgb565, "Display P3"))
        } finally {
            wide.recycle()
            rgb565.recycle()
        }
    }

    @Test fun detectsNewNativeP3ChannelEndsEvenWhenTheSrgbConversionAlreadyClips() = runBlocking<Unit> {
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val image = Bitmap.createBitmap(257, 129, Bitmap.Config.ARGB_8888, true, p3)
        try {
            suspend fun measure(clipped: Int): CinematicWhites {
                val data = ByteBuffer.allocate(image.byteCount)
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        val at = y * image.width + x
                        data.put((if (at >= image.width * image.height - clipped) 255 else 250).toByte())
                        data.put(55).put(85).put(255.toByte())
                    }
                    data.position((y + 1) * image.rowBytes)
                }
                data.rewind()
                image.copyPixelsFromBuffer(data)
                for (pixel in listOf(image.getPixel(0, 0), image.getPixel(image.width - 1, image.height - 1)))
                    assertEquals(255, maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)))
                return checkNotNull(CinematicWhites.measure(image, "Display P3"))
            }
            val budget = image.width * image.height / 200
            val before = measure(0)
            val atLimit = measure(budget)
            val above = measure(budget + 1)
            assertEquals(0L, before.channelEnds)
            assertEquals(budget.toLong(), atLimit.channelEnds)
            assertEquals((budget + 1).toLong(), above.channelEnds)
            assertTrue(before.permits(atLimit))
            assertFalse(before.permits(above))
            assertEquals(0L, above.count)
        } finally { image.recycle() }
    }

    @Test fun fullScanFindsHighlightsBetweenGridSamplesIncludingTheLastStripe() = runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(257, 129, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLACK)
            val before = checkNotNull(CinematicWhites.measure(bitmap, "sRGB"))
            assertEquals(0L, before.count)
            // The grid samples only odd x coordinates at width 257.
            val x = bitmap.width - 1
            bitmap.setPixel(x, bitmap.height - 1, Color.WHITE)
            assertEquals(1L, checkNotNull(CinematicWhites.measure(bitmap, "sRGB")).count)

            for (y in bitmap.height - 34 until bitmap.height) bitmap.setPixel(x, y, Color.WHITE)
            val sample = checkNotNull(AdvisorStatisticsSampler.sample(bitmap, "sRGB", 1, 1))
            assertEquals(0.0, checkNotNull(sample.values).nearWhite, 0.0)
            val after = checkNotNull(CinematicWhites.measure(bitmap, "sRGB"))
            assertEquals(34L, after.count)
            assertFalse(before.permits(after))

            bitmap.setPixel(x, bitmap.height - 1, Color.TRANSPARENT)
            assertNull(CinematicWhites.measure(bitmap, "sRGB"))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun unknownOutputColorlessAndRecycledBitmapsAreUnavailable() = runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val alpha = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
        try {
            bitmap.eraseColor(Color.WHITE)
            assertNull(CinematicWhites.measure(bitmap, "ProPhoto"))
            alpha.eraseColor(Color.WHITE)
            assertNull(CinematicWhites.measure(alpha, "sRGB"))
        } finally {
            bitmap.recycle()
            alpha.recycle()
        }
        assertNull(CinematicWhites.measure(bitmap, "sRGB"))
    }

    @Test fun scanHonorsCoroutineCancellation() = runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                CinematicWhites.measure(bitmap, "sRGB")
                fail("Cancelled scan returned normally")
            }.join()
        } finally {
            bitmap.recycle()
        }
    }
}
