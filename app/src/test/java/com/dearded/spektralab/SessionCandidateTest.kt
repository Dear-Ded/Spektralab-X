package com.dearded.spektralab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
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

class SessionCandidateTest {
    private data class Frame(
        val handle: Long, val uri: String, val edit: String,
        val history: List<String>, val image: String, val wb: Int,
    )

    private val old = Frame(1, "old.raw", "cropped", listOf("original"), "old image", 6500)

    @Test fun decodeFailureKeepsTheWholePreviousFrame() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
            val released = mutableListOf<Long>()
            val current = old
            val failure = runCatching {
                withSessionCandidate(engine, released::add) {
                    throw IllegalArgumentException("white balance outside camera response")
                }
            }
            assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
            assertEquals(old, current)
            assertTrue(released.isEmpty())
        }
    }

    @Test fun metadataFailureReleasesOnlyTheCandidate() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
            val released = mutableListOf<Long>()
            val failure = runCatching {
                withSessionCandidate(engine, released::add) { candidate ->
                    withContext(engine) { candidate.own(2) }
                    throw IllegalStateException("invalid source metadata")
                }
            }
            assertTrue(failure.exceptionOrNull() is IllegalStateException)
            assertEquals(listOf(2L), released)
        }
    }

    @Test fun cancellationBeforeAllocationLeavesTheOldSession() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
            val released = mutableListOf<Long>()
            val entered = CompletableDeferred<Unit>()
            val never = CompletableDeferred<Unit>()
            val job = launch {
                withSessionCandidate(engine, released::add) {
                    entered.complete(Unit)
                    never.await()
                }
            }
            withTimeout(5000) { entered.await() }
            job.cancelAndJoin()
            assertTrue(released.isEmpty())
        }
    }

    @Test fun cancellationOnNativeReturnReleasesAnUnreturnedHandle() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
            val released = mutableListOf<Long>()
            val entered = CompletableDeferred<Unit>()
            val finishNative = CountDownLatch(1)
            var current = old
            val job = launch {
                withSessionCandidate(engine, released::add) { candidate ->
                    withContext(engine) {
                        candidate.own(2)
                        entered.complete(Unit)
                        // Model a native decode which returns only after cancellation.
                        finishNative.await()
                    }
                    candidate.commit { current = old.copy(handle = it) }
                }
            }
            try {
                withTimeout(5000) { entered.await() }
                job.cancel()
            } finally {
                finishNative.countDown()
            }
            withTimeout(5000) { job.join() }
            assertEquals(old, current)
            assertEquals(listOf(2L), released)
        }
    }

    @Test fun successfulReopenPreservesEditAndHistoryForCustomAndResetWhite() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
            for (white in listOf(4000, 0)) {
                val released = mutableListOf<Long>()
                var current = old
                var retired = 0L
                withSessionCandidate(engine, released::add) { candidate ->
                    withContext(engine) { candidate.own(2) }
                    val prepared = old.copy(handle = 2, image = "new image", wb = white)
                    assertEquals(old, current)
                    candidate.commit { handle ->
                        assertEquals(prepared.handle, handle)
                        retired = current.handle
                        current = prepared
                    }
                    withContext(engine) { released.add(retired) }
                }
                assertEquals(old.uri, current.uri)
                assertEquals(old.edit, current.edit)
                assertEquals(old.history, current.history)
                assertEquals(white, current.wb)
                assertEquals(listOf(1L), released)
            }
        }
    }

    @Test fun cancellationDuringCommitCannotLeaveHalfPublishedState() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
            val released = mutableListOf<Long>()
            val installing = CompletableDeferred<Unit>()
            val finishCommit = CompletableDeferred<Unit>()
            var current = old
            val replacement = Frame(2, "saved.raw", "saved edit", emptyList(), "new image", 0)
            val job = launch {
                withSessionCandidate(engine, released::add) { candidate ->
                    withContext(engine) { candidate.own(2) }
                    candidate.commit {
                        installing.complete(Unit)
                        finishCommit.await()
                        current = replacement
                    }
                }
            }
            withTimeout(5000) { installing.await() }
            job.cancel()
            assertEquals(old, current)
            assertFalse(job.isCompleted)
            finishCommit.complete(Unit)
            withTimeout(5000) { job.join() }
            assertEquals(replacement, current)
            assertTrue(released.isEmpty())
        }
    }

    @Test fun commitFailureBeforeInstallationReleasesTheCandidate() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
            val released = mutableListOf<Long>()
            val failure = runCatching {
                withSessionCandidate(engine, released::add) { candidate ->
                    withContext(engine) { candidate.own(2) }
                    candidate.commit { throw IllegalStateException("install was not performed") }
                }
            }
            assertTrue(failure.exceptionOrNull() is IllegalStateException)
            assertEquals(listOf(2L), released)
        }
    }

    @Test fun closingDuringCommitWaitsBeforeReleasingTheInstalledSession() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { engine ->
            val released = mutableListOf<Long>()
            val installing = CompletableDeferred<Unit>()
            val finishCommit = CompletableDeferred<Unit>()
            val closingStarted = CompletableDeferred<Unit>()
            var current = 1L
            val opening = launch {
                withSessionCandidate(engine, released::add) { candidate ->
                    withContext(engine) { candidate.own(2) }
                    var retired = 0L
                    try {
                        candidate.commit { replacement ->
                            installing.complete(Unit)
                            finishCommit.await()
                            retired = current
                            current = replacement
                        }
                    } finally {
                        if (retired != 0L) withContext(NonCancellable) {
                            withContext(engine) { released.add(retired) }
                        }
                    }
                }
            }
            withTimeout(5000) { installing.await() }
            val closing = launch {
                opening.cancel()
                closingStarted.complete(Unit)
                opening.join()
                withContext(engine) {
                    released.add(current)
                    current = 0
                }
            }
            withTimeout(5000) { closingStarted.await() }
            assertTrue(released.isEmpty())
            finishCommit.complete(Unit)
            withTimeout(5000) { closing.join() }
            assertEquals(0L, current)
            assertEquals(listOf(1L, 2L), released)
        }
    }
}
