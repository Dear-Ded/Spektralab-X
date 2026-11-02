package com.dearded.spektralab

import android.graphics.Bitmap

/**
 * A decoded phone photo: the SDR picture in [space] ("sRGB" or "Display P3"),
 * and, for an Ultra HDR file on Android 14+, its gain map and metadata.
 */
class Photo(val bitmap: Bitmap, val space: String, val gain: Bitmap? = null, val gainMeta: FloatArray? = null) {
    val hdr get() = gain != null && gainMeta != null

    fun recycle() {
        bitmap.recycle()
        gain?.recycle()
    }
}

/** The handle and whether the session was opened on linear (HDR) values. */
data class OpenedPhoto(val handle: Long, val linear: Boolean)

/**
 * Opens [photo] on [engine] and recycles its bitmaps. An HDR photo is opened
 * linear with its gain map applied; if that fails it falls back to the plain
 * SDR open, so a malformed gain map never costs the photo. A photo without a
 * gain map gets [highlightStops] of highlight recovery (0 = as encoded).
 * Engine thread only. [params] builds the opening JSON for (input space, linear).
 */
fun openPhotoSession(engine: Long, photo: Photo, highlightStops: Float, params: (String, Boolean) -> String): OpenedPhoto {
    try {
        if (photo.hdr) {
            val h = NativeEngine.openBitmapHdr(engine, photo.bitmap, photo.gain!!, photo.gainMeta!!, params(photo.space, true))
            if (h != 0L) return OpenedPhoto(h, true)
        }
        val linear = highlightStops > 0f
        return OpenedPhoto(NativeEngine.openBitmap(engine, photo.bitmap, params(photo.space, linear), highlightStops), linear)
    } finally {
        photo.recycle()
    }
}

/** Android 14's Gainmap API, kept in its own class so older devices never load it. */
@androidx.annotation.RequiresApi(34)
object GainmapReader {
    fun read(bitmap: Bitmap): Pair<Bitmap, FloatArray>? = runCatching {
        val g = bitmap.gainmap ?: return null
        var contents = g.gainmapContents
        if (contents.config == Bitmap.Config.HARDWARE || (contents.config != Bitmap.Config.ALPHA_8 &&
                contents.config != Bitmap.Config.ARGB_8888)) {
            contents = contents.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            // Our own copy: the base bitmap may be recycled or rescaled first.
            contents = contents.copy(contents.config!!, false) ?: return null
        }
        val meta = FloatArray(15)
        g.ratioMin.copyInto(meta, 0)
        g.ratioMax.copyInto(meta, 3)
        g.gamma.copyInto(meta, 6)
        g.epsilonSdr.copyInto(meta, 9)
        g.epsilonHdr.copyInto(meta, 12)
        contents to meta
    }.getOrNull()
}
