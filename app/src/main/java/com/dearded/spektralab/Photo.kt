package com.dearded.spektralab

import android.graphics.Bitmap

/**
 * A decoded phone photo: the SDR picture in [space] ("sRGB" or "Display P3"),
 * stored as RGBA_F16 when its encoded SDR precision can be kept, otherwise ARGB_8888,
 * and, for an Ultra HDR file on Android 14+, its gain map and metadata.
 */
class Photo(
    val bitmap: Bitmap, val space: String, val gain: Bitmap? = null, val gainMeta: FloatArray? = null,
    /** Original dimensions after EXIF orientation, before the working-size decode. */
    val sourceWidth: Int = bitmap.width, val sourceHeight: Int = bitmap.height,
) {
    val hdr get() = gain != null && gainMeta != null
    val highPrecisionSdr get() = bitmap.config == Bitmap.Config.RGBA_F16
    /** Native F16 v1 alpha contract: opaque, premultiplied, or straight. */
    internal val alphaMode get() = if (!bitmap.hasAlpha()) 0 else if (bitmap.isPremultiplied) 1 else 2

    fun recycle() {
        bitmap.recycle()
        gain?.recycle()
    }
}

internal enum class PhotoInputRoute { RGBA8, F16_SDR_V1, GAINMAP }

internal fun Photo.inputRoute(highlightStops: Float): PhotoInputRoute = when {
    hdr && highlightStops > 0f -> PhotoInputRoute.GAINMAP
    highPrecisionSdr -> PhotoInputRoute.F16_SDR_V1
    else -> PhotoInputRoute.RGBA8
}

/** The handle and whether highlights were recovered (gain map or highlight stops). */
data class OpenedPhoto(val handle: Long, val linear: Boolean)

/**
 * Opens [photo] on [engine] and recycles its bitmaps. The pixels leave the
 * photo's space natively and the engine opens on linear ProPhoto, as upstream's
 * desktop host does for every file (input_transform.hpp), so [params] is always
 * asked for ("ProPhoto RGB", linear), and so is anything that reopens the
 * session's source later (film cards). An HDR photo gets its gain map applied;
 * if that fails it falls back to the plain SDR open, so a malformed gain map
 * never costs the photo. A photo without a gain map gets [highlightStops] of
 * highlight recovery; 0 is the picture as encoded, what the desktop app hands
 * the engine. Engine thread only.
 */
fun openPhotoSession(engine: Long, photo: Photo, highlightStops: Float, params: (String, Boolean) -> String): OpenedPhoto {
    try {
        val opening = params(INPUT_SPACE, true)
        val route = photo.inputRoute(highlightStops)
        if (route == PhotoInputRoute.GAINMAP) {
            val h = NativeEngine.openBitmapHdr(engine, photo.bitmap, photo.gain!!, photo.gainMeta!!, photo.space, opening,
                photo.sourceWidth, photo.sourceHeight)
            if (h != 0L) return OpenedPhoto(h, true)
        }
        if (route == PhotoInputRoute.F16_SDR_V1) {
            return OpenedPhoto(NativeEngine.openBitmapF16V1(engine, photo.bitmap, photo.space, opening,
                highlightStops, photo.alphaMode, photo.sourceWidth, photo.sourceHeight), highlightStops > 0f)
        }
        return OpenedPhoto(NativeEngine.openBitmap(engine, photo.bitmap, photo.space, opening, highlightStops,
            photo.sourceWidth, photo.sourceHeight), highlightStops > 0f)
    } finally {
        photo.recycle()
    }
}

/** The engine's input space for every opened photo: linear ProPhoto, as for a RAW. */
const val INPUT_SPACE = "ProPhoto RGB"

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
