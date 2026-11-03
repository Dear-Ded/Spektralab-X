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
     * The TIFF tiff16.cpp writes: the header parses, it is RGB16 at some size,
     * and the entry holds every byte of the strip the header points to.
     */
    fun checkTiff(context: Context, uri: Uri) {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val head = ByteArray(512)
            val n = FileInputStream(pfd.fileDescriptor).use { s -> s.read(head) }
            val b = ByteBuffer.wrap(head, 0, maxOf(0, n)).order(ByteOrder.LITTLE_ENDIAN)
            fun bad(why: String): Nothing = throw EngineException("TIFF 校验失败：$why")
            if (n < 16 || head[0] != 'I'.code.toByte() || head[1] != 'I'.code.toByte() || b.getShort(2).toInt() != 42) bad("文件头")
            val ifd = b.getInt(4)
            if (ifd < 8 || ifd + 2 > n) bad("目录位置")
            val entries = b.getShort(ifd).toInt() and 0xFFFF
            if (ifd + 2 + entries * 12 > n) bad("目录不完整")
            var width = 0L; var height = 0L; var offset = 0L; var count = 0L
            for (i in 0 until entries) {
                val at = ifd + 2 + i * 12
                val tag = b.getShort(at).toInt() and 0xFFFF
                val type = b.getShort(at + 2).toInt()
                val value = if (type == 3) (b.getShort(at + 8).toLong() and 0xFFFF) else (b.getInt(at + 8).toLong() and 0xFFFFFFFFL)
                when (tag) {
                    256 -> width = value
                    257 -> height = value
                    273 -> offset = value
                    279 -> count = value
                }
            }
            if (width <= 0 || height <= 0 || count != width * height * 6) bad("尺寸 ${width}×$height，数据 $count 字节")
            if (pfd.statSize < offset + count) bad("文件不完整（${pfd.statSize} / ${offset + count} 字节）")
        } ?: throw EngineException("TIFF 校验失败：无法读取")
    }
}
