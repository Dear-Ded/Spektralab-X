package com.dearded.spektralab

/**
 * Retry below the estimated effective area, rather than a device cap which may
 * already exceed the source. For SHARE, outPixels estimates the share output,
 * not the larger working source opened before rendering; it is only a sizing
 * hint here, not a prediction of decode memory or the final framed dimensions.
 * Unknown output area retains the existing cap-only fallback (or no cap).
 */
internal fun exportRetryPixelCap(maxPixels: Long, outPixels: Long): Long {
    val pixels = when {
        outPixels > 0 && maxPixels > 0 -> minOf(outPixels, maxPixels)
        outPixels > 0 -> outPixels
        maxPixels > 0 -> maxPixels
        else -> return 0L
    }
    // Divide first to avoid overflow; a positive cap must not become unlimited.
    return (pixels / 10 * 6 + pixels % 10 * 6 / 10).coerceAtLeast(1L)
}
