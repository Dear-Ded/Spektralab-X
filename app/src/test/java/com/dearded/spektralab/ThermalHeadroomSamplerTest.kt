package com.dearded.spektralab

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalHeadroomSamplerTest {
    private class PendingExecutor : Executor {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runNext() { tasks.remove().run() }
    }

    @Test fun firstSampleAtZeroAndTenSecondBoundary() {
        var now = 0L
        val executor = PendingExecutor()
        var calls = 0
        val sampler = ThermalHeadroomSampler({ now }, executor, { ++calls; 0.6f })

        assertTrue(sampler.headroom().isNaN())
        repeat(50) { assertTrue(sampler.headroom().isNaN()) }
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertEquals(0.6f, sampler.headroom(), 0f)
        now = 9_999L
        sampler.headroom()
        assertEquals(0, executor.tasks.size)
        now = 10_000L
        sampler.headroom()
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertEquals(2, calls)
    }

    @Test fun failedSamplesExpireInsteadOfKeepingStaleHeadroomForever() {
        var now = 0L
        var next = 1.1f
        val executor = PendingExecutor()
        val sampler = ThermalHeadroomSampler({ now }, executor, { next })
        sampler.headroom()
        executor.runNext()
        next = Float.NaN
        now = 10_000L
        assertEquals(1.1f, sampler.headroom(), 0f)
        executor.runNext()
        now = 20_000L
        next = Float.POSITIVE_INFINITY
        sampler.headroom()
        executor.runNext()
        now = 29_999L
        assertEquals(1.1f, sampler.headroom(), 0f)
        now = 30_000L
        assertTrue(sampler.headroom().isNaN())
        next = 0.4f
        executor.runNext()
        assertEquals(0.4f, sampler.headroom(), 0f)
    }

    @Test fun slowOrFailedQueryIsNotResubmittedUntilTenSecondsAfterCompletion() {
        var now = 0L
        var throws = true
        val executor = PendingExecutor()
        val sampler = ThermalHeadroomSampler({ now }, executor, {
            if (throws) error("HAL unavailable")
            0.8f
        })
        sampler.headroom()
        now = 60_000L
        sampler.headroom()
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        now = 69_999L
        assertTrue(sampler.headroom().isNaN())
        assertEquals(0, executor.tasks.size)
        now = 70_000L
        throws = false
        sampler.headroom()
        executor.runNext()
        assertEquals(0.8f, sampler.headroom(), 0f)
    }

    @Test fun rejectedExecutorDoesNotLeaveSamplingPermanentlyInFlight() {
        var now = 0L
        var attempts = 0
        val executor = Executor { ++attempts; throw RejectedExecutionException() }
        val sampler = ThermalHeadroomSampler({ now }, executor, { 0f })
        repeat(50) { assertTrue(sampler.headroom().isNaN()) }
        assertEquals(1, attempts)
        now = 10_000L
        sampler.headroom()
        assertEquals(2, attempts)
    }

    @Test fun simultaneousCallersQueueOnlyOneQuery() {
        val executor = PendingExecutor()
        val queries = AtomicInteger()
        val sampler = ThermalHeadroomSampler({ 0L }, executor, { queries.incrementAndGet(); 0.5f })
        val callers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val results = (0 until 32).map {
                callers.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repeat(100) { sampler.headroom() }
                }
            }
            start.countDown()
            results.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, executor.tasks.size)
            assertEquals(0, queries.get())
            executor.runNext()
            assertEquals(1, queries.get())
        } finally {
            callers.shutdownNow()
        }
    }
}
