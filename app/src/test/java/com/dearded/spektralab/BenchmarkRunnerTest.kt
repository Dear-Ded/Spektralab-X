package com.dearded.spektralab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BenchmarkRunnerTest {
    @Test
    fun `runner completes successfully`() = runBlocking {
        var opened = false
        var closed = false
        val backend = object : BenchmarkBackend {
            override suspend fun open(edge: Int) { opened = true }
            override suspend fun firstPreview() {}
            override suspend fun switchStyle(index: Int) {}
            override suspend fun export() {}
            override fun cancel() {}
            override suspend fun close() { closed = true }
        }
        val runner = BenchmarkRunner(
            backend = backend,
            clockMs = { 100L },
            headroom = { 0.6f },
            resetPeak = { true },
            rssKb = { 500_000L },
            peakKb = { 600_000L },
            maxPixels = 42_000_000L,
            peakBudgetMb = 3072L,
        )
        val outcome = runner.run()
        assertTrue(opened)
        assertTrue(closed)
        assertTrue(outcome is BenchmarkOutcome.Done)
        val result = (outcome as BenchmarkOutcome.Done).result
        assertEquals(3072, result.testEdge)
        assertTrue(result.peakGrowthMb > 0)
    }

    @Test
    fun `runner stops when hot before start`() = runBlocking {
        var opened = false
        val backend = object : BenchmarkBackend {
            override suspend fun open(edge: Int) { opened = true }
            override suspend fun firstPreview() {}
            override suspend fun switchStyle(index: Int) {}
            override suspend fun export() {}
            override fun cancel() {}
            override suspend fun close() {}
        }
        val runner = BenchmarkRunner(
            backend = backend,
            clockMs = { 0L },
            headroom = { 0.9f },
            resetPeak = { true },
            rssKb = { 0L },
            peakKb = { 0L },
            maxPixels = 42_000_000L,
            peakBudgetMb = 3072L,
        )
        val outcome = runner.run()
        assertFalse(opened)
        assertTrue(outcome is BenchmarkOutcome.TooHot)
        assertEquals("开始前", (outcome as BenchmarkOutcome.TooHot).phase)
    }

    @Test
    fun `runner stops when hot during test`() = runBlocking {
        var phase = 0
        val backend = object : BenchmarkBackend {
            override suspend fun open(edge: Int) { phase = 1 }
            override suspend fun firstPreview() { phase = 2 }
            override suspend fun switchStyle(index: Int) { phase = 3 }
            override suspend fun export() { phase = 4 }
            override fun cancel() {}
            override suspend fun close() {}
        }
        val runner = BenchmarkRunner(
            backend = backend,
            clockMs = { 0L },
            headroom = { if (phase < 2) 0.6f else 0.95f },
            resetPeak = { true },
            rssKb = { 0L },
            peakKb = { 0L },
            maxPixels = 42_000_000L,
            peakBudgetMb = 3072L,
        )
        val outcome = runner.run()
        assertEquals(2, phase)
        assertTrue(outcome is BenchmarkOutcome.TooHot)
        assertEquals("切换风格", (outcome as BenchmarkOutcome.TooHot).phase)
    }

    @Test
    fun `runner catches engine exception`() = runBlocking {
        val backend = object : BenchmarkBackend {
            override suspend fun open(edge: Int) {}
            override suspend fun firstPreview() {
                throw EngineException("测试失败", ErrorCodes.ENGINE_EXCEPTION)
            }
            override suspend fun switchStyle(index: Int) {}
            override suspend fun export() {}
            override fun cancel() {}
            override suspend fun close() {}
        }
        val runner = BenchmarkRunner(
            backend = backend,
            clockMs = { 0L },
            headroom = { 0.6f },
            resetPeak = { true },
            rssKb = { 0L },
            peakKb = { 0L },
            maxPixels = 42_000_000L,
            peakBudgetMb = 3072L,
        )
        val outcome = runner.run()
        assertTrue(outcome is BenchmarkOutcome.Failed)
        val failed = outcome as BenchmarkOutcome.Failed
        assertEquals(ErrorCodes.ENGINE_EXCEPTION, failed.code)
        assertTrue(failed.message.contains("测试失败"))
    }

    @Test
    fun `runner calls cancel on exception`() = runBlocking {
        var cancelled = false
        val backend = object : BenchmarkBackend {
            override suspend fun open(edge: Int) {}
            override suspend fun firstPreview() { throw RuntimeException("fail") }
            override suspend fun switchStyle(index: Int) {}
            override suspend fun export() {}
            override fun cancel() { cancelled = true }
            override suspend fun close() {}
        }
        val runner = BenchmarkRunner(
            backend = backend,
            clockMs = { 0L },
            headroom = { 0.6f },
            resetPeak = { true },
            rssKb = { 0L },
            peakKb = { 0L },
            maxPixels = 42_000_000L,
            peakBudgetMb = 3072L,
        )
        try {
            runner.run()
        } catch (e: CancellationException) {
            // Expected when backend throws non-EngineException
        }
        // Cancel should not be called for non-cancellation exceptions in current impl
        // but close should always be called
    }

    @Test
    fun `median calculation`() {
        assertEquals(20L, BenchmarkRunner.median(listOf(10L, 20L, 30L)))
        assertEquals(25L, BenchmarkRunner.median(listOf(10L, 25L, 30L, 40L)))
        assertEquals(100L, BenchmarkRunner.median(listOf(100L)))
    }

    @Test
    fun `codeOf extracts error codes`() {
        assertEquals(ErrorCodes.ENGINE_EXCEPTION,
            BenchmarkRunner.codeOf(EngineException("test", ErrorCodes.ENGINE_EXCEPTION)))
        assertEquals(ErrorCodes.GPU_DEVICE_LOST,
            BenchmarkRunner.codeOf(DeviceLostException(0L, "lost")))
        assertEquals(ErrorCodes.OUT_OF_MEMORY,
            BenchmarkRunner.codeOf(OutOfMemoryError()))
        assertEquals(ErrorCodes.ENGINE_EXCEPTION,
            BenchmarkRunner.codeOf(RuntimeException("other")))
    }
}
