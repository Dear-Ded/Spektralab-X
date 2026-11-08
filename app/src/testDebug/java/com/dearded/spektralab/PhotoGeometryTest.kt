package com.dearded.spektralab

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real decode geometry: a working preview must retain the full export size. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PhotoGeometryTest {
    private fun jpeg(orientation: Int, check: (Context, Uri) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("photo-geometry-", ".jpg", context.cacheDir)
        try {
            val source = Bitmap.createBitmap(6000, 4000, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(Color.rgb(170, 130, 80))
                file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally { source.recycle() }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            check(context, Uri.fromFile(file))
        } finally { file.delete() }
    }

    @Test fun scaledPreviewRetainsTwentyFourMegapixelSource() = jpeg(ExifInterface.ORIENTATION_NORMAL) { context, uri ->
        val photo = SourceLoader(context).decodePhoto(uri, 3072, 30_000_000)
        try {
            assertEquals(3072, photo.bitmap.width)
            assertEquals(2048, photo.bitmap.height)
            assertEquals(6000, photo.sourceWidth)
            assertEquals(4000, photo.sourceHeight)
            assertEquals(24_000_000L, photo.sourceWidth.toLong() * photo.sourceHeight)
        } finally { photo.recycle() }
    }

    @Test fun originalAndWorkingSizesBothUseExifOrientation() = jpeg(ExifInterface.ORIENTATION_ROTATE_90) { context, uri ->
        val photo = SourceLoader(context).decodePhoto(uri, 3072, 30_000_000)
        try {
            assertEquals(2048, photo.bitmap.width)
            assertEquals(3072, photo.bitmap.height)
            assertEquals(4000, photo.sourceWidth)
            assertEquals(6000, photo.sourceHeight)
        } finally { photo.recycle() }
    }

    @Test fun decodeEnforcesAreaAndLongEdgeTogether() = jpeg(ExifInterface.ORIENTATION_NORMAL) { context, uri ->
        val photo = SourceLoader(context).decodePhoto(uri, 4096, 2_000_000)
        try {
            assertTrue(maxOf(photo.bitmap.width, photo.bitmap.height) <= 4096)
            assertTrue(photo.bitmap.width.toLong() * photo.bitmap.height <= 2_000_000)
            assertEquals(24_000_000L, photo.sourceWidth.toLong() * photo.sourceHeight)
        } finally { photo.recycle() }
    }
}
