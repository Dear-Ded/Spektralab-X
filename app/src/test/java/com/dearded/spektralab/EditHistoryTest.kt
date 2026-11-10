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
}
