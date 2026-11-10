package com.dearded.spektralab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ColorSpace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native bitmap storage and pure routing; never loads NativeEngine. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PhotoInputF16Test {
    private fun bitmap(space: ColorSpace.Named, alpha: Boolean = true) =
        Bitmap.createBitmap(2, 1, Bitmap.Config.RGBA_F16, alpha, ColorSpace.get(space))

    private fun pixels(bitmap: Bitmap): ByteArray = ByteBuffer.allocate(bitmap.byteCount).let {
        bitmap.copyPixelsToBuffer(it)
        it.array()
    }

    private fun prepare(bitmap: Bitmap, eligible: Boolean = true, edge: Int = 0): Photo =
        SourceLoader.preparePhoto(bitmap, null, edge, 0, 200, 100, eligible)

    private fun adjacentHalfPixels(space: ColorSpace.Named, expected: String) {
        val decoded = bitmap(space, alpha = false)
        // Independent half literals: 0.5, its next representable value, 0.25, 0.75, 1.
        val words = intArrayOf(0x3800, 0x3400, 0x3a00, 0x3c00,
            0x3801, 0x3400, 0x3a00, 0x3c00)
        val buffer = ByteBuffer.allocate(decoded.byteCount).order(ByteOrder.nativeOrder())
        words.forEach { buffer.putShort(it.toShort()) }
        buffer.rewind()
        decoded.copyPixelsFromBuffer(buffer)
        val original = pixels(decoded)
        val photo = prepare(decoded)
        try {
            assertSame(decoded, photo.bitmap)
            assertEquals(Bitmap.Config.RGBA_F16, photo.bitmap.config)
            assertEquals(expected, photo.space)
            assertTrue(photo.highPrecisionSdr)
            assertEquals(PhotoInputRoute.F16_SDR_V1, photo.inputRoute(0f))
            assertArrayEquals(original, pixels(photo.bitmap))
            val stored = ByteBuffer.wrap(pixels(photo.bitmap)).order(ByteOrder.nativeOrder())
            assertEquals(0x3800, stored.getShort(0).toInt() and 0xffff)
            assertEquals(0x3801, stored.getShort(8).toInt() and 0xffff)
            assertEquals(200, photo.sourceWidth)
            assertEquals(100, photo.sourceHeight)
        } finally { photo.recycle() }
    }

    @Test fun adjacentSrgbHalfCodesSurviveWithoutEightBitQuantization() =
        adjacentHalfPixels(ColorSpace.Named.SRGB, "sRGB")

    @Test fun adjacentP3HalfCodesSurviveWithoutEightBitQuantization() =
        adjacentHalfPixels(ColorSpace.Named.DISPLAY_P3, "Display P3")

    @Test fun scaledF16RetainsPrecisionAndActualP3Tag() {
        val decoded = bitmap(ColorSpace.Named.DISPLAY_P3)
        val photo = prepare(decoded, edge = 1)
        try {
            assertTrue(decoded.isRecycled)
            assertEquals(1, photo.bitmap.width)
            assertEquals(1, photo.bitmap.height)
            assertEquals(Bitmap.Config.RGBA_F16, photo.bitmap.config)
            assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), photo.bitmap.colorSpace)
            assertEquals("Display P3", photo.space)
        } finally { photo.recycle() }
    }

    @Test fun ineligibleHeaderKeepsLegacyEightBitConversion() {
        val decoded = bitmap(ColorSpace.Named.DISPLAY_P3)
        val photo = prepare(decoded, eligible = false)
        try {
            assertTrue(decoded.isRecycled)
            assertFalse(photo.highPrecisionSdr)
            assertEquals(Bitmap.Config.ARGB_8888, photo.bitmap.config)
            assertEquals(PhotoInputRoute.RGBA8, photo.inputRoute(0f))
        } finally { photo.recycle() }
    }

    @Test fun headerEligibilityExcludesLinearAndHdrTransfers() {
        for (space in listOf(ColorSpace.Named.SRGB, ColorSpace.Named.EXTENDED_SRGB,
            ColorSpace.Named.DISPLAY_P3)) {
            assertTrue(SourceLoader.encodedSdrSource(ColorSpace.get(space)))
        }
        for (space in listOf(ColorSpace.Named.LINEAR_SRGB, ColorSpace.Named.LINEAR_EXTENDED_SRGB,
            ColorSpace.Named.BT2020_HLG, ColorSpace.Named.BT2020_PQ)) {
            assertFalse(SourceLoader.encodedSdrSource(ColorSpace.get(space)))
        }
        assertFalse(SourceLoader.encodedSdrSource(null))
    }

    @Test fun unsupportedF16TransferRecyclesOwnedPixels() {
        val decoded = bitmap(ColorSpace.Named.LINEAR_SRGB)
        assertThrows(EngineException::class.java) { prepare(decoded) }
        assertTrue(decoded.isRecycled)
    }

    @Test fun alphaModesUseActualBitmapState() {
        val opaque = prepare(bitmap(ColorSpace.Named.SRGB, alpha = false))
        val premultiplied = prepare(bitmap(ColorSpace.Named.SRGB))
        val straight = prepare(bitmap(ColorSpace.Named.SRGB).apply { setPremultiplied(false) })
        try {
            assertEquals(0, opaque.alphaMode)
            assertEquals(1, premultiplied.alphaMode)
            assertEquals(2, straight.alphaMode)
            for (photo in listOf(opaque, premultiplied, straight)) {
                assertEquals(PhotoInputRoute.F16_SDR_V1, photo.inputRoute(0f))
                assertEquals(PhotoInputRoute.F16_SDR_V1, photo.inputRoute(2f))
            }
        } finally {
            opaque.recycle()
            premultiplied.recycle()
            straight.recycle()
        }
    }

    @Test fun gainmapAndEightBitRoutesRemainCompatible() {
        val decoded = bitmap(ColorSpace.Named.DISPLAY_P3)
        val gain = Bitmap.createBitmap(2, 1, Bitmap.Config.ALPHA_8)
        val photo = SourceLoader.preparePhoto(decoded, gain to FloatArray(15), 0, 0, 200, 100)
        try {
            assertFalse(photo.highPrecisionSdr)
            assertEquals(Bitmap.Config.ARGB_8888, photo.bitmap.config)
            assertEquals(PhotoInputRoute.RGBA8, photo.inputRoute(0f))
            assertEquals(PhotoInputRoute.GAINMAP, photo.inputRoute(1f))
            assertSame(gain, photo.gain)
        } finally { photo.recycle() }
        assertTrue(gain.isRecycled)
    }
}
