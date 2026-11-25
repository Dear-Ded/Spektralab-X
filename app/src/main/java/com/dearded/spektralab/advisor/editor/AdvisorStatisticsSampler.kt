package com.dearded.spektralab.advisor.editor

import android.graphics.Bitmap
import android.graphics.ColorSpace
import com.dearded.spektralab.advisor.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil

/** Call on the serial engine dispatcher so a pooled bitmap cannot be overwritten during sampling. */
object AdvisorStatisticsSampler {
    suspend fun sample(bitmap: Bitmap, output: String, sourceRevision: Long, sampleRevision: Long): AdviceStatistics? {
        if (bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE) return null
        val expected = when (output) {
            "sRGB" -> ColorSpace.get(ColorSpace.Named.SRGB)
            "Display P3" -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            else -> return null
        }
        if (bitmap.colorSpace != expected) return null
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        val width = minOf(128, sourceWidth)
        val height = minOf(128, sourceHeight)
        val total = width * height
        if (total == 0) return null
        val row = IntArray(sourceWidth)
        val luma = DoubleArray(total)
        val areaLuma = if (width >= 4 && height >= 4 && (width < sourceWidth || height < sourceHeight))
            DoubleArray(total) else null
        val areaRow = areaLuma?.let { DoubleArray(width) }
        val pointXs = IntArray(width) { center(it, sourceWidth, width) }
        val red = IntArray(16); val green = IntArray(16); val blue = IntArray(16); val coarse = IntArray(64)
        var black = 0; var white = 0
        var pointY = 0
        var nextPointY = center(0, sourceHeight, height)
        var areaOffset = 0
        var remainingY = sourceHeight
        var directionOpaque = true
        for (y in 0 until (if (areaLuma != null) sourceHeight else height)) {
            currentCoroutineContext().ensureActive()
            val sourceY = if (areaLuma != null) y else nextPointY
            if (sourceY != nextPointY && !directionOpaque) continue
            // Android getPixels converts tagged P3 into non-premultiplied 8-bit sRGB.
            // Merely changing the bitmap's tag would not perform this conversion.
            bitmap.getPixels(row, 0, row.size, 0, sourceY, row.size, 1)
            if (sourceY == nextPointY) {
                for (x in 0 until width) {
                    val pixel = row[pointXs[x]]
                    if (pixel ushr 24 != 255) return null
                    val r = (pixel ushr 16) and 255; val g = (pixel ushr 8) and 255; val b = pixel and 255
                    val value = lumaCode(r, g, b) / 255.0
                    luma[pointY * width + x] = value
                    if (value <= .02) black++
                    if (value >= .98) white++
                    red[r / 16]++; green[g / 16]++; blue[b / 16]++
                    coarse[(r / 64) * 16 + (g / 64) * 4 + b / 64]++
                }
                pointY++
                nextPointY = if (pointY < height) center(pointY, sourceHeight, height) else -1
            }

            if (areaLuma == null || areaRow == null || !directionOpaque) continue
            // Alpha outside the old point grid invalidates only direction, not the distributions.
            directionOpaque = sumAreaRow(row, areaRow)
            if (!directionOpaque) continue

            // In units of 1/height of a source row, a target row spans sourceHeight units.
            val used = minOf(remainingY, height)
            val carry = height - used
            for (x in 0 until width) {
                areaLuma[areaOffset + x] += used * areaRow[x]
                if (carry != 0) areaLuma[areaOffset + width + x] = carry * areaRow[x]
            }
            remainingY -= used
            if (remainingY == 0) {
                areaOffset += width
                remainingY = sourceHeight - carry
            }
        }
        val brightSide = when {
            areaLuma == null -> measuredBrightSide(width, height, luma)
            !directionOpaque -> null
            else -> {
                val scale = 255.0 * sourceWidth * sourceHeight
                for (i in areaLuma.indices) areaLuma[i] /= scale
                measuredBrightSide(width, height, areaLuma)
            }
        }
        luma.sort()
        fun IntArray.distribution() = map { it.toDouble() / total }
        val quantiles = listOf(.05, .5, .95).map { luma[(ceil(it * total).toInt() - 1).coerceAtLeast(0)] }
        return AdviceStatistics(sourceRevision, sampleRevision, width, height,
            AdviceStatisticsValues(quantiles, black.toDouble() / total, white.toDouble() / total,
                red.distribution(), green.distribution(), blue.distribution(), coarse.distribution()), brightSide)
    }

    private fun center(index: Int, source: Int, target: Int) = ((2L * index + 1) * source / (2L * target)).toInt()

    private fun lumaCode(r: Int, g: Int, b: Int) = .2126 * r + .7152 * g + .0722 * b

    private fun sumAreaRow(pixels: IntArray, sums: DoubleArray): Boolean {
        var x = 0
        var sum = 0.0
        var remaining = pixels.size
        // A source pixel spans sums.size units and can overlap at most two target cells.
        for (pixel in pixels) {
            if (pixel ushr 24 != 255) return false
            val value = lumaCode((pixel ushr 16) and 255, (pixel ushr 8) and 255, pixel and 255)
            val used = minOf(remaining, sums.size)
            sum += used * value
            remaining -= used
            if (remaining == 0) {
                sums[x++] = sum
                val carry = sums.size - used
                sum = carry * value
                remaining = pixels.size - carry
            }
        }
        return true
    }
}
