package com.dearded.spektralab

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawOpenCancellationTest {
    @Test fun successStopsTheWatcherBeforeReleasingItsToken() = runBlocking {
        val events = mutableListOf<String>()
        val result = withRawOpenCancellation(
            create = { events.add("create"); 7L },
            cancel = { assertEquals(7L, it); events.add("cancel") },
            release = { assertEquals(7L, it); events.add("release") },
        ) { token ->
            assertEquals(7L, token)
            events.add("native returned")
            42
        }
        assertEquals(42, result)
        assertEquals(listOf("create", "native returned", "cancel", "release"), events)
    }

    @Test fun cancellationReachesBlockedNativeWithoutReleasingItsToken() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
            val entered = CompletableDeferred<Unit>()
            val finishNative = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val released = AtomicBoolean(false)
            val nativeFinished = AtomicBoolean(false)
            val job = launch {
                withRawOpenCancellation(
                    create = { 7L }, cancel = { cancelled.countDown() },
                    release = { assertTrue(nativeFinished.get()); released.set(true) },
                ) {
                    withContext(engine) {
                        entered.complete(Unit)
                        check(finishNative.await(5, TimeUnit.SECONDS))
                        nativeFinished.set(true)
                    }
                }
            }
            try {
                withTimeout(5000) { entered.await() }
                job.cancel()
                assertTrue(cancelled.await(5, TimeUnit.SECONDS))
                assertFalse(released.get())
                assertFalse(job.isCompleted)
            } finally {
                finishNative.countDown()
                withTimeout(5000) { job.join() }
            }
            assertTrue(released.get())
        }
    }

    @Test fun lateNativeSuccessReleasesCandidateAndKeepsPreviousEditor() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
            val entered = CompletableDeferred<Unit>()
            val finishNative = CountDownLatch(1)
            val events = Collections.synchronizedList(mutableListOf<String>())
            var installed = 1L
            val job = launch {
                withSessionCandidate(engine, { events.add("release session $it") }) { candidate ->
                    withRawOpenCancellation(
                        create = { 7L }, cancel = { events.add("cancel") },
                        release = { events.add("release token") },
                    ) {
                        withContext(engine) {
                            entered.complete(Unit)
                            check(finishNative.await(5, TimeUnit.SECONDS))
                            candidate.own(2)
                            events.add("native returned")
                        }
                    }
                    candidate.commit { installed = it }
                }
            }
            try {
                withTimeout(5000) { entered.await() }
                job.cancel()
            } finally {
                finishNative.countDown()
                withTimeout(5000) { job.join() }
            }
            assertEquals(1L, installed)
            assertEquals(listOf("cancel", "native returned", "release token", "release session 2"), events)
        }
    }

    @Test fun cancellationBeforeDispatcherEntryDoesNotStartNative() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        executor.asCoroutineDispatcher().use { engine ->
            val occupied = CountDownLatch(1)
            val freeEngine = CountDownLatch(1)
            executor.execute { occupied.countDown(); check(freeEngine.await(5, TimeUnit.SECONDS)) }
            assertTrue(occupied.await(5, TimeUnit.SECONDS))
            var started = false
            var releases = 0
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                withRawOpenCancellation(create = { 7L }, cancel = {}, release = { releases++ }) {
                    withContext(engine) { started = true }
                }
            }
            try {
                job.cancel()
                freeEngine.countDown()
                withTimeout(5000) { job.join() }
                assertFalse(started)
                assertEquals(1, releases)
            } finally {
                freeEngine.countDown()
            }
        }
    }

    @Test fun failureAndRetryOwnDifferentTokens() = runBlocking {
        val events = mutableListOf<String>()
        var next = 0L
        val create = { ++next }
        val cancel: (Long) -> Unit = { events.add("cancel $it") }
        val release: (Long) -> Unit = { events.add("release $it") }
        val failure = runCatching {
            withRawOpenCancellation(create, cancel, release) { throw IllegalArgumentException("decode failed") }
        }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertEquals(2L, withRawOpenCancellation(create, cancel, release) { it })
        assertEquals(listOf("cancel 1", "release 1", "cancel 2", "release 2"), events)
    }

    @Test fun failedTokenAllocationDoesNotEnterNativeOrReleaseNull() = runBlocking {
        var entered = false
        var released = false
        val failure = runCatching {
            withRawOpenCancellation(create = { 0L }, cancel = {}, release = { released = true }) {
                entered = true
            }
        }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertFalse(entered)
        assertFalse(released)
    }
}
