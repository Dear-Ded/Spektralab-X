package com.dearded.spektralab

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 64-bin R, G, B and luma counts of the shown print. Read from the preview
 * bitmap (encoded sRGB, what the user sees), sampled on a stride so a 3400 px
 * preview costs a few milliseconds.
 */
class Histogram private constructor(val r: IntArray, val g: IntArray, val b: IntArray, val y: IntArray) {
    /** Share of pixels with a channel at 0 or 255: clipped shadows / highlights. */
    var clippedLow = 0f; private set
    var clippedHigh = 0f; private set

    companion object {
        const val BINS = 64

        fun of(bitmap: Bitmap): Histogram? {
            if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) return null
            val w = bitmap.width
            val h = bitmap.height
            // About 250k samples whatever the size.
            val step = max(1, sqrt(w.toDouble() * h / 250_000.0).toInt())
            val r = IntArray(BINS); val g = IntArray(BINS); val b = IntArray(BINS); val y = IntArray(BINS)
            val row = IntArray(w)
            var low = 0; var high = 0; var n = 0
            var yy = 0
            while (yy < h) {
                bitmap.getPixels(row, 0, w, 0, yy, w, 1)
                var x = 0
                while (x < w) {
                    val p = row[x]
                    val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
                    r[pr shr 2]++; g[pg shr 2]++; b[pb shr 2]++
                    // Rec.709 weights on the encoded values: a display histogram, not a measurement.
                    y[((pr * 54 + pg * 183 + pb * 19) shr 8).coerceIn(0, 255) shr 2]++
                    if (pr == 0 || pg == 0 || pb == 0) low++
                    if (pr == 255 || pg == 255 || pb == 255) high++
                    n++
                    x += step
                }
                yy += step
            }
            return Histogram(r, g, b, y).also {
                if (n > 0) { it.clippedLow = low.toFloat() / n; it.clippedHigh = high.toFloat() / n }
            }
        }
    }
}

@Composable
fun HistogramView(h: Histogram, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Color.Black.copy(alpha = 0.55f), size = size)
        // Square-root scale so mid-tones stay visible next to a tall spike.
        val peak = max(1, maxOf(h.r.max(), h.g.max(), h.b.max(), h.y.max())).toFloat()
        val scale = sqrt(peak)
        val bw = size.width / Histogram.BINS
        fun curve(bins: IntArray, color: Color, mode: BlendMode) {
            val path = Path().apply {
                moveTo(0f, size.height)
                for (i in bins.indices) {
                    val top = size.height * (1f - sqrt(bins[i].toFloat()) / scale)
                    lineTo(i * bw, top); lineTo((i + 1) * bw, top)
                }
                lineTo(size.width, size.height); close()
            }
            drawPath(path, color, blendMode = mode)
        }
        curve(h.y, Color.White.copy(alpha = 0.35f), BlendMode.SrcOver)
        curve(h.r, Color(0x99FF3B30), BlendMode.Plus)
        curve(h.g, Color(0x9934C759), BlendMode.Plus)
        curve(h.b, Color(0x99007AFF), BlendMode.Plus)
        // Clipping marks: a red bar at either edge, as tall as the clipped share (x10, capped).
        val mark = Size(3f, 0f)
        if (h.clippedLow > 0.001f)
            drawRect(Color.Red, Offset(0f, size.height * (1 - minOf(1f, h.clippedLow * 10))),
                mark.copy(height = size.height * minOf(1f, h.clippedLow * 10)))
        if (h.clippedHigh > 0.001f)
            drawRect(Color.Red, Offset(size.width - 3f, size.height * (1 - minOf(1f, h.clippedHigh * 10))),
                mark.copy(height = size.height * minOf(1f, h.clippedHigh * 10)))
    }
}
