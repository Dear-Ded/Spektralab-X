package com.dearded.spektralab

import org.junit.Assert.*
import org.junit.Test

class BenchmarkPolicyTest {
    @Test
    fun `thermalGate recognizes hot state`() {
        assertEquals(ThermalGate.HOT, BenchmarkPolicy.thermalGate(0.85f))
        assertEquals(ThermalGate.HOT, BenchmarkPolicy.thermalGate(0.9f))
        assertEquals(ThermalGate.HOT, BenchmarkPolicy.thermalGate(1.0f))
    }

    @Test
    fun `thermalGate recognizes OK state`() {
        assertEquals(ThermalGate.OK, BenchmarkPolicy.thermalGate(0.5f))
        assertEquals(ThermalGate.OK, BenchmarkPolicy.thermalGate(0.7f))
        assertEquals(ThermalGate.OK, BenchmarkPolicy.thermalGate(0.84f))
    }

    @Test
    fun `thermalGate handles unknown`() {
        assertEquals(ThermalGate.UNKNOWN, BenchmarkPolicy.thermalGate(Float.NaN))
        assertEquals(ThermalGate.UNKNOWN, BenchmarkPolicy.thermalGate(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `advise recommends live preview when fast`() {
        val advice = BenchmarkPolicy.advise(
            testEdge = 3072,
            switchStyleMs = 70L,
            exportMs = 2000L,
            peakGrowthMb = 800L,
            thermalAfter = 0.6f,
            maxPixels = 42_000_000L,
            peakBudgetMb = 3072L,
        )
        assertEquals(LivePreviewAdvice.ON, advice.livePreview)
    }

    @Test
    fun `advise does not recommend live preview when slow`() {
        val advice = BenchmarkPolicy.advise(
            testEdge = 3072,
            switchStyleMs = 250L,
            exportMs = 5000L,
            peakGrowthMb = 1500L,
            thermalAfter = 0.7f,
            maxPixels = 42_000_000L,
            peakBudgetMb = 3072L,
        )
        assertEquals(LivePreviewAdvice.OFF, advice.livePreview)
    }

    @Test
    fun `advise caps edge when hot`() {
        val advice = BenchmarkPolicy.advise(
            testEdge = 3072,
            switchStyleMs = 100L,
            exportMs = 3000L,
            peakGrowthMb = 1000L,
            thermalAfter = 0.9f,
            maxPixels = 42_000_000L,
            peakBudgetMb = 3072L,
        )
        assertTrue(advice.edge <= 3072)
        assertTrue(advice.caveats.any { it.contains("偏热") })
    }

    @Test
    fun `advise scales down when export is slow`() {
        val advice = BenchmarkPolicy.advise(
            testEdge = 3072,
            switchStyleMs = 100L,
            exportMs = 15000L,
            peakGrowthMb = 1200L,
            thermalAfter = 0.6f,
            maxPixels = 42_000_000L,
            peakBudgetMb = 4096L,
        )
        assertTrue(advice.edge < 3072)
    }

    @Test
    fun `advise scales down when memory is tight`() {
        val advice = BenchmarkPolicy.advise(
            testEdge = 3072,
            switchStyleMs = 80L,
            exportMs = 3000L,
            peakGrowthMb = 1800L,
            thermalAfter = 0.6f,
            maxPixels = 42_000_000L,
            peakBudgetMb = 2048L,
        )
        assertTrue(advice.edge < 3072)
    }

    @Test
    fun `advise adds caveat when extrapolating`() {
        val advice = BenchmarkPolicy.advise(
            testEdge = 3072,
            switchStyleMs = 100L,
            exportMs = 10000L,
            peakGrowthMb = 1000L,
            thermalAfter = 0.6f,
            maxPixels = 42_000_000L,
            peakBudgetMb = 3072L,
        )
        assertTrue(advice.caveats.any { it.contains("换算") })
    }

    @Test
    fun `advise handles missing memory data`() {
        val advice = BenchmarkPolicy.advise(
            testEdge = 3072,
            switchStyleMs = 100L,
            exportMs = 3000L,
            peakGrowthMb = -1L,
            thermalAfter = 0.6f,
            maxPixels = 42_000_000L,
            peakBudgetMb = 0L,
        )
        assertNotNull(advice.edge)
        assertTrue(advice.edge > 0)
    }

    @Test
    fun `lines formats result correctly`() {
        val result = BenchmarkResult(
            testEdge = 3072,
            firstPreviewMs = 450L,
            switchStyleMs = 80L,
            exportMs = 3000L,
            peakGrowthMb = 1200L,
            thermalBefore = 0.6f,
            thermalAfter = 0.7f,
            advice = BenchmarkAdvice(LivePreviewAdvice.ON, 3072, listOf()),
            finishedAt = System.currentTimeMillis(),
        )
        val lines = BenchmarkPolicy.lines(result)
        assertTrue(lines.any { it.contains("450 ms") })
        assertTrue(lines.any { it.contains("3000 ms") })
        assertTrue(lines.any { it.contains("1200 MB") })
        assertTrue(lines.any { it.contains("可以开") })
    }

    @Test
    fun `diagnostics formats result in English`() {
        val result = BenchmarkResult(
            testEdge = 3072,
            firstPreviewMs = 450L,
            switchStyleMs = 80L,
            exportMs = 3000L,
            peakGrowthMb = 1200L,
            thermalBefore = 0.6f,
            thermalAfter = 0.7f,
            advice = BenchmarkAdvice(LivePreviewAdvice.ON, 3072, listOf()),
            finishedAt = System.currentTimeMillis(),
        )
        val diag = BenchmarkPolicy.diagnostics(result)
        assertTrue(diag.contains("Benchmark"))
        assertTrue(diag.contains("450 ms"))
        assertTrue(diag.contains("3000 ms"))
        assertTrue(diag.contains("3072"))
    }
}
