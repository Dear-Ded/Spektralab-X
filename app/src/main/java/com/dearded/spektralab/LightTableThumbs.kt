package com.dearded.spektralab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * The light table's thumbnails: the system's own (ContentResolver.loadThumbnail,
 * EXIF orientation applied), else a RAW's embedded preview, else a sampled
 * decode. Nothing here touches the engine: opening the light table develops
 * nothing (upstream once spent 7 s and 363 MB doing that on a folder open).
 * Requests are cancelled when their cell scrolls away.
 */
object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(
        min(Runtime.getRuntime().maxMemory() / 8, 48L shl 20).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    // A few at a time: a provider answers one thumbnail faster than ten at once.
    private val permits = Semaphore(4)

    /** Requests are rounded up to a few sizes, so the cache hits across 2 to 6 columns. */
    fun bucket(px: Int) = when {
        px <= 256 -> 256
        px <= 512 -> 512
        px <= 1024 -> 1024
        else -> 2048
    }

    private fun key(uri: String, bucket: Int) = "$bucket|$uri"

    fun cached(uri: String, px: Int): Bitmap? = cache.get(key(uri, bucket(px)))

    fun clear() = cache.evictAll()

    suspend fun load(context: Context, frame: Frame, px: Int): Bitmap? {
        val b = bucket(px)
        val k = key(frame.uri, b)
        cache.get(k)?.let { return it }
        return permits.withPermit {
            cache.get(k) ?: withContext(Dispatchers.IO) { decode(context.applicationContext, frame, b) }?.also { cache.put(k, it) }
        }
    }

    private suspend fun decode(context: Context, frame: Frame, px: Int): Bitmap? {
        val uri = Uri.parse(frame.uri)
        val r = context.contentResolver
        val signal = CancellationSignal()
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { signal.cancel() }
        try {
            runCatching { return r.loadThumbnail(uri, Size(px, px), signal) }
            if (signal.isCanceled) return null
            // Most RAWs carry a full-size JPEG preview that ExifInterface can hand over.
            if (frame.raw) {
                runCatching {
                    r.openFileDescriptor(uri, "r")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)
                        exif.thumbnailBitmap?.let { return fit(rotate(it, exif.rotationDegrees), px) }
                    }
                }
            }
            if (signal.isCanceled) return null
            return runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(r, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val short = min(info.size.width, info.size.height)
                    decoder.setTargetSampleSize(max(1, short / px))
                }
            }.getOrNull()?.let { fit(it, px) }
        } finally {
            handle?.dispose()
        }
    }

    private fun rotate(b: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return b
        val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    // The long edge at most twice the request: enough for a centre crop, no more.
    private fun fit(b: Bitmap, px: Int): Bitmap {
        val long = max(b.width, b.height)
        if (long <= px * 2) return b
        val s = px * 2f / long
        return Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true)
    }
}

/** [frame]'s thumbnail at about [px], null until it arrives; the load stops when the caller leaves. */
@Composable
fun rememberThumbnail(frame: Frame, px: Int): State<Bitmap?> {
    val context = LocalContext.current
    return produceState(Thumbnails.cached(frame.uri, px), frame.uri, Thumbnails.bucket(px)) {
        if (value == null) value = Thumbnails.load(context, frame, px)
    }
}
