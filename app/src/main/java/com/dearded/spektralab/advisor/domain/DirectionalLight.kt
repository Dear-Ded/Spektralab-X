package com.dearded.spektralab.advisor.domain

import com.dearded.spektralab.EditMask
import com.dearded.spektralab.MaskTone

enum class BrightSide(val wire: String, private val dx: Double, private val dy: Double) {
    LEFT("left", -1.0, 0.0), RIGHT("right", 1.0, 0.0),
    TOP("top", 0.0, -1.0), BOTTOM("bottom", 0.0, 1.0);

    internal fun mask(tone: MaskTone, inverted: Boolean) = EditMask(inverted = inverted, tone = tone,
        ax = .5 - .3 * dx, ay = .5 - .3 * dy, bx = .5 + .3 * dx, by = .5 + .3 * dy)
}

data class DirectionalLight(val brightSide: BrightSide, val brightEv: Double, val darkEv: Double) {
    init {
        if (brightEv !in .05..1.0 || darkEv !in -1.5..-.05) throw AdviceFailure(AdviceError.OUT_OF_RANGE)
    }

    internal fun masks(percent: Int): List<EditMask> {
        if (percent == 0) return emptyList()
        val strength = percent / 100.0
        return listOf(brightSide.mask(MaskTone(exposure = darkEv * strength), inverted = true),
            brightSide.mask(MaskTone(exposure = brightEv * strength), inverted = false))
    }
}

/** A direction needs support across and inside the frame, not just at its border. */
internal fun measuredBrightSide(width: Int, height: Int, luma: DoubleArray): BrightSide? {
    if (width < 4 || height < 4) return null
    fun edgeEvidence(length: Int, cross: Int, at: (Int, Int) -> Double): Double {
        val depth = maxOf(1, length / 5)
        val inset = maxOf(1, length / 10)
        var evidence = 0.0
        for (band in 0 until 4) {
            val start = cross * band / 4
            val end = cross * (band + 1) / 4
            var delta = 0.0
            var insideDelta = 0.0
            for (j in start until end) for (i in 0 until depth) {
                delta += at(length - 1 - i, j) - at(i, j)
                insideDelta += at(length - 1 - inset - i, j) - at(inset + i, j)
            }
            val count = (end - start) * depth
            val edge = delta / count
            val inside = insideDelta / count
            // The inset confirms the sign; the outer bands still determine the strength.
            evidence = when {
                edge >= .06 && inside >= .01 && evidence >= 0 -> if (band == 0) edge else minOf(evidence, edge)
                edge <= -.06 && inside <= -.01 && evidence <= 0 -> if (band == 0) edge else maxOf(evidence, edge)
                else -> return 0.0
            }
        }
        return evidence
    }
    val horizontal = edgeEvidence(width, height) { x, y -> luma[y * width + x] }
    val vertical = edgeEvidence(height, width) { y, x -> luma[y * width + x] }
    // Neither an ambiguous diagonal nor an isolated patch establishes a full bright side.
    val h = kotlin.math.abs(horizontal)
    val v = kotlin.math.abs(vertical)
    return when {
        h > v * 1.25 -> if (horizontal > 0) BrightSide.RIGHT else BrightSide.LEFT
        v > h * 1.25 -> if (vertical > 0) BrightSide.BOTTOM else BrightSide.TOP
        else -> null
    }
}
