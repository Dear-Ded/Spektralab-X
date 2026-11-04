package com.dearded.spektralab

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream

/**
 * Shooting metadata carried from the source photo to an exported JPEG.
 *
 * Read once when the photo opens (the content stream is gone by export time).
 * Location is copied only when the user turns on 导出保留拍摄位置
 * ([LocationSetting]); it is read separately ([readLocation]), because a
 * plain content URI hands out a copy with the GPS tags redacted.
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

    /** The real focal length in mm (FocalLength), never the 35 mm equivalent; null when the file does not say. */
    fun actualFocal(): Double? = rational(ExifInterface.TAG_FOCAL_LENGTH)

    /** The lens as the camera names it, or blank. A JPEG fill ([fillMissing]) supplies it only when blank. */
    val lensModel: String get() = values[ExifInterface.TAG_LENS_MODEL]?.trim().orEmpty()

    fun fNumber(): Double? = rational(ExifInterface.TAG_F_NUMBER)

    fun aperture(): String? = fNumber()?.let { if (it >= 10) "%.0f".format(it) else "%.1f".format(it).removeSuffix(".0") }

    fun shutter(): String? = rational(ExifInterface.TAG_EXPOSURE_TIME)?.let { t ->
        if (t >= 1) "${"%.1f".format(t).removeSuffix(".0")}s" else "1/${kotlin.math.round(1 / t).toInt()}s"
    }

    fun iso(): Int? = values[ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY]?.split(',')?.firstOrNull()?.trim()?.toIntOrNull()

    /**
     * True when a RAW lacks shooting data a same-shot JPEG usually carries (phone
     * DNGs often have no lens model or 35 mm focal length): offer to fill it in.
     */
    val missingShooting: Boolean get() = SHOOTING.any { values[it].isNullOrBlank() }

    /** These tags, plus each of [other]'s tags this one lacks. What it already has wins. */
    fun filledFrom(other: ExifCopy): ExifCopy = ExifCopy(fillMissing(values, other.values))

    /** Tags [other] would add ([filledFrom]). */
    fun missingFrom(other: ExifCopy): Map<String, String> = fillMissing(values, other.values) - values.keys

    /** These tags plus the source's GPS tags ([readLocation]); empty leaves it as it is. */
    fun withLocation(location: Map<String, String>): ExifCopy =
        if (location.isEmpty()) this else ExifCopy(values + location)

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

        // What a same-shot JPEG is asked for when a RAW lacks it.
        private val SHOOTING = listOf(
            ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        )

        /** [base] plus every copied tag of [extra] that [base] has no non-blank value for. Pure. */
        fun fillMissing(base: Map<String, String>, extra: Map<String, String>): Map<String, String> {
            val out = LinkedHashMap(base)
            for (tag in TAGS) {
                val v = extra[tag]?.trim()
                if (!v.isNullOrEmpty() && out[tag].isNullOrBlank()) out[tag] = v
            }
            return out
        }

        /**
         * Seconds between two `yyyy:MM:dd HH:mm:ss` stamps, or null when either is
         * missing or malformed. A same-shot JPEG is within a few seconds of its RAW.
         */
        fun secondsApart(a: String?, b: String?): Long? {
            fun parse(s: String?): Long? {
                val m = Regex("""^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""").find(s?.trim() ?: return null) ?: return null
                val (y, mo, d, h, mi, se) = m.destructured
                return runCatching {
                    java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), se.toInt())
                        .toEpochSecond(java.time.ZoneOffset.UTC)
                }.getOrNull()
            }
            val x = parse(a) ?: return null
            val y = parse(b) ?: return null
            return kotlin.math.abs(x - y)
        }

        // Tags filled in from a JPEG the user picked, per source URI, so a reopen
        // and the export queue (which reads the RAW again) carry them too. Only
        // ever fills gaps ([fillMissing]); the newest 64 sources are kept.
        private const val FILL_PREFS = "exif_fill"
        private const val FILL_KEEP = 64

        fun rememberFill(context: Context, source: Uri, tags: Map<String, String>) {
            if (tags.isEmpty()) return
            val prefs = context.getSharedPreferences(FILL_PREFS, 0)
            val text = "${System.currentTimeMillis()}\n" + tags.entries.joinToString("\n") {
                "${it.key}=${it.value.replace('\n', ' ')}"
            }
            val edit = prefs.edit().putString(source.toString(), text)
            val all = prefs.all.filterKeys { it != source.toString() }
            if (all.size >= FILL_KEEP) {
                all.entries.sortedBy { (it.value as? String)?.substringBefore('\n')?.toLongOrNull() ?: 0L }
                    .take(all.size - FILL_KEEP + 1).forEach { edit.remove(it.key) }
            }
            edit.apply()
        }

        private fun rememberedFill(context: Context, source: Uri): Map<String, String> =
            context.getSharedPreferences(FILL_PREFS, 0).getString(source.toString(), null)
                ?.lineSequence()?.drop(1)?.mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
                }?.toMap().orEmpty()

        /** Never throws: a source without readable EXIF gives [NONE]. */
        fun read(open: () -> InputStream?): ExifCopy = runCatching {
            open()?.use { stream ->
                val exif = ExifInterface(stream)
                val map = LinkedHashMap<String, String>()
                for (tag in TAGS) exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { map[tag] = it }
                ExifCopy(map)
            }
        }.getOrNull() ?: NONE

        /**
         * [uri]'s tags, with any gaps filled from a remembered JPEG ([fillFromJpeg]).
         * [fillKey] is the URI the fill was remembered under: the export queue
         * reads a kept local copy but the fill belongs to the picked source.
         */
        fun read(context: android.content.Context, uri: android.net.Uri, fillKey: Uri = uri): ExifCopy {
            val fromFd = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    val map = LinkedHashMap<String, String>()
                    for (tag in TAGS) exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { map[tag] = it }
                    ExifCopy(map)
                }
            }.getOrNull()
            val own = fromFd?.takeIf { !it.isEmpty } ?: read { context.contentResolver.openInputStream(uri) }
            val filled = rememberedFill(context, fillKey)
            return if (filled.isEmpty()) own else ExifCopy(fillMissing(own.values, filled))
        }

        /**
         * Fills [raw]'s gaps from [jpeg], a photo the user picked as the same
         * shot (the system picker hands over only that one URI, no media
         * permission). Refused, with a reason, when the JPEG carries nothing new
         * or was taken more than a minute apart from the RAW. Remembered for
         * [source]. IO; never throws.
         */
        fun fillFromJpeg(context: Context, source: Uri, raw: ExifCopy, jpeg: Uri): Pair<ExifCopy, String> {
            val other = runCatching { read(context, jpeg) }.getOrDefault(NONE)
            if (other.isEmpty) return raw to "这张照片读不到拍摄参数"
            val apart = secondsApart(raw.dateTimeOriginal, other.dateTimeOriginal)
            if (apart != null && apart > 60) return raw to "两张照片的拍摄时间相差 ${apart} 秒，不像同一次拍摄，没有合并"
            val added = raw.missingFrom(other)
            if (added.isEmpty()) return raw to "这张照片没有可补充的参数"
            val all = rememberedFill(context, source) + added
            rememberFill(context, source, all)
            return raw.filledFrom(other) to "已从 JPEG 补充 ${added.size} 项拍摄参数"
        }

        /**
         * The source's GPS tags ([ExifLocation.TAGS]), or empty. IO; never throws.
         * MediaStore redacts them for an app without ACCESS_MEDIA_LOCATION, so a
         * gallery photo (or a document backed by one) is read through
         * MediaStore.setRequireOriginal first, then as it is. Refused, redacted or
         * absent all give empty: the export simply carries no location.
         */
        fun readLocation(context: Context, uri: Uri): Map<String, String> {
            val candidates = ArrayList<Uri>()
            if (LocationSetting.permitted(context)) mediaUri(context, uri)?.let { candidates += MediaStore.setRequireOriginal(it) }
            candidates += uri
            for (source in candidates) {
                val found = runCatching {
                    context.contentResolver.openFileDescriptor(source, "r")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)
                        ExifLocation.keep(ExifLocation.TAGS.mapNotNull { t -> exif.getAttribute(t)?.let { t to it } }.toMap())
                    }
                }.getOrNull().orEmpty()
                if (found.isNotEmpty()) return found
            }
            return emptyMap()
        }

        /** The MediaStore item behind [uri], or null when it is not one (a file manager, a cloud drive). */
        private fun mediaUri(context: Context, uri: Uri): Uri? {
            if (uri.scheme != "content") return null
            if (uri.authority == MediaStore.AUTHORITY) return uri
            if (!DocumentsContract.isDocumentUri(context, uri)) return null
            return runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
        }
    }
}

