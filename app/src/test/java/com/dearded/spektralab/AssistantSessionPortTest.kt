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
        val initialScope = scopePrompt()
        assertTrue(received.isEmpty())
        port.answer(initialScope.options.first())
        assertEquals(AssistantQuestion("How strong?", listOf("Subtle", "Strong")), shown { it.question?.prompt == "How strong?" }.question)
        port.answer("Subtle")
        val answerScope = scopePrompt(except = initialScope)
        port.answer(initialScope.options.first())
        assertEquals(1, received.size)
        port.answer(answerScope.options.first())
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

    @Test fun exactWaterTargetDoesNotSampleSendOrRenderAndCannotReuseAnOldConfirmation() = withPort {
        val received = mutableListOf<AdviceRequest>()
        connect { request, _ ->
            received += request
            AdviceResult.Proposal(listOf(ParameterChange(AdviceParameter.EXPOSURE.id, .4)))
        }
        port.open()
        port.statistics(true)
        port.generate("Darken only the water, leaving the rocks unchanged.")
        val water = scopePrompt()
        port.answer(water.options.last())
        shown { it.notice == AssistantNotice.Unsupported }
        port.answer(water.options.first())
        assertTrue(received.isEmpty())
        assertEquals(0, editor.samples)
        assertEquals(0, editor.renders)
        assertEquals(editor.formal, editor.displayed)

        port.generate("Bring up the exposure of the whole photo.")
        val whole = scopePrompt(except = water)
        port.answer(water.options.first())
        assertTrue(received.isEmpty())
        port.answer(whole.options.first())
        port.answer(whole.options.first())
        shown { it.canApply }
        assertEquals(listOf("Bring up the exposure of the whole photo."), received.map { it.goal })
        assertEquals(1, editor.samples)
        assertEquals(1, editor.renders)

        port.generate("Lift only the water next.")
        val followUp = scopePrompt(except = whole)
        port.answer(followUp.options.last())
        shown { it.notice == AssistantNotice.Unsupported }
        assertEquals(1, received.size)
        assertEquals(1, editor.samples)
        assertEquals(1, editor.renders)
        assertTrue(port.view.value.canApply)
        assertEquals(.4, editor.displayed.look.exposure, 0.0)
        assertEquals(0.0, editor.formal.look.exposure, 0.0)
    }

    @Test fun exactRegionInAFreeTextClarificationIsNeverDispatched() = withPort {
        val received = mutableListOf<TaskClarification?>()
        connect { _, clarification ->
            received += clarification
            AdviceResult.Question("text", "Which area?", emptyList())
        }
        port.open()
        port.generate("Bring up the exposure.")
        port.answer(scopePrompt().options.first())
        shown { it.question?.prompt == "Which area?" }
        port.answer("The water only; leave the rocks alone.")
        val answerScope = scopePrompt()
        port.answer(answerScope.options.last())
        shown { it.notice == AssistantNotice.Unsupported }
        assertEquals(listOf<TaskClarification?>(null), received)
        assertEquals(0, editor.samples)
        assertEquals(0, editor.renders)
    }

    @Test fun sourceAndStatisticsChangesRequireFreshScopeEvenForTheSameGoal() = withPort {
        val received = mutableListOf<AdviceRequest>()
        connect { request, _ -> received += request; AdviceResult.NoChange }
        port.open()
        val goal = "Raise the exposure."
        port.generate(goal)
        val oldSource = scopePrompt()
        editor.source = 2
        ui.value = ui.value.copy(previewSource = 2)
        port.answer(oldSource.options.first())
        assertEquals(DraftPhase.IDLE, controller.state.value.phase)
        port.open()
        port.generate(goal)
        val oldStatistics = scopePrompt(except = oldSource)
        port.statistics(true)
        port.answer(oldStatistics.options.first())
        port.generate(goal)
        val fresh = scopePrompt(except = oldStatistics)
        port.answer(oldSource.options.first())
        port.answer(oldStatistics.options.first())
        assertTrue(received.isEmpty())
        assertEquals(0, editor.samples)
        port.answer(fresh.options.first())
        shown { it.notice == AssistantNotice.NoChange }
        assertEquals(1, received.size)
        assertEquals(1, editor.samples)
    }

    @Test fun exportClearsPendingScopeWithoutApplyingTheLastDraft() = withPort {
        val received = mutableListOf<AdviceRequest>()
        connect { request, _ ->
            received += request
            AdviceResult.Proposal(listOf(ParameterChange(AdviceParameter.EXPOSURE.id, .4)))
        }
        port.open()
        port.generate("Raise the exposure.")
        val first = scopePrompt()
        port.answer(first.options.first())
        shown { it.canApply }
        port.generate("Make the whole photo a little warmer.")
        val followUp = scopePrompt(except = first)

        val ready = CompletableDeferred<Unit>()
        controller.prepareExport { ready.complete(Unit) }
        ready.await()
        port.answer(followUp.options.first())
        shown { it.question == null && it.showingCurrent && it.canApply }
        assertEquals(1, received.size)
        assertEquals(0, editor.commits)
        assertEquals(editor.formal, editor.displayed)
        assertEquals(.4, controller.state.value.draft!!.edit.look.exposure, 0.0)
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

    @Test fun undoReopensTheSessionOnTheRestoredEditWithoutLeavingThePanel() = withPort {
        val original = editor.formal
        onUndo = {
            controller.invalidate()
            editor.formal = original
            editor.displayed = original
        }
        port.open()
        port.tryPreset(AssistantPreset.Brighten)
        shown { it.canApply }
        port.apply()
        editor.saved.complete(Unit)
        shown { it.phase == AssistantPhase.Saved }
        assertEquals(.35, editor.formal.look.exposure, 0.0)

        port.undo()
        assertEquals(1, undos)
        port.tryPreset(AssistantPreset.Soften)
        assertEquals(listOf(AssistantChange(AssistantParam.CONTRAST, 0.0, -8.0)),
            shown { it.phase == AssistantPhase.Previewing }.changes)
        assertEquals(original, controller.state.value.entry!!.edit)
        assertEquals(original, editor.formal)
    }

    @Test fun toolbarHistoryInvalidationUsesTheNewEditOnTheNextAssistantRequest() = withPort {
        val received = mutableListOf<AdviceRequest>()
        connect { request, _ -> received += request; AdviceResult.NoChange }
        port.open()
        controller.invalidate()
        val restored = editor.formal.copy(look = editor.formal.look.copy(exposure = .25))
        editor.formal = restored
        editor.displayed = restored

        port.generate("Lift the midtones.")
        port.answer(scopePrompt().options.first())
        shown { it.notice == AssistantNotice.NoChange }
        assertEquals(.25, received.single().stored.getValue(AdviceParameter.EXPOSURE.id), 0.0)

        controller.invalidate()
        port.statistics(true)
        assertTrue(shown { it.statistics }.statistics)
        assertEquals(restored, controller.state.value.entry!!.edit)
        assertEquals(1, received.size)
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
        var onUndo: () -> Unit = {}
        var settings = 0
        val port = AssistantSessionPort(scope, controller, runtime, ui, { canBegin }, { ui.value.ready },
            { undos++; onUndo() }, { settings++ })
        suspend fun shown(predicate: (AssistantView) -> Boolean) = port.view.first(predicate)
        suspend fun scopePrompt(except: AssistantQuestion? = null) = checkNotNull(shown {
            it.question?.let { q -> q != except && q.options.size == 2 && q.options.all { o -> '#' in o } } == true
        }.question)

        fun connect(reply: (AdviceRequest, TaskClarification?) -> AdviceResult) {
            val endpoint = EndpointConfig("test", 1, "Test Service", "https://example.test/v1".toHttpUrl(),
                AdvisorTransport.CHAT, AdvisorDialect.OPENAI, AdvisorAuth.BEARER)
            val binding = ModelBinding("binding", 1, endpoint.id, "test-model")
            val configuration = AdvisorConfiguration(1, listOf(endpoint), listOf(binding), binding.id)
            controller.configure(AdviceConnection(AdviceConnectionIdentity(1, endpoint.id, 1, 0, binding.id, 1,
                "catalog", "chat")) { request, clarification -> AdvisorReply(reply(request, clarification), AdvisorTokenUsage()) })
            runtime.value = AdvisorRuntimeState(configuration = configuration, configurationReady = true)
        }
    }

    private class Editor : AdvisorEditorPort {
        var formal = Catalog.EMPTY.normalize(Edit.NONE).let { it.copy(look = it.look.copy(palette = 50.0)) }
        var displayed = formal
        var captures = 0
        var releases = 0
        var commits = 0
        var retries = 0
        var samples = 0
        var renders = 0
        var source = 1L
        var restoreSuccess = true
        var restoreGate: CompletableDeferred<Unit>? = null
        var commitGate: CompletableDeferred<Unit>? = null
        val saved = CompletableDeferred<Unit>()
        private val receipt = AppliedReceipt("test-commit", 1, 2)

        override fun capture() = EditSnapshot(formal, RequestTicket(source, 1, 0, 1, 0, 0)).also { captures++ }
        override fun catalog() = Catalog.EMPTY
        override fun current(snapshot: EditSnapshot) = snapshot.ticket.photoSessionId == source && snapshot.edit == formal
        override suspend fun render(edit: Edit, entry: EditSnapshot): Boolean {
            if (!current(entry)) return false
            renders++
            displayed = edit
            return true
        }
        override suspend fun restore(entry: EditSnapshot): Boolean {
            restoreGate?.await()
            return restoreSuccess && render(entry.edit, entry)
        }
        override suspend fun statistics(snapshot: EditSnapshot, entry: EditSnapshot) =
            cinematicStatistics(snapshot).copy(scene = SceneEvidence(4, 4, List(16) { .5 }, null)).also { samples++ }
        override fun endPreview() { releases++ }
        override suspend fun commit(candidate: AdviceCandidate, entry: EditSnapshot, learnAdoption: Boolean): CommittedDraft {
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
