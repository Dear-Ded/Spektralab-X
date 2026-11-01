package com.dearded.spektralab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Everything between a content URI and pixels the engine can take: naming,
 * RAW detection, descriptor access for LibRaw, and the phone-photo decode.
 */
class SourceLoader(private val context: Context) {

    fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "image"
    }

    fun isRaw(name: String, mime: String?, uri: Uri?): Boolean {
        if (isRawByName(name, mime)) return true
        val m = mime?.lowercase(Locale.ROOT)
        // Files and cloud providers often say application/octet-stream (or
        // nothing) for a DNG: trust the TIFF header instead of the type.
        if (uri == null || (m != null && m != "application/octet-stream")) return false
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { s -> ByteArray(4).takeIf { s.read(it) == 4 } }
        }.getOrNull() ?: return false
        return looksLikeTiff(head)
    }

    /**
     * A read-only descriptor LibRaw can open as `/proc/self/fd/<fd>`, so a
     * 50-100 MB RAW is decoded in place rather than copied into cache first.
     * Null when the provider cannot give one (a pipe or a remote document).
     */
    fun openDescriptor(uri: Uri): ParcelFileDescriptor? =
        runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()

    /**
     * Copies [uri] into cache/[folder]. The editor and the processing queue use
     * different folders: each clears its own, never the other's file mid-decode.
     */
    fun copyToCache(uri: Uri, name: String, folder: String = "open"): File {
        val dir = File(context.cacheDir, folder).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifEmpty { "image.raw" }
        val file = File(dir, safe)
        val input = context.contentResolver.openInputStream(uri) ?: throw EngineException("无法读取该文件")
        input.use { i -> file.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
        return file
    }

    /**
     * Phone photos (JPEG, HEIC/HEIF, Ultra HDR JPEG, PNG, WebP). ImageDecoder
     * applies the EXIF rotation. Display P3 sources stay in P3 so their wide
     * gamut reaches the engine; everything else is converted to sRGB. Returns
     * the bitmap and the engine's name for its space.
     */
    fun decodePhoto(uri: Uri, edge: Int, maxPixels: Long): Photo {
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        var space = "sRGB"
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(
                if (info.colorSpace == p3) { space = "Display P3"; p3 } else ColorSpace.get(ColorSpace.Named.SRGB)
            )
            val s = fitScale(info.size.width, info.size.height, edge, maxPixels)
            if (s < 1.0) decoder.setTargetSize(max(1, (info.size.width * s).toInt()), max(1, (info.size.height * s).toInt()))
        }
        // Taken before any rescale or copy: the gain map is sampled by
        // normalised position, so the base's final size does not matter.
        val gain = if (android.os.Build.VERSION.SDK_INT >= 34) GainmapReader.read(decoded) else null
        // setTargetSize is a hint some decoders ignore (seen with DNG and very
        // large HEIC in another port), so the pixel cap is enforced here too.
        var out = decoded
        if (maxPixels > 0 && out.width.toLong() * out.height > maxPixels * 21 / 20) {
            val s = sqrt(maxPixels.toDouble() / (out.width.toDouble() * out.height))
            val scaled = Bitmap.createScaledBitmap(out, max(1, (out.width * s).toInt()), max(1, (out.height * s).toInt()), true)
            if (scaled !== out) out.recycle()
            out = scaled
        }
        if (out.config == Bitmap.Config.ARGB_8888) return Photo(out, space, gain?.first, gain?.second)
        val copy = out.copy(Bitmap.Config.ARGB_8888, false)
        out.recycle()
        return Photo(copy ?: throw EngineException("无法转换图像格式"), space, gain?.first, gain?.second)
    }

    companion object {
        val RAW_EXTENSIONS = setOf(
            "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "pef",
            "srw", "x3f", "3fr", "iiq", "erf", "kdc", "mrw", "rwl", "raw", "mos", "mef", "dcr",
        )
        private val DECODABLE_X = setOf("image/x-ms-bmp", "image/x-icon")

        fun isRawByName(name: String, mime: String?): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext in RAW_EXTENSIONS) return true
            val m = mime?.lowercase(Locale.ROOT) ?: return false
            return m.contains("dng") || m.contains("raw") || (m.startsWith("image/x-") && m !in DECODABLE_X)
        }

        /** "II" or "MM": a TIFF container, which is what DNG and most RAWs are. */
        fun looksLikeTiff(head: ByteArray): Boolean =
            head.size >= 2 && ((head[0] == 0x49.toByte() && head[1] == 0x49.toByte()) ||
                (head[0] == 0x4D.toByte() && head[1] == 0x4D.toByte()))

        /** A failure to open the file at all, as opposed to a file LibRaw cannot decode. */
        fun isOpenFailure(error: String): Boolean =
            error.contains("RAW open") || error.contains("cannot read the RAW metadata")

        /** Scale that fits (w, h) inside [edge] on the long side and [maxPixels] in area; 0 = no limit. */
        fun fitScale(w: Int, h: Int, edge: Int, maxPixels: Long): Double {
            var s = 1.0
            if (edge > 0 && max(w, h) > edge) s = edge.toDouble() / max(w, h)
            if (maxPixels > 0 && w.toDouble() * h * s * s > maxPixels) s = sqrt(maxPixels.toDouble() / (w.toDouble() * h))
            return s
        }
    }
}
