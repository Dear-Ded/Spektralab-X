package com.dearded.spektralab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import com.dearded.spektralab.advisor.domain.BrightSide
import com.dearded.spektralab.advisor.domain.SceneRect
import com.dearded.spektralab.advisor.editor.AdvisorStatisticsSampler
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DarkroomSceneEvidenceTest {
    @Test fun matchedHistogramsKeepBroadLightDistinctFromACornerAtBothSamplingScales() = runBlocking<Unit> {
        for (size in listOf(8, 264)) {
            val scale = size / 8
            val broad = measure(size, size) { x, _ -> if (x / scale >= 6) 204 else 51 }
            val patch = measure(size, size) { x, y -> if (x / scale >= 4 && y / scale < 4) 204 else 51 }
            assertEquals(broad.values, patch.values)
            assertEquals(BrightSide.RIGHT, broad.measuredBrightSide)
            assertNull(patch.measuredBrightSide)
            assertGrid(List(4) { listOf(.2, .2, .2, .8) }.flatten(), broad.scene!!.gridLumaMeans)
            assertGrid(List(2) { listOf(.2, .2, .8, .8) }.flatten() + List(8) { .2 }, patch.scene!!.gridLumaMeans)
            val mirror = measure(size, size) { x, _ -> if ((size - 1 - x) / scale >= 6) 204 else 51 }
            val rotate = measure(size, size) { _, y -> if (y / scale >= 6) 204 else 51 }
            assertEquals(BrightSide.LEFT, mirror.measuredBrightSide)
            assertEquals(BrightSide.BOTTOM, rotate.measuredBrightSide)
            assertGrid(listOf(.8, .2, .2, .2).let { row -> List(4) { row }.flatten() }, mirror.scene!!.gridLumaMeans)
            assertGrid(List(12) { .2 } + List(4) { .8 }, rotate.scene!!.gridLumaMeans)
        }
    }

    @Test fun cropAndOrientationKeepTheSameReferencePixelsAndExcludeOpaqueBorder() = runBlocking<Unit> {
        fun content(x: Int, y: Int) = when {
            x in 1..2 && y in 1..2 -> 128
            x >= 6 -> 204
            else -> 51
        }
        val cropped = measure(11, 9, SceneRect(2, 1, 10, 7), SceneRect(1, 1, 3, 3)) { x, y ->
            if (x in 2..9 && y in 1..6) content(x - 2, y - 1) else 255
        }
        val mirrored = measure(8, 6, reference = SceneRect(5, 1, 7, 3)) { x, y -> content(7 - x, y) }
        val rotated = measure(6, 8, reference = SceneRect(3, 1, 5, 3)) { x, y -> content(y, 5 - x) }
        assertEquals(BrightSide.RIGHT, cropped.measuredBrightSide)
        assertEquals(BrightSide.LEFT, mirrored.measuredBrightSide)
        assertEquals(BrightSide.BOTTOM, rotated.measuredBrightSide)
        assertEquals(cropped.values, mirrored.values)
        assertEquals(cropped.values, rotated.values)
        assertEquals(0.0, cropped.values!!.nearWhite, 0.0)
        val actual = listOf(cropped, mirrored, rotated).map { it.scene!!.reference!! }
        actual.zip(listOf(listOf(1.0/8, 1.0/6, 3.0/8, 3.0/6), listOf(5.0/8, 1.0/6, 7.0/8, 3.0/6),
            listOf(3.0/6, 1.0/8, 5.0/6, 3.0/8))).forEach { (roi, expected) ->
            assertGrid(expected, listOf(roi.left, roi.top, roi.right, roi.bottom))
            assertEquals(1.0 / 12, roi.fraction, 1e-12)
            assertEquals(128.0 / 255, roi.mean, 1e-12)
            assertEquals(.2, roi.neighborhoodMean!!, 1e-12)
            assertEquals(0.0, roi.nearWhite, 0.0)
        }
    }

    @Test fun smallHighlightIsCheckedOnPreviewPixelsEvenWhenDownsampledAndAtTheEdge() = runBlocking<Unit> {
        val dark = measure(257, 257, reference = SceneRect(0, 0, 1, 1)) { x, y ->
            if (x == 0 && y == 0) 250 else 64
        }
        assertEquals(0.0, dark.values!!.nearWhite, 0.0)
        val region = dark.scene!!.reference!!
        assertEquals(1.0, region.nearWhite, 0.0)
        assertEquals(1.0 / (257 * 257), region.fraction, 1e-12)
        assertEquals(64.0 / 255, region.neighborhoodMean!!, 1e-12)
        val whole = measure(1, 1, reference = SceneRect(0, 0, 1, 1)) { _, _ -> 5 }
        assertEquals(1.0, whole.scene!!.reference!!.nearBlack, 0.0)
        assertNull(whole.scene.reference.neighborhoodMean)
    }

    @Test fun taggedP3ReferenceAndGridUseTheSameEncodedSrgbDomainAsLegacyNumbers() = runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        try {
            bitmap.setPixel(0, 0, Color.rgb(179, 51, 77))
            bitmap.setColorSpace(p3)
            val sample = checkNotNull(AdvisorStatisticsSampler.sample(bitmap, "Display P3", 1, 1,
                reference = SceneRect(0, 0, 1, 1)))
            assertEquals(.2763670588235294, sample.values!!.lumaQuantiles[1], 1.5 / 255)
            assertGrid(sample.values.lumaQuantiles.take(1), sample.scene!!.gridLumaMeans)
            assertEquals(sample.values.lumaQuantiles[1], sample.scene.reference!!.mean, 1e-12)
            assertEquals(p3, bitmap.colorSpace)
        } finally { bitmap.recycle() }
    }

    @Test fun jpegDecodeKeepsBroadLightAndTranslucencyOnlyDisablesSpatialEvidenceOffGrid() = runBlocking<Unit> {
        val bitmap = fixture(256, 256) { x, _ -> if (x >= 192) 204 else 51 }
        try {
            val bytes = ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                .toByteArray()
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                val sample = checkNotNull(AdvisorStatisticsSampler.sample(decoded, "sRGB", 1, 1))
                assertEquals(BrightSide.RIGHT, sample.measuredBrightSide)
                assertTrue(sample.scene!!.gridLumaMeans[3] - sample.scene.gridLumaMeans[0] > .5)
            } finally { decoded.recycle() }
            val before = AdvisorStatisticsSampler.sample(bitmap, "sRGB", 1, 1)!!
            bitmap.setPixel(0, 0, Color.TRANSPARENT)
            val after = AdvisorStatisticsSampler.sample(bitmap, "sRGB", 1, 1)!!
            assertEquals(before.values, after.values)
            assertNull(after.scene)
        } finally { bitmap.recycle() }
    }

    private suspend fun measure(w: Int, h: Int, content: SceneRect = SceneRect(0, 0, w, h),
        reference: SceneRect? = null, code: (Int, Int) -> Int) = fixture(w, h, code).let { bitmap ->
        try { checkNotNull(AdvisorStatisticsSampler.sample(bitmap, "sRGB", 11, 17, content, reference)) }
        finally { bitmap.recycle() }
    }

    private fun fixture(w: Int, h: Int, code: (Int, Int) -> Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(w * h) { i -> code(i % w, i / w).let { Color.rgb(it, it, it) } }
        setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun assertGrid(expected: List<Double>, actual: List<Double>) =
        assertArrayEquals(expected.toDoubleArray(), actual.toDoubleArray(), 1e-12)
}
