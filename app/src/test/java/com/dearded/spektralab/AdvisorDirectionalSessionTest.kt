package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.*
import com.dearded.spektralab.advisor.editor.*
import com.dearded.spektralab.advisor.network.*
import com.dearded.spektralab.advisor.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AdvisorDirectionalSessionTest {
    private class Editor : AdvisorEditorPort {
        var formal = Catalog.EMPTY.normalize(Edit.NONE)
        var drawn = formal
        var revision = 1L
        var side: BrightSide? = BrightSide.TOP
        var rejectsHighlights = false
        var acceptsReduced = false
        val reduced = mutableListOf<AdviceCandidate>()
        val history = EditHistory()
        var commits = 0
        override fun capture() = EditSnapshot(formal, RequestTicket(1, revision, 0, 1, 0, 0))
        override fun catalog() = Catalog.EMPTY
        override fun current(snapshot: EditSnapshot) = snapshot.edit == formal && snapshot.ticket.formalRevision == revision
        override suspend fun render(edit: Edit, entry: EditSnapshot): Boolean { drawn = edit; return true }
        override suspend fun renderCinematicCandidate(candidate: AdviceCandidate, entry: EditSnapshot, base: EditSnapshot): AdvisorRenderResult {
            assertEquals(entry, base)
            if (rejectsHighlights) return AdvisorRenderResult.HIGHLIGHTS_CLIPPED
            render(candidate.edit, entry)
            return AdvisorRenderResult.SHOWN
        }
        override suspend fun renderReducedCinematicCandidate(candidate: AdviceCandidate, entry: EditSnapshot,
            base: EditSnapshot): AdvisorRenderResult {
            assertEquals(entry, base)
            reduced += candidate
            if (!acceptsReduced) return AdvisorRenderResult.NO_IMPROVEMENT
            render(candidate.edit, entry)
            return AdvisorRenderResult.SHOWN
        }
        override suspend fun statistics(snapshot: EditSnapshot, entry: EditSnapshot) =
            cinematicStatistics(snapshot).copy(measuredBrightSide = side)
        override suspend fun restore(entry: EditSnapshot) = render(entry.edit, entry)
        override fun endPreview() = Unit
        override suspend fun commit(candidate: AdviceCandidate, entry: EditSnapshot): CommittedDraft {
            val verified = AdviceProjection.commitEdit(entry, candidate, catalog())
            history.record(formal, verified, 1)
            formal = verified
            commits++
            return CommittedDraft(AppliedReceipt("direction", 1, ++revision), SaveTarget.SIDECAR, CompletableDeferred(Unit))
        }
        override fun retrySave(applied: AppliedReceipt): Deferred<Unit> = error("Unexpected retry")
    }

    private fun AdviceSessionController.connect(send: (AdviceRequest) -> AdviceResult) = configure(
        AdviceConnection(AdviceConnectionIdentity(1, "endpoint", 1, 1, "binding", 1, "catalog", "adapter")) { request, _ ->
            AdvisorReply(send(request), AdvisorTokenUsage())
        })

    private suspend fun AdviceSessionController.at(phase: DraftPhase) = withTimeout(3000) { state.first { it.phase == phase } }

    @Test fun `preview strength export handoff and one undo use the same two masks`() = runBlocking<Unit> {
        val editor = Editor()
        val baseline = editor.formal
        val controller = AdviceSessionController(this, editor)
        var sends = 0
        controller.connect { request ->
            sends++
            assertEquals(3, request.schemaVersion)
            assertEquals(BrightSide.TOP, request.brightSide)
            AdviceResult.Proposal(emptyList(), DirectionalLight(BrightSide.TOP, .6, -1.2))
        }
        controller.begin()
        controller.generateDirectionalLightRecommendation()
        assertEquals(DraftPhase.READY, controller.state.value.phase)
        controller.includeStatistics(true)
        controller.generateDirectionalLightRecommendation()
        val full = controller.at(DraftPhase.PREVIEWING).draft!!.edit
        assertTrue(controller.state.value.canApply)
        assertEquals(baseline, editor.formal)
        assertFalse(editor.history.canUndo)

        controller.setStrength(0)
        val zero = controller.at(DraftPhase.PREVIEWING)
        assertEquals(0, zero.strengthPercent)
        assertFalse(zero.canApply)
        assertEquals(baseline, editor.drawn)
        assertEquals(AdviceError.NO_EFFECT, assertThrows(AdviceFailure::class.java) {
            AdviceProjection.commitEdit(zero.entry!!, zero.draft!!, editor.catalog())
        }.code)

        controller.setStrength(50)
        val half = controller.at(DraftPhase.PREVIEWING)
        assertEquals(50, half.strengthPercent)
        assertEquals(full.look.masks.map { it.copy(tone = MaskTone(exposure = it.tone.exposure * .5)) }, editor.drawn.look.masks)
        val exported = CompletableDeferred<Edit>()
        controller.prepareExport { exported.complete(editor.drawn) }
        assertEquals(baseline, withTimeout(3000) { exported.await() })
        assertEquals(1, sends)
        controller.allowParameters(AdviceParameter.entries.toSet() - AdviceParameter.EXPOSURE)
        assertFalse(controller.state.value.canApply)
        controller.apply()
        assertEquals(0, editor.commits)
        controller.allowParameters(AdviceParameter.entries.toSet())
        controller.apply()
        controller.at(DraftPhase.SAVED)
        assertEquals(1, editor.commits)
        assertEquals(half.draft!!.edit, editor.formal)
        assertEquals(baseline, editor.history.undo(editor.formal))
        assertFalse(editor.history.canUndo)
        assertEquals(editor.formal, editor.history.redo(baseline))
    }

    @Test fun `no directional evidence sends nothing and clipping keeps the entry`() = runBlocking<Unit> {
        val editor = Editor().apply { side = null }
        val controller = AdviceSessionController(this, editor)
        var sends = 0
        controller.connect {
            sends++
            AdviceResult.Proposal(emptyList(), DirectionalLight(BrightSide.TOP, .6, -1.2))
        }
        controller.begin()
        controller.includeStatistics(true)
        controller.generateDirectionalLightRecommendation()
        assertEquals(AdviceNotice.CINEMATIC_NO_ADJUSTMENT, controller.at(DraftPhase.READY).notice)
        assertEquals(0, sends)

        editor.side = BrightSide.TOP
        editor.rejectsHighlights = true
        controller.generateDirectionalLightRecommendation()
        assertEquals(AdviceNotice.CINEMATIC_NO_ADJUSTMENT, controller.at(DraftPhase.READY).notice)
        assertEquals(1, sends)
        assertEquals(1, editor.reduced.size)
        assertEquals(editor.formal, editor.drawn)
        assertFalse(controller.state.value.canApply)
        assertFalse(editor.history.canUndo)
    }

    @Test fun `clipped direction retries once with an exact half strength pair`() = runBlocking<Unit> {
        val editor = Editor().apply { rejectsHighlights = true; acceptsReduced = true }
        val controller = AdviceSessionController(this, editor)
        val light = DirectionalLight(BrightSide.TOP, .6, -1.2)
        var sends = 0
        controller.connect { sends++; AdviceResult.Proposal(emptyList(), light) }
        controller.begin()
        controller.includeStatistics(true)
        controller.generateDirectionalLightRecommendation()
        val state = controller.at(DraftPhase.PREVIEWING)
        assertEquals(1, sends)
        assertEquals(50, state.strengthPercent)
        assertEquals(light.masks(50), editor.drawn.look.masks)
        assertSame(editor.reduced.single(), state.draft)
        assertEquals(0, editor.commits)
    }
}
