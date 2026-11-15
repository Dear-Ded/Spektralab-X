package com.dearded.spektralab

import org.junit.Assert.*
import org.junit.Test

class HapticCadenceTest {
    @Test fun everyPulseClassRespectsTheSixteenMillisecondFloor() {
        for (first in HapticEvent.entries) for (next in HapticEvent.entries) {
            val cadence = HapticCadence()
            assertTrue(cadence.accept(first, 100))
            assertFalse("$first -> $next must not bypass the floor", cadence.accept(next, 115))
            assertTrue(cadence.accept(next, 116))
        }
    }

    @Test fun rejectedPulsesDoNotExtendTheWindowOrQueue() {
        val cadence = HapticCadence()
        assertTrue(cadence.accept(HapticEvent.Detent, 0))
        for (time in 1L..15L) assertFalse(cadence.accept(HapticEvent.Detent, time))
        assertTrue(cadence.accept(HapticEvent.Tick, 16))
        assertFalse(cadence.accept(HapticEvent.Confirm, 17))
        assertTrue(cadence.accept(HapticEvent.Confirm, 32))
    }
}
