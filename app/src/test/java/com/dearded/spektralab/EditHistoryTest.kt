package com.dearded.spektralab

import org.junit.Assert.*
import org.junit.Test

/** Co-created with GPT (OpenAI). An asynchronous RAW reopen must not consume history early. */
class EditHistoryTest {
    private val neutral = Edit.NONE
    private val standard = neutral.copy(rawDevelopment = RawDevelopmentMode.STANDARD_V1)

    @Test fun inspectingPendingUndoAndRedoLeavesBothStacksIntact() {
        val history = EditHistory()
        history.record(neutral, standard, 1000)
        repeat(3) { assertEquals(neutral, history.undoTarget) }
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(neutral, history.undo(standard))
        repeat(3) { assertEquals(standard, history.redoTarget) }
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals(standard, history.redo(neutral))
        assertEquals(neutral, history.undoTarget)
        assertFalse(history.canRedo)
    }

    @Test fun abandonedCandidateDoesNotDiscardRedoOrCreateUndo() {
        val history = EditHistory()
        history.record(neutral, standard, 1000)
        history.undo(standard)
        val pending = history.redoTarget
        assertEquals(standard, pending)
        // Decode failure/cancellation abandons the inspected target without a stack move.
        assertEquals(standard, history.redoTarget)
        assertNull(history.undoTarget)
        history.record(neutral, neutral.copy(rawExposureEv = .5), 2000)
        assertNull(history.redoTarget)
        assertEquals(neutral, history.undoTarget)
    }

    @Test fun editingImmediatelyAfterUndoKeepsThePriorStep() {
        val history = EditHistory()
        val first = neutral.copy(rawExposureEv = .5)
        val second = first.copy(rawExposureEv = 1.0)
        val replacement = first.copy(rawExposureEv = .75)
        history.record(neutral, first, 10)
        history.record(first, second, 1000)
        assertEquals(first, history.undo(second))
        history.record(first, replacement, 1100)
        assertEquals(first, history.undo(replacement))
        assertEquals(neutral, history.undo(first))
        assertNull(history.redoTarget?.takeIf { it == second })
    }

    @Test fun editingImmediatelyAfterRedoCreatesItsOwnStep() {
        val history = EditHistory()
        val first = neutral.copy(rawExposureEv = .5)
        val second = first.copy(rawExposureEv = 1.0)
        history.record(neutral, first, 10)
        assertEquals(neutral, history.undo(first))
        assertEquals(first, history.redo(neutral))
        history.record(first, second, 20)
        assertEquals(first, history.undo(second))
        assertEquals(neutral, history.undo(first))
    }

    @Test fun whiteBalanceConfirmationStaysSeparateFromRapidSliderEdits() {
        val history = EditHistory()
        val exposed = neutral.copy(look = neutral.look.copy(exposure = .5))
        val balanced = exposed.copy(look = exposed.look.copy(temperature = 12.0, tint = -8.0))
        val firstDrag = balanced.copy(look = balanced.look.copy(contrast = 10.0))
        val lastDrag = firstDrag.copy(look = firstDrag.look.copy(contrast = 20.0))
        history.record(neutral, exposed, 1000)
        history.endGroup()
        history.record(exposed, balanced, 1010)
        history.endGroup()
        history.record(balanced, firstDrag, 1020)
        history.record(firstDrag, lastDrag, 1030)
        assertEquals(balanced, history.undo(lastDrag))
        assertEquals(exposed, history.undo(balanced))
        assertEquals(neutral, history.undo(exposed))
        assertEquals(exposed, history.redo(neutral))
        assertEquals(balanced, history.redo(exposed))
        assertEquals(lastDrag, history.redo(balanced))
    }

    @Test fun unchangedConfirmationDoesNotCreateAnUndoStep() {
        val history = EditHistory()
        val exposed = neutral.copy(look = neutral.look.copy(exposure = .5))
        val adjusted = exposed.copy(look = exposed.look.copy(contrast = 10.0))
        history.record(neutral, exposed, 1000)
        history.endGroup()
        history.record(exposed, exposed, 1010)
        history.endGroup()
        history.record(exposed, adjusted, 1020)
        assertEquals(exposed, history.undo(adjusted))
        assertEquals(neutral, history.undo(exposed))
        assertNull(history.undo(neutral))
    }
}
