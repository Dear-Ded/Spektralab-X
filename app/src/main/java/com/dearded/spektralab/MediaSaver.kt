package com.dearded.spektralab

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes files into the gallery so that a kill at any moment leaves either a
 * complete, verified file or nothing visible:
 *
 *  1. the file is encoded and checked first (a `.part` in cache, or written
 *     straight into an entry that stays IS_PENDING=1, which the gallery hides);
 *  2. a pending entry gets the bytes and is checked again;
 *  3. only then is IS_PENDING cleared.
 *
 * A pending entry a killed process left behind is removed at the next start
 * ([sweepPending], ProcessingQueue); MediaStore itself expires them after a week.
 */
object MediaSaver {
    const val FOLDER = "Spektralab-X"
    /** Every name this app has saved under, before and after the rename. */
    private val PREFIXES = listOf("Spektralab-X_", "SpektraLab_")

    enum class State { GONE, PENDING, PUBLISHED }

    /** A new pending entry for [name]. */
    fun create(context: Context, name: String, mime: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        // Some builds refuse TIFF in the images collection; Downloads takes any type.
        return runCatching {
            resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        }.getOrNull() ?: run {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$FOLDER")
            resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } ?: throw EngineException("无法在相册中创建文件")
    }

    /** Makes a checked entry visible. */
    fun publish(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (context.contentResolver.update(uri, values, null, null) != 1) throw EngineException("无法发布到相册")
    }

