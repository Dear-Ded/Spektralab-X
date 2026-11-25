package com.dearded.spektralab.advisor.domain

import kotlin.math.abs

/** Ephemeral display-domain measurements, never a profile or image identifier. */
data class AdviceStatistics(val sourceRevision: Long, val sampleRevision: Long? = null,
    val width: Int = 0, val height: Int = 0, val values: AdviceStatisticsValues? = null,
    val measuredBrightSide: BrightSide? = null) {
    val available get() = values != null
    init {
        require(sourceRevision >= 0)
        require(if (available) sampleRevision != null && sampleRevision > 0 && width in 1..128 && height in 1..128
            else sampleRevision == null && width == 0 && height == 0)
    }
    companion object {
        const val DOMAIN = "display_srgb_encoded"
        const val VERSION = "srgb8.grid128.v1"
    }
}

data class AdviceStatisticsValues(val lumaQuantiles: List<Double>, val nearBlack: Double, val nearWhite: Double,
    val red: List<Double>, val green: List<Double>, val blue: List<Double>, val coarseRgb: List<Double>) {
    init {
        require(lumaQuantiles.size == 3 && lumaQuantiles.zipWithNext().all { (a, b) -> a <= b })
        require((lumaQuantiles + nearBlack + nearWhite).all { it.isFinite() && it in 0.0..1.0 })
        require(nearBlack + nearWhite <= 1.0 + 1e-9)
        for ((bins, count) in listOf(red to 16, green to 16, blue to 16, coarseRgb to 64)) {
            require(bins.size == count && bins.all { it.isFinite() && it in 0.0..1.0 } && abs(bins.sum() - 1.0) < 1e-9)
        }
    }
}
