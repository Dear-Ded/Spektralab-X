package com.dearded.spektralab

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream

/** The HEIF metadata carrier must never leak its 1x1 JPEG dimensions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeifExifTest {
    @Test fun carrierDimensionsDescribeTheExport() {
        val dir = RuntimeEnvironment.getApplication().cacheDir
        val block = HeifExport.exifBlock(ExifCopy.NONE, 1600, 1000, dir)
        assertNotNull(block)
        // ExifInterface accepts a JPEG stream; wrap the actual HEIF APP1 body.
        val body = block!!
        val size = body.size + 2
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(),
            (size shr 8).toByte(), size.toByte()) + body + byteArrayOf(0xff.toByte(), 0xd9.toByte())
        val exif = ExifInterface(ByteArrayInputStream(jpeg))
        assertEquals(1600, exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0))
        assertEquals(1000, exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0))
        assertEquals(1600, exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0))
        assertEquals(1000, exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
    }
}
