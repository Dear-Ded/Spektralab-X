package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.*
import com.dearded.spektralab.advisor.editor.*
import com.dearded.spektralab.advisor.network.*
import com.dearded.spektralab.advisor.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

internal fun cinematicStatistics(snapshot: EditSnapshot, luma: List<Double> = listOf(.24, .50, .68),
    black: Double = 0.0, white: Double = 0.0, rgb: Int = 21): AdviceStatistics {
    val channel = List(16) { if (it == 6) 1.0 else 0.0 }
    val coarse = List(64) { if (it == rgb) 1.0 else 0.0 }
    return AdviceStatistics(snapshot.ticket.formalRevision, 1L, 128, 128,
        AdviceStatisticsValues(luma, black, white, channel, channel, channel, coarse))
}

internal interface TestCinematicEditorPort : AdvisorEditorPort {
    override suspend fun renderCinematicCandidate(candidate: AdviceCandidate, entry: EditSnapshot,
        base: EditSnapshot): AdvisorRenderResult =
        if (renderCandidate(candidate, entry)) AdvisorRenderResult.SHOWN else AdvisorRenderResult.FAILED
}

/** Real controller with a controllable editor boundary; no native or API validation is claimed. */
class AdvisorSessionControllerTest {
    private class Editor : TestCinematicEditorPort {
        var formal = Catalog.EMPTY.normalize(Edit.NONE)
        var displayed = formal
        var revision = 1L
        var commits = 0
        var releases = 0
        var restoreSuccess = true
        var renderFailureAt: Double? = null
        var renderGate: (suspend (Edit) -> Unit)? = null
        var restoreGate: (suspend () -> Unit)? = null
        var statisticsGate: (suspend (EditSnapshot) -> AdviceStatistics?)? = null
        var statisticsCalls = 0
        var rejectCinematicHighlights = false
        var reducedResult = AdvisorRenderResult.HIGHLIGHTS_CLIPPED
        val cinematicRenders = ArrayList<Triple<AdviceCandidate, EditSnapshot, EditSnapshot>>()
        val reducedRenders = ArrayList<Triple<AdviceCandidate, EditSnapshot, EditSnapshot>>()
        val offers = ArrayList<AdviceCandidate>()
        override fun offered(candidate: AdviceCandidate, entry: EditSnapshot) { offers += candidate }
        val saved = CompletableDeferred<Unit>()
        val retried = CompletableDeferred<Unit>()
        var retries = 0
        override fun capture() = EditSnapshot(formal, RequestTicket(1, revision, 0, 1, 0, 0))
        override fun catalog() = Catalog.EMPTY
        override fun current(snapshot: EditSnapshot) = snapshot.ticket.formalRevision == revision && snapshot.edit == formal
        override suspend fun render(edit: Edit, entry: EditSnapshot): Boolean {
            renderGate?.invoke(edit)
            currentCoroutineContext().ensureActive()
            if (!current(entry) || edit.look.saturation == renderFailureAt) return false
            displayed = edit
            return true
        }
        override suspend fun renderCinematicCandidate(candidate: AdviceCandidate, entry: EditSnapshot,
            base: EditSnapshot): AdvisorRenderResult {
            cinematicRenders += Triple(candidate, entry, base)
            if (rejectCinematicHighlights) return AdvisorRenderResult.HIGHLIGHTS_CLIPPED
            return super.renderCinematicCandidate(candidate, entry, base)
        }
        override suspend fun renderReducedCinematicCandidate(candidate: AdviceCandidate, entry: EditSnapshot,
            base: EditSnapshot): AdvisorRenderResult {
            reducedRenders += Triple(candidate, entry, base)
            if (reducedResult == AdvisorRenderResult.SHOWN) check(render(candidate.edit, entry))
            return reducedResult
        }
        override suspend fun restore(entry: EditSnapshot): Boolean {
            restoreGate?.invoke()
            if (!current(entry) || !restoreSuccess) return false
            displayed = entry.edit
            return true
        }
        override suspend fun statistics(snapshot: EditSnapshot, entry: EditSnapshot): AdviceStatistics? {
            statisticsCalls++
            return statisticsGate?.invoke(snapshot)
        }
        override fun endPreview() { releases++ }
        override suspend fun commit(candidate: AdviceCandidate, entry: EditSnapshot): CommittedDraft {
            check(current(entry))
            formal = candidate.edit
            revision++
            commits++
            return CommittedDraft(AppliedReceipt("commit", 1, revision), SaveTarget.SIDECAR, saved)
        }
        override fun retrySave(applied: AppliedReceipt): Deferred<Unit> {
            check(applied.revision == revision)
            retries++
            return retried
        }
    }

    private fun changes(value: Double) = listOf(ParameterChange("layer2.saturation", value))
    private suspend fun AdviceSessionController.phase(phase: DraftPhase) = withTimeout(3000) { state.first { it.phase == phase } }

    @Test fun strengthUsesFrozenEntryAndExactRecommendationWithoutRequestsAndAppliesTheMidpoint() = runBlocking<Unit> {
        val editor = Editor().apply {
            formal = formal.copy(look = formal.look.copy(saturation = 4.04, exposure = .04, brightness = 3.0))
            displayed = formal
        }
        val entry = editor.formal
        var requests = 0
        val controller = AdviceSessionController(this, editor)
        controller.configure(AdviceConnection(identity()) { _, _ ->
            requests++
            reply(AdviceResult.Proposal(changes(10.06) + ParameterChange(AdviceParameter.EXPOSURE.id, .25)))
        })
        controller.begin()
        assertNull(controller.state.value.strengthPercent)
        controller.generate("synthetic strength")
        val original = controller.phase(DraftPhase.PREVIEWING)
        assertEquals(100, original.strengthPercent)
        assertTrue(original.canSetStrength)

        controller.setStrength(0)
        val zero = controller.phase(DraftPhase.PREVIEWING)
        assertEquals(0, zero.strengthPercent)
        assertEquals(entry, editor.displayed)
        assertEquals(entry, zero.draft!!.edit)
        assertTrue(zero.canSetStrength)
        assertFalse(zero.canApply)
        controller.apply()
        assertEquals(0, editor.commits)
        assertEquals(1, editor.offers.size)

        controller.setStrength(50)
        val midpoint = controller.phase(DraftPhase.PREVIEWING)
        val expected = entry.copy(look = entry.look.copy(saturation = 7.1, exposure = .15))
        assertEquals(50, midpoint.strengthPercent)
        assertEquals(expected, editor.displayed)
        assertEquals(expected, midpoint.draft!!.edit)
        val offers = editor.offers.size
        controller.setStrength(50)
        assertSame(midpoint, controller.state.value)
        assertEquals(offers, editor.offers.size)

        controller.setStrength(100)
        assertEquals(original.draft!!.edit, controller.phase(DraftPhase.PREVIEWING).draft!!.edit)
        controller.setStrength(50)
        assertEquals(expected, controller.phase(DraftPhase.PREVIEWING).draft!!.edit)
        assertEquals(1, requests)
        assertEquals(original.usage, controller.state.value.usage)
        assertEquals(entry, editor.formal)

        controller.apply()
        controller.phase(DraftPhase.APPLIED)
        assertEquals(1, editor.commits)
        assertEquals(expected, editor.formal)
        editor.saved.complete(Unit)
        controller.phase(DraftPhase.SAVED)
    }

    @Test fun strengthPreservesManualOverridesAndLocksAtTheirLastConfirmedValues() = runBlocking<Unit> {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        controller.begin()
        controller.propose(changes(20.0) + ParameterChange(AdviceParameter.EXPOSURE.id, .6) +
            ParameterChange(AdviceParameter.CONTRAST.id, 10.0))
        val proposal = controller.phase(DraftPhase.PREVIEWING).draft!!
        controller.adjust(changes(14.0))
        controller.phase(DraftPhase.PREVIEWING)
        controller.allowParameters(AdviceParameter.entries.toSet() - AdviceParameter.EXPOSURE)

        for ((percent, contrast) in listOf(0 to 0.0, 50 to 5.0, 100 to 10.0)) {
            controller.setStrength(percent)
            val state = controller.phase(DraftPhase.PREVIEWING)
            assertEquals(percent, state.strengthPercent)
            assertTrue(state.canApply) // The manual value and lock still differ from entry at 0%.
            val candidate = state.draft!!
            assertTrue(candidate.manuallyAdjusted)
            assertSame(proposal, candidate.correctionOf)
            assertEquals(14.0, candidate.edit.look.saturation, 0.0)
            assertEquals(.6, candidate.edit.look.exposure, 0.0)
            assertEquals(contrast, candidate.edit.look.contrast, 0.0)
            assertEquals(candidate.edit, editor.displayed)
        }
    }

