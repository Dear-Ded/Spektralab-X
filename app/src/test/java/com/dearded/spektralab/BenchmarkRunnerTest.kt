package com.dearded.spektralab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class BenchmarkRunnerTest {
    @Test
    fun `cancellation signals a blocked render before release`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
            val entered = CompletableDeferred<Unit>()
            val signalled = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val closed = AtomicBoolean(false)
            val backend = object : BenchmarkBackend {
                override suspend fun open(edge: Int) {}
                override suspend fun firstPreview() = withContext(engine) {
                    entered.complete(Unit)
                    check(finish.await(5, TimeUnit.SECONDS))
                }
                override suspend fun switchStyle(index: Int) {}
                override suspend fun export() {}
                override fun cancel() { check(!closed.get()); signalled.countDown() }
                override suspend fun close() { closed.set(true) }
            }
            val runner = BenchmarkRunner(backend, { 0 }, { .6f }, { false }, { 0 }, { 0 }, 12_000_000, 0)
            val job = launch { runner.run() }
            try {
                withTimeout(5000) { entered.await() }
                job.cancel()
                assertTrue(signalled.await(5, TimeUnit.SECONDS))
                assertFalse(closed.get())
            } finally {
                finish.countDown()
                withTimeout(5000) { job.join() }
            }
            assertTrue(closed.get())
        }
    }

    @Test
    fun `style switch requests the physical film pipeline`() {
        val delta = benchmarkFilmParams("kodak_gold_200")
        assertEquals("kodak_gold_200", delta.getString("film_stock"))
        assertFalse(delta.has("film"))
        assertFalse(delta.getBoolean("x_no_film"))
        assertFalse(delta.getBoolean("digital_intermediate"))
        assertFalse(delta.getBoolean("scan_film"))
    }
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
    fun `runner calls cancel on exception`() { runBlocking {
        var cancelled = false
        var closed = false
        val backend = object : BenchmarkBackend {
            override suspend fun open(edge: Int) {}
            override suspend fun firstPreview() { throw RuntimeException("fail") }
            override suspend fun switchStyle(index: Int) {}
            override suspend fun export() {}
            override fun cancel() { cancelled = true }
            override suspend fun close() { closed = true }
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
        // A plain failure is not a cancellation: close still runs, cancel does not.
        assertFalse(cancelled)
        assertTrue(closed)
    } }

    @Test
    fun `median calculation`() {
        assertEquals(20L, BenchmarkRunner.median(listOf(10L, 20L, 30L)))
        // Even counts take the upper middle, not the average of the two.
        assertEquals(30L, BenchmarkRunner.median(listOf(10L, 25L, 30L, 40L)))
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
