package com.dearded.spektralab

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Production restart-state writer, co-created with GPT (OpenAI). No JNI or UI collector. */
class EditorStateAutosaveTest {
    private val first = EditorStateSave("file:///one.dng", "one", "one.dng", true,
        Edit.NONE.copy(exposureEv = 0.75), 4700.0, -0.12)
    private val second = EditorStateSave("file:///two.jpg", null, "two.jpg", false,
        Edit.NONE.copy(exposureEv = -0.5), 0.0, 0.0)

    private suspend fun scenario(block: suspend (CoroutineScope) -> Unit) {
        val owner = SupervisorJob()
        try { withTimeout(5000) { block(CoroutineScope(owner)) } }
        finally { owner.cancelAndJoin() }
    }

    @Test fun snapshotsKeepTheirSourceRecipeAndWbTogether() {
        val json = JSONObject(first.toJson())
        assertEquals(first.uri, json.getString("uri"))
        assertEquals(first.frameId, json.getString("frameId"))
        val restored = Sidecar.fromJson(json)!!
        assertEquals(first.edit, restored.edit)
        assertEquals(first.wbK, restored.wbK, 0.0)
        assertEquals(first.wbTint, restored.wbTint, 0.0)
        assertFalse(JSONObject(second.toJson()).has("frameId"))
    }

    @Test fun backgroundFlushWritesTheLatestSnapshotWithoutWaitingForDebounce() = runBlocking {
        scenario { scope ->
            val written = mutableListOf<EditorStateSave>()
            val saves = EditorStateAutosave(scope, write = written::add)
            saves.changed(first)
            saves.changed(second)
            saves.requestFlush()
            saves.flush()
            assertEquals(listOf(second), written)
        }
    }

    @Test fun disposalFlushesPendingStateAndStopsItsWorker() = runBlocking {
        scenario { scope ->
            val written = mutableListOf<EditorStateSave>()
            val saves = EditorStateAutosave(scope, write = written::add)
            saves.changed(first)
            saves.close()
            assertEquals(listOf(first), written)
            assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.children.none())
        }
    }

    @Test fun aNewSnapshotCannotBeOverwrittenByAnOlderInFlightWrite() = runBlocking {
        scenario { scope ->
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val written = mutableListOf<EditorStateSave>()
            val saves = EditorStateAutosave(scope) {
                if (it == first) {
                    entered.complete(Unit)
                    check(release.await(4, TimeUnit.SECONDS))
                }
                written.add(it)
            }
            try {
                saves.changed(first)
                saves.requestFlush()
                entered.await()
                saves.changed(second)
                val flush = scope.async { saves.flush() }
                assertFalse(flush.isCompleted)
                release.countDown()
                flush.await()
                assertEquals(listOf(first, second), written)
            } finally { release.countDown() }
        }
    }

    @Test fun cancellingTheEditorDoesNotCancelTheProcessOwnedSave() = runBlocking {
        scenario { scope ->
            val written = mutableListOf<EditorStateSave>()
            val saves = EditorStateAutosave(scope, write = written::add)
            val editor = launch { saves.changed(first); saves.requestFlush() }
            editor.join()
            editor.cancelAndJoin()
            saves.close()
            assertEquals(listOf(first), written)
        }
    }

    @Test fun failuresReachTheFlushCallerRetainPendingStateAndReportOnceUntilRecovery() = runBlocking {
        scenario { scope ->
            var failing = true
            var failures = 0
            val written = mutableListOf<EditorStateSave>()
            val saves = EditorStateAutosave(scope, onFailure = { failures++ }) {
                if (failing) throw IOException("synthetic storage full")
                written.add(it)
            }
            saves.changed(first)
            repeat(2) {
                try { saves.flush(); fail("write must fail") } catch (_: IOException) { }
            }
            assertEquals(1, failures)
            saves.changed(second)
            failing = false
            saves.flush()
            assertEquals(listOf(second), written)
            failing = true
            saves.changed(first)
            try { saves.flush(); fail("write must fail") } catch (_: IOException) { }
            assertEquals(2, failures)
            failing = false
            saves.close()
        }
    }

    @Test fun quietStateIsSavedEvenWithoutAnExplicitFlush() = runBlocking {
        scenario { scope ->
            val written = CompletableDeferred<EditorStateSave>()
            val saves = EditorStateAutosave(scope) { written.complete(it) }
            saves.changed(first)
            assertEquals(first, written.await())
        }
    }
}
