package com.dearded.spektralab

import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.dearded.spektralab.advisor.domain.AdviceError
import com.dearded.spektralab.advisor.domain.AdviceOrigin
import com.dearded.spektralab.advisor.domain.AdviceResult
import com.dearded.spektralab.advisor.domain.ParameterChange
import com.dearded.spektralab.advisor.network.AdvisorReply
import com.dearded.spektralab.advisor.network.AdvisorTokenUsage
import com.dearded.spektralab.advisor.session.AdviceConnection
import com.dearded.spektralab.advisor.session.AdviceConnectionIdentity
import com.dearded.spektralab.advisor.session.DraftPhase
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real editor commands and files, with a synthetic published frame in place of JNI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdvisorEditorIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<SpektraLabApp>()
    private val store = ViewModelStore()
    private val editor = EditorViewModel(app, startEngineWork = false).also { store.put("editor", it) }
    private val file = File(app.filesDir, "editor-state.json")
    private val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)

    @Suppress("UNCHECKED_CAST")
    private val ui = field("_ui").get(editor) as MutableStateFlow<UiState>
    @Suppress("UNCHECKED_CAST")
    private val publication = field("previewPublication").get(editor) as PreviewPublication<Edit>
    private val writer = field("editorState").get(editor) as EditorStateAutosave

    private fun field(name: String) = EditorViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun opening(): Edit {
        val edit = Catalog.EMPTY.normalize(Edit.NONE)
        ui.value = ui.value.copy(ready = true, busy = null, source = SourceInfo("synthetic", 32, 32, 32, 32, "photo", ""),
            previewSource = 1, edit = edit)
        field("session").setLong(editor, 1L)
        field("wanted").set(editor, edit)
        field("lastUri").set(editor, Uri.parse("content://synthetic/photo"))
        publication.replaceSource(1L)
        present(edit)
        return edit
    }

    private fun present(edit: Edit) {
        assertTrue(publication.publish(publication.frame(1L, publication.request(edit), "preview", "sRGB")))
        ui.value = ui.value.copy(image = bitmap, imageTier = "preview")
    }

    private fun draft(formal: Edit): Edit {
        val proposed = formal.copy(look = formal.look.copy(exposure = .4))
        editor.assistantSession.begin()
        present(proposed)
        editor.assistantSession.propose(listOf(ParameterChange("layer2.exposure", .4)))
        return proposed
    }

    private suspend fun until(predicate: () -> Boolean) = withTimeout(8_000) {
        while (!predicate()) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(5)
        }
    }

    private fun persisted() = Sidecar.fromJson(JSONObject(file.readText()))?.edit

    @After fun close() {
        field("session").setLong(editor, 0)
        store.clear()
        bitmap.recycle()
    }

    @Test fun acceptedDraftRetriesTheSameSaveAndUndoesBetweenTwoOrdinaryEdits() = runBlocking {
        val base = opening()
        val manual = base.copy(look = base.look.copy(temperature = 5.0))
        editor.update(manual)
        writer.flush()
        val proposed = draft(manual)
        until { editor.assistantSession.state.value.phase == DraftPhase.PREVIEWING }
        assertEquals(manual, ui.value.edit)
        assertEquals(manual, persisted())
        val blocked = File(file.parentFile, file.name + ".part")
        assertTrue(blocked.mkdir())
        try {
            editor.assistantSession.apply()
            until { editor.assistantSession.state.value.error == AdviceError.SAVE_FAILED }
            assertEquals(proposed, ui.value.edit)
            assertEquals(manual, persisted())
        } finally { assertTrue(blocked.delete()) }
        editor.assistantSession.retrySave()
        until { editor.assistantSession.state.value.phase == DraftPhase.SAVED }
        assertEquals(proposed, persisted())

        val later = proposed.copy(look = proposed.look.copy(tint = 6.0))
        editor.update(later)
        editor.undo(); assertEquals(proposed, ui.value.edit)
        editor.undo(); assertEquals(manual, ui.value.edit)
        editor.undo(); assertEquals(base, ui.value.edit)
        editor.assistantSession.retrySave()
        writer.flush()
        assertEquals(base, persisted())
    }

    @Test fun bothPhotoExportsQueueTheFormalEditAndDoNotCommitDraft() = runBlocking {
        val formal = opening()
        val queue = app.host.queue
        queue.pause("advisor integration fixture", serviceStopped = true)
        val existing = queue.jobs.value.map { it.id }.toSet()
        val proposed = draft(formal)
        until { editor.assistantSession.state.value.hasPreview }
        assertEquals(proposed, publication.shownFrame()?.intent?.edit)
        // Supply the restored engine publication to the VM's existing restore path.
        present(formal)
        editor.export(ExportFormat.TIFF)
        until { queue.jobs.value.count { it.id !in existing } == 1 }
        editor.exportExtreme()
        until { queue.jobs.value.count { it.id !in existing } == 2 }
        val jobs = queue.jobs.value.filter { it.id !in existing }
        assertEquals(listOf(ExportFormat.TIFF, ExportFormat.JPEG), jobs.map { it.format })
        assertEquals(listOf(false, true), jobs.map { it.extreme })
        assertTrue(jobs.all { it.editJson == formal.toJson().toString() && it.lookJson.isEmpty() })
        assertTrue(editor.assistantSession.state.value.showingEntry)
        assertEquals(formal, ui.value.edit)
        assertFalse(ui.value.canUndo)
        jobs.forEach { editor.cancelJob(it.id) }
    }

    @Test fun onlyAnAcknowledgedModelApplyEntersLocalPreferences() = runBlocking {
        val formal = opening()
        val proposed = formal.copy(look = formal.look.copy(exposure = .4))
        val preferences = editor.assistantPreferences
        until { !preferences.preferences.value.busy }
        preferences.learn(true)
        until { preferences.preferences.value.learn }
        val snapshot = File(app.noBackupFilesDir, "assistant-preferences.json")
        fun adoptions() = JSONObject(snapshot.readText()).getJSONArray("adoptions")
        val session = editor.assistantSession
        val binding = "98479a84-21f7-4a7e-9560-5007bd05124c"
        val identity = AdviceConnectionIdentity(1, "fixture", 1, 1, binding, 1, "catalog.test", "adapter.test")
        val origin = AdviceOrigin.Model(binding, 1, identity.catalogVersion, identity.adapterVersion, "prompt.test")
        session.configure(AdviceConnection(identity, origin) { _, _ ->
            AdvisorReply(AdviceResult.Proposal(listOf(ParameterChange("layer2.exposure", .4))), AdvisorTokenUsage())
        })
        session.begin()
        present(proposed)
        session.generate("synthetic model draft")
        until { session.state.value.hasPreview }
        val candidate = checkNotNull(session.state.value.draft)
        assertEquals(0, adoptions().length())
        val blocked = File(file.parentFile, file.name + ".part")
        assertTrue(blocked.mkdir())
        try {
            session.apply()
            until { session.state.value.error == AdviceError.SAVE_FAILED }
            assertEquals(0, adoptions().length())
        } finally { assertTrue(blocked.delete()) }
        session.retrySave()
        until { session.state.value.phase == DraftPhase.SAVED }
        until { adoptions().length() == 1 }
        assertEquals(proposed, persisted())
        assertEquals(candidate.id, adoptions().getJSONObject(0).getString("commitId"))
    }
}
