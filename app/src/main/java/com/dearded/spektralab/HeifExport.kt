package com.dearded.spektralab

import android.graphics.Bitmap
import androidx.heifwriter.HeifWriter
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * HEIF · 全尺寸: the print as an 8-bit sRGB HEIC through androidx.heifwriter
 * (MediaCodec's HEVC encoder, 512 px grid tiles, so any size the encoder
 * takes in tiles fits). No ICC profile and no 10-bit: HeifWriter's bitmap
 * input has neither, so a wide-gamut print would be read as sRGB; the queue
 * only sends sRGB pixels here (tools/work/platform-export_research.md 1).
 *
 * EXIF cannot be written into a HEIF afterwards (ExifInterface saves JPEG,
 * PNG and WebP only), so the job's tags are written into a 1x1 JPEG by the
 * same [ExifCopy.applyTo] every JPEG export uses, and its APP1 block
 * ("Exif\0\0" + TIFF) is handed to the writer.
 */
object HeifExport {
    private const val QUALITY = 90
    /** HeifWriter.stop's wait, in ms: a full-size grid encode takes seconds, not minutes. */
    private const val STOP_TIMEOUT_MS = 180_000L

    /** IO. Encodes [bitmap] into [part] with [exif] (from [exifBlock]); throws on any failure. */
    fun encode(bitmap: Bitmap, part: File, exif: ByteArray?) {
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw EngineException("HEIF 编码失败：无法转换像素格式")
        try {
            val writer = try {
                HeifWriter.Builder(part.absolutePath, src.width, src.height, HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(QUALITY)
                    .setGridEnabled(true)
                    .setMaxImages(1)
                    .setPrimaryIndex(0)
                    .build()
            } catch (e: Exception) {
                throw EngineException("这台手机没有可用的 HEIF 编码器（${e.message ?: e.javaClass.simpleName}）")
            }
            try {
                writer.start()
                writer.addBitmap(src)
                if (exif != null) writer.addExifData(0, exif, 0, exif.size)
                writer.stop(STOP_TIMEOUT_MS)
            } catch (e: Exception) {
                throw EngineException("HEIF 编码失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                // HeifWriter is AutoCloseable; WriterBase.close itself is library-internal.
                (writer as AutoCloseable).close()
            }
        } finally {
            if (src !== bitmap) src.recycle()
        }
    }

    /** The EXIF block [tags] makes for a [width] x [height] sRGB image; null when there is none. */
    fun exifBlock(tags: ExifCopy, width: Int, height: Int, dir: File): ByteArray? {
        val tiny = File(dir, "exif-${System.nanoTime()}.jpg")
        return try {
            val one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                tiny.outputStream().use { one.compress(Bitmap.CompressFormat.JPEG, 50, it) }
            } finally {
                one.recycle()
            }
            tags.applyTo(tiny, width, height, wide = false)
            app1(tiny.readBytes())
        } catch (e: Exception) {
            null
        } finally {
            tiny.delete()
        }
    }

    /** The body of [jpeg]'s APP1 Exif segment ("Exif\0\0" and the TIFF that follows); null when it has none. */
    fun app1(jpeg: ByteArray): ByteArray? {
        val tag = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)   // "Exif\0\0"
        var i = 2
        while (i + 4 <= jpeg.size && (jpeg[i].toInt() and 0xFF) == 0xFF) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > jpeg.size) break
            val body = i + 4
            if (marker == 0xE1 && len >= 2 + tag.size && tag.indices.all { jpeg[body + it] == tag[it] }) {
                return ByteArrayOutputStream().apply { write(jpeg, body, len - 2) }.toByteArray()
            }
            i += 2 + len
        }
        return null
    }
}
