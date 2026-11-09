package com.dearded.spektralab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

class ExportMemoryTest {
    private val deviceCap = 42_000_000L
    private val floorPixels = 4_000_000L

    @Test fun shareKeepsTheWorkingSourceResident() {
        val sourcePixels = 6000L * 4000
        val sharePixels = 3400L * 2266
        val headroom = 1_200_000_000L
        // The old output-based predictor admits this; the source alone uses twice the budget.
        assertTrue(sharePixels * 100 < headroom)
        val working = exportWorkingArea(sourcePixels, 0, deviceCap)
        val cap = exportMemoryPixelCap(working, deviceCap, headroom, 100.0, floorPixels)
        assertEquals(24_000_000L, working)
        assertEquals(12_000_000L, cap)
        assertTrue(fittedArea(6000, 4000, 0, cap) <= cap)
    }

    @Test fun unknownRawWithOriginalEdgeGetsBudgetedBeforeOpen() {
        val working = exportWorkingArea(0, 0, deviceCap)
        assertEquals(deviceCap, working)
        val cap = exportMemoryPixelCap(working, deviceCap, 800_000_000, 100.0, floorPixels)
        assertEquals(8_000_000L, cap)
        assertTrue(fittedArea(8192, 6144, 0, cap) <= cap)
    }

    @Test fun unknownUnrestrictedSourceMustGetAFiniteCap() {
        assertEquals(Long.MAX_VALUE, exportWorkingArea(0, 0, 0))
        assertEquals(8_000_000L, exportMemoryPixelCap(Long.MAX_VALUE, 0, 800_000_000, 100.0, floorPixels))
    }

    @Test fun missingLegacyHintUsesBothEnforcedBounds() {
        assertEquals(4096L * 4096, exportWorkingArea(0, 4096, deviceCap))
        assertEquals(8_388_608L, exportWorkingArea(0, 4096, 8_388_608))
        // This must cover square images too, rather than assume the old 4:3 fallback.
        assertTrue(exportWorkingArea(0, 4096, deviceCap) >= fittedArea(6000, 6000, 4096, deviceCap))
    }

    @Test fun knownWorkingAreaIsNotReplacedByTheLargerDeviceCap() {
        assertEquals(12_000_000L, exportWorkingArea(12_000_000, 6144, deviceCap))
        assertEquals(6_000_000L, exportWorkingArea(12_000_000, 6144, 6_000_000))
        assertEquals(deviceCap, exportMemoryPixelCap(12_000_000, deviceCap, 1_200_000_000, 100.0, floorPixels))
    }

    @Test fun conservativeBoundDoesNotShrinkAJobThatFits() {
        val bound = exportWorkingArea(0, 3072, deviceCap)
        assertEquals(deviceCap, exportMemoryPixelCap(bound, deviceCap, 1_000_000_000, 100.0, floorPixels))
    }

    @Test fun memoryFloorNeverRaisesATighterUserOrRetryCap() {
        assertEquals(2_000_000L, exportMemoryPixelCap(2_000_000, 2_000_000, -1, 100.0, floorPixels))
        assertEquals(floorPixels, exportMemoryPixelCap(deviceCap, deviceCap, -1, 100.0, floorPixels))
        assertEquals(1L, exportWorkingArea(0, Int.MAX_VALUE, 1))
        assertEquals(Int.MAX_VALUE.toLong() * Int.MAX_VALUE, exportWorkingArea(0, Int.MAX_VALUE, 0))
    }

    @Test fun sourceBoundsCoverIndependentFitAcrossAspectRatiosAndCaps() {
        for ((w, h) in listOf(6000 to 4000, 8192 to 6144, 6000 to 6000, 12000 to 3000, 3000 to 12000)) {
            for (edge in listOf(0, 2048, 3072, 4096, 6144)) {
                for (cap in listOf(0L, 8_388_608L, deviceCap)) {
                    val bound = exportWorkingArea(0, edge, cap)
                    assertTrue("$w x $h edge=$edge cap=$cap", fittedArea(w, h, edge, cap) <= bound)
                }
            }
        }
    }

    // Independent reproduction of native fit's dimensions, including flooring.
    private fun fittedArea(w: Int, h: Int, edge: Int, cap: Long): Long {
        var scale = if (edge > 0 && max(w, h) > edge) edge.toDouble() / max(w, h) else 1.0
        if (cap > 0 && w.toDouble() * h * scale * scale > cap) scale = sqrt(cap.toDouble() / (w.toDouble() * h))
        return max(1, floor(w * scale).toInt()).toLong() * max(1, floor(h * scale).toInt())
    }
}