    /** Whether [uri] is still pending, already published, or gone, with its display name. */
    fun state(context: Context, uri: Uri): Pair<State, String> {
        val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DISPLAY_NAME)
        val cursor: Cursor? = if (Build.VERSION.SDK_INT >= 30) {
            val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }
            context.contentResolver.query(uri, projection, args, null)
        } else {
            @Suppress("DEPRECATION")
            context.contentResolver.query(MediaStore.setIncludePending(uri), projection, null, null, null)
        }
        cursor?.use { c ->
            if (c.moveToFirst()) return (if (c.getInt(0) != 0) State.PENDING else State.PUBLISHED) to c.getString(1).orEmpty()
        }
        return State.GONE to ""
    }

    /**
     * Deletes [uri] only while it is still pending: a kill between publishing
     * and recording it must not delete a finished photo. Never throws.
     */
    fun discardIfPending(context: Context, uri: Uri): State {
        val (state, _) = runCatching { state(context, uri) }.getOrElse { return State.GONE }
        if (state == State.PENDING) runCatching { context.contentResolver.delete(uri, null, null) }
        return state
    }

    /**
     * Copies a verified [file] into a new entry, checks the copy's size and
     * publishes it. [onCreated] hears the pending entry before any byte is
     * written, so a kill from then on can be cleaned up.
     */
    fun saveFile(context: Context, file: File, name: String, mime: String, onCreated: (Uri) -> Unit = {}): Uri {
        val resolver = context.contentResolver
        val uri = create(context, name, mime)
        try {
            onCreated(uri)
            resolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
                ?: throw EngineException("无法写入文件")
            val written = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            if (written != file.length()) throw EngineException("写入相册不完整（$written / ${file.length()} 字节）")
            publish(context, uri)
            return uri
        } catch (e: Throwable) {
            discardIfPending(context, uri)
            throw e
        }
    }

    /** Writes one file into the gallery through [write], pending until it is complete. */
    fun save(context: Context, name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        val resolver = context.contentResolver
        val uri = create(context, name, mime)
        try {
            resolver.openOutputStream(uri, "w")?.use(write) ?: throw EngineException("无法写入文件")
            val written = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            if (written <= 0L) throw EngineException("写入相册失败：文件为空")
            publish(context, uri)
        } catch (e: Throwable) {
            discardIfPending(context, uri)
            throw e
        }
    }

    /** Removes every pending entry of ours (a killed export's); returns how many. Never throws. */
    fun sweepPending(context: Context): Int {
        var removed = 0
        for (collection in listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )) runCatching {
            val selection = PREFIXES.joinToString(" OR ") { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" }
            val args = PREFIXES.map { "$it%" }.toTypedArray()
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            // MediaStore lists only the calling app's own pending entries, so these are ours.
            val cursor = if (Build.VERSION.SDK_INT >= 30) {
                context.contentResolver.query(collection, projection, Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                }, null)
            } else {
                @Suppress("DEPRECATION")
                context.contentResolver.query(MediaStore.setIncludePending(collection), projection,
                    "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ($selection)", args, null)
            }
            val ids = ArrayList<Long>()
            cursor?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
            for (id in ids) {
                if (runCatching { context.contentResolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
                        .getOrDefault(0) > 0) removed++
            }
        }
        return removed
    }

    /**
     * A JPEG is complete when its header gives the expected size and it ends
     * with the end-of-image marker (a cut-off file decodes to grey rows).
     */
    fun checkJpeg(file: File, width: Int, height: Int) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth != width || opts.outHeight != height) {
            throw EngineException("JPEG 校验失败：${opts.outWidth}×${opts.outHeight}，应为 ${width}×$height")
        }
        val tail = RandomAccessFile(file, "r").use { f ->
            if (f.length() < 4) null else ByteArray(2).also { f.seek(f.length() - 2); f.readFully(it) }
        }
        if (tail == null || tail[0] != 0xFF.toByte() || tail[1] != 0xD9.toByte()) throw EngineException("JPEG 校验失败：文件不完整")
    }

    /**
     * A HEIF is complete when it opens with an ftyp box of a still-image brand
     * (heic, heix, mif1) and the platform decoder reports the expected size.
     */
    fun checkHeif(file: File, width: Int, height: Int) {
        val head = RandomAccessFile(file, "r").use { f ->
            if (f.length() < 12) null else ByteArray(12).also { f.readFully(it) }
        } ?: throw EngineException("HEIF 校验失败：文件不完整")
        val box = String(head, 4, 4, Charsets.US_ASCII)
        val brand = String(head, 8, 4, Charsets.US_ASCII)
        if (box != "ftyp" || brand !in setOf("heic", "heix", "mif1")) throw EngineException("HEIF 校验失败：不是 HEIF 文件（$box $brand）")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth != width || opts.outHeight != height) {
            throw EngineException("HEIF 校验失败：${opts.outWidth}×${opts.outHeight}，应为 ${width}×$height")
        }
    }

    /**
     * The TIFF tiff16.cpp writes, read back from the entry: the header points
     * to a directory (patched last, so a cut-off file has none), it is RGB16,
     * the strips cover the frame, and every strip inflates to exactly its rows.
     * Uncompressed strips (older builds) are checked for size only.
     */
    fun checkTiff(context: Context, uri: Uri) {
        fun bad(why: String): Nothing = throw EngineException("TIFF 校验失败：$why")
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: bad("无法读取")
        pfd.use {
            // Not closed itself: the descriptor belongs to pfd.
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val size = channel.size()
            fun read(at: Long, n: Int): ByteBuffer {
                if (at < 0 || n < 0 || at + n > size) bad("文件不完整（$size 字节）")
                val buf = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
                while (buf.hasRemaining()) if (channel.read(buf, at + buf.position()) < 0) bad("读取中断")
                buf.flip()
                return buf
            }
            val head = read(0, 8)
            if (head.get(0) != 'I'.code.toByte() || head.get(1) != 'I'.code.toByte() || head.getShort(2).toInt() != 42) bad("文件头")
            val ifd = head.getInt(4).toLong() and 0xFFFFFFFFL
            if (ifd < 8) bad("目录缺失，文件未写完")
            val entries = read(ifd, 2).getShort(0).toInt() and 0xFFFF
            val dir = read(ifd + 2, entries * 12)
            val tags = HashMap<Int, LongArray>()
            for (i in 0 until entries) {
                val at = i * 12
                val tag = dir.getShort(at).toInt() and 0xFFFF
                val type = dir.getShort(at + 2).toInt()
                if (type != 3 && type != 4) continue
                val count = dir.getInt(at + 4).toLong() and 0xFFFFFFFFL
                if (count > 1_000_000) bad("目录项 $tag 过大")
                val unit = if (type == 3) 2 else 4
                val bytes = (count * unit).toInt()
                val data = if (bytes <= 4) {
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).also { b -> for (k in 0 until 4) b.put(k, dir.get(at + 8 + k)) }
                } else {
                    read(dir.getInt(at + 8).toLong() and 0xFFFFFFFFL, bytes)
                }
                tags[tag] = LongArray(count.toInt()) { k ->
                    if (type == 3) data.getShort(k * 2).toLong() and 0xFFFF else data.getInt(k * 4).toLong() and 0xFFFFFFFFL
                }
            }
            fun one(tag: Int): Long = tags[tag]?.singleOrNull() ?: bad("缺少标签 $tag")
            val width = one(256)
            val height = one(257)
            if (width <= 0 || height <= 0) bad("尺寸 ${width}×$height")
            if (tags[258]?.toList() != listOf(16L, 16L, 16L) || one(277) != 3L) bad("不是 16 位 RGB")
            val compression = one(259)
            val rows = tags[278]?.singleOrNull() ?: height
            val offsets = tags[273] ?: bad("缺少数据位置")
            val counts = tags[279] ?: bad("缺少数据长度")
            if (rows <= 0 || offsets.size.toLong() != (height + rows - 1) / rows || counts.size != offsets.size) bad("分条信息不一致")
            val rowBytes = width * 6
            val stripBytes = rowBytes * minOf(rows, height)
            if (stripBytes >= Int.MAX_VALUE) bad("分条过大")
            // One byte spare, so a strip that inflates to more than its rows is caught.
            val out = ByteArray(stripBytes.toInt() + 1)
            val inflater = java.util.zip.Inflater()
            try {
                for (s in offsets.indices) {
                    val expect = rowBytes * minOf(rows, height - s * rows)
                    when (compression) {
                        1L -> if (counts[s] != expect || offsets[s] + counts[s] > size) bad("第 ${s + 1} 条不完整")
                        8L, 32946L -> {
                            val input = read(offsets[s], counts[s].toInt())
                            inflater.reset()
                            inflater.setInput(input.array(), 0, input.limit())
                            var got = 0
                            while (!inflater.finished() && got < out.size) {
                                val k = inflater.inflate(out, got, out.size - got)
                                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                                got += k
                            }
                            if (!inflater.finished() || got.toLong() != expect) bad("第 ${s + 1} 条数据损坏")
                        }
                        else -> bad("压缩方式 $compression")
                    }
                }
            } catch (e: java.util.zip.DataFormatException) {
                bad("数据损坏")
            } finally {
                inflater.end()
            }
        }
    }
}
