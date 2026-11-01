package com.dearded.spektralab

import android.graphics.Bitmap

/**
 * The JNI surface in `spektralab_jni.cpp`. Handles are raw native pointers.
 * Every call must come from [EngineThread]: the engine's last-error string is
 * thread-local, and one session is not safe to drive from two threads.
 */
object NativeEngine {
    init {
        System.loadLibrary("spektralab_jni")
    }

    external fun lastError(): String
    external fun deviceInfo(): String

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

    /** Opens a session on an sRGB ARGB_8888 bitmap. */
    external fun openBitmap(engine: Long, bitmap: Bitmap, paramsJson: String): Long

    /** A small sRGB thumbnail of the frame before any film was applied. */
    external fun beforeBitmap(session: Long): Bitmap?

    /** The photo at 384 px through [paramsJson] (complete opening params), in a throwaway session. */
    external fun filmPreview(session: Long, paramsJson: String): Bitmap?

    external fun sourceInfo(session: Long): String
    external fun setParams(session: Long, deltaJson: String): String?
    external fun getParams(session: Long): String?

    /** Tier is "live", "preview" or "full". */
    external fun renderBitmap(session: Long, tier: String): Bitmap?

    /** Renders [tier] ("full" or "preview") and writes an RGB16 TIFF to [path]. */
    external fun exportTiff(session: Long, path: String, iccPath: String, tier: String): Boolean

    external fun release(session: Long)
}
