package com.dearded.spektralab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The thumbnail input contract runs through real Android Bitmap and Skia conversion. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class FilmThumbnailColorTest {
    private val codes = intArrayOf(64, 128, 192)
    // Android ARM uses RGBA; Robolectric's Linux Skia can use BGRA. Inspect storage,
    // then keep the colour assertions on raw bytes rather than getPixel's sRGB conversion.
    private val channelOffsets by lazy {
        val primary = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            intArrayOf(Color.RED, Color.GREEN, Color.BLUE).map { color ->
                primary.eraseColor(color)
                val raw = pixels(primary)
                (0..2).single { (raw[it].toInt() and 255) == 255 }
            }.toIntArray()
        } finally { primary.recycle() }
    }

    private fun bitmap(space: ColorSpace, width: Int = 7, height: Int = 3): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, false, space)
        val bytes = ByteBuffer.allocate(bitmap.byteCount)
        for (y in 0 until height) for (x in 0 until width) {
            val offset = y * bitmap.rowBytes + 4 * x
            for (channel in 0..2) bytes.put(offset + channelOffsets[channel], codes[channel].toByte())
            bytes.put(offset + 3, 255.toByte())
        }
        bitmap.copyPixelsFromBuffer(bytes)
        return bitmap
    }

    private fun pixels(bitmap: Bitmap): ByteArray = ByteBuffer.allocate(bitmap.byteCount).also {
        bitmap.copyPixelsToBuffer(it)
    }.array()

    private fun verify(source: Bitmap, target: ColorSpace.Named, limit: Int = 32) {
        val original = pixels(source)
        val expectedSpace = ColorSpace.get(target)
        // ColourSpace's analytic connector is independent of Canvas's Skia implementation.
        val expected = ColorSpace.connect(source.colorSpace!!, expectedSpace).transform(
            codes[0] / 255f, codes[1] / 255f, codes[2] / 255f,
        )
        val photo = FilmThumbs.prepareSource(source, limit)
        try {
            assertEquals(Bitmap.Config.ARGB_8888, photo.bitmap.config)
            assertEquals(expectedSpace, photo.bitmap.colorSpace)
            assertEquals(if (target == ColorSpace.Named.DISPLAY_P3) "Display P3" else "sRGB", photo.space)
            val actual = pixels(photo.bitmap)
            for (y in 0 until photo.bitmap.height) for (x in 0 until photo.bitmap.width) {
                val offset = y * photo.bitmap.rowBytes + 4 * x
                for (channel in 0..2) {
                    // One 8-bit output quantisation plus independent Java/Skia transfer rounding.
                    assertEquals("pixel $x,$y channel $channel", expected[channel],
                        (actual[offset + channelOffsets[channel]].toInt() and 255) / 255f, 2f / 255f)
                }
                assertEquals(255, actual[offset + 3].toInt() and 255)
            }
            if (source.colorSpace == expectedSpace && source.width == photo.bitmap.width && source.height == photo.bitmap.height) {
                assertArrayEquals("A supported input changed packed pixel codes", original, actual)
            }
            assertFalse(source.isRecycled)
            assertArrayEquals("Preparing a film thumbnail mutated its shared source", original, pixels(source))
        } finally {
            photo.recycle()
        }
    }

    @Test fun adobeRgbActuallyConvertsToP3() {
        val source = bitmap(ColorSpace.get(ColorSpace.Named.ADOBE_RGB))
        try { verify(source, ColorSpace.Named.DISPLAY_P3) } finally { source.recycle() }
    }

    @Test fun customP3TransferActuallyConvertsToNamedP3() {
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3) as ColorSpace.Rgb
        val source = bitmap(ColorSpace.Rgb("P3 with gamma 2.6", p3.primaries, p3.whitePoint, 2.6))
        try { verify(source, ColorSpace.Named.DISPLAY_P3) } finally { source.recycle() }
    }

    @Test fun linearSrgbActuallyConvertsToEncodedSrgb() {
        val source = bitmap(ColorSpace.get(ColorSpace.Named.LINEAR_SRGB))
        try { verify(source, ColorSpace.Named.SRGB) } finally { source.recycle() }
    }

    @Test fun supportedSpacesKeepTheirCodes() {
        for (target in listOf(ColorSpace.Named.SRGB, ColorSpace.Named.DISPLAY_P3)) {
            val source = bitmap(ColorSpace.get(target))
            try { verify(source, target) } finally { source.recycle() }
        }
    }

    @Test fun thumbnailDecoderCanPreserveAnUnsupportedInputSpace() {
        val source = bitmap(ColorSpace.get(ColorSpace.Named.ADOBE_RGB))
        try {
            val encoded = ByteArrayOutputStream().also {
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                assertFalse(decoded.colorSpace!!.isSrgb)
                assertFalse(decoded.colorSpace == ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
                // copy(ARGB_8888), used by the old path, does not normalise the colour space.
                val copied = decoded.copy(Bitmap.Config.ARGB_8888, false)
                try {
                    assertEquals(decoded.colorSpace, copied.colorSpace)
                    verify(copied, ColorSpace.Named.DISPLAY_P3)
                } finally { copied.recycle() }
            } finally { decoded.recycle() }
        } finally { source.recycle() }
    }

    @Test fun sizingNeverUpscalesAndStillFitsTheRequestedLongEdge() {
        val source = bitmap(ColorSpace.get(ColorSpace.Named.ADOBE_RGB), 17, 11)
        try {
            val small = FilmThumbs.prepareSource(source, 8)
            try {
                assertEquals(8, small.bitmap.width)
                assertEquals(5, small.bitmap.height)
            } finally { small.recycle() }
            val full = FilmThumbs.prepareSource(source, 32)
            try {
                assertEquals(17, full.bitmap.width)
                assertEquals(11, full.bitmap.height)
            } finally { full.recycle() }
            verify(source, ColorSpace.Named.DISPLAY_P3, 8)
        } finally { source.recycle() }
    }
}
