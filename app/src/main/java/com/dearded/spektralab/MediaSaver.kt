package com.dearded.spektralab

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore

/** Writes one file into the gallery, pending until it is complete. */
object MediaSaver {
    fun save(context: Context, name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SpektraLab")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        // Some builds refuse TIFF in the images collection; Downloads takes any type.
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        }.getOrNull() ?: run {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SpektraLab")
            resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } ?: throw EngineException("无法在相册中创建文件")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw EngineException("无法写入文件")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