    @Test fun failedStrengthRenderDoesNotPublishAndNoChangeReplyKeepsTheAnchor() = runBlocking<Unit> {
        val editor = Editor()
        var requests = 0
        val controller = AdviceSessionController(this, editor)
        controller.configure(AdviceConnection(identity()) { _, _ -> requests++; reply(AdviceResult.NoChange) })
        controller.begin()
        controller.generate("no initial proposal")
        assertNull(controller.phase(DraftPhase.READY).strengthPercent)
        controller.propose(changes(20.0))
        val proposal = controller.phase(DraftPhase.PREVIEWING).draft!!
        val waiting = CompletableDeferred<Unit>()
        editor.renderFailureAt = 10.0
        editor.renderGate = { if (it.look.saturation == 10.0) waiting.await() }
        controller.setStrength(50)
        assertEquals(DraftPhase.RENDERING, controller.state.value.phase)
        assertEquals(100, controller.state.value.strengthPercent)
        assertFalse(controller.state.value.canSetStrength)
        waiting.complete(Unit)
        val rejected = controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceError.RENDER_FAILED, rejected.error)
        assertEquals(100, rejected.strengthPercent)
        assertSame(proposal, rejected.draft)
        assertEquals(proposal.edit, editor.displayed)

        editor.renderFailureAt = null
        controller.setStrength(50)
        assertEquals(50, controller.phase(DraftPhase.PREVIEWING).strengthPercent)
        controller.generate("keep the last proposal")
        val noChange = controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceNotice.NO_CHANGE, noChange.notice)
        assertEquals(50, noChange.strengthPercent)
        controller.setStrength(100)
        assertEquals(proposal.edit, controller.phase(DraftPhase.PREVIEWING).draft!!.edit)
        assertEquals(2, requests)
    }

    @Test fun lateNativeReturnCannotPublishOverNewerDraft() = runBlocking {
        val editor = Editor()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        editor.renderGate = { if (it.look.saturation == 10.0) {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        } }
        val controller = AdviceSessionController(this, editor)
        controller.begin()
        controller.propose(changes(10.0))
        entered.await()
        controller.propose(changes(20.0))
        release.complete(Unit)
        controller.phase(DraftPhase.PREVIEWING)
        assertEquals(20.0, editor.displayed.look.saturation, 0.0)
        assertEquals(20.0, controller.state.value.edit!!.look.saturation, 0.0)
        assertEquals(0, editor.commits)
        assertEquals(listOf(20.0), editor.offers.map { it.edit.look.saturation })
    }

    @Test fun multiRoundDiscardDoesNotCommitOrSave() = runBlocking {
        val editor = Editor()
        val original = editor.formal
        val controller = AdviceSessionController(this, editor)
        controller.begin()
        for (value in listOf(10.0, 20.0, 30.0)) {
            controller.propose(changes(value)); controller.phase(DraftPhase.PREVIEWING)
        }
        controller.discard(); controller.phase(DraftPhase.IDLE)
        assertEquals(original, editor.formal)
        assertEquals(original, editor.displayed)
        assertEquals(0, editor.commits)
        assertFalse(editor.saved.isCompleted)
    }

    @Test fun exportWaitsForFormalRenderAndRetainsDraft() = runBlocking {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        controller.begin(); controller.propose(changes(15.0)); controller.phase(DraftPhase.PREVIEWING)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        editor.restoreGate = { entered.complete(Unit); release.await() }
        var exported: Edit? = null
        controller.prepareExport { exported = editor.displayed }
        entered.await()
        assertNull(exported)
        release.complete(Unit)
        controller.phase(DraftPhase.PREVIEWING)
        assertEquals(editor.formal, exported)
        assertEquals(15.0, controller.state.value.draft!!.edit.look.saturation, 0.0)
        assertTrue(controller.state.value.showingEntry)
        controller.compare(false); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(15.0, editor.displayed.look.saturation, 0.0)
        assertEquals(2, editor.offers.size)
        assertSame(editor.offers.first(), editor.offers.last())
    }

    @Test fun saveDuringComparisonStillExportsTheFormalEditOnce() = runBlocking {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        controller.begin(); controller.propose(changes(15.0)); controller.phase(DraftPhase.PREVIEWING)
        val draft = controller.state.value.draft
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        editor.restoreGate = { entered.complete(Unit); release.await() }
        controller.compare(true)
        withTimeout(3000) { entered.await() }
        assertEquals(DraftPhase.COMPARING, controller.state.value.phase)

        val exports = mutableListOf<Edit>()
        controller.prepareExport { exports += editor.displayed }
        assertTrue(exports.isEmpty())
        release.complete(Unit)
        controller.phase(DraftPhase.PREVIEWING)
        assertEquals(listOf(editor.formal), exports)
        assertSame(draft, controller.state.value.draft)
        assertTrue(controller.state.value.showingEntry)
        assertEquals(0, editor.commits)
    }

    @Test fun modelSourceIsFrozenIntoTheActualOfferedCandidateAndUntrackedNetworkIsNeverLocal() = runBlocking<Unit> {
        val editor = Editor()
        val origin = AdviceOrigin.Model(java.util.UUID.randomUUID().toString(), 7, "catalog.1", "chat.1", "prompt.1")
        val identity = AdviceConnectionIdentity(1, "endpoint", 1, 1, origin.bindingId, 7, "catalog.1", "chat.1")
        val connection = AdviceConnection(identity, origin) { _, _ -> AdvisorReply(AdviceResult.Proposal(changes(10.0)), AdvisorTokenUsage()) }
        val controller = AdviceSessionController(this, editor)
        controller.configure(connection); controller.begin(); controller.generate("synthetic")
        controller.phase(DraftPhase.PREVIEWING)
        assertSame(origin, editor.offers.last().origin)
        controller.configure(AdviceConnection(identity.copy(configurationRevision = 2)) { _, _ ->
            AdvisorReply(AdviceResult.Proposal(changes(20.0)), AdvisorTokenUsage()) })
        controller.compare(false); controller.phase(DraftPhase.PREVIEWING)
        assertSame(origin, editor.offers.last().origin)
        controller.generate("synthetic"); controller.phase(DraftPhase.PREVIEWING)
        assertNull(editor.offers.last().origin)
        controller.propose(changes(30.0)); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceOrigin.Local, editor.offers.last().origin)
    }

    @Test fun manualCorrectionsKeepTheOriginalModelSuggestionAcrossRoundsAndARealNewReplyReplacesIt() = runBlocking<Unit> {
        val editor = Editor()
        val origin = AdviceOrigin.Model(java.util.UUID.randomUUID().toString(), 1, "catalog.1", "chat.1", "prompt.1")
        val identity = AdviceConnectionIdentity(1, "endpoint", 1, 1, origin.bindingId, 1, "catalog.1", "chat.1")
        var requests = 0
        val controller = AdviceSessionController(this, editor)
        controller.configure(AdviceConnection(identity, origin) { _, _ ->
            requests++
            AdvisorReply(AdviceResult.Proposal(changes(if (requests == 1) 10.0 else 25.0)), AdvisorTokenUsage())
        })
        controller.begin(); controller.generate("synthetic"); controller.phase(DraftPhase.PREVIEWING)
        val proposal = controller.state.value.draft!!
        for (value in listOf(20.0, 15.0)) {
            controller.adjust(changes(value)); controller.phase(DraftPhase.PREVIEWING)
            val corrected = controller.state.value.draft!!
            assertTrue(corrected.manuallyAdjusted)
            assertSame(proposal, corrected.correctionOf)
            assertSame(origin, corrected.origin)
        }
        assertEquals(1, requests)
        assertEquals(0, editor.commits)
        controller.generate("synthetic continuation"); controller.phase(DraftPhase.PREVIEWING)
        val next = controller.state.value.draft!!
        assertFalse(next.manuallyAdjusted); assertNull(next.correctionOf); assertSame(origin, next.origin)
        controller.setStrength(50); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(12.5, controller.state.value.draft!!.edit.look.saturation, 0.0)
        controller.setStrength(100); controller.phase(DraftPhase.PREVIEWING)
        val fullStrength = controller.state.value.draft!!
        assertEquals(next.edit, fullStrength.edit)
        controller.adjust(changes(30.0)); controller.phase(DraftPhase.PREVIEWING)
        assertSame(fullStrength, controller.state.value.draft!!.correctionOf)
        assertEquals(2, requests)
    }

    @Test fun manualDraftWithoutSuggestionIsManualAndUnknownSuggestionNeverBecomesLocal() = runBlocking<Unit> {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        controller.begin()
        for (value in listOf(5.0, 10.0)) {
            controller.adjust(changes(value)); controller.phase(DraftPhase.PREVIEWING)
            assertEquals(AdviceOrigin.Manual, controller.state.value.draft!!.origin)
            assertTrue(controller.state.value.draft!!.manuallyAdjusted)
            assertNull(controller.state.value.draft!!.correctionOf)
        }
        val identity = AdviceConnectionIdentity(1, "endpoint", 1, 1, "unknown", 1, "catalog.1", "chat.1")
        controller.configure(AdviceConnection(identity) { _, _ -> AdvisorReply(AdviceResult.Proposal(changes(15.0)), AdvisorTokenUsage()) })
        controller.generate("synthetic"); controller.phase(DraftPhase.PREVIEWING)
        val unknown = controller.state.value.draft!!
        controller.adjust(changes(20.0)); controller.phase(DraftPhase.PREVIEWING)
        assertNull(controller.state.value.draft!!.origin)
        assertSame(unknown, controller.state.value.draft!!.correctionOf)
        controller.propose(changes(30.0)); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceOrigin.Local, controller.state.value.draft!!.origin)
        assertFalse(controller.state.value.draft!!.manuallyAdjusted)
        assertNull(controller.state.value.draft!!.correctionOf)
    }

    @Test fun failedOrLockedManualCorrectionRestoresTheLastValidCandidateAndItsSource() = runBlocking<Unit> {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        controller.begin(); controller.propose(changes(10.0)); controller.phase(DraftPhase.PREVIEWING)
        val proposal = controller.state.value.draft!!
        controller.adjust(changes(15.0)); controller.phase(DraftPhase.PREVIEWING)
        val corrected = controller.state.value.draft!!
        editor.renderFailureAt = 20.0
        controller.adjust(changes(20.0)); controller.phase(DraftPhase.PREVIEWING)
        assertSame(corrected, controller.state.value.draft)
        assertSame(corrected, editor.offers.last())
        assertEquals(AdviceError.RENDER_FAILED, controller.state.value.error)
        controller.allowParameters(emptySet())
        controller.adjust(changes(25.0)); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceError.LOCK_CONFLICT, controller.state.value.error)
        assertSame(corrected, controller.state.value.draft)
        assertSame(proposal, corrected.correctionOf)
        assertEquals(0, editor.commits)
    }

    @Test fun failedRestoreBlocksExportAndAllowsDiscardRetry() = runBlocking {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        controller.begin(); controller.propose(changes(15.0)); controller.phase(DraftPhase.PREVIEWING)
        editor.restoreSuccess = false
        var exports = 0
        controller.prepareExport { exports++ }; controller.phase(DraftPhase.FAILED)
        controller.prepareExport { exports++ }
        assertEquals(0, exports)
        assertFalse(controller.state.value.canApply)
        editor.restoreSuccess = true
        controller.discard(); controller.phase(DraftPhase.IDLE)
        assertEquals(editor.formal, editor.displayed)
    }

    @Test fun cancellationAndRenderFailureRetainLastShownDraft() = runBlocking {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        controller.begin(); controller.propose(changes(10.0)); controller.phase(DraftPhase.PREVIEWING)
        editor.renderFailureAt = 20.0
        controller.propose(changes(20.0)); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceError.RENDER_FAILED, controller.state.value.error)
        assertEquals(10.0, editor.displayed.look.saturation, 0.0)
        val entered = CompletableDeferred<Unit>()
        editor.renderGate = { if (it.look.saturation == 30.0) { entered.complete(Unit); awaitCancellation() } }
        controller.propose(changes(30.0)); entered.await()
        controller.cancelRequest(); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(10.0, editor.displayed.look.saturation, 0.0)
        assertEquals(0, editor.commits)
    }

    @Test fun applyIsOneCommitAndAcknowledgesActualSaveFailure() = runBlocking {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        controller.begin(); controller.propose(changes(10.0)); controller.phase(DraftPhase.PREVIEWING)
        controller.propose(listOf(ParameterChange("layer2.exposure", .4))); controller.phase(DraftPhase.PREVIEWING)
        controller.apply(); controller.apply(); controller.phase(DraftPhase.APPLIED)
        assertEquals(1, editor.commits)
        assertEquals(10.0, editor.formal.look.saturation, 0.0)
        assertEquals(.4, editor.formal.look.exposure, 0.0)
        assertNull(controller.state.value.error)
        editor.saved.completeExceptionally(IOException("synthetic full disk"))
        withTimeout(3000) { controller.state.first { it.error == AdviceError.SAVE_FAILED } }
        assertEquals(DraftPhase.APPLIED, controller.state.value.phase)
        controller.retrySave(); controller.retrySave()
        yield()
        assertEquals(1, editor.retries)
        assertEquals(1, editor.commits)
        assertNull(controller.state.value.error)
        editor.retried.complete(Unit)
        controller.phase(DraftPhase.SAVED)
        assertEquals(1, editor.commits)
    }

    @Test fun failedProposalAfterComparisonLabelsTheRestoredDraftCorrectly() = runBlocking {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        controller.begin(); controller.propose(changes(10.0)); controller.phase(DraftPhase.PREVIEWING)
        controller.compare(true); controller.phase(DraftPhase.PREVIEWING)
        assertTrue(controller.state.value.showingEntry)
        controller.propose(changes(101.0)); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceError.OUT_OF_RANGE, controller.state.value.error)
        assertEquals(10.0, editor.displayed.look.saturation, 0.0)
        assertFalse(controller.state.value.showingEntry)
    }

    @Test fun sameValuesAfterExternalRevisionAndLateSaveCannotReviveSession() = runBlocking {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        controller.begin(); editor.revision++
        controller.propose(changes(10.0))
        assertEquals(DraftPhase.IDLE, controller.state.value.phase)
        controller.begin(); controller.propose(changes(15.0)); controller.phase(DraftPhase.PREVIEWING)
        controller.apply(); controller.phase(DraftPhase.APPLIED)
        controller.invalidate()
        editor.saved.complete(Unit)
        yield()
        assertEquals(DraftPhase.IDLE, controller.state.value.phase)
    }

    @Test fun offlineGatewayUsesTheSameStrictContract() {
        assertEquals(listOf(ParameterChange("layer2.exposure", .35)), LocalAdviceGateway.brighter().changes)
        assertEquals(listOf(ParameterChange("layer2.contrast", -8.0)), LocalAdviceGateway.softer().changes)
    }

    @Test fun offlineTrialsMoveInThePromisedDirectionWithoutSendingDataOrCommitting() = runBlocking<Unit> {
        val editor = Editor().apply {
            formal = formal.copy(look = formal.look.copy(exposure = .8, contrast = -21.0, palette = 60.0))
        }
        val before = editor.capture()
        val controller = AdviceSessionController(this, editor)
        var requests = 0
        controller.configure(AdviceConnection(identity()) { _, _ -> requests++; reply(AdviceResult.NoChange) })
        controller.begin()
        controller.includeStatistics(true)
        controller.tryLocalPreset(LocalAdvicePreset.BRIGHTEN)
        val brighter = controller.phase(DraftPhase.PREVIEWING).draft!!
        assertEquals(1.15, brighter.edit.look.exposure, 1e-9)
        assertTrue(brighter.edit.look.resolved().exposure > before.edit.look.resolved().exposure)
        assertEquals(AdviceOrigin.Local, brighter.origin)

        controller.tryLocalPreset(LocalAdvicePreset.SOFTEN)
        val softer = controller.phase(DraftPhase.PREVIEWING).draft!!
        assertEquals(-29.0, softer.edit.look.contrast, 0.0)
        assertEquals(brighter.edit.look.exposure, softer.edit.look.exposure, 0.0)
        assertEquals(0, requests)
        assertEquals(0, editor.statisticsCalls)
        assertEquals(0, editor.commits)
        assertEquals(before, editor.capture())
        controller.discard(); controller.phase(DraftPhase.IDLE)
        assertEquals(before.edit, editor.displayed)
    }

    @Test fun aLocalTrialAtTheLimitOrWithNoEffectiveMovementDoesNotInventAnEdit() = runBlocking<Unit> {
        val none = ReplyCase(3.0, 100.0, setOf(AdviceParameter.EXPOSURE))
        for (case in listOf(none, none.copy(exposure = .8, palette = 0.0), none.copy(exposure = .8, allowed = emptySet()))) {
            val editor = Editor().apply {
                formal = formal.copy(look = formal.look.copy(exposure = case.exposure, palette = case.palette))
            }
            val controller = AdviceSessionController(this, editor)
            controller.begin()
            controller.allowParameters(case.allowed)
            controller.tryLocalPreset(LocalAdvicePreset.BRIGHTEN)
            val result = controller.phase(DraftPhase.READY)
            assertEquals(AdviceNotice.NO_CHANGE, result.notice)
            assertNull(result.draft)
            assertEquals(0, editor.commits)
        }
        val range = AdviceConstraints(mapOf(AdviceParameter.EXPOSURE to ParameterRange(-.5, .5)), 0)
        assertEquals(AdviceResult.NoChange, LocalAdvicePreset.BRIGHTEN.suggest(Look(exposure = 1.0), range))
        val proposal = LocalAdvicePreset.BRIGHTEN.suggest(Look(exposure = .4), range) as AdviceResult.Proposal
        assertEquals(listOf(ParameterChange("layer2.exposure", .5)), proposal.changes)
    }

    private data class ReplyCase(val exposure: Double, val palette: Double, val allowed: Set<AdviceParameter>)

    private fun identity(revision: Long = 1) = AdviceConnectionIdentity(revision, "endpoint", revision, 1,
        "binding", revision, "catalog.test", "adapter.test")
    private fun reply(result: AdviceResult) = AdvisorReply(result, AdvisorTokenUsage(totalTokens = 123))

    @Test fun statisticsAreOptInAndSampleTheCurrentDraftWithUnavailableFallback() = runBlocking<Unit> {
        val editor = Editor(); val controller = AdviceSessionController(this, editor)
        val inputs = mutableListOf<AdviceRequest>(); val sampledEdits = mutableListOf<Edit>()
        editor.statisticsGate = { sampledEdits.add(it.edit); null }
        controller.configure(AdviceConnection(identity()) { request, _ -> inputs.add(request); reply(AdviceResult.NoChange) })
        controller.begin(); controller.generate("Parameters only"); controller.phase(DraftPhase.READY)
        assertEquals(0, editor.statisticsCalls); assertNull(inputs.last().statistics)
        controller.propose(changes(10.0)); controller.phase(DraftPhase.PREVIEWING)
        controller.includeStatistics(true); controller.generate("Statistics requested"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(1, editor.statisticsCalls); assertEquals(10.0, sampledEdits.single().look.saturation, 0.0)
        assertFalse(inputs.last().statistics!!.available)
        controller.includeStatistics(false); controller.generate("Parameters again"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(1, editor.statisticsCalls); assertNull(inputs.last().statistics); assertEquals(0, editor.commits)
    }

    @Test fun togglingStatisticsOffDuringSamplingPreventsDispatchEvenIfTheSampleReturnsLate() = runBlocking<Unit> {
        val editor = Editor(); val controller = AdviceSessionController(this, editor)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        editor.statisticsGate = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; AdviceStatistics(it.ticket.formalRevision) }
        var calls = 0
        controller.configure(AdviceConnection(identity()) { _, _ -> calls++; reply(AdviceResult.NoChange) })
        controller.begin(); controller.includeStatistics(true); controller.generate("Synthetic")
        entered.await(); controller.includeStatistics(false); release.complete(Unit)
        controller.phase(DraftPhase.READY)
        assertEquals(0, calls); assertFalse(controller.state.value.includeStatistics)
        controller.generate("Parameters only"); controller.phase(DraftPhase.READY)
        assertEquals(1, calls); assertEquals(1, editor.statisticsCalls)
    }

    @Test fun networkGenerationIsSingleFlightAndSubsequentRoundsStartFromAcceptedDraft() = runBlocking {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val inputs = mutableListOf<AdviceRequest>()
        controller.configure(AdviceConnection(identity()) { request, _ ->
            inputs.add(request)
            if (inputs.size == 1) { entered.complete(Unit); release.await() }
            reply(AdviceResult.Proposal(changes(if (inputs.size == 1) 10.0 else 20.0)))
        })
        controller.begin(); controller.generate("Synthetic first"); entered.await()
        controller.generate("Duplicate click")
        assertEquals(1, inputs.size)
        release.complete(Unit); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(1, controller.state.value.draft!!.ticket.configurationRevision)
        assertEquals("endpoint", controller.state.value.draft!!.ticket.endpointId)
        assertEquals(123L, controller.state.value.usage!!.totalTokens)
        controller.generate("Synthetic second"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(10.0, inputs.last().stored.getValue("layer2.saturation"), 0.0)
        assertEquals(20.0, editor.displayed.look.saturation, 0.0)
        assertEquals(0.0, editor.formal.look.saturation, 0.0)
        controller.apply(); controller.phase(DraftPhase.APPLIED)
        assertEquals(1, editor.commits); editor.saved.complete(Unit); controller.phase(DraftPhase.SAVED)
        assertEquals(20.0, editor.formal.look.saturation, 0.0)
    }

    @Test fun changingEndpointCancelsTheOldRequestEvenWhenItReturnsAfterCancellation() = runBlocking {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        controller.configure(AdviceConnection(identity()) { _, _ ->
            entered.complete(Unit); withContext(NonCancellable) { release.await() }
            reply(AdviceResult.Proposal(changes(99.0)))
        })
        controller.begin(); controller.generate("Synthetic old"); entered.await()
        controller.configure(AdviceConnection(identity(2)) { _, _ -> reply(AdviceResult.Proposal(changes(20.0))) })
        release.complete(Unit); controller.phase(DraftPhase.READY)
        assertEquals(editor.formal, editor.displayed); assertNull(controller.state.value.draft)
        controller.generate("Synthetic new"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(20.0, editor.displayed.look.saturation, 0.0)
        assertEquals(2, controller.state.value.draft!!.ticket.configurationRevision)
    }

    @Test fun lockChangesInvalidateInflightRequestsAndRejectTheWholeNextConflictingProposal() = runBlocking {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        val entered = CompletableDeferred<Unit>()
        controller.configure(AdviceConnection(identity()) { _, _ -> entered.complete(Unit); awaitCancellation() })
        controller.begin(); controller.generate("Synthetic"); entered.await()
        controller.allowParameters(AdviceParameter.entries.toSet() - AdviceParameter.SATURATION)
        controller.phase(DraftPhase.READY)
        var allowed: Set<String>? = null
        controller.configure(AdviceConnection(identity(2)) { request, _ ->
            allowed = request.allowed.keys
            reply(AdviceResult.Proposal(changes(20.0) + ParameterChange("layer2.exposure", .5)))
        })
        controller.generate("Synthetic conflict"); controller.phase(DraftPhase.READY)
        assertFalse(allowed!!.contains("layer2.saturation")); assertEquals(7, allowed!!.size)
        assertEquals(AdviceError.LOCK_CONFLICT, controller.state.value.error)
        assertEquals(editor.formal, editor.displayed); assertNull(controller.state.value.draft)
    }

    @Test fun questionsNeverSendAutomaticallyAndTheThirdQuestionEndsTheLoopWithoutEditing() = runBlocking {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        val question = AdviceResult.Question("choice", "Synthetic preference?", listOf(AdviceResult.Option("a", "A"), AdviceResult.Option("b", "B")))
        val answers = mutableListOf<TaskClarification?>()
        controller.configure(AdviceConnection(identity()) { _, clarification -> answers.add(clarification); reply(question) })
        controller.begin(); controller.generate("Synthetic"); controller.phase(DraftPhase.READY)
        assertEquals(question, controller.state.value.question); yield(); assertEquals(1, answers.size)
        try { controller.answer("not_an_option"); fail("invalid choice") } catch (_: IllegalArgumentException) { }
        assertEquals(1, answers.size)
        controller.answer("a"); controller.phase(DraftPhase.READY)
        assertEquals("a", answers[1]!!.answer); assertNotNull(controller.state.value.question)
        controller.answer("b"); controller.phase(DraftPhase.READY)
        assertEquals(3, answers.size); assertNull(controller.state.value.question)
        assertEquals(AdviceNotice.QUESTION_LIMIT, controller.state.value.notice)
        controller.answer("a"); yield(); assertEquals(3, answers.size)
        assertEquals(editor.formal, editor.displayed); assertEquals(0, editor.commits)
    }

    @Test fun noChangeKeepsTheComparedFormalImageAndErrorsRetainThePreviousDraftWithoutRetry() = runBlocking {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        controller.begin(); controller.propose(changes(10.0)); controller.phase(DraftPhase.PREVIEWING)
        controller.compare(true); controller.phase(DraftPhase.PREVIEWING)
        var calls = 0
        controller.configure(AdviceConnection(identity()) { _, _ ->
            calls++
            if (calls == 1) reply(AdviceResult.NoChange) else throw AdvisorNetworkFailure(AdvisorNetworkError.RATE_LIMIT, 429)
        })
        controller.generate("Synthetic"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceNotice.NO_CHANGE, controller.state.value.notice)
        assertEquals(123L, controller.state.value.usage?.totalTokens)
        assertTrue(controller.state.value.showingEntry); assertEquals(editor.formal, editor.displayed)
        controller.generate("Synthetic retry"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceError.NETWORK_RATE_LIMIT, controller.state.value.error)
        assertEquals(10.0, editor.displayed.look.saturation, 0.0)
        assertFalse(controller.state.value.showingEntry); yield(); assertEquals(2, calls)
        assertEquals(0, editor.commits)
    }

    @Test(timeout = 15000) fun cinematicRecommendationUsesTheConfiguredGatewayAndOnlyPreviews() = runBlocking {
        AdvisorAdapterFixture().use { fixture ->
            val adapter = ResponsesAdapter(AdvisorWireFixtures.schema)
            val endpoint = fixture.endpoint()
            val binding = ModelBinding("binding", 1, endpoint.id, "synthetic", "openai/gpt-6-luna", reasoningEffort = "low")
            val config = AdvisorConfiguration(1, listOf(endpoint), listOf(binding), binding.id)
            val editor = Editor().apply {
                formal = formal.copy(look = formal.look.copy(exposure = .31, temperature = 7.0, tint = -3.0, palette = 50.0, tone = 20.0))
                displayed = formal
                statisticsGate = { cinematicStatistics(it, listOf(.21, .48, .65)) }
            }
            val controller = AdviceSessionController(this, editor)
            controller.configure(AdviceConnection.from(config, AdvisorWireFixtures.catalog.version, fixture.gateway(adapter)))
            controller.begin()
            controller.generateCinematicRecommendation(); controller.phase(DraftPhase.READY)
            assertEquals(AdviceNotice.CINEMATIC_PREVIEW_NOT_ANALYZED, controller.state.value.notice)
            assertNull(controller.state.value.usage)
            assertEquals(0, editor.statisticsCalls); assertEquals(0, fixture.server.requestCount)
            controller.includeStatistics(true)
            val proposal = AdvisorWireFixtures.proposal.replace("layer2.exposure", "layer2.contrast").replace("0.25", "3.0")
            fixture.server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(AdvisorWireFixtures.responses(proposal).toString()))
            controller.generateCinematicRecommendation(); controller.phase(DraftPhase.PREVIEWING)
            val request = checkNotNull(fixture.server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS))
            val body = org.json.JSONObject(request.body.readUtf8())
            val content = org.json.JSONObject(body.getJSONArray("input").getJSONObject(0).getString("content"))
            assertEquals(CINEMATIC_RECOMMENDATION_GOAL, content.getString("goal"))
            assertEquals("statistics", content.getString("input_mode"))
            val allowed = content.getJSONObject("allowed_parameters")
            assertEquals(setOf("layer2.contrast"), allowed.keys().asSequence().toSet())
            assertEquals(5.0, allowed.getJSONObject("layer2.contrast").getDouble("maximum"), 0.0)
            assertTrue(content.getJSONObject("current_draft_statistics").getBoolean("available"))
            assertEquals(setOf("schema_version", "registry_version", "input_mode", "goal", "stored", "effective",
                "allowed_parameters", "locked_parameters", "read_only", "current_draft_statistics"), content.keys().asSequence().toSet())
            assertEquals(editor.formal.copy(look = editor.formal.look.copy(contrast = 3.0)), editor.displayed)
            assertEquals(1.5, editor.displayed.look.resolved().contrast - editor.formal.look.resolved().contrast, 1e-6)
            assertEquals(1, fixture.server.requestCount); assertEquals(1, editor.statisticsCalls)
            assertEquals(0, editor.commits); assertFalse(editor.saved.isCompleted)
            controller.discard(); controller.phase(DraftPhase.IDLE); assertEquals(editor.formal, editor.displayed)
        }
    }

    private fun cinematicController(scope: CoroutineScope, editor: Editor): AdviceSessionController {
        editor.statisticsGate = { cinematicStatistics(it, listOf(.21, .48, .65)) }
        val contrast = AdviceParameter.CONTRAST.id
        return AdviceSessionController(scope, editor).apply {
            configure(AdviceConnection(identity()) { request, _ ->
                assertTrue(3.0 in checkNotNull(request.allowed[contrast]))
                reply(AdviceResult.Proposal(listOf(ParameterChange(contrast, 3.0))))
            })
            begin()
            includeStatistics(true)
        }
    }

    @Test fun safeCinematicCandidateIsShownAndOfferedWithoutWriting() = runBlocking {
        val editor = Editor()
        val formal = editor.capture()
        val controller = cinematicController(this, editor)

        controller.generateCinematicRecommendation()
        val result = controller.phase(DraftPhase.PREVIEWING)
        val (candidate, entry, base) = editor.cinematicRenders.single()
        assertEquals(formal, entry)
        assertEquals(formal.edit, base.edit)
        assertEquals(3.0, candidate.edit.look.contrast, 0.0)
        assertSame(candidate, result.draft)
        assertSame(candidate, editor.offers.single())
        assertEquals(candidate.edit, editor.displayed)
        assertNull(result.error)
        assertNull(result.notice)
        assertTrue(result.canApply)
        assertEquals(formal, editor.capture())
        assertEquals(0, editor.commits)
        assertFalse(editor.saved.isCompleted)

        controller.setStrength(50)
        val midpoint = controller.phase(DraftPhase.PREVIEWING)
        assertEquals(50, midpoint.strengthPercent)
        assertEquals(1.5, midpoint.draft!!.edit.look.contrast, 0.0)
        assertSame(base, editor.cinematicRenders.last().third)
        assertEquals(2, editor.cinematicRenders.size)
        assertEquals(1, editor.statisticsCalls)
    }

    @Test fun clippedCinematicCandidateLeavesOnlyThePreviousDraftAvailableToCompareAndApply() = runBlocking<Unit> {
        val editor = Editor().apply {
            rejectCinematicHighlights = true
            reducedResult = AdvisorRenderResult.NO_IMPROVEMENT
        }
        val formal = editor.capture()
        val controller = cinematicController(this, editor)
        controller.propose(changes(9.0))
        val previous = checkNotNull(controller.phase(DraftPhase.PREVIEWING).draft)
        assertSame(previous, editor.offers.single())

        controller.generateCinematicRecommendation()
        val result = controller.phase(DraftPhase.PREVIEWING)
        val (rejected, entry, base) = editor.cinematicRenders.single()
        assertEquals(formal, entry)
        assertEquals(previous.edit, base.edit)
        assertEquals(previous.edit.copy(look = previous.edit.look.copy(contrast = 3.0)), rejected.edit)
        assertEquals(1.5, editor.reducedRenders.single().first.edit.look.contrast, 0.0)
        assertEquals(AdviceNotice.CINEMATIC_NO_ADJUSTMENT, result.notice)
        assertNull(result.error)
        assertNull(result.pending)
        assertSame(previous, result.draft)
        assertEquals(previous.edit, editor.displayed)
        assertFalse(result.showingEntry)
        assertTrue(result.canApply)
        assertEquals(0, editor.commits)
        assertEquals(formal, editor.capture())
        assertFalse(editor.saved.isCompleted)

        controller.compare(true)
        assertTrue(controller.phase(DraftPhase.PREVIEWING).showingEntry)
        assertEquals(formal.edit, editor.displayed)
        controller.compare(false)
        val compared = controller.phase(DraftPhase.PREVIEWING)
        assertFalse(compared.showingEntry)
        assertSame(previous, compared.draft)
        assertEquals(previous.edit, editor.displayed)
        assertTrue(editor.offers.all { it === previous })

        controller.apply()
        controller.phase(DraftPhase.APPLIED)
        assertEquals(1, editor.commits)
        assertEquals(previous.edit, editor.formal)
        assertTrue(editor.offers.all { it === previous })
        editor.saved.complete(Unit)
        controller.phase(DraftPhase.SAVED)
    }

    @Test fun clippedCinematicRecommendationRecoversOnlyTheCurrentRequestDelta() = runBlocking<Unit> {
        val editor = Editor().apply {
            rejectCinematicHighlights = true
            reducedResult = AdvisorRenderResult.SHOWN
            statisticsGate = { cinematicStatistics(it) }
        }
        var sends = 0
        val controller = AdviceSessionController(this, editor)
        controller.configure(AdviceConnection(identity()) { request, _ ->
            sends++
            assertFalse(request.allowed.containsKey(AdviceParameter.SATURATION.id))
            reply(AdviceResult.Proposal(listOf(ParameterChange(AdviceParameter.CONTRAST.id, 6.0))))
        })
        controller.begin()
        controller.includeStatistics(true)
        controller.propose(changes(9.0) + ParameterChange(AdviceParameter.CONTRAST.id, 2.0))
        val previous = checkNotNull(controller.phase(DraftPhase.PREVIEWING).draft)
        controller.allowParameters(AdviceParameter.entries.toSet() - AdviceParameter.SATURATION)
        controller.generateCinematicRecommendation()
        val recovered = controller.phase(DraftPhase.PREVIEWING)
        val reduced = editor.reducedRenders.single()
        assertEquals(previous.edit, reduced.third.edit)
        assertEquals(50, recovered.strengthPercent)
        assertEquals(4.0, editor.displayed.look.contrast, 0.0)
        assertEquals(9.0, editor.displayed.look.saturation, 0.0)
        assertSame(recovered.draft, editor.offers.last())
        assertEquals(0, editor.commits)

        controller.setStrength(100) // A manual choice still uses the gate and is not retried.
        assertSame(recovered.draft, controller.phase(DraftPhase.PREVIEWING).draft)
        assertEquals(1, editor.reducedRenders.size)
        editor.rejectCinematicHighlights = false
        controller.setStrength(0)
        assertEquals(0, controller.phase(DraftPhase.PREVIEWING).strengthPercent)
        assertEquals(previous.edit, editor.displayed)
        assertEquals(1, sends)
    }

    @Test fun clippedFirstCinematicCandidateLeavesFormalEditAndCannotBeWritten() = runBlocking {
        val editor = Editor().apply { rejectCinematicHighlights = true }
        val formal = editor.capture()
        val controller = cinematicController(this, editor)

        controller.generateCinematicRecommendation()
        val result = controller.phase(DraftPhase.READY)
        val (rejected, entry, base) = editor.cinematicRenders.single()
        assertEquals(formal, entry)
        assertEquals(formal.edit, base.edit)
        assertEquals(3.0, rejected.edit.look.contrast, 0.0)
        assertEquals(1, editor.reducedRenders.size)
        assertEquals(AdviceNotice.CINEMATIC_NO_ADJUSTMENT, result.notice)
        assertNull(result.error)
        assertNull(result.pending)
        assertNull(result.draft)
        assertFalse(result.canApply)
        assertEquals(formal.edit, editor.displayed)

        controller.apply()
        yield()
        assertEquals(DraftPhase.READY, controller.state.value.phase)
        assertEquals(formal, editor.capture())
        assertEquals(0, editor.commits)
        assertTrue(editor.offers.isEmpty())
        assertFalse(editor.saved.isCompleted)
    }

    @Test fun clippedCinematicRecommendationDoesNotReplaceAManualOverrideDuringRetry() = runBlocking<Unit> {
        val editor = Editor().apply {
            rejectCinematicHighlights = true
            reducedResult = AdvisorRenderResult.SHOWN
        }
        val controller = cinematicController(this, editor)
        controller.propose(changes(9.0))
        controller.phase(DraftPhase.PREVIEWING)
        controller.adjust(listOf(ParameterChange(AdviceParameter.CONTRAST.id, 2.0)))
        val manual = checkNotNull(controller.phase(DraftPhase.PREVIEWING).draft)

        controller.generateCinematicRecommendation()
        val rejected = controller.phase(DraftPhase.PREVIEWING)
        assertEquals(3.0, editor.cinematicRenders.single().first.edit.look.contrast, 0.0)
        assertTrue(editor.reducedRenders.isEmpty())
        assertEquals(AdviceNotice.CINEMATIC_NO_ADJUSTMENT, rejected.notice)
        assertSame(manual, rejected.draft)
        assertEquals(2.0, editor.displayed.look.contrast, 0.0)
    }

    @Test fun cinematicProposalWithUnchangedRenderInputsKeepsThePreviousDraft() = runBlocking {
        val editor = Editor().apply {
            formal = formal.copy(look = formal.look.copy(contrast = 40.0))
            displayed = formal
            statisticsGate = { cinematicStatistics(it) }
        }
        val controller = AdviceSessionController(this, editor)
        var requests = 0
        controller.configure(AdviceConnection(identity()) { request, _ ->
            requests++
            val parameter = AdviceParameter.CONTRAST.id
            val target = 40.0000015
            assertTrue(target in checkNotNull(request.allowed[parameter]))
            reply(AdviceResult.Proposal(listOf(ParameterChange(parameter, target))))
        })
        controller.begin(); controller.includeStatistics(true)
        controller.propose(changes(18.0))
        val previous = checkNotNull(controller.phase(DraftPhase.PREVIEWING).draft)
        controller.generateCinematicRecommendation()
        val result = controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceError.NO_EFFECT, result.error)
        assertSame(previous, result.draft)
        assertEquals(previous.edit, editor.displayed)
        assertTrue(editor.offers.all { it === previous })
        assertEquals(1, requests)
        assertEquals(0, editor.commits)
    }

    @Test fun cinematicResultMatchingTheFormalEditClosesTheDraftWithoutSaving() = runBlocking {
        val editor = Editor()
        val formal = editor.formal
        val contrast = AdviceParameter.CONTRAST.id
        val controller = AdviceSessionController(this, editor)
        editor.statisticsGate = { snapshot ->
            assertEquals(-1.0, snapshot.edit.look.contrast, 0.0)
            assertEquals(snapshot.edit, editor.displayed)
            cinematicStatistics(snapshot, listOf(.21, .48, .65))
        }
        var calls = 0
        controller.configure(AdviceConnection(identity()) { request, _ ->
            calls++
            assertTrue(0.0 in checkNotNull(request.allowed[contrast]))
            reply(AdviceResult.Proposal(listOf(ParameterChange(contrast, 0.0))))
        })
        controller.begin(); controller.includeStatistics(true)
        controller.adjust(listOf(ParameterChange(contrast, -1.0)))
        controller.phase(DraftPhase.PREVIEWING)
        assertTrue(controller.state.value.canApply)
        controller.generateCinematicRecommendation()
        val result = withTimeout(3000) {
            controller.state.first { it.phase == DraftPhase.READY || it.phase == DraftPhase.PREVIEWING }
        }
        assertEquals(DraftPhase.READY, result.phase)
        assertEquals(AdviceNotice.NO_CHANGE, result.notice)
        assertNull(result.draft)
        assertTrue(result.includeStatistics)
        assertEquals(formal, editor.displayed)
        assertEquals(listOf(-1.0), editor.offers.map { it.edit.look.contrast })
        controller.apply()
        assertEquals(1, calls)
        assertEquals(0, editor.commits)
        assertFalse(editor.saved.isCompleted)
    }

    @Test fun cinematicPermissionsFollowLumaHeadroomAndChromaWithoutUnlockingFields() {
        val editor = Editor()
        val snapshot = editor.capture()
        fun ranges(luma: List<Double>, black: Double = 0.0, white: Double = 0.0, rgb: Int = 21,
            hard: AdviceConstraints = AdviceConstraints.all()) = cinematicConstraints(snapshot, hard,
            cinematicStatistics(snapshot, luma, black, white, rgb).values).ranges
        val flat = listOf(.25, .49, .69)
        assertEquals(mapOf(AdviceParameter.CONTRAST to ParameterRange(0.0, 4.0),
            AdviceParameter.SHADOWS to ParameterRange(-3.0, 0.0), AdviceParameter.VIBRANCE to ParameterRange(0.0, 4.0)),
            ranges(flat, rgb = 22))
        assertFalse(AdviceParameter.VIBRANCE in ranges(flat, rgb = 49))
        assertEquals(mapOf(AdviceParameter.CONTRAST to ParameterRange(0.0, 2.0)), ranges(listOf(.11, .46, .75)))
        assertEquals(ParameterRange(0.0, 2.0), ranges(flat, black = .003)[AdviceParameter.CONTRAST])
        assertTrue(ranges(listOf(.08, .20, .41), rgb = 22).isEmpty())
        val autumn = checkNotNull(cinematicStatistics(snapshot, listOf(.11, .32, .71)).values)
        val crowded = autumn.copy(blue = List(16) { if (it == 0) .07 else if (it == 6) .93 else 0.0 })
        assertTrue(cinematicConstraints(snapshot, AdviceConstraints.all(), crowded).ranges.isEmpty())
        assertTrue(ranges(listOf(.10, .42, .82)).isEmpty())
        assertEquals(mapOf(AdviceParameter.HIGHLIGHTS to ParameterRange(-8.0, 0.0)),
            ranges(listOf(.12, .48, .96), white = .02))
        assertEquals(mapOf(AdviceParameter.SHADOWS to ParameterRange(0.0, 5.0)),
            ranges(listOf(.05, .17, .60), black = .01))
        assertEquals(setOf(AdviceParameter.HIGHLIGHTS, AdviceParameter.SHADOWS),
            ranges(listOf(.04, .40, .96), black = .01, white = .01).keys)
        assertTrue(ranges(listOf(.02, .035, .21), black = .10).isEmpty())
        assertEquals(mapOf(AdviceParameter.CONTRAST to ParameterRange(0.0, .5)), ranges(flat,
            hard = AdviceConstraints(mapOf(AdviceParameter.CONTRAST to ParameterRange(-1.0, .5)), 1)))
        val zero = snapshot.copy(edit = snapshot.edit.copy(look = snapshot.edit.look.copy(palette = 0.0)))
        assertTrue(cinematicConstraints(zero, AdviceConstraints.all(), cinematicStatistics(zero, flat, rgb = 22).values).ranges.isEmpty())
    }

    @Test fun cinematicDoesNotCompressTonesJustBecauseThePhotoIsDarkOrBright() {
        val snapshot = Editor().capture()
        fun ranges(luma: List<Double>, black: Double, white: Double) = cinematicConstraints(snapshot,
            AdviceConstraints.all(), cinematicStatistics(snapshot, luma, black, white).values).ranges
        assertTrue("A dark scene without crowded blacks should not be flattened",
            ranges(listOf(.074, .166, .405), black = .0002, white = 0.0).isEmpty())
        assertTrue("Bright fog without crowded whites should not be flattened",
            ranges(listOf(.125, .815, .948), black = .0046, white = 0.0).isEmpty())
        assertEquals(setOf(AdviceParameter.SHADOWS),
            ranges(listOf(.093, .806, .936), black = .0158, white = 0.0).keys)
    }

    @Test fun cinematicOnlyPartlyRestoresContrastAttenuatedByThePalette() {
        val base = Editor().capture()
        val contrast = AdviceParameter.CONTRAST
        val moderate = listOf(.13, .40, .68)
        val flat = listOf(.21, .48, .65)
        fun delta(palette: Double, luma: List<Double>, hard: AdviceConstraints = AdviceConstraints.all()): Double? {
            val edit = base.edit.copy(look = base.edit.look.copy(palette = palette, contrast = -6.0))
            val snapshot = base.copy(edit = edit)
            val range = cinematicConstraints(snapshot, hard, cinematicStatistics(snapshot, luma).values).ranges[contrast]
                ?: return null
            return contrast.write(edit.look, range.maximum).resolved().contrast - edit.look.resolved().contrast
        }
        assertEquals(2.0, delta(100.0, moderate)!!, 1e-9)
        assertEquals(1.5, delta(50.0, moderate)!!, 1e-9)
        assertEquals(2.5, delta(50.0, flat)!!, 1e-9)
        assertEquals(2.0, delta(80.0, moderate)!!, 1e-9)
        assertEquals(.15, delta(5.0, moderate)!!, 1e-9)
        assertNull(delta(0.0, moderate))
        assertNull(delta(50.0, listOf(.08, .24, .40)))
        assertEquals(.5, delta(50.0, moderate, AdviceConstraints(mapOf(contrast to ParameterRange(-6.0, -5.0)), 1))!!, 1e-9)
        assertNull(delta(50.0, moderate, AdviceConstraints(emptyMap(), 2)))
    }

    @Test fun cinematicShadowsOnlyOfferTargetsBeyondTheTonePadClamp() {
        val entry = Editor().capture()
        val shadows = AdviceParameter.SHADOWS
        fun verify(tone: Double, palette: Double, stored: Double, luma: List<Double>,
            expectedEffective: Double, noEffect: Double, extreme: Double, black: Double = 0.0) {
            val look = entry.edit.look.copy(tone = tone, palette = palette, shadows = stored)
            val snapshot = entry.copy(edit = entry.edit.copy(look = look))
            val statistics = cinematicStatistics(snapshot, luma, black = black).values
            assertEquals(expectedEffective, look.resolved().shadows, 0.0)
            assertEquals(expectedEffective, look.copy(shadows = noEffect).resolved().shadows, 0.0)
            val permissions = cinematicConstraints(snapshot, AdviceConstraints.all(), statistics)
            val range = checkNotNull(permissions.ranges[shadows])
            assertFalse("Advertises an actually unchanged shadow target: $range", noEffect in range)
            assertTrue(extreme in range)
            for (target in listOf(range.minimum, range.maximum)) {
                val candidate = AdviceProjection.candidate(snapshot, listOf(ParameterChange(shadows.id, target)),
                    permissions, Catalog.EMPTY, snapshot.ticket)
                assertEquals(look.copy(shadows = target), candidate.edit.look)
                assertNotEquals(expectedEffective.toFloat(), candidate.edit.look.resolved().shadows.toFloat())
            }
            val hard = AdviceConstraints(mapOf(shadows to ParameterRange(minOf(stored, noEffect), maxOf(stored, noEffect))), 0)
            assertTrue(cinematicConstraints(snapshot, hard, statistics).ranges.isEmpty())
        }

        verify(tone = -100.0, palette = 80.0, stored = -78.0, luma = listOf(.05, .17, .60),
            expectedEffective = -100.0, noEffect = -76.0, extreme = -73.0, black = .01)
        verify(tone = 50.0, palette = 100.0, stored = 82.0, luma = listOf(.25, .49, .69),
            expectedEffective = 100.0, noEffect = 81.0, extreme = 79.0)
    }

    @Test fun cinematicLocalSkipsKeepTheDraftWithoutCallingTheServiceOrShowingUsage() = runBlocking {
        val editor = Editor(); val controller = AdviceSessionController(this, editor)
        controller.begin(); controller.propose(changes(9.0)); controller.phase(DraftPhase.PREVIEWING)
        val draft = controller.state.value.draft
        var calls = 0
        controller.configure(AdviceConnection(identity()) { _, _ -> calls++; reply(AdviceResult.NoChange) })
        controller.includeStatistics(true)
        editor.statisticsGate = { cinematicStatistics(it, listOf(.21, .48, .65)) }
        controller.generateCinematicRecommendation(); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceNotice.NO_CHANGE, controller.state.value.notice)
        assertEquals(123L, controller.state.value.usage?.totalTokens)
        assertEquals(1, calls)

        editor.statisticsGate = { null }
        controller.generateCinematicRecommendation(); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceNotice.CINEMATIC_PREVIEW_NOT_ANALYZED, controller.state.value.notice)
        assertNull(controller.state.value.usage)
        assertEquals(2, editor.statisticsCalls); assertEquals(1, calls)
        assertSame(draft, controller.state.value.draft); assertEquals(draft!!.edit, editor.displayed)

        editor.statisticsGate = { cinematicStatistics(it, listOf(.10, .50, .79)) }
        controller.generateCinematicRecommendation(); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceNotice.CINEMATIC_NO_ADJUSTMENT, controller.state.value.notice)
        assertNull(controller.state.value.usage)
        assertEquals(3, editor.statisticsCalls); assertEquals(1, calls)
        assertSame(draft, controller.state.value.draft); assertEquals(draft.edit, editor.displayed)
        assertEquals(0, editor.commits); assertEquals(1, editor.offers.size)
    }

    @Test fun cinematicReplyAndClarificationStayBoundedButManualGoalsKeepTheFullContract() = runBlocking {
        val editor = Editor(); val controller = AdviceSessionController(this, editor)
        editor.statisticsGate = { cinematicStatistics(it, listOf(.21, .48, .65)) }
        val inputs = mutableListOf<AdviceRequest>()
        val question = AdviceResult.Question("text", "Synthetic contrast preference?", emptyList())
        controller.configure(AdviceConnection(identity()) { request, clarification ->
            inputs += request
            when (inputs.size) {
                1 -> reply(question)
                2 -> { assertEquals("gentle", clarification?.answer)
                    reply(AdviceResult.Proposal(listOf(ParameterChange("layer2.contrast", 5.0)))) }
                else -> reply(AdviceResult.Proposal(listOf(ParameterChange("layer2.exposure", .25))))
            }
        })
        controller.begin(); controller.includeStatistics(true)
        controller.generateCinematicRecommendation(); controller.phase(DraftPhase.READY)
        assertEquals(question, controller.state.value.question)
        controller.answer("gentle"); controller.phase(DraftPhase.READY)
        assertEquals(inputs.first().allowed, inputs[1].allowed)
        assertEquals(AdviceError.LOCK_CONFLICT, controller.state.value.error)
        assertEquals(editor.formal, editor.displayed); assertTrue(editor.offers.isEmpty())
        controller.generateCinematicRecommendation(); controller.phase(DraftPhase.READY)
        assertEquals(AdviceError.LOCK_CONFLICT, controller.state.value.error)
        assertTrue(editor.offers.isEmpty())
        controller.includeStatistics(false)
        controller.generate("Manual exposure"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceParameter.entries.map { it.id }.toSet(), inputs.last().allowed.keys)
        assertNull(inputs.last().statistics)
        assertEquals(.25, editor.displayed.look.exposure, 0.0)
        assertEquals(0, editor.commits)
    }

    @Test fun cinematicClarificationContinuesWithTheSameDraftWhileComparingTheFormalEdit() = runBlocking<Unit> {
        val editor = Editor()
        editor.statisticsGate = { snapshot ->
            if (snapshot.edit == editor.displayed) cinematicStatistics(snapshot, listOf(.21, .48, .65)) else null
        }
        val controller = AdviceSessionController(this, editor)
        val question = AdviceResult.Question("text", "Synthetic contrast preference?", emptyList())
        val requests = mutableListOf<Pair<AdviceRequest, TaskClarification?>>()
        controller.configure(AdviceConnection(identity()) { request, answer ->
            requests += request to answer
            if (answer == null) reply(question)
            else reply(AdviceResult.Proposal(listOf(ParameterChange("layer2.contrast", 3.0))))
        })
        controller.begin(); controller.propose(changes(9.0)); controller.phase(DraftPhase.PREVIEWING)
        val draft = checkNotNull(controller.state.value.draft)
        controller.includeStatistics(true)
        controller.generateCinematicRecommendation(); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(question, controller.state.value.question)
        controller.compare(true); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(editor.formal, editor.displayed)
        controller.answer("gentle"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals("Comparing the formal edit must not end the clarification", 2, requests.size)
        assertEquals(requests.first().first, requests.last().first)
        assertEquals(TaskClarification(question, "gentle"), requests.last().second)
        assertEquals(draft.edit.copy(look = draft.edit.look.copy(contrast = 3.0)), editor.displayed)
        assertEquals(0, editor.commits)

        controller.generateCinematicRecommendation(); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(question, controller.state.value.question)
        controller.compare(true); controller.phase(DraftPhase.PREVIEWING)
        controller.includeStatistics(false)
        controller.answer("gentle"); yield()
        assertEquals(3, requests.size)
        assertNull(controller.state.value.question)
        assertEquals(editor.formal, editor.displayed)
        assertEquals(0, editor.commits)
    }

    @Test fun failedCinematicAnswerCanBeRetriedWithTheOriginalQuestionAndSample() = runBlocking<Unit> {
        val editor = Editor()
        editor.statisticsGate = { snapshot ->
            if (snapshot.edit == editor.displayed) cinematicStatistics(snapshot, listOf(.21, .48, .65)) else null
        }
        val controller = AdviceSessionController(this, editor)
        val question = AdviceResult.Question("choice", "Synthetic contrast preference?",
            listOf(AdviceResult.Option("gentle", "Gentle"), AdviceResult.Option("strong", "Strong")))
        val requests = mutableListOf<Pair<AdviceRequest, TaskClarification?>>()
        controller.configure(AdviceConnection(identity()) { request, clarification ->
            requests += request to clarification
            when (requests.size) {
                1 -> reply(question)
                2 -> throw AdvisorNetworkFailure(AdvisorNetworkError.TIMEOUT)
                else -> reply(AdviceResult.Proposal(listOf(ParameterChange("layer2.contrast", 3.0))))
            }
        })
        controller.begin(); controller.propose(changes(9.0)); controller.phase(DraftPhase.PREVIEWING)
        val draft = checkNotNull(controller.state.value.draft)
        controller.includeStatistics(true)
        controller.generateCinematicRecommendation(); controller.phase(DraftPhase.PREVIEWING)
        controller.compare(true); controller.phase(DraftPhase.PREVIEWING)
        controller.answer("gentle"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceError.NETWORK_TIMEOUT, controller.state.value.error)
        assertEquals(question, controller.state.value.question)
        assertSame(draft, controller.state.value.draft)
        assertEquals(0, editor.commits)
        yield(); assertEquals(2, requests.size)

        controller.answer("gentle"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(3, requests.size)
        assertEquals(requests[1], requests[2])
        assertEquals(1, editor.statisticsCalls)
        assertNull(controller.state.value.question)
        assertEquals(3.0, editor.displayed.look.contrast, 0.0)
        assertEquals(0, editor.commits)
    }

    @Test fun cancellingACinematicAnswerKeepsTheQuestionAndDraftWithoutResending() = runBlocking<Unit> {
        val editor = Editor()
        editor.statisticsGate = { snapshot ->
            if (snapshot.edit == editor.displayed) cinematicStatistics(snapshot, listOf(.21, .48, .65)) else null
        }
        val controller = AdviceSessionController(this, editor)
        val question = AdviceResult.Question("text", "Synthetic contrast preference?", emptyList())
        val submitted = kotlinx.coroutines.channels.Channel<Unit>(2)
        val requests = mutableListOf<Pair<AdviceRequest, TaskClarification?>>()
        controller.configure(AdviceConnection(identity()) { request, clarification ->
            requests += request to clarification
            when {
                clarification == null -> reply(question)
                requests.size == 2 || clarification.answer == "cancel again" -> {
                    submitted.send(Unit)
                    awaitCancellation()
                }
                else -> reply(AdviceResult.Proposal(listOf(ParameterChange("layer2.contrast", 3.0))))
            }
        })
        controller.begin(); controller.propose(changes(9.0)); controller.phase(DraftPhase.PREVIEWING)
        val draft = checkNotNull(controller.state.value.draft)
        controller.includeStatistics(true)
        controller.generateCinematicRecommendation(); controller.phase(DraftPhase.PREVIEWING)
        controller.compare(true); controller.phase(DraftPhase.PREVIEWING)
        controller.answer("gentle")
        withTimeout(3000) { submitted.receive() }
        controller.cancelRequest(); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(question, controller.state.value.question)
        assertEquals(AdviceNotice.CANCELLED, controller.state.value.notice)
        assertSame(draft, controller.state.value.draft)
        assertEquals(draft.edit, editor.displayed)
        yield(); assertEquals(2, requests.size)
        assertEquals(0, editor.commits)

        controller.answer("gentle"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(requests[1], requests[2])
        assertEquals(1, editor.statisticsCalls)
        assertEquals(3.0, editor.displayed.look.contrast, 0.0)
        assertNull(controller.state.value.question)

        controller.generateCinematicRecommendation(); controller.phase(DraftPhase.PREVIEWING)
        controller.answer("cancel again")
        withTimeout(3000) { submitted.receive() }
        controller.cancelRequest(); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(question, controller.state.value.question)
        controller.includeStatistics(false)
        controller.answer("cancel again"); yield()
        assertNull(controller.state.value.question)
        assertEquals(5, requests.size)
    }

    @Test fun saveWhileAnsweringACinematicQuestionExportsOnlyTheFormalEditAndPreservesTheQuestion() = runBlocking<Unit> {
        val editor = Editor()
        editor.statisticsGate = { snapshot ->
            if (snapshot.edit == editor.displayed) cinematicStatistics(snapshot, listOf(.21, .48, .65)) else null
        }
        val controller = AdviceSessionController(this, editor)
        val question = AdviceResult.Question("text", "Synthetic contrast preference?", emptyList())
        val answering = CompletableDeferred<Unit>()
        val requests = mutableListOf<Pair<AdviceRequest, TaskClarification?>>()
        controller.configure(AdviceConnection(identity()) { request, clarification ->
            requests += request to clarification
            when (requests.size) {
                1 -> reply(question)
                2 -> { answering.complete(Unit); awaitCancellation() }
                else -> reply(AdviceResult.Proposal(listOf(ParameterChange("layer2.contrast", 3.0))))
            }
        })
        controller.begin(); controller.propose(changes(9.0)); controller.phase(DraftPhase.PREVIEWING)
        val draft = checkNotNull(controller.state.value.draft)
        controller.includeStatistics(true)
        controller.generateCinematicRecommendation(); controller.phase(DraftPhase.PREVIEWING)
        controller.answer("gentle")
        withTimeout(3000) { answering.await() }

        val exports = mutableListOf<Edit>()
        controller.prepareExport { exports += editor.displayed }
        controller.phase(DraftPhase.PREVIEWING)
        assertEquals(listOf(editor.formal), exports)
        assertSame(draft, controller.state.value.draft)
        assertTrue(controller.state.value.showingEntry)
        assertEquals(question, controller.state.value.question)
        assertEquals(AdviceNotice.CANCELLED, controller.state.value.notice)
        yield(); assertEquals(2, requests.size)
        assertEquals(0, editor.commits)

        controller.answer("gentle"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(3, requests.size)
        assertEquals(requests[1], requests[2])
        assertEquals(1, editor.statisticsCalls)
        assertNull(controller.state.value.question)
        assertEquals(3.0, editor.displayed.look.contrast, 0.0)
        assertEquals(listOf(editor.formal), exports)
        assertEquals(0, editor.commits)
    }

    @Test fun timeoutKeepsTheLastDraftAndRequiresAnExplicitRetry() = runBlocking {
        val editor = Editor()
        val controller = AdviceSessionController(this, editor)
        controller.begin(); controller.propose(changes(10.0)); controller.phase(DraftPhase.PREVIEWING)
        var calls = 0
        controller.configure(AdviceConnection(identity()) { _, _ ->
            calls++
            throw AdvisorNetworkFailure(AdvisorNetworkError.TIMEOUT)
        })
        controller.generate("Synthetic"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(AdviceError.NETWORK_TIMEOUT, controller.state.value.error)
        assertEquals(10.0, controller.state.value.draft!!.edit.look.saturation, 0.0)
        assertEquals(10.0, editor.displayed.look.saturation, 0.0)
        assertEquals(0, editor.commits)
        yield(); assertEquals(1, calls)
        controller.generate("Synthetic retry"); controller.phase(DraftPhase.PREVIEWING)
        assertEquals(2, calls)
    }
}
