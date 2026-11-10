package com.dearded.spektralab

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.ByteBuffer
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

/** Co-created with GPT (OpenAI). Input labels describe the actual bitmap pixels. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PhotoInputColorTest {
    private fun bitmap(space: ColorSpace.Named, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap =
        Bitmap.createBitmap(17, 11, config, true, ColorSpace.get(space)).apply {
            eraseColor(android.graphics.Color.rgb(220, 80, 30))
        }

    private fun pixels(bitmap: Bitmap): ByteArray = ByteBuffer.allocate(bitmap.byteCount).let {
        bitmap.copyPixelsToBuffer(it)
        it.array()
    }

    private fun prepare(bitmap: Bitmap, gain: Bitmap? = null, edge: Int = 0, maxPixels: Long = 0): Photo =
        SourceLoader.preparePhoto(bitmap, gain?.let { it to FloatArray(15) }, edge, maxPixels, 170, 110)

    private fun unchanged(named: ColorSpace.Named, expected: String) {
        val decoded = bitmap(named)
        val original = pixels(decoded)
        val photo = prepare(decoded)
        try {
            assertSame(decoded, photo.bitmap)
            assertEquals(expected, photo.space)
            assertEquals(ColorSpace.get(named), photo.bitmap.colorSpace)
            assertArrayEquals(original, pixels(photo.bitmap))
            assertEquals(170, photo.sourceWidth)
            assertEquals(110, photo.sourceHeight)
        } finally { photo.recycle() }
    }

    @Test fun srgbPixelsAndTagAreUnchanged() = unchanged(ColorSpace.Named.SRGB, "sRGB")

    @Test fun p3PixelsAndTagAreUnchanged() = unchanged(ColorSpace.Named.DISPLAY_P3, "Display P3")

    @Test fun f16CopyAndRescaleRetainP3MetadataAndOwnedGainMap() {
        val decoded = bitmap(ColorSpace.Named.DISPLAY_P3, Bitmap.Config.RGBA_F16)
        val gain = Bitmap.createBitmap(3, 2, Bitmap.Config.ALPHA_8)
        val photo = prepare(decoded, gain, edge = 9, maxPixels = 40)
        try {
            assertTrue(decoded.isRecycled)
            assertEquals(Bitmap.Config.ARGB_8888, photo.bitmap.config)
            assertEquals("Display P3", photo.space)
            assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), photo.bitmap.colorSpace)
            assertTrue(photo.bitmap.width <= 9)
            assertTrue(photo.bitmap.width.toLong() * photo.bitmap.height <= 40)
            assertSame(gain, photo.gain)
            assertFalse(gain.isRecycled)
            assertEquals(170, photo.sourceWidth)
            assertEquals(110, photo.sourceHeight)
        } finally { photo.recycle() }
        assertTrue(gain.isRecycled)
    }

    @Test fun unsupportedGamutAndTransferDoNotBecomeSrgbAndReleaseOwnedStorage() {
        for (space in listOf(ColorSpace.Named.ADOBE_RGB, ColorSpace.Named.LINEAR_SRGB)) {
            val decoded = bitmap(space)
            val gain = Bitmap.createBitmap(3, 2, Bitmap.Config.ALPHA_8)
            assertThrows(EngineException::class.java) { prepare(decoded, gain) }
            assertTrue(decoded.isRecycled)
            assertTrue(gain.isRecycled)
        }
    }

    private fun jpeg(named: ColorSpace.Named, expected: String, edge: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("photo-input-color-", ".jpg", context.cacheDir)
        val source = bitmap(named)
        try {
            file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            val photo = SourceLoader(context).decodePhoto(Uri.fromFile(file), edge, 0)
            try {
                assertEquals(expected, photo.space)
                assertEquals(ColorSpace.get(named), photo.bitmap.colorSpace)
                assertEquals(17, photo.sourceWidth)
                assertEquals(11, photo.sourceHeight)
                assertTrue(photo.bitmap.width <= if (edge > 0) edge else 17)
            } finally { photo.recycle() }
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test fun realSrgbJpegDecodeUsesActualMetadata() = jpeg(ColorSpace.Named.SRGB, "sRGB", 0)

    @Test fun realP3JpegDecodeAndResizeUseActualMetadata() = jpeg(ColorSpace.Named.DISPLAY_P3, "Display P3", 9)
}
