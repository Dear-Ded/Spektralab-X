package com.dearded.spektralab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-normal-notround-port-night-mdpi-keyshidden-nonav")
class PreviewOpticsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun translucentPhotoIsFilteredOnceAndTheSurroundIsUnchanged() {
        var amount by mutableFloatStateOf(0.5f)
        compose.setContent {
            Box(Modifier.size(160.dp).background(Color.Green).testTag("optics")) {
                Canvas(Modifier.fillMaxSize().latentPreview(200, 100, FrameSpec()) { amount }) {
                    drawRect(Color.Red.copy(alpha = 0.5f))
                }
            }
        }
        val output = File(compose.activity.cacheDir, "optics-alpha.png")
        fun pixels(): Bitmap {
            compose.waitForIdle()
            compose.onNodeWithTag("optics").captureRoboImage(output.absolutePath)
            return BitmapFactory.decodeFile(output.absolutePath)
        }
        fun check(pixel: Int, r: Int, g: Int, b: Int) {
            assertTrue("Red: $pixel", kotlin.math.abs(android.graphics.Color.red(pixel) - r) <= 2)
            assertTrue("Green: $pixel", kotlin.math.abs(android.graphics.Color.green(pixel) - g) <= 2)
            assertTrue("Blue: $pixel", kotlin.math.abs(android.graphics.Color.blue(pixel) - b) <= 2)
        }
        pixels().let { image ->
            try {
                check(image.getPixel(image.width / 2, image.height / 2), 64, 191, 64)
                check(image.getPixel(image.width / 2, 5), 128, 128, 0)
            } finally { image.recycle() }
        }
        compose.runOnIdle { amount = 0f }
        pixels().let { image ->
            try { check(image.getPixel(image.width / 2, image.height / 2), 128, 128, 0) }
            finally { image.recycle() }
        }
    }

    @Test fun shutterDigitsRemainReadableWithCommaAndArabicNumberLocales() {
        val file = File(compose.activity.cacheDir, "optics-exif.jpg")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally { bitmap.recycle() }
        val previous = Locale.getDefault()
        try {
            for (exposure in listOf("3/2" to "1.5s", "2/1" to "2s", "1/125" to "1/125s")) {
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposure.first)
                    saveAttributes()
                }
                for (locale in listOf(Locale.GERMANY, Locale.forLanguageTag("ar"), Locale.CHINA)) {
                    Locale.setDefault(locale)
                    assertEquals(locale.toLanguageTag(), exposure.second, ExifCopy.read { file.inputStream() }.shutter())
                }
            }
        } finally {
            Locale.setDefault(previous)
            file.delete()
        }
    }
}
