package com.dearded.spektralab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/**
 * The light table's film thumbnails: a frame's small system thumbnail
 * developed through an edit (its own sidecar, or a style to compare). This
 * is the low-resolution path of docs/UI.md 2.9: one small session per
 * thumbnail, one engine-thread hop each, and only while nothing else wants
 * the engine (no finger on the editor for 1.5 s, no export running, the
 * phone not hot). The nearest request goes first; a request whose cell has
 * left the screen is dropped before it is developed.
 *
 * A RAW's thumbnail is its embedded camera JPEG, so its film thumbnail is an
 * approximation of the frame the editor develops from the raw data.
 */
object FilmThumbs {
    private class Waiter(val priority: () -> Int)

    private class Request(val key: String, val frame: Frame, val edit: Edit, val px: Int) {
        val waiters = ArrayList<Waiter>()
        val done = CompletableDeferred<Bitmap?>()
        var running = false
    }

    private val lock = Any()
    private val pending = LinkedHashMap<String, Request>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private val memory = object : LruCache<String, Bitmap>(
        min(Runtime.getRuntime().maxMemory() / 10, 32L shl 20).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private const val IDLE_MS = 1500L
    private const val HOT_HEADROOM = 0.85f
    private const val DISK_CAP = 64L shl 20

    /** What a film thumbnail shows: the film's colour and the frame's geometry, never the border or date. */
    private fun bare(edit: Edit) = edit.copy(filmEdge = false, dateImprint = false, frame = FrameSpec(), borderId = "none")

    // Small on purpose: the engine develops at most this long edge per thumbnail.
    private fun sizeFor(px: Int) = if (px <= 256) 256 else 512

    private fun keyOf(frame: Frame, edit: Edit, px: Int): String {
        val text = "${frame.id}|$px|${edit.toJson()}|${edit.look.toJson()}"
        return MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    fun clear() = memory.evictAll()

    /**
     * [frame] through [edit] at about [px], or null if it could not be
     * developed. Suspends until the engine is idle; [priority] is read each
     * time the worker chooses (lower first), so it can follow the screen.
     */
    suspend fun get(context: Context, frame: Frame, edit: Edit, px: Int, priority: () -> Int): Bitmap? {
        val size = sizeFor(px)
        val e = bare(edit)
        val key = keyOf(frame, e, size)
        memory.get(key)?.let { return it }
        val app = context.applicationContext
        withContext(Dispatchers.IO) { readDisk(app, key) }?.let { memory.put(key, it); return it }
        val waiter = Waiter(priority)
        val req = synchronized(lock) {
            pending.getOrPut(key) { Request(key, frame, e, size) }.also { it.waiters += waiter }
        }
        ensureWorker(app)
        wake.trySend(Unit)
        try {
            return req.done.await()
        } finally {
            synchronized(lock) {
                req.waiters -= waiter
                if (req.waiters.isEmpty() && !req.running && !req.done.isCompleted) pending.remove(key)
            }
        }
    }

    private fun ensureWorker(app: Context) {
        val host = (app as SpektraLabApp).host
        synchronized(lock) {
            if (worker?.isActive == true) return
            worker = host.scope.launch { loop(app, host) }
        }
    }

    private suspend fun loop(app: Context, host: EngineHost) {
        while (true) {
            val next = synchronized(lock) {
                pending.values.filter { !it.running && it.waiters.isNotEmpty() }
                    .minByOrNull { r -> r.waiters.minOf { w -> runCatching { w.priority() }.getOrDefault(Int.MAX_VALUE) } }
                    ?.also { it.running = true }
            }
            if (next == null) { wake.receive(); continue }
            waitForIdle(host)
            val wanted = synchronized(lock) { next.waiters.isNotEmpty() }
            val bitmap = if (wanted) {
                try { develop(app, host, next) } catch (e: Throwable) { null }
            } else null
            synchronized(lock) { pending.remove(next.key) }
            if (bitmap != null) memory.put(next.key, bitmap)
            next.done.complete(bitmap)
        }
    }

    /** Behind the editor and the export: thumbnails are optional, the photo being worked on is not. */
    private suspend fun waitForIdle(host: EngineHost) {
        while (true) {
            val now = android.os.SystemClock.uptimeMillis()
            val busy = now - host.lastInteraction < IDLE_MS ||
                host.queue.jobs.value.any { it.state == JobState.RUNNING } ||
                host.thermal.headroom() > HOT_HEADROOM
            if (!busy) return
            delay(250)
        }
    }

    private suspend fun develop(app: Context, host: EngineHost, r: Request): Bitmap? {
        val thumb = Thumbnails.load(app, r.frame, r.px) ?: return null
        // Our own software copy at the request's size: the cached thumbnail is shared,
        // and the session takes (and recycles) the bitmap it opens on.
        val copy = withContext(Dispatchers.Default) {
            val long = max(thumb.width, thumb.height)
            val scaled = if (long > r.px) {
                val s = r.px.toFloat() / long
                Bitmap.createScaledBitmap(thumb, max(1, (thumb.width * s).toInt()), max(1, (thumb.height * s).toInt()), true)
            } else thumb
            val soft = scaled.copy(Bitmap.Config.ARGB_8888, false)
            if (scaled !== thumb) scaled.recycle()
            soft
        } ?: return null
        val space = if (copy.colorSpace == ColorSpace.get(ColorSpace.Named.DISPLAY_P3)) "Display P3" else "sRGB"
        val ready = host.start(r.edit.film, r.edit.paper)
        val edit = ready.catalog.normalize(r.edit)
        val out = withContext(EngineThread.dispatcher) {
            val opened = openPhotoSession(ready.engine, Photo(copy, space), 0f) { s, linear ->
                Edit.openingParams(edit, inputSpace = s, linear = linear).toString()
            }
            val h = opened.handle
            if (h == 0L) return@withContext null
            try {
                NativeEngine.setOutput(h, DisplayColor.space, false)
                if (!edit.look.neutral) NativeEngine.setLook(h, edit.look.values())
                NativeEngine.renderBitmap(h, "full")?.also(DisplayColor::tag)
            } finally {
                NativeEngine.release(h)
            }
        }
        if (out != null) withContext(Dispatchers.IO) { writeDisk(app, r.key, out) }
        return out
    }

    // --- disk cache: cache/FilmThumbs, oldest dropped past DISK_CAP -----------------

    private fun dir(app: Context) = File(app.cacheDir, "FilmThumbs")

    private fun readDisk(app: Context, key: String): Bitmap? = runCatching {
        val f = File(dir(app), "$key.jpg")
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis())
        BitmapFactory.decodeFile(f.absolutePath)?.also(DisplayColor::tag)
    }.getOrNull()

    private fun writeDisk(app: Context, key: String, b: Bitmap) {
        runCatching {
            val d = dir(app).apply { mkdirs() }
            val part = File(d, "$key.part")
            part.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            part.renameTo(File(d, "$key.jpg"))
            val files = d.listFiles { f -> f.extension == "jpg" }.orEmpty().sortedByDescending { it.lastModified() }
            var total = 0L
            for (f in files) { total += f.length(); if (total > DISK_CAP) f.delete() }
        }
    }
}

/** [frame] through [edit] (null = none wanted), developed when the engine is idle; null until then. */
@Composable
fun rememberFilmThumb(frame: Frame, edit: Edit?, px: Int, priority: () -> Int): State<Bitmap?> {
    val context = LocalContext.current
    val currentPriority by rememberUpdatedState(priority)
    return produceState<Bitmap?>(null, frame.id, frame.uri, edit, px) {
        value = null
        if (edit != null) value = FilmThumbs.get(context, frame, edit, px) { currentPriority() }
    }
}

/** [frame]'s sidecar edit, read off the main thread; null while reading or when it has none. */
@Composable
fun rememberSidecarEdit(store: FrameStore, frame: Frame): State<Edit?> =
    produceState<Edit?>(null, frame.id, frame.editedAt) {
        value = if (!frame.edited) null else withContext(Dispatchers.IO) { store.sidecar(frame.id)?.edit }
    }
