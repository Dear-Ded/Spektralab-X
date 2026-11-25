package com.dearded.spektralab.advisor.editor

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import com.dearded.spektralab.advisor.domain.BrightSide
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface CinematicRetryGoal {
    data object Contrast : CinematicRetryGoal
    data class Direction(val side: BrightSide) : CinematicRetryGoal
}

data class CinematicWhites(val width: Int, val height: Int, val output: String, val count: Long, val channelEnds: Long,
    private val retryEvidence: Pair<Double, Double>? = null) {
    fun permits(after: CinematicWhites): Boolean {
        if (width != after.width || height != after.height || output != after.output) return false
        val pixels = width.toLong() * height
        return after.count - count <= pixels / 1_000L &&
            after.channelEnds - channelEnds <= pixels / 200L
    }

    internal fun improves(after: CinematicWhites, goal: CinematicRetryGoal): Boolean {
        val (beforeFirst, beforeSecond) = checkNotNull(retryEvidence)
        val (afterFirst, afterSecond) = checkNotNull(after.retryEvidence)
        return when (goal) {
            CinematicRetryGoal.Contrast -> afterFirst - beforeFirst >= 2.0
            is CinematicRetryGoal.Direction -> afterFirst - beforeFirst >= 1.0 && beforeSecond - afterSecond >= 1.0
        }
    }

    companion object {
        private const val STRIPE_PIXELS = 32_768

        /** Caller must keep the bitmap alive and unchanged for the duration of the scan. */
        suspend fun measure(bitmap: Bitmap, output: String): CinematicWhites? = measure(bitmap, output, null)

        internal suspend fun measure(bitmap: Bitmap, output: String, goal: CinematicRetryGoal?): CinematicWhites? {
            val context = currentCoroutineContext()
            context.ensureActive()
            if (bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE) return null
            val expected = when (output) {
                "sRGB" -> ColorSpace.get(ColorSpace.Named.SRGB)
                "Display P3" -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
                else -> return null
            }
            if (bitmap.colorSpace != expected) return null

            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return null
            val stripeRows = (STRIPE_PIXELS / width).coerceIn(1, height)
            val native = if (output == "Display P3") {
                val config = bitmap.config
                if (config != Bitmap.Config.ARGB_8888 && config != Bitmap.Config.RGBA_F16) return null
                P3Channels(bitmap, stripeRows, config, expected)
            } else null
            return native.use { scan(bitmap, output, goal, context, stripeRows, it) }
        }

        private fun scan(bitmap: Bitmap, output: String, goal: CinematicRetryGoal?, context: CoroutineContext,
            stripeRows: Int, native: P3Channels?): CinematicWhites? {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * stripeRows)
            var count = 0L
            var channelEnds = 0L
            val histogram = if (goal == CinematicRetryGoal.Contrast) LongArray(256) else null
            val side = (goal as? CinematicRetryGoal.Direction)?.side
            val acrossRows = side == BrightSide.TOP || side == BrightSide.BOTTOM
            val brightFirst = side == BrightSide.LEFT || side == BrightSide.TOP
            val axisLength = if (acrossRows) height else width
            val band = maxOf(1, axisLength / 5)
            var firstBand = 0L
            var lastBand = 0L
            var y = 0
            while (y < height) {
                context.ensureActive()
                val rows = minOf(stripeRows, height - y)
                // Keep the existing sRGB white and luma statistics in both output spaces.
                bitmap.getPixels(pixels, 0, width, 0, y, width, rows)
                for (i in 0 until width * rows) {
                    val pixel = pixels[i]
                    if (pixel ushr 24 != 255) return null
                    if (pixel == Color.WHITE) count++
                    val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                    if (native == null && (atByteEnd(r) || atByteEnd(g) || atByteEnd(b))) channelEnds++
                    if (goal != null) {
                        val luma = (2126 * r + 7152 * g + 722 * b + 5000) / 10000
                        if (histogram != null) histogram[luma]++
                        else {
                            val position = if (acrossRows) y + i / width else i % width
                            if (position < band) firstBand += luma
                            if (position >= axisLength - band) lastBand += luma
                        }
                    }
                }
                if (native != null) channelEnds += native.count(bitmap, y, rows)
                y += rows
            }
            context.ensureActive()
            val evidence = when {
                histogram != null -> {
                    val total = width.toLong() * height
                    (percentile(histogram, total, 95) - percentile(histogram, total, 5)).toDouble() to 0.0
                }
                side != null -> {
                    val bandPixels = band.toDouble() * (if (acrossRows) width else height)
                    val first = firstBand / bandPixels
                    val last = lastBand / bandPixels
                    if (brightFirst) first to last else last to first
                }
                else -> null
            }
            return CinematicWhites(width, height, output, count, channelEnds, evidence)
        }

        private fun atByteEnd(value: Int) = value == 0 || value == 255

        private class P3Channels(source: Bitmap, stripeRows: Int, config: Bitmap.Config, space: ColorSpace) : AutoCloseable {
            private val width = source.width
            private val half = config == Bitmap.Config.RGBA_F16
            private val pixelBytes = if (half) 8 else 4
            private val stripe = Bitmap.createBitmap(width, stripeRows, config, true, space).apply { density = source.density }
            private val rowBytes = stripe.rowBytes
            private val canvas = Canvas(stripe)
            private val src = Rect()
            private val dst = Rect()
            private val paint = Paint().apply { blendMode = BlendMode.SRC }
            private val bytes = ByteBuffer.allocate(stripe.byteCount).order(ByteOrder.nativeOrder())

            fun count(source: Bitmap, y: Int, rows: Int): Long {
                // Copy unscaled into the same color space; buffer reads do not convert to sRGB.
                src.set(0, y, width, y + rows)
                dst.set(0, 0, width, rows)
                canvas.drawBitmap(source, src, dst, paint)
                bytes.rewind()
                stripe.copyPixelsToBuffer(bytes)
                var ends = 0L
                for (row in 0 until rows) for (x in 0 until width) {
                    val at = row * rowBytes + x * pixelBytes
                    if (half) {
                        if (atHalfEnd(at) || atHalfEnd(at + 2) || atHalfEnd(at + 4)) ends++
                    } else {
                        val rgb = bytes.getInt(at)
                        if (atByteEnd(rgb and 255) || atByteEnd(rgb ushr 8 and 255) || atByteEnd(rgb ushr 16 and 255)) ends++
                    }
                }
                return ends
            }

            private fun atHalfEnd(at: Int): Boolean {
                val code = bytes.getShort(at).toInt()
                return code and 0x7fff == 0 || code == 0x3c00
            }

            override fun close() = stripe.recycle()
        }

        private fun percentile(histogram: LongArray, total: Long, percent: Int): Int {
            var rank = (total * percent + 99) / 100
            for (code in histogram.indices) {
                rank -= histogram[code]
                if (rank <= 0) return code
            }
            return histogram.lastIndex
        }
    }
}
