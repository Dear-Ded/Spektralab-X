package com.dearded.spektralab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
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
     * Where LibRaw reads [uri] from: `/proc/self/fd/<fd>` when the provider
     * gives a regular file that path can reopen (LibRaw stats and fopens it,
     * a new open file with its own offset), else a copy in cache/[folder] as
     * before. Close it once the decode is done, on every path.
     */
    fun rawInput(uri: Uri, name: String, folder: String = "open"): RawInput {
        openDescriptor(uri)?.let { pfd ->
            val path = "/proc/self/fd/${pfd.fd}"
            if (reopens(pfd, path)) return RawInput(path, pfd, null)
            runCatching { pfd.close() }
        }
        val file = copyToCache(uri, name, folder)
        return RawInput(file.absolutePath, null, file)
    }

    // A pipe, a socket or a file this app may not open by path (another
    // app's private file handed over as a descriptor) fails here, not in
    // LibRaw: the first bytes read through the path must match the descriptor's.
    private fun reopens(pfd: ParcelFileDescriptor, path: String): Boolean = runCatching {
        val st = Os.fstat(pfd.fileDescriptor)
        if (!OsConstants.S_ISREG(st.st_mode) || st.st_size < 16) return false
        val want = ByteArray(16)
        if (Os.pread(pfd.fileDescriptor, want, 0, want.size, 0L) != want.size) return false
        val got = ByteArray(16)
        java.io.FileInputStream(path).use { s -> if (s.readNBytesCompat(got) != got.size) return false }
        want.contentEquals(got)
    }.getOrDefault(false)

    private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

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
     * applies the EXIF rotation. A source wider than sRGB is decoded to
     * Display P3 so its wide gamut reaches the engine ([wideSource]); the rest
     * is converted to sRGB. Returns the bitmap and the engine's name for its space.
     */
    fun decodePhoto(uri: Uri, edge: Int, maxPixels: Long): Photo {
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        var sourceWidth = 0
        var sourceHeight = 0
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            sourceWidth = info.size.width
            sourceHeight = info.size.height
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(
                if (wideSource(info.colorSpace)) p3 else ColorSpace.get(ColorSpace.Named.SRGB)
            )
            val s = fitScale(info.size.width, info.size.height, edge, maxPixels)
            if (s < 1.0) decoder.setTargetSize(max(1, (info.size.width * s).toInt()), max(1, (info.size.height * s).toInt()))
        }
        // Taken before any rescale or copy: the gain map is sampled by
        // normalised position, so the base's final size does not matter.
        val gain = if (android.os.Build.VERSION.SDK_INT >= 34) GainmapReader.read(decoded) else null
        return preparePhoto(decoded, gain, edge, maxPixels, sourceWidth, sourceHeight)
    }

    companion object {
        /** Owns decoder pixels until a validated photo takes them, including on failure. */
        internal fun preparePhoto(decoded: Bitmap, gain: Pair<Bitmap, FloatArray>?, edge: Int, maxPixels: Long,
                                  sourceWidth: Int, sourceHeight: Int): Photo {
            var out = decoded
            try {
                // Enforce both limits on the actual decoder result, including providers
                // whose decoder did not honour the requested working dimensions.
                val s = fitScale(out.width, out.height, edge, maxPixels)
                if (s < 1.0) {
                    val scaled = Bitmap.createScaledBitmap(out, max(1, (out.width * s).toInt()), max(1, (out.height * s).toInt()), true)
                    if (scaled !== out) out.recycle()
                    out = scaled
                }
                if (out.config != Bitmap.Config.ARGB_8888) {
                    val copy = out.copy(Bitmap.Config.ARGB_8888, false)
                        ?: throw EngineException("无法转换图像格式")
                    out.recycle()
                    out = copy
                }
                // A requested ImageDecoder target is not a guarantee. Never label
                // another transfer function or gamut as the engine's sRGB/P3 input.
                val cs = out.colorSpace
                val space = when {
                    cs?.isSrgb == true -> "sRGB"
                    cs == ColorSpace.get(ColorSpace.Named.DISPLAY_P3) -> "Display P3"
                    else -> throw EngineException("无法转换图像色彩空间：${cs?.name ?: "unknown"}")
                }
                return Photo(out, space, gain?.first, gain?.second, sourceWidth, sourceHeight)
            } catch (failure: Throwable) {
                out.recycle()
                gain?.first?.recycle()
                throw failure
            }
        }

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

        /**
         * Whether a photo in [cs] should open in Display P3 rather than sRGB.
         * Judged by primaries, not object identity: an ICC-tagged P3 JPEG or a
         * HEIC with nclx P3 (Xiaomi) decodes to a ColorSpace.Rgb that is not
         * the named DISPLAY_P3 instance, and was converted to sRGB before.
         * A wider space (BT.2020 HDR HEIC, Adobe RGB, DCI-P3) also goes to P3:
         * the engine takes only sRGB and P3, and P3 keeps more of it than sRGB.
         */
        fun wideSource(cs: ColorSpace?): Boolean {
            val rgb = cs as? ColorSpace.Rgb ?: return false
            return gamutArea(rgb.getPrimaries()) > SRGB_AREA * 1.05
        }

        // The xy triangle's area of [x0, y0, x1, y1, x2, y2] primaries.
        fun gamutArea(p: FloatArray): Double =
            kotlin.math.abs((p[2] - p[0]).toDouble() * (p[5] - p[1]) - (p[4] - p[0]).toDouble() * (p[3] - p[1])) / 2

        // sRGB 0.1121, Display P3 0.1520, BT.2020 0.2119.
        private val SRGB_AREA = gamutArea(floatArrayOf(0.640f, 0.330f, 0.300f, 0.600f, 0.150f, 0.060f))

        /** Scale that fits (w, h) inside [edge] on the long side and [maxPixels] in area; 0 = no limit. */
        fun fitScale(w: Int, h: Int, edge: Int, maxPixels: Long): Double {
            var s = 1.0
            if (edge > 0 && max(w, h) > edge) s = edge.toDouble() / max(w, h)
            if (maxPixels > 0 && w.toDouble() * h * s * s > maxPixels) s = sqrt(maxPixels.toDouble() / (w.toDouble() * h))
            return s
        }
    }
}

/**
 * A RAW as LibRaw opens it: [path] is either `/proc/self/fd/<fd>` of the held
 * descriptor or a cache copy. [close] releases whichever it is, once.
 */
class RawInput(val path: String, private val pfd: ParcelFileDescriptor?, private val copy: File?) : java.io.Closeable {
    val direct: Boolean get() = pfd != null
    override fun close() {
        runCatching { pfd?.close() }
        copy?.delete()
    }
}
