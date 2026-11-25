package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.*
import com.dearded.spektralab.advisor.session.*
import org.junit.Assert.*
import org.junit.Test

class AdvisorDraftTest {
    private val initial = Catalog.EMPTY.normalize(Edit.NONE)
    private val context = RequestTicket(1, 2, 0, 3, 0, 0)
    private fun reducer() = DraftReducer().also { it.begin(EditSnapshot(initial, context)) }
    private fun candidate(snapshot: EditSnapshot, value: Double) = AdviceProjection.candidate(snapshot,
        listOf(ParameterChange("layer2.saturation", value)), AdviceConstraints.all(), Catalog.EMPTY, snapshot.ticket)

    @Test fun strengthProjectionKeepsFrozenFieldsAndRoundsNegativeHalfStepsAtRegistryPrecision() {
        val entry = initial.copy(look = initial.look.copy(exposure = -.04, saturation = 4.04, shadows = 110.0))
        val recommendation = entry.copy(look = entry.look.copy(exposure = -.25, saturation = 10.06,
            tint = 12.0, contrast = 8.0, shadows = 90.0))
        val allowed = AdviceConstraints.all().ranges - AdviceParameter.CONTRAST
        val manualOverrides = setOf(AdviceParameter.TINT)
        val midpoint = AdviceProjection.strengthChanges(entry, recommendation, recommendation, 50, allowed, manualOverrides)
        assertEquals(listOf(ParameterChange(AdviceParameter.EXPOSURE.id, -.15),
            ParameterChange(AdviceParameter.SATURATION.id, 7.1)), midpoint)
        assertEquals(listOf(ParameterChange(AdviceParameter.EXPOSURE.id, -.04),
            ParameterChange(AdviceParameter.SATURATION.id, 4.04)),
            AdviceProjection.strengthChanges(entry, recommendation, recommendation, 0, allowed, manualOverrides))
    }

    @Test fun lateResponseCannotReplaceANewerRequestOrPublishedDraft() {
        val reducer = reducer()
        val slow = reducer.request()
        val fast = reducer.request()
        assertFalse(reducer.received(candidate(slow, 20.0)))
        assertTrue(reducer.received(candidate(fast, -10.0)))
        assertTrue(reducer.rendered(fast.ticket))
        assertFalse(reducer.received(candidate(slow, 20.0)))
        assertEquals(-10.0, reducer.state.edit!!.look.saturation, 0.0)
    }

    @Test fun staleRenderDoesNotPublishAndTheNextRoundStartsAtLastShownDraft() {
        val reducer = reducer()
        val first = reducer.request()
        reducer.received(candidate(first, 10.0))
        val second = reducer.request()
        assertEquals(initial, second.edit)
        assertFalse(reducer.rendered(first.ticket))
        reducer.received(candidate(second, 20.0))
        reducer.rendered(second.ticket)
        val third = reducer.request()
        assertEquals(20.0, third.edit.look.saturation, 0.0)
        assertNotEquals(second.ticket.draftRevision, third.ticket.draftRevision)
    }

    @Test fun failureOrCancelKeepsTheLastValidDraftAndInvalidatesTheResponse() {
        val reducer = reducer()
        val first = reducer.request()
        reducer.received(candidate(first, 10.0))
        reducer.rendered(first.ticket)
        val second = reducer.request()
        assertTrue(reducer.failed(second.ticket, AdviceError.RENDER_FAILED))
        assertTrue(reducer.state.canApply)
        assertEquals(10.0, reducer.state.edit!!.look.saturation, 0.0)
        assertFalse(reducer.received(candidate(second, 30.0)))
    }

    @Test fun identicalPhotoValuesAfterInvalidationCannotReviveAnOldTicket() {
        val reducer = reducer()
        val old = reducer.request()
        reducer.invalidate()
        reducer.begin(EditSnapshot(initial, context))
        val new = reducer.request()
        assertNotEquals(old.ticket, new.ticket)
        assertFalse(reducer.received(candidate(old, 20.0)))
    }

    @Test fun threeRoundsDiscardLeaveTheEntryUntouched() {
        val reducer = reducer()
        for (value in listOf(10.0, 20.0, 30.0)) {
            val request = reducer.request()
            reducer.received(candidate(request, value))
            reducer.rendered(request.ticket)
        }
        assertEquals(initial, reducer.state.entry!!.edit)
        reducer.restoring()
        reducer.restored(true)
        assertEquals(DraftPhase.IDLE, reducer.state.phase)
        assertNull(reducer.state.draft)
    }

    @Test fun failedRestoreCannotClaimIdleAndAppliedDoesNotClaimSaved() {
        val reducer = reducer()
        reducer.restoring()
        reducer.restored(false)
        assertEquals(DraftPhase.FAILED, reducer.state.phase)
        assertFalse(reducer.state.canApply)
        reducer.begin(EditSnapshot(initial, context))
        val request = reducer.request()
        reducer.received(candidate(request, 10.0))
        reducer.rendered(request.ticket)
        assertEquals(10.0, reducer.committing().edit.look.saturation, 0.0)
        reducer.applied()
        assertEquals(DraftPhase.APPLIED, reducer.state.phase)
        reducer.saved(false)
        assertEquals(AdviceError.SAVE_FAILED, reducer.state.error)
        reducer.saved(true)
        assertEquals(DraftPhase.SAVED, reducer.state.phase)
    }
}