/**
 * The GPS tags an export may carry, as ExifInterface strings. Pure Kotlin, so
 * it is unit-tested on the JVM (src/test). Kept in a job as [encode]d text.
 */
object ExifLocation {
    val TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_IMG_DIRECTION,
    )

    /**
     * Only the location tags, and none of them unless latitude and longitude
     * are real: a redacted copy reads as zeros (or zero denominators), and
     * writing 0° N 0° E into a photo would be worse than writing nothing.
     */
    fun keep(raw: Map<String, String>): Map<String, String> {
        val m = raw.filter { (k, v) -> k in TAGS && v.isNotBlank() }
        val lat = m[ExifInterface.TAG_GPS_LATITUDE]?.let(::degrees)
        val lon = m[ExifInterface.TAG_GPS_LONGITUDE]?.let(::degrees)
        if (lat == null || lon == null || lat > 90.0 || lon > 180.0 || (lat == 0.0 && lon == 0.0)) return emptyMap()
        if (m[ExifInterface.TAG_GPS_LATITUDE_REF].isNullOrBlank() || m[ExifInterface.TAG_GPS_LONGITUDE_REF].isNullOrBlank()) return emptyMap()
        return m
    }

    /** "d/1,m/1,s/100" to degrees; null when it is not three valid rationals. */
    fun degrees(dms: String): Double? {
        val parts = dms.split(',')
        if (parts.size != 3) return null
        var total = 0.0
        var scale = 1.0
        for (p in parts) {
            val nd = p.trim().split('/')
            val n = nd.getOrNull(0)?.toDoubleOrNull() ?: return null
            val d = if (nd.size == 2) nd[1].toDoubleOrNull() ?: return null else 1.0
            if (nd.size > 2 || d == 0.0 || n < 0) return null
            total += n / d / scale
            scale *= 60.0
        }
        return total.takeIf { it.isFinite() }
    }

    fun encode(tags: Map<String, String>): String = tags.entries.joinToString("\n") { "${it.key}=${it.value}" }

    fun decode(text: String): Map<String, String> = keep(
        text.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
    )
}

/** 设置 → 导出保留拍摄位置. Off by default: an export carries no location. */
object LocationSetting {
    private const val KEY = "keep_location"
    private const val REQUEST = 41

    fun enabled(context: Context): Boolean = context.getSharedPreferences("settings", 0).getBoolean(KEY, false)

    /** Turning it on asks once for ACCESS_MEDIA_LOCATION; a refusal only means gallery photos export without it. */
    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences("settings", 0).edit().putBoolean(KEY, on).apply()
        if (on && !permitted(context)) {
            runCatching { activity(context)?.requestPermissions(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), REQUEST) }
        }
    }

    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun activity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
