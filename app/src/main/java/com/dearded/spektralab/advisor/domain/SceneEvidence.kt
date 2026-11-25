package com.dearded.spektralab.advisor.domain

/** Half-open pixel bounds. A reference uses content pixels, not view or RAW coordinates. */
data class SceneRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(left >= 0 && top >= 0 && left < right && top < bottom) }
}

/** Rendered sRGB Y' measurements tied to the enclosing AdviceStatistics revisions. */
@ConsistentCopyVisibility
data class SceneEvidence internal constructor(
    val gridColumns: Int,
    val gridRows: Int,
    val gridLumaMeans: List<Double>,
    val reference: SceneReference?,
) {
    companion object {
        const val VERSION = "srgb8.area-grid4.v1"
    }
}

@ConsistentCopyVisibility
data class SceneReference internal constructor(
    val left: Double, val top: Double, val right: Double, val bottom: Double,
    val fraction: Double,
    val mean: Double,
    val nearBlack: Double,
    val nearWhite: Double,
    val neighborhoodMean: Double?,
)

internal fun sceneEvidence(width: Int, height: Int, areaLuma: DoubleArray, reference: SceneReference?): SceneEvidence {
    val columns = minOf(4, width)
    val rows = minOf(4, height)
    val means = List(columns * rows) { tile ->
        val x0 = tile % columns * width / columns
        val x1 = (tile % columns + 1) * width / columns
        val y0 = tile / columns * height / rows
        val y1 = (tile / columns + 1) * height / rows
        var sum = 0.0
        for (y in y0 until y1) for (x in x0 until x1) sum += areaLuma[y * width + x]
        sum / ((x1 - x0) * (y1 - y0))
    }
    return SceneEvidence(columns, rows, means, reference)
}
