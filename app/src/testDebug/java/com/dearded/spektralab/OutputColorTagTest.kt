package com.dearded.spektralab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Co-created with GPT (OpenAI). Output tags label already-packed pixel codes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class OutputColorTagTest {
    private fun pixels(bitmap: Bitmap): ByteArray {
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        return buffer.array()
    }

    private fun withBitmap(check: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(7, 3, Bitmap.Config.ARGB_8888)
        try {
            val colors = IntArray(bitmap.width * bitmap.height) { i ->
                Color.rgb((i * 47) % 256, (i * 83 + 17) % 256, (i * 113 + 29) % 256)
            }
            bitmap.setPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            check(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun checkOrder(vararg targets: Pair<String, ColorSpace.Named>) = withBitmap { bitmap ->
        val original = pixels(bitmap)
        for ((target, named) in targets) {
            DisplayColor.tagOutput(bitmap, target)
            assertEquals(ColorSpace.get(named), bitmap.colorSpace)
            assertArrayEquals("Tagging $target changed packed pixel bytes", original, pixels(bitmap))
        }
    }

    @Test fun srgbThenP3PreservesPackedPixels() = checkOrder(
        "sRGB" to ColorSpace.Named.SRGB,
        "Display P3" to ColorSpace.Named.DISPLAY_P3,
        "sRGB" to ColorSpace.Named.SRGB,
    )

    @Test fun p3ThenSrgbPreservesPackedPixels() = checkOrder(
        "Display P3" to ColorSpace.Named.DISPLAY_P3,
        "sRGB" to ColorSpace.Named.SRGB,
        "Display P3" to ColorSpace.Named.DISPLAY_P3,
    )

    @Test fun unknownTargetPreservesExistingTagAndPackedPixels() = withBitmap { bitmap ->
        for (target in listOf("sRGB", "Display P3")) {
            DisplayColor.tagOutput(bitmap, target)
            val originalSpace = bitmap.colorSpace
            val originalPixels = pixels(bitmap)
            assertThrows(IllegalArgumentException::class.java) {
                DisplayColor.tagOutput(bitmap, "Adobe RGB")
            }
            assertEquals(originalSpace, bitmap.colorSpace)
            assertArrayEquals(originalPixels, pixels(bitmap))
        }
    }
}
