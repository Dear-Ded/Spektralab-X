package com.dearded.spektralab

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dearded.spektralab.advisor.domain.AdviceCandidate
import com.dearded.spektralab.advisor.domain.AdviceConstraints
import com.dearded.spektralab.advisor.domain.AdviceOrigin
import com.dearded.spektralab.advisor.domain.AdviceParameter
import com.dearded.spektralab.advisor.domain.AdviceProjection
import com.dearded.spektralab.advisor.domain.EditSnapshot
import com.dearded.spektralab.advisor.domain.ParameterChange
import com.dearded.spektralab.advisor.domain.RequestTicket
import com.dearded.spektralab.advisor.editor.AppliedReceipt
import com.dearded.spektralab.advisor.editor.SaveCoordinator
import com.dearded.spektralab.advisor.editor.SaveReceipt
import com.dearded.spektralab.advisor.editor.SaveTarget
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AssistantPreferenceStoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val file = File(app.noBackupFilesDir, "assistant-preferences.json")
    private var job = Job()

    @After fun close() = runBlocking { job.cancelAndJoin() }

    @Test fun permissionsDefaultOffAndPersistIndependentlyInNoBackup() = runBlocking {
        var store = open()
        assertEquals(AssistantPreferences(), store.settled())
        assertNull(offer().capture(store))
        assertFalse(file.exists())

        store.useRules(true)
        assertEquals(AssistantPreferences(useRules = true), store.settled())
        assertNull(offer().capture(store))
        assertTrue(file.isFile)
        assertFalse(File(app.filesDir, file.name).exists())
        assertFalse(snapshot().has("rules"))
        assertEquals(0, count())

        store = open()
        assertEquals(AssistantPreferences(useRules = true), store.settled())
        store.learn(true)
        assertEquals(AssistantPreferences(learn = true, useRules = true), store.settled())
        store.useRules(false)
        assertEquals(AssistantPreferences(learn = true), store.settled())

        store = open()
        assertEquals(AssistantPreferences(learn = true), store.settled())
        assertNotNull(offer().capture(store))
        assertFalse(snapshot().has("rules"))
    }

    @Test fun onlyAnExactSuccessfulReceiptRecordsAndRetryDeduplicatesAcrossRestart() = runBlocking {
        var store = learning()
        val offer = offer()
        val pending = requireNotNull(offer.capture(store))
        val failedWrite = CompletableDeferred<Unit>().apply { completeExceptionally(IOException("photo write failed")) }
        val saves = SaveCoordinator()
        store.record(pending, saves.awaitSaved(offer.commit, offer.target, failedWrite))
        val receipt = offer.success
        store.record(pending, receipt.copy(target = SaveTarget.EDITOR_STATE))
        store.record(pending, receipt.copy(commit = offer.commit.copy(commitId = offer().commit.commitId)))
        store.record(pending, receipt.copy(commit = offer.commit.copy(photoSessionId = offer.commit.photoSessionId + 1)))
        store.record(pending, receipt.copy(commit = offer.commit.copy(revision = offer.commit.revision + 1)))
        drain(store)
        assertEquals(0, count())

        val retry = saves.awaitSaved(offer.commit, offer.target, CompletableDeferred(Unit))
        store.record(pending, retry)
        store.record(pending, retry)
        store.record(requireNotNull(offer.capture(store)), retry)
        drain(store)
        val json = snapshot()
        assertEquals(setOf("version", "learn", "useRules", "generation", "clearEpoch", "adoptions"), json.keys().asSequence().toSet())
        assertEquals(2, json.getInt("version"))
        assertEquals(1, count())
        val record = json.getJSONArray("adoptions").getJSONObject(0)
        assertEquals(setOf("origin", "registryVersion", "commitId", "photoSessionId", "revision", "target", "entry", "offered", "final", "active"),
            record.keys().asSequence().toSet())
        assertTrue(record.getBoolean("active"))
        assertEquals("model", record.getString("origin"))
        assertEquals(AdviceParameter.VERSION, record.getString("registryVersion"))
        assertEquals(offer.commit.commitId, record.getString("commitId"))
        assertEquals(offer.commit.photoSessionId, record.getLong("photoSessionId"))
        assertEquals(offer.commit.revision, record.getLong("revision"))
        assertEquals(offer.target.name, record.getString("target"))
        assertScalars(offer.entry.edit, record.getJSONObject("entry"))
        assertScalars(offer.candidate.edit, record.getJSONObject("offered"))
        assertScalars(offer.candidate.edit, record.getJSONObject("final"))
        assertFalse(file.readText().contains(ORIGIN.bindingId))

        store = open()
        store.record(pending, retry)
        store.record(requireNotNull(offer.capture(store)), retry)
        drain(store)
        assertEquals(1, count())
    }

    @Test fun restoresVersionOneEvidenceAsInactiveWithItsOriginalValues() = runBlocking {
        var store = learning()
        val offer = offer()
        store.record(requireNotNull(offer.capture(store)), offer.success)
        drain(store)
        val previous = snapshot().put("version", 1)
        val record = previous.getJSONArray("adoptions").getJSONObject(0)
        record.remove("active")
        job.cancelAndJoin()
        file.writeText(previous.toString())

        store = open()
        assertTrue(store.preferences.value.learn)
        drain(store)
        assertEquals(2, snapshot().getInt("version"))
        val migrated = snapshot().getJSONArray("adoptions").getJSONObject(0)
        assertFalse(migrated.getBoolean("active"))
        migrated.remove("active")
        assertEquals(record.toString(), migrated.toString())
    }

    @Test fun undoBeforeRecordingRejectsTheLateApplyReceiptAndRedoDoesNotCreateEvidence() = runBlocking {
        val store = learning()
        val offer = offer()
        val pending = requireNotNull(offer.capture(store))
        store.undo(pending)
        store.record(pending, offer.success)
        store.redo(pending) { error("An unrecorded apply does not require a redo receipt") }
        drain(store)
        assertEquals(0, count())
    }

    @Test fun deactivationSurvivesAPreferenceWriteFailureUntilTheNextSave() = runBlocking {
        val store = learning()
        val offer = offer()
        val pending = requireNotNull(offer.capture(store))
        store.record(pending, offer.success)
        drain(store)
        val blocked = File(file.parentFile, file.name + ".new")
        assertTrue(blocked.mkdir())
        try {
            store.undo(pending)
            withTimeout(8_000) { store.preferences.first { it.error == AssistantError.Save } }
        } finally { assertTrue(blocked.delete()) }
        drain(store)
        open()
        assertFalse(snapshot().getJSONArray("adoptions").getJSONObject(0).getBoolean("active"))
    }

    @Test fun failedClearInvalidatesAnInFlightRedoReceipt() = runBlocking {
        val store = learning()
        val offer = offer()
        val pending = requireNotNull(offer.capture(store))
        store.record(pending, offer.success)
        store.undo(pending)
        drain(store)
        val redo = CompletableDeferred<Unit>()
        store.redo(pending) { redo }
        val blocked = File(file.parentFile, file.name + ".new")
        assertTrue(blocked.mkdir())
        try {
            store.clearPreferences()
            assertEquals(AssistantError.Save, store.settled().error)
        } finally { assertTrue(blocked.delete()) }
        redo.complete(Unit)
        store.useRules(false)
        assertEquals(AssistantError.Save, store.settled().error)
        assertFalse(snapshot().getJSONArray("adoptions").getJSONObject(0).getBoolean("active"))
    }

    @Test fun captureRejectsOfflineManualNonScalarAndChangedOrMismatchedCommits() = runBlocking {
        val store = learning()
        val offer = offer()
        val original = offer.candidate
        assertNull(offer(origin = AdviceOrigin.Local).capture(store))
        val adjusted = AdviceCandidate(original.edit, original.changes, original.ticket, ORIGIN,
            manuallyAdjusted = true, correctionOf = original)
        assertNull(Offer(offer.entry, adjusted, offer.target).capture(store))
        val nonScalar = AdviceCandidate(original.edit.copy(filmEdge = !original.edit.filmEdge),
            original.changes, original.ticket, ORIGIN)
        assertNull(Offer(offer.entry, nonScalar, offer.target).capture(store))
        assertNull(store.capture(offer.entry, original, offer.entry.edit, offer.commit, offer.target))
        assertNull(store.capture(offer.entry, original, original.edit, offer.commit.copy(revision = 99), offer.target))
        assertEquals(0, count())
    }

    @Test fun clearRemovesSavedEvidenceAndInvalidatesAnOutstandingSave() = runBlocking {
        var store = learning()
        val saved = offer(target = SaveTarget.EDITOR_STATE)
        store.record(requireNotNull(saved.capture(store)), saved.success)
        drain(store)
        assertEquals(1, count())
        val oldEpoch = snapshot().getLong("clearEpoch")
        val outstanding = offer()
        val pending = requireNotNull(outstanding.capture(store))

        store.clearPreferences()
        store.record(pending, outstanding.success)
        assertEquals(AssistantPreferences(learn = true, useRules = true), store.settled())
        store.record(pending, outstanding.success)
        drain(store)
        assertEquals(oldEpoch + 1, snapshot().getLong("clearEpoch"))
        assertEquals(0, count())

        store = open()
        store.record(pending, outstanding.success)
        drain(store)
        assertEquals(0, count())
        val fresh = offer()
        store.record(requireNotNull(fresh.capture(store)), fresh.success)
        drain(store)
        assertEquals(1, count())
    }

    @Test fun pauseAndReenableNeverReplayButUseRulesDoesNotInvalidateCapture() = runBlocking {
        var store = learning()
        val delayed = offer()
        val pending = requireNotNull(delayed.capture(store))
        val generation = snapshot().getLong("generation")

        store.learn(false)
        store.record(pending, delayed.success)
        assertFalse(store.settled().learn)
        assertNull(offer().capture(store))
        store.learn(true)
        store.record(pending, delayed.success)
        assertTrue(store.settled().learn)
        store.record(pending, delayed.success)
        assertEquals(generation + 2, snapshot().getLong("generation"))

        val current = offer()
        val fresh = requireNotNull(current.capture(store))
        drain(store)
        assertEquals(0, count())
        store.record(fresh, current.success)
        drain(store)
        assertEquals(current.commit.commitId, snapshot().getJSONArray("adoptions").getJSONObject(0).getString("commitId"))

        store.learn(false)
        assertFalse(store.settled().learn)
        store = open()
        assertFalse(store.preferences.value.learn)
        assertEquals(1, count())
    }

    @Test fun collectionWriteFailureLeavesPhotoSuccessAndDoesNotReplayTheAppend() = runBlocking {
        val store = learning()
        val previous = file.readText()
        val offer = offer()
        val pending = requireNotNull(offer.capture(store))
        val blocked = File(file.parentFile, file.name + ".new")
        assertTrue(blocked.mkdir())
        val photoWrite = File(app.filesDir, "saved-photo-fixture")
        val write = CompletableDeferred<Unit>()
        photoWrite.writeText("successful photo writer payload")
        write.complete(Unit)
        val receipt = SaveCoordinator().awaitSaved(offer.commit, offer.target, write)
        try {
            store.record(pending, receipt)
            withTimeout(8_000) { store.preferences.first { it.error == AssistantError.Save } }
            assertTrue(receipt.success)
            assertEquals("successful photo writer payload", photoWrite.readText())
            assertEquals(previous, file.readText())
        } finally { assertTrue(blocked.delete()) }

        store.record(pending, receipt)
        drain(store)
        assertEquals(0, count())
        val next = offer()
        store.record(requireNotNull(next.capture(store)), next.success)
        drain(store)
        assertEquals(1, count())
    }

    @Test fun unreadableAndUnknownSnapshotsArePreservedUntilExplicitClear() = runBlocking {
        for (raw in listOf("not JSON", """{"version":99}""")) {
            job.cancelAndJoin()
            file.writeText(raw)
            val store = open()
            assertEquals(AssistantPreferences(error = AssistantError.Preferences), store.settled())
            store.learn(true)
            store.useRules(true)
            assertEquals(AssistantPreferences(error = AssistantError.Preferences), store.settled())
            assertNull(offer().capture(store))
            assertEquals(raw, file.readText())
            store.clearPreferences()
            assertEquals(AssistantPreferences(), store.settled())
            assertEquals(0, count())
        }
    }

    @Test fun aFailedClearDoesNotReplayOnAnUnrelatedSettingChange() = runBlocking {
        val store = learning()
        val first = offer()
        store.record(requireNotNull(first.capture(store)), first.success)
        drain(store)
        val blocked = File(file.parentFile, file.name + ".new")
        assertTrue(blocked.mkdir())
        try {
            store.clearPreferences()
            assertEquals(AssistantError.Save, store.settled().error)
        } finally { assertTrue(blocked.delete()) }
        store.useRules(false)
        assertEquals(AssistantPreferences(learn = true, error = AssistantError.Save), store.settled())
        assertEquals(1, count())
        assertNull(offer().capture(store))
        store.clearPreferences()
        assertEquals(AssistantPreferences(learn = true), store.settled())
        assertEquals(0, count())
        assertNotNull(offer().capture(store))
    }

    @Test fun retainsOnlyTheNewest64AndCannotReplayAnEvictedPendingToken() = runBlocking {
        val store = learning()
        val first = offer()
        val pending = requireNotNull(first.capture(store))
        store.record(pending, first.success)
        val newer = List(64) { offer() }
        newer.chunked(16).forEach { chunk ->
            chunk.forEach { store.record(requireNotNull(it.capture(store)), it.success) }
            drain(store)
        }
        store.record(pending, first.success)
        drain(store)
        val records = snapshot().getJSONArray("adoptions")
        assertEquals(newer.map { it.commit.commitId }, (0 until records.length()).map { records.getJSONObject(it).getString("commitId") })
        assertTrue(file.length() < 256 * 1024)
    }

    private suspend fun open(): AssistantPreferenceStore {
        job.cancelAndJoin()
        job = Job()
        return AssistantPreferenceStore(app, CoroutineScope(job)).also { it.settled() }
    }

    private suspend fun learning() = open().also {
        it.learn(true)
        assertEquals(AssistantPreferences(learn = true), it.settled())
    }

    private suspend fun AssistantPreferenceStore.settled() = withTimeout(8_000) { preferences.first { !it.busy } }

    private suspend fun drain(store: AssistantPreferenceStore) {
        val rules = !store.preferences.value.useRules
        store.useRules(rules) // FIFO barrier without a learning generation or clear epoch change.
        assertEquals(rules, store.settled().useRules)
        assertNull(store.preferences.value.error)
    }

    private fun snapshot() = JSONObject(file.readText())
    private fun count() = snapshot().getJSONArray("adoptions").length()

    private fun assertScalars(edit: Edit, json: JSONObject) {
        assertEquals(setOf("stored", "effective"), json.keys().asSequence().toSet())
        val expected = mapOf("stored" to AdviceParameter.values(edit.look), "effective" to AdviceParameter.values(edit.look.resolved()))
        for ((kind, values) in expected) {
            val actual = json.getJSONObject(kind)
            assertEquals(values.keys, actual.keys().asSequence().toSet())
            for ((parameter, value) in values) assertEquals(value, actual.getDouble(parameter), 0.0)
        }
    }

    private class Offer(val entry: EditSnapshot, val candidate: AdviceCandidate, val target: SaveTarget) {
        val commit = AppliedReceipt(candidate.id, entry.ticket.photoSessionId, entry.ticket.formalRevision + 1)
        val success = SaveReceipt(commit, target, true)
        fun capture(store: AssistantPreferenceStore) = store.capture(entry, candidate, candidate.edit, commit, target)
    }

    private fun offer(origin: AdviceOrigin = ORIGIN, target: SaveTarget = SaveTarget.SIDECAR): Offer {
        val ticket = RequestTicket(11, 13, 0, 1, 0, 0, bindingId = ORIGIN.bindingId, endpointId = "private-endpoint")
        val entry = EditSnapshot(Catalog.EMPTY.normalize(Edit.NONE), ticket)
        val changes = listOf(ParameterChange(AdviceParameter.EXPOSURE.id, .4))
        val candidate = AdviceProjection.candidate(entry, changes, AdviceConstraints.all(), Catalog.EMPTY, ticket, origin)
        return Offer(entry, candidate, target)
    }

    private companion object {
        val ORIGIN = AdviceOrigin.Model("00000000-0000-0000-0000-000000000007", 1, "catalog.1", "adapter.1", "prompt.1")
    }
}
