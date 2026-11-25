package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.*
import com.dearded.spektralab.advisor.editor.*
import com.dearded.spektralab.advisor.network.*
import com.dearded.spektralab.advisor.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AssistantSessionPortTest {
    @Test fun offlineDraftComparesDiscardsAndClosesThroughVerifiedRestores() = withPort {
        canBegin = false
        port.open()
        assertEquals(DraftPhase.IDLE, controller.state.value.phase)
        assertEquals(0, editor.captures)
        canBegin = true
        port.open()
        port.statistics(true)
        shown { it.statistics }
        assertFalse(port.view.value.cinematicReady)
        ui.value = ui.value.copy(ready = true)
        shown { it.cinematicReady }

        val original = editor.formal
        port.tryPreset(AssistantPreset.Brighten)
        val preview = shown { it.phase == AssistantPhase.Previewing }
        assertEquals(listOf(AssistantChange(AssistantParam.EXPOSURE, 0.0, .35)), preview.changes)
        assertTrue(preview.local)
        assertTrue(preview.canApply)
        assertEquals(original, editor.formal)
        assertNotEquals(original, editor.displayed)
        port.showCurrent(true)
        shown { it.phase == AssistantPhase.Previewing && it.showingCurrent }
        assertEquals(original, editor.displayed)
        port.showCurrent(false)
        shown { it.phase == AssistantPhase.Previewing && !it.showingCurrent }

        editor.restoreGate = CompletableDeferred()
        port.discard()
        shown { it.phase == AssistantPhase.Busy }
        assertNotEquals(original, editor.displayed)
        assertEquals(0, editor.releases)
        editor.restoreGate!!.complete(Unit)
        val discarded = shown { it.phase == AssistantPhase.Ready }
        assertEquals(original, editor.displayed)
        assertEquals(1, editor.releases)
        assertEquals(1, editor.captures)
        assertTrue(discarded.statistics)
        assertTrue(discarded.changes.isEmpty())
        assertNull(discarded.strength)

        port.tryPreset(AssistantPreset.Soften)
        assertEquals(listOf(AssistantChange(AssistantParam.CONTRAST, 0.0, -8.0)),
            shown { it.phase == AssistantPhase.Previewing }.changes)
        editor.restoreGate = CompletableDeferred()
        port.close()
        port.open() // Reopening before the old preview is released must not capture it.
        assertEquals(1, editor.captures)
        editor.restoreGate!!.complete(Unit)
        controller.state.first { it.phase == DraftPhase.READY }
        shown { it.phase == AssistantPhase.Ready && !it.statistics }
        assertEquals(2, editor.captures)
        assertEquals(2, editor.releases)
        assertEquals(original, editor.displayed)
        assertEquals(0, editor.commits)
        port.openSettings()
        assertEquals(1, settings)
    }

    @Test fun typedQuestionAndRenderedDiffUseTheRealSessionWithoutSceneEvidence() = withPort {
        val endpoint = EndpointConfig("test", 1, "Test Service", "https://example.test/v1".toHttpUrl(),
            AdvisorTransport.CHAT, AdvisorDialect.OPENAI, AdvisorAuth.BEARER)
        val binding = ModelBinding("binding", 1, endpoint.id, "test-model")
        val configuration = AdvisorConfiguration(1, listOf(endpoint), listOf(binding), binding.id)
        runtime.value = AdvisorRuntimeState(configuration = configuration)
        assertFalse(port.view.value.configured)
        val received = mutableListOf<Pair<AdviceRequest, TaskClarification?>>()
        controller.configure(AdviceConnection(AdviceConnectionIdentity(1, endpoint.id, 1, 0, binding.id, 1,
            "catalog", "chat")) { request, clarification ->
            received += request to clarification
            val result = if (clarification == null) AdviceResult.Question("choice", "How strong?",
                listOf(AdviceResult.Option("soft", "Subtle"), AdviceResult.Option("strong", "Strong")))
            else AdviceResult.Proposal(listOf(ParameterChange(AdviceParameter.SATURATION.id, 12.0),
                ParameterChange(AdviceParameter.EXPOSURE.id, .4)))
            AdvisorReply(result, AdvisorTokenUsage())
        })
        runtime.value = runtime.value.copy(configurationReady = true)
        val configured = shown { it.configured }
        assertEquals("Test Service", configured.service)
        assertEquals("test-model", configured.model)
        port.open()
        port.statistics(true)
        assertTrue(received.isEmpty())
        port.generate("Keep the color restrained.")
        assertEquals(AssistantQuestion("How strong?", listOf("Subtle", "Strong")), shown { it.question != null }.question)
        port.answer("Subtle")
        val draft = shown { it.phase == AssistantPhase.Previewing }
        assertFalse(draft.local)
        assertEquals(mapOf(AssistantParam.EXPOSURE to .4, AssistantParam.SATURATION to 12.0),
            draft.changes.associate { it.param to it.after })
        assertEquals(2, received.size)
        assertEquals("soft", received.last().second?.answer)
        for ((request, clarification) in received) {
            assertEquals("Keep the color restrained.", request.goal)
            assertNotNull(request.statistics?.scene)
            val json = JSONObject(RequestContextBuilder.parameters(request, clarification))
            assertTrue(json.has("current_draft_statistics"))
            assertFalse(json.has("scene_evidence"))
        }

        port.setStrength(0)
        val zero = shown { it.phase == AssistantPhase.Previewing && it.strength == 0 }
        assertTrue(zero.changes.isEmpty())
        assertFalse(zero.canApply)
        assertTrue(zero.canSetStrength)
        port.setStrength(100)
        shown { it.phase == AssistantPhase.Previewing && it.strength == 100 }
        port.adjust(AssistantParam.EXPOSURE, .2)
        val corrected = shown { it.changes.any { c -> c.param == AssistantParam.EXPOSURE && c.after == .2 } }
        assertEquals(12.0, corrected.changes.single { it.param == AssistantParam.SATURATION }.after, 0.0)
        assertEquals(2, received.size)
    }

    @Test fun closingDuringApplyPreservesTheCommitAndSaveRetry() = withPort {
        editor.commitGate = CompletableDeferred()
        port.open()
        port.tryPreset(AssistantPreset.Brighten)
        shown { it.canApply }
        port.apply()
        shown { it.phase == AssistantPhase.Busy }
        port.close()
        assertFalse(controller.state.value.visible)
        editor.commitGate!!.complete(Unit)
        editor.saved.completeExceptionally(IOException("test write error"))
        shown { it.error == AssistantError.Save }
        assertEquals(1, editor.commits)
        assertEquals(0, editor.releases)
        assertFalse(controller.state.value.visible)

        port.open()
        assertTrue(controller.state.value.visible)
        port.retrySave()
        shown { it.phase == AssistantPhase.Saved }
        assertEquals(1, editor.commits)
        assertEquals(1, editor.retries)
        port.discard()
        assertEquals(DraftPhase.SAVED, controller.state.value.phase)
        assertEquals(editor.formal, editor.displayed)
        port.undo()
        assertEquals(1, undos)
        port.close()
        assertEquals(DraftPhase.IDLE, controller.state.value.phase)
    }

    @Test fun failedDiscardKeepsThePreviewButFailedCloseHandsItBack() = withPort {
        port.open()
        port.tryPreset(AssistantPreset.Brighten)
        shown { it.canApply }
        editor.restoreSuccess = false
        port.discard()
        val failed = shown { it.phase == AssistantPhase.Failed }
        assertEquals(AssistantError.Render, failed.error)
        assertEquals(0, editor.releases)
        assertNotEquals(editor.formal, editor.displayed)

        port.close()
        controller.state.first { it.phase == DraftPhase.FAILED }
        assertEquals(1, editor.releases)
        var exports = 0
        controller.prepareExport { exports++ }
        controller.state.first { it.phase == DraftPhase.FAILED }
        assertEquals(0, exports)
    }

    private fun withPort(test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try { withTimeout(5000) { Fixture(scope).test() } } finally { scope.cancel() }
    }

    private class Fixture(scope: CoroutineScope) {
        val editor = Editor()
        val controller = AdviceSessionController(scope, editor)
        val runtime = MutableStateFlow(AdvisorRuntimeState())
        val ui = MutableStateFlow(UiState(previewSource = 1))
        var canBegin = true
        var undos = 0
        var settings = 0
        val port = AssistantSessionPort(scope, controller, runtime, ui, { canBegin }, { ui.value.ready },
            { undos++ }, { settings++ })
        suspend fun shown(predicate: (AssistantView) -> Boolean) = port.view.first(predicate)
    }

    private class Editor : AdvisorEditorPort {
        var formal = Catalog.EMPTY.normalize(Edit.NONE).let { it.copy(look = it.look.copy(palette = 50.0)) }
        var displayed = formal
        var captures = 0
        var releases = 0
        var commits = 0
        var retries = 0
        var restoreSuccess = true
        var restoreGate: CompletableDeferred<Unit>? = null
        var commitGate: CompletableDeferred<Unit>? = null
        val saved = CompletableDeferred<Unit>()
        private val receipt = AppliedReceipt("test-commit", 1, 2)

        override fun capture() = EditSnapshot(formal, RequestTicket(1, 1, 0, 1, 0, 0)).also { captures++ }
        override fun catalog() = Catalog.EMPTY
        override fun current(snapshot: EditSnapshot) = snapshot.edit == formal
        override suspend fun render(edit: Edit, entry: EditSnapshot): Boolean {
            if (!current(entry)) return false
            displayed = edit
            return true
        }
        override suspend fun restore(entry: EditSnapshot): Boolean {
            restoreGate?.await()
            return restoreSuccess && render(entry.edit, entry)
        }
        override suspend fun statistics(snapshot: EditSnapshot, entry: EditSnapshot) =
            cinematicStatistics(snapshot).copy(scene = SceneEvidence(4, 4, List(16) { .5 }, null))
        override fun endPreview() { releases++ }
        override suspend fun commit(candidate: AdviceCandidate, entry: EditSnapshot): CommittedDraft {
            commitGate?.await()
            check(current(entry))
            formal = candidate.edit
            commits++
            return CommittedDraft(receipt, SaveTarget.SIDECAR, saved)
        }
        override fun retrySave(applied: AppliedReceipt): Deferred<Unit> {
            check(applied == receipt)
            retries++
            return CompletableDeferred(Unit)
        }
    }
}
