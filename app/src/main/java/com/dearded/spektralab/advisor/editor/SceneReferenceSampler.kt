package com.dearded.spektralab.advisor.editor

import com.dearded.spektralab.advisor.domain.SceneRect
import com.dearded.spektralab.advisor.domain.SceneReference

/** Exact preview pixels within the rectangle and its immediate surrounding ring. */
internal class SceneReferenceSampler(private val bounds: SceneRect, private val width: Int, private val height: Int) {
    private val padX = maxOf(1, (bounds.right - bounds.left) / 2)
    private val padY = maxOf(1, (bounds.bottom - bounds.top) / 2)
    private val left = maxOf(0, bounds.left - padX)
    private val top = maxOf(0, bounds.top - padY)
    private val right = minOf(width, bounds.right + padX)
    private val bottom = minOf(height, bounds.bottom + padY)
    private var sum = 0.0
    private var ringSum = 0.0
    private var black = 0
    private var white = 0

    fun observe(y: Int, row: IntArray) {
        if (y !in top until bottom) return
        for (x in left until right) {
            val value = srgbLumaCode(row[x]) / 255.0
            if (y in bounds.top until bounds.bottom && x in bounds.left until bounds.right) {
                sum += value
                if (value <= .02) black++
                if (value >= .98) white++
            } else ringSum += value
        }
    }

    fun finish(): SceneReference {
        val count = (bounds.right - bounds.left).toLong() * (bounds.bottom - bounds.top)
        val ringCount = (right - left).toLong() * (bottom - top) - count
        return SceneReference(bounds.left.toDouble() / width, bounds.top.toDouble() / height,
            bounds.right.toDouble() / width, bounds.bottom.toDouble() / height,
            count / (width.toDouble() * height), sum / count, black.toDouble() / count,
            white.toDouble() / count, if (ringCount == 0L) null else ringSum / ringCount)
    }
}

internal fun srgbLumaCode(pixel: Int): Double =
    .2126 * ((pixel ushr 16) and 255) + .7152 * ((pixel ushr 8) and 255) + .0722 * (pixel and 255)
