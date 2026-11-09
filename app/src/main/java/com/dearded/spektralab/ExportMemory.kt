package com.dearded.spektralab

/**
 * Area of the opened working source, before SHARE rendering or paper framing.
 * A missing hint uses bounds enforced by open/fit, never the smaller output
 * estimate. With no limits and no dimensions, Long.MAX_VALUE denotes unknown
 * unbounded area, so admission must install a finite memory cap.
 * This does not account for the RAW decoder's full-resolution scratch space.
 */
internal fun exportWorkingArea(
    workingPixels: Long,
    workingEdge: Int,
    maxPixels: Long,
): Long {
    var bound = Long.MAX_VALUE
    if (workingEdge > 0) bound = minOf(bound, workingEdge.toLong() * workingEdge)
    if (maxPixels > 0) bound = minOf(bound, maxPixels)
    return if (workingPixels > 0) minOf(workingPixels, bound) else bound
}

/** Preserve the existing minimum memory tier without raising a tighter cap. */
internal fun exportMemoryPixelCap(
    workingArea: Long,
    maxPixels: Long,
    headroomBytes: Long,
    bytesPerWorkingPixel: Double,
    minimumTierPixels: Long,
): Long {
    require(bytesPerWorkingPixel.isFinite() && bytesPerWorkingPixel > 0)
    if (workingArea * bytesPerWorkingPixel <= headroomBytes.toDouble()) return maxPixels
    var fit = maxOf(minimumTierPixels, (headroomBytes / bytesPerWorkingPixel).toLong())
    if (maxPixels > 0) fit = minOf(fit, maxPixels)
    return if (fit < workingArea) fit else maxPixels
}
