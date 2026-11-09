package com.dearded.spektralab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientSessionLeaseTest {
    @Test fun eitherDispatcherWaitsUntilTheOtherNativeLifetimeEnds() = runBlocking {
        withTimeout(5000) {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { editor ->
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { export ->
                    for (editorFirst in listOf(true, false)) {
                        val lease = TransientSessionLease()
                        val first = if (editorFirst) editor else export
                        val second = if (editorFirst) export else editor
                        val allocated = CompletableDeferred<Unit>()
                        val finish = CompletableDeferred<Unit>()
                        val waiting = CompletableDeferred<Unit>()
                        val entered = CompletableDeferred<Unit>()
                        val active = AtomicInteger()
                        val maximum = AtomicInteger()
                        val holder = launch {
                            lease.withLease {
                                withContext(first) {
                                    maximum.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
                                    allocated.complete(Unit)
                                }
                                try { finish.await() }
                                finally { withContext(NonCancellable + first) { active.decrementAndGet() } }
                            }
                        }
                        allocated.await()
                        val waiter = launch {
                            lease.withLease(onWaiting = { waiting.complete(Unit) }) {
                                withContext(second) {
                                    maximum.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
                                    entered.complete(Unit)
                                    active.decrementAndGet()
                                }
                            }
                        }
                        waiting.await()
                        assertFalse(entered.isCompleted)
                        assertEquals(1, active.get())
                        finish.complete(Unit)
                        holder.join(); waiter.join()
                        assertTrue(entered.isCompleted)
                        assertEquals(1, maximum.get())
                        assertEquals(0, active.get())
                    }
                }
            }
        }
    }

    @Test fun cancelledWaiterDoesNotAllocateOrReleaseTheHoldersLease() = runBlocking {
        withTimeout(5000) {
            val lease = TransientSessionLease()
            val held = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val waiting = CompletableDeferred<Unit>()
            var allocations = 0
            val holder = launch {
                lease.withLease { held.complete(Unit); finish.await() }
            }
            held.await()
            val waiter = launch {
                lease.withLease(onWaiting = { waiting.complete(Unit) }) { allocations++ }
            }
            waiting.await()
            waiter.cancelAndJoin()
            var nextEntered = false
            val next = launch(start = CoroutineStart.UNDISPATCHED) {
                lease.withLease { nextEntered = true }
            }
            assertFalse(nextEntered)
            assertEquals(0, allocations)
            finish.complete(Unit)
            holder.join(); next.join()
            assertTrue(nextEntered)
        }
    }

    @Test fun candidateMetadataFailureClosesCandidateBeforeNextAdmission() = runBlocking {
        withTimeout(5000) {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
                val lease = TransientSessionLease()
                val allocated = CompletableDeferred<Unit>()
                val fail = CompletableDeferred<Unit>()
                val waiting = CompletableDeferred<Unit>()
                val released = mutableListOf<Long>()
                var current = 1L
                val opening = launch {
                    val failure = runCatching {
                        lease.withLease {
                            withSessionCandidate(engine, released::add) { candidate ->
                                withContext(engine) { candidate.own(2) }
                                allocated.complete(Unit)
                                fail.await()
                                throw IllegalArgumentException("metadata invalid")
                            }
                        }
                    }
                    assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
                }
                allocated.await()
                val export = launch {
                    lease.withLease(onWaiting = { waiting.complete(Unit) }) {
                        assertEquals(listOf(2L), released)
                        assertEquals(1L, current)
                    }
                }
                waiting.await(); fail.complete(Unit)
                opening.join(); export.join()
                assertEquals(1L, current)
            }
        }
    }

    @Test fun cancellationOnNativeReturnClosesCandidateBeforeExportEnters() = runBlocking {
        withTimeout(5000) {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
                val lease = TransientSessionLease()
                val allocated = CompletableDeferred<Unit>()
                val finishNative = CountDownLatch(1)
                val waiting = CompletableDeferred<Unit>()
                val released = mutableListOf<Long>()
                var current = 1L
                val opening = launch {
                    lease.withLease {
                        withSessionCandidate(engine, released::add) { candidate ->
                            withContext(engine) {
                                candidate.own(2)
                                allocated.complete(Unit)
                                finishNative.await()
                            }
                            candidate.commit { current = it }
                        }
                    }
                }
                try {
                    allocated.await()
                    val export = launch {
                        lease.withLease(onWaiting = { waiting.complete(Unit) }) {
                            assertEquals(listOf(2L), released)
                            assertEquals(1L, current)
                        }
                    }
                    waiting.await()
                    opening.cancel()
                    finishNative.countDown()
                    opening.join(); export.join()
                } finally { finishNative.countDown() }
            }
        }
    }

    @Test fun committedCandidateRetiresOldHandleBeforeExportEnters() = runBlocking {
        withTimeout(5000) {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
                val lease = TransientSessionLease()
                val committed = CompletableDeferred<Unit>()
                val allowRetirement = CompletableDeferred<Unit>()
                val waiting = CompletableDeferred<Unit>()
                val released = mutableListOf<Long>()
                var current = 1L
                val opening = launch {
                    lease.withLease {
                        withSessionCandidate(engine, released::add) { candidate ->
                            withContext(engine) { candidate.own(2) }
                            var retired = 0L
                            try {
                                candidate.commit {
                                    retired = current
                                    current = it
                                    committed.complete(Unit)
                                }
                            } finally {
                                withContext(NonCancellable + engine) {
                                    allowRetirement.await()
                                    released.add(retired)
                                }
                            }
                        }
                    }
                }
                try {
                    committed.await()
                    val export = launch {
                        lease.withLease(onWaiting = { waiting.complete(Unit) }) {
                            assertEquals(2L, current)
                            assertEquals(listOf(1L), released)
                        }
                    }
                    waiting.await()
                    opening.cancel()
                    assertTrue(released.isEmpty())
                    allowRetirement.complete(Unit)
                    opening.join(); export.join()
                } finally {
                    allowRetirement.complete(Unit)
                }
            }
        }
    }

    @Test fun exportExceptionReleasesNativeBeforeCandidateStarts() = runBlocking {
        withTimeout(5000) {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { exportThread ->
                val lease = TransientSessionLease()
                val allocated = CompletableDeferred<Unit>()
                val fail = CompletableDeferred<Unit>()
                val waiting = CompletableDeferred<Unit>()
                var handle = 0L
                var admitted = false
                val export = launch {
                    val result = runCatching {
                        lease.withLease {
                            admitted = true // fitToMemory belongs here, before opening.
                            try {
                                withContext(exportThread) { handle = 3L }
                                allocated.complete(Unit)
                                fail.await()
                                throw IllegalStateException("encode failed")
                            } finally { withContext(NonCancellable + exportThread) { handle = 0L } }
                        }
                    }
                    assertTrue(result.exceptionOrNull() is IllegalStateException)
                }
                allocated.await()
                val opening = launch {
                    lease.withLease(onWaiting = { waiting.complete(Unit) }) {
                        assertTrue(admitted)
                        assertEquals(0L, handle)
                    }
                }
                waiting.await(); fail.complete(Unit)
                export.join(); opening.join()
            }
        }
    }

    @Test fun peakSamplingExcludesWaitingAndFinishesAfterRetryCleanupBeforeNextCandidate() = runBlocking {
        withTimeout(5000) {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { exportThread ->
                    for (failExport in listOf(false, true)) {
                        val lease = TransientSessionLease()
                        val candidateReady = CompletableDeferred<Unit>()
                        val finishCandidate = CompletableDeferred<Unit>()
                        val exportWaiting = CompletableDeferred<Unit>()
                        val retryReady = CompletableDeferred<Unit>()
                        val finishRetry = CompletableDeferred<Unit>()
                        val nextWaiting = CompletableDeferred<Unit>()
                        val events = mutableListOf<String>()
                        val active = AtomicInteger()
                        val candidate = launch {
                            lease.withLease {
                                withContext(engine) { active.incrementAndGet() }
                                candidateReady.complete(Unit)
                                try { finishCandidate.await() }
                                finally { withContext(NonCancellable + engine) { active.decrementAndGet() } }
                            }
                        }
                        candidateReady.await()
                        val export = launch {
                            val result = runCatching {
                                lease.withLease(onWaiting = { exportWaiting.complete(Unit) }) {
                                    assertEquals(0, active.get())
                                    events.add("sample.start")
                                    try {
                                        for (attempt in 1..2) {
                                            try {
                                                withContext(exportThread) { active.incrementAndGet() }
                                                events.add("native.$attempt")
                                                if (attempt == 1) throw OutOfMemoryError("retry")
                                                retryReady.complete(Unit)
                                                finishRetry.await()
                                                if (failExport) throw IllegalStateException("encode failed")
                                            } catch (e: OutOfMemoryError) {
                                                if (attempt != 1) throw e
                                            } finally {
                                                withContext(NonCancellable + exportThread) { active.decrementAndGet() }
                                                events.add("release.$attempt")
                                            }
                                        }
                                    } finally {
                                        assertEquals(0, active.get())
                                        events.add("sample.end")
                                    }
                                }
                            }
                            assertEquals(failExport, result.exceptionOrNull() is IllegalStateException)
                        }
                        exportWaiting.await()
                        assertTrue(events.isEmpty())
                        finishCandidate.complete(Unit)
                        candidate.join()
                        retryReady.await()
                        val next = launch {
                            lease.withLease(onWaiting = { nextWaiting.complete(Unit) }) {
                                assertEquals("sample.end", events.last())
                                events.add("next.candidate")
                            }
                        }
                        nextWaiting.await()
                        assertFalse(events.contains("sample.end"))
                        finishRetry.complete(Unit)
                        export.join(); next.join()
                        assertEquals(listOf("sample.start", "native.1", "release.1", "native.2",
                            "release.2", "sample.end", "next.candidate"), events)
                    }
                }
            }
        }
    }
}
