package com.dearded.spektralab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real production autosave queue, without JNI or a UI observer. Co-created with GPT (OpenAI). */
class FrameEditAutosaveTest {
    private val neutral = Edit.NONE
    private val adjusted = neutral.copy(exposureEv = 0.75, look = Look(contrast = 12.0))

    private suspend fun scenario(block: suspend (CoroutineScope) -> Unit) {
        val owner = SupervisorJob()
        try { withTimeout(5000) { block(CoroutineScope(owner)) } }
        finally { owner.cancelAndJoin() }
    }

    @Test fun untouchedOpenDoesNotMarkAFrameEdited() = runBlocking {
        scenario { scope ->
            val written = mutableListOf<FrameEditSave>()
            val saves = FrameEditAutosave(scope, write = written::add)
            saves.opened("a", neutral, 0.0, 0.0, reopening = false)
            saves.changed(neutral)
            saves.flush()
            assertTrue(written.isEmpty())
        }
    }

    @Test fun firstEditBeforeAnyDebounceIsSavedEvenAfterSwitchingPhotos() = runBlocking {
        scenario { scope ->
            val written = mutableListOf<FrameEditSave>()
            val saves = FrameEditAutosave(scope, write = written::add)
            saves.opened("a", neutral, 0.0, 0.0, reopening = false)
            saves.changed(adjusted)
            saves.opened("b", neutral, 0.0, 0.0, reopening = false)
            saves.changed(neutral.copy(exposureEv = -1.0))
            saves.flush()
            assertEquals(listOf("a", "b"), written.map { it.id })
            assertEquals(adjusted, written[0].edit)
            assertEquals(-1.0, written[1].edit.exposureEv, 0.0)
        }
    }

    @Test fun fastChangesCoalescePerFrameAndKeepTheFinalUndoValue() = runBlocking {
        scenario { scope ->
            val written = mutableListOf<FrameEditSave>()
            val saves = FrameEditAutosave(scope, write = written::add)
            saves.opened("a", neutral, 0.0, 0.0, reopening = false)
            repeat(100) { saves.changed(neutral.copy(exposureEv = it / 100.0)) }
            saves.changed(neutral)
            saves.flush()
            assertEquals(listOf(FrameEditSave("a", neutral, 0.0, 0.0)), written)
        }
    }

    @Test fun successfulRawReopenSavesWbAndDevelopmentWhileNewOpensKeepTheirBaseline() = runBlocking {
        scenario { scope ->
            val written = mutableListOf<FrameEditSave>()
            val saves = FrameEditAutosave(scope, write = written::add)
            val developed = adjusted.copy(rawDevelopment = RawDevelopmentMode.STANDARD_V1)
            saves.opened("a", adjusted, 0.0, 0.0, reopening = false)
            saves.opened("a", developed, 4200.0, 0.1, reopening = true)
            saves.flush()
            assertEquals(FrameEditSave("a", developed, 4200.0, 0.1), written.single())
            saves.opened("b", neutral, 0.0, 0.0, reopening = false)
            saves.opened("b", neutral, 0.0, 0.0, reopening = true)
            saves.flush()
            assertEquals(1, written.size)
        }
    }

    @Test fun ordinaryPhotoOpenDetachesSavingAndDoesNotStealPendingEdits() = runBlocking {
        scenario { scope ->
            val written = mutableListOf<FrameEditSave>()
            val saves = FrameEditAutosave(scope, write = written::add)
            saves.opened("a", neutral, 0.0, 0.0, reopening = false)
            saves.changed(adjusted)
            saves.opened(null, neutral, 0.0, 0.0, reopening = false)
            saves.changed(neutral.copy(exposureEv = -2.0))
            saves.flush()
            assertNull(saves.frameId)
            assertEquals(listOf(FrameEditSave("a", adjusted, 0.0, 0.0)), written)
        }
    }

