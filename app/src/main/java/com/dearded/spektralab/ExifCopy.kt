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

    /** `yyyy:MM:dd HH:mm:ss`, or null. */
    val dateTimeOriginal: String? get() = values[ExifInterface.TAG_DATETIME_ORIGINAL]

    // For the shooting-data caption. Each is null (or blank) when the photo does not say.
    val make: String get() = values[ExifInterface.TAG_MAKE]?.trim().orEmpty()
    val model: String get() = values[ExifInterface.TAG_MODEL]?.trim().orEmpty()

    private fun rational(tag: String): Double? = values[tag]?.let { v ->
        val parts = v.split('/')
        if (parts.size == 2) parts[0].toDoubleOrNull()?.let { n -> parts[1].toDoubleOrNull()?.takeIf { it != 0.0 }?.let { n / it } }
        else v.toDoubleOrNull()
    }?.takeIf { it.isFinite() && it > 0 }

    fun focal35(): Int? = values[ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM]?.toIntOrNull()?.takeIf { it > 0 }
        ?: rational(ExifInterface.TAG_FOCAL_LENGTH)?.let { kotlin.math.round(it).toInt() }

    fun fNumber(): Double? = rational(ExifInterface.TAG_F_NUMBER)

    fun aperture(): String? = fNumber()?.let { if (it >= 10) "%.0f".format(it) else "%.1f".format(it).removeSuffix(".0") }

    fun shutter(): String? = rational(ExifInterface.TAG_EXPOSURE_TIME)?.let { t ->
        if (t >= 1) "${"%.1f".format(t).removeSuffix(".0")}s" else "1/${kotlin.math.round(1 / t).toInt()}s"
    }

    fun iso(): Int? = values[ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY]?.split(',')?.firstOrNull()?.trim()?.toIntOrNull()

    /** Writes the copied tags into [jpeg] in place. Failures leave the file as it was. */
    fun applyTo(jpeg: File, width: Int, height: Int, wide: Boolean = false) {
        runCatching {
            val exif = ExifInterface(jpeg)
            for ((tag, value) in values) runCatching { exif.setAttribute(tag, value) }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Spektralab-X ${BuildConfig.VERSION_NAME}")
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            // 1 = sRGB; 65535 = uncalibrated, i.e. read the embedded profile (P3).
            exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, if (wide) "65535" else "1")
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

        fun read(context: android.content.Context, uri: android.net.Uri): ExifCopy {
            val fromFd = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    val map = LinkedHashMap<String, String>()
                    for (tag in TAGS) exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { map[tag] = it }
                    ExifCopy(map)
                }
            }.getOrNull()
            return fromFd?.takeIf { !it.isEmpty } ?: read { context.contentResolver.openInputStream(uri) }
        }
    }
}
