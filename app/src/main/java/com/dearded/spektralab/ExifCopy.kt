package com.dearded.spektralab

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream

/**
 * Shooting metadata carried from the source photo to an exported JPEG.
 *
 * Read once when the photo opens (the content stream is gone by export time).
 * Location is never copied: the source was read through a plain content URI,
 * which Android redacts anyway, and baking it in silently would be worse.
 * Orientation is always written as 1, because both decoders (ImageDecoder and
 * LibRaw) have already rotated the pixels.
 */
class ExifCopy private constructor(private val values: Map<String, String>) {

    val isEmpty get() = values.isEmpty()

    /** Writes the copied tags into [jpeg] in place. Failures leave the file as it was. */
    fun applyTo(jpeg: File, width: Int, height: Int) {
        runCatching {
            val exif = ExifInterface(jpeg)
            for ((tag, value) in values) runCatching { exif.setAttribute(tag, value) }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "SpektraLab Android ${BuildConfig.VERSION_NAME}")
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")  // sRGB, what the export is
            exif.saveAttributes()
        }
    }

    companion object {
        val NONE = ExifCopy(emptyMap())

        private val TAGS = listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SPECIFICATION,
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        )

        /** Never throws: a source without readable EXIF gives [NONE]. */
        fun read(open: () -> InputStream?): ExifCopy = runCatching {
            open()?.use { stream ->
                val exif = ExifInterface(stream)
                val map = LinkedHashMap<String, String>()
                for (tag in TAGS) exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { map[tag] = it }
                ExifCopy(map)
            }
        }.getOrNull() ?: NONE
    }
}
