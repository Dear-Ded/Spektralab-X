package com.dearded.spektralab

import android.graphics.Bitmap

/**
 * The JNI surface in `spektralab_jni.cpp`. Handles are raw native pointers.
 * One session is used on one thread only: the editor's on [EngineThread], an
 * export's on [ExportThread]. lastError, lastWasCancelled and
 * lastWasDeviceLost read a thread-local string, so they are called on the
 * thread of the call that failed. Engine-level calls (create, destroy,
 * capabilities, trimMemory, warmUp) run on EngineThread only.
 */
object NativeEngine {
    init {
        System.loadLibrary("spektralab_jni")
    }

    external fun lastError(): String
    external fun deviceInfo(): String

    /**
     * The storage-buffer size, in bytes up to [ceiling], whose far writes were
     * measured to land on a throwaway VkDevice; 0 when even 128 MiB failed
     * (lastError has the log). Slow: EngineHost runs it once per driver.
     */
    external fun probeStorageRange(ceiling: Long): Long

    external fun create(resourcesDir: String, tmpDir: String): Long
    external fun destroy(engine: Long)
    external fun capabilities(engine: Long): String

    /**
     * Decodes a RAW file and opens a session on it, downscaled to fit both limits (0 = none).
     * [temperatureK] 0 decodes with the camera's as-shot white balance.
     */
    external fun openRaw(
        engine: Long, path: String, maxEdge: Int, maxPixels: Long, paramsJson: String,
        temperatureK: Double, tint: Double,
    ): Long

    /**
     * Opens a session on an ARGB_8888 bitmap in [space] ("sRGB" or "Display P3").
     * The pixels are converted to linear ProPhoto natively, as upstream's host
     * does, so [paramsJson] must say input_color_space "ProPhoto RGB" and
     * input_cctf_decoding false. [headroomStops] > 0 undoes the phone's
     * highlight shoulder so encoded white lands that many stops above 1.0.
     */
    external fun openBitmap(engine: Long, bitmap: Bitmap, space: String, paramsJson: String, headroomStops: Float): Long

    /**
     * Opens an Ultra HDR photo: [bitmap] (the SDR base, ARGB_8888, in [space])
     * recovered with its gain map [gain] (ALPHA_8 or ARGB_8888) to linear light
     * at full boost, then converted to linear ProPhoto. [meta] is ratioMin,
     * ratioMax, gamma, epsilonSdr, epsilonHdr, 3 each. [paramsJson] as for openBitmap.
     */
    external fun openBitmapHdr(engine: Long, bitmap: Bitmap, gain: Bitmap, meta: FloatArray, space: String, paramsJson: String): Long

    /** A small sRGB thumbnail of the frame before any film was applied. */
    external fun beforeBitmap(session: Long): Bitmap?

    /** The photo at 384 px through [paramsJson] (complete opening params), in a throwaway session. */
    external fun filmPreview(session: Long, paramsJson: String): Bitmap?

    external fun sourceInfo(session: Long): String
    external fun setParams(session: Long, deltaJson: String): String?
    external fun getParams(session: Long): String?

    /** Tier is "live", "preview" or "full". */
    external fun renderBitmap(session: Long, tier: String): Bitmap?

    /**
     * [renderBitmap] for an export: null (lastError "export check: ...") when
     * the frame is flat black or white, which only a failed render produces.
     */
    external fun exportBitmap(session: Long, tier: String): Bitmap?

    /**
     * [bitmap] (ARGB_8888) as a 4:4:4 JPEG at [quality] with optimised
     * Huffman tables, [icc] embedded when not null (libjpeg-turbo). Any thread,
     * no session; false with lastError (read on the same thread) on failure.
     */
    external fun writeJpeg444(bitmap: Bitmap, path: String, quality: Int, icc: ByteArray?): Boolean

    /** Renders [tier] into [reuse] when it is the same size, else into a new bitmap; null on failure. */
    external fun renderReuse(session: Long, tier: String, reuse: Bitmap?): Bitmap?

    /** Renders [tier] ("full" or "preview") and writes an RGB16 TIFF to [path]. */
    external fun exportTiff(session: Long, path: String, iccPath: String, tier: String): Boolean

    /** [exportTiff] into an open writable descriptor (a pending MediaStore entry); the caller closes it. */
    external fun exportTiffFd(session: Long, fd: Int, iccPath: String, tier: String): Boolean

    external fun release(session: Long)

    /** Layer 2 for the session's display and export packs (Look.values()); null clears it. */
    external fun setLook(session: Long, values: FloatArray?)

    /**
     * The user LUT (lut3d.hpp): [side]^3 RGB over encoded ProPhoto, red fastest,
     * as CubeLut.kt's LutBake makes it; mixed in at [strength] 0..1 before every
     * Layer 2 slider and kept across setLook. Null or strength 0 clears it;
     * false when the table was refused.
     */
    external fun setLut(session: Long, values: FloatArray?, side: Int, strength: Float): Boolean

    /** The last display render again, through the current look, into [reuse] if it fits. */
    external fun repack(session: Long, reuse: Bitmap?): Bitmap?

    /**
     * Where the session's pixels go: [target] "sRGB" or "Display P3", for the
     * screen (matrix and clip) or, [exporting], a file (CAM16 chroma
     * compression into the space). Engine thread.
     */
    external fun setOutput(session: Long, target: String, exporting: Boolean): Boolean

    /** Any thread: stops the session's in-flight render at the next node. */
    external fun cancel(session: Long)
    external fun warmUp(engine: Long, film: String, paper: String): Boolean
    external fun trimMemory(engine: Long): Long
    external fun lastWasCancelled(): Boolean

    /** True when the last failure lost the GPU device (VkResult -4): only a new engine helps (EngineHost.recover). */
    external fun lastWasDeviceLost(): Boolean
}