    @Test fun flushWaitsForTheFileWriteBeforeAReopenCanReadIt() = runBlocking {
        scenario { scope ->
            val writing = CompletableDeferred<Unit>()
            val finish = CountDownLatch(1)
            var stored: FrameEditSave? = null
            val saves = FrameEditAutosave(scope) {
                writing.complete(Unit)
                check(finish.await(3, TimeUnit.SECONDS))
                stored = it
            }
            saves.opened("a", neutral, 0.0, 0.0, reopening = false)
            saves.changed(adjusted)
            val flushed = launch { saves.flush() }
            try {
                writing.await()
                assertFalse(flushed.isCompleted)
                assertNull(stored)
            } finally { finish.countDown() }
            flushed.join()
            assertEquals(adjusted, stored!!.edit)
        }
    }

    @Test fun cancellingAReadDoesNotCancelItsAlreadyQueuedWrite() = runBlocking {
        scenario { scope ->
            val writing = CompletableDeferred<Unit>()
            val finish = CountDownLatch(1)
            val written = mutableListOf<FrameEditSave>()
            val saves = FrameEditAutosave(scope) {
                writing.complete(Unit)
                check(finish.await(3, TimeUnit.SECONDS))
                written += it
            }
            saves.opened("a", neutral, 0.0, 0.0, reopening = false)
            saves.changed(adjusted)
            val reader: Job = launch { saves.flush() }
            try { writing.await(); reader.cancelAndJoin() }
            finally { finish.countDown() }
            saves.flush()
            assertEquals(adjusted, written.single().edit)
        }
    }

    @Test fun backgroundFlushWritesWithoutWaitingForTheDebounce() = runBlocking {
        scenario { scope ->
            val written = CompletableDeferred<FrameEditSave>()
            val saves = FrameEditAutosave(scope) { written.complete(it) }
            saves.opened("a", neutral, 0.0, 0.0, reopening = false)
            saves.changed(adjusted)
            saves.requestFlush()
            assertEquals(adjusted, written.await().edit)
        }
    }

    @Test fun failedWriteReleasesTheWaitingReadAndCanBeRetried() = runBlocking {
        scenario { scope ->
            var fail = true
            var failures = 0
            val written = mutableListOf<FrameEditSave>()
            val saves = FrameEditAutosave(scope, onFailure = { failures++ }) {
                if (fail) throw java.io.IOException("synthetic unavailable storage")
                written += it
            }
            saves.opened("a", neutral, 0.0, 0.0, reopening = false)
            saves.changed(adjusted)
            assertTrue(runCatching { saves.flush() }.exceptionOrNull() is java.io.IOException)
            fail = false
            saves.flush()
            assertEquals(1, failures)
            assertEquals(adjusted, written.single().edit)
        }
    }

    @Test fun closingTheEditorFlushesItsLastEditAndStopsItsWorker() = runBlocking {
        scenario { scope ->
            val written = mutableListOf<FrameEditSave>()
            val saves = FrameEditAutosave(scope, write = written::add)
            saves.opened("a", neutral, 0.0, 0.0, reopening = false)
            saves.changed(adjusted)
            saves.close()
            assertEquals(adjusted, written.single().edit)
            assertTrue(scope.coroutineContext[Job]!!.children.none())
        }
    }

    @Test fun anEditArrivingDuringAFileWriteWinsAfterTheNextFlush() = runBlocking {
        scenario { scope ->
            val writing = CompletableDeferred<Unit>()
            val finish = CountDownLatch(1)
            val written = mutableListOf<FrameEditSave>()
            val saves = FrameEditAutosave(scope) {
                if (written.isEmpty()) {
                    writing.complete(Unit)
                    check(finish.await(3, TimeUnit.SECONDS))
                }
                written += it
            }
            saves.opened("a", neutral, 0.0, 0.0, reopening = false)
            saves.changed(adjusted)
            saves.requestFlush()
            try {
                writing.await()
                saves.changed(neutral)
            } finally { finish.countDown() }
            saves.flush()
            assertEquals(listOf(adjusted, neutral), written.map { it.edit })
        }
    }

    @Test fun ordinaryEditingAlsoPersistsWithoutAnExplicitFlush() = runBlocking {
        scenario { scope ->
            val written = CompletableDeferred<FrameEditSave>()
            val saves = FrameEditAutosave(scope) { written.complete(it) }
            saves.opened("a", neutral, 0.0, 0.0, reopening = false)
            saves.changed(adjusted)
            assertEquals(adjusted, written.await().edit)
        }
    }
}
