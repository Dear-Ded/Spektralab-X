package com.dearded.spektralab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportRetryTest {
    @Test fun sourceBelowDeviceCapStillShrinksWhenFirstRetryEdgeExceedsSource() {
        // Edge 0 -> 6144 alone leaves both 6000x4000 and 4000x3000 unchanged.
        val deviceCap = 42_000_000L
        val retry24Mp = exportRetryPixelCap(deviceCap, 24_000_000L)
        val retry12Mp = exportRetryPixelCap(deviceCap, 12_000_000L)
        assertEquals(14_400_000L, retry24Mp)
        assertEquals(7_200_000L, retry12Mp)
        assertTrue(retry24Mp < 6000L * 4000)
        assertTrue(retry12Mp < 4000L * 3000)
    }

    @Test fun sourceAboveDeviceCapShrinksFromTheActiveCap() {
        assertEquals(25_200_000L, exportRetryPixelCap(42_000_000L, 80_000_000L))
    }

    @Test fun knownSourceWithNoCapReceivesASmallerCap() {
        assertEquals(14_400_000L, exportRetryPixelCap(0L, 24_000_000L))
    }

    @Test fun subsequentRetryShrinksFromThePreviousCap() {
        val first = exportRetryPixelCap(42_000_000L, 24_000_000L)
        assertEquals(8_640_000L, exportRetryPixelCap(first, 24_000_000L))
    }

    @Test fun unknownOutputRetainsCapOnlyFallback() {
        for (unknown in listOf(0L, -1L)) {
            assertEquals(25_200_000L, exportRetryPixelCap(42_000_000L, unknown))
            // No source estimate and no cap: only the existing edge fallback applies.
            assertEquals(0L, exportRetryPixelCap(0L, unknown))
        }
    }

    @Test fun shareOutputHintSetsCapBelowShareAreaWithoutClaimingWorkingSourceSize() {
        // A 6000x4000 source has a 3400x2266.67 share estimate, not a 24 MP estimate.
        val sharePixels = 7_706_666L
        assertEquals(4_623_999L, exportRetryPixelCap(42_000_000L, sharePixels))
    }

    @Test fun retryHasNoMemoryTierFloorAndDoesNotTurnPositiveCapOff() {
        assertEquals(1_200_000L, exportRetryPixelCap(42_000_000L, 2_000_000L))
        assertEquals(1L, exportRetryPixelCap(1L, 1L))
        assertEquals(5_534_023_222_112_865_484L, exportRetryPixelCap(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
