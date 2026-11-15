package com.dearded.spektralab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticCadenceTest {
    @Test fun fastDetentsDoNotQueueButTheNeutralAndEndStopsStillArrive() {
        val cadence = HapticCadence()
        assertTrue(cadence.accept(HapticEvent.Detent, 100))
        assertFalse(cadence.accept(HapticEvent.Detent, 116))
        assertTrue(cadence.accept(HapticEvent.Tick, 120))
        assertFalse(cadence.accept(HapticEvent.Tick, 140))
        assertTrue(cadence.accept(HapticEvent.Edge, 160))
        assertFalse(cadence.accept(HapticEvent.Edge, 180))
        assertFalse(cadence.accept(HapticEvent.Detent, 190))
        assertTrue(cadence.accept(HapticEvent.Detent, 220))
    }

    @Test fun separateViewsDoNotSuppressEachOtherAndNewGesturesCanStart() {
        val first = HapticCadence()
        val second = HapticCadence()
        assertTrue(first.accept(HapticEvent.Detent, 0))
        assertTrue(second.accept(HapticEvent.Detent, 1))
        assertTrue(first.accept(HapticEvent.GestureStart, 2))
        assertTrue(first.accept(HapticEvent.Confirm, 200))
    }

    @Test fun semanticStopsAndConfirmationCanInterruptOtherFeedbackImmediately() {
        val cadence = HapticCadence()
        assertTrue(cadence.accept(HapticEvent.Tick, 100))
        assertTrue(cadence.accept(HapticEvent.Edge, 101))
        assertFalse(cadence.accept(HapticEvent.Edge, 102))
        assertTrue(cadence.accept(HapticEvent.GestureStart, 200))
        assertTrue(cadence.accept(HapticEvent.Confirm, 201))
    }
}
