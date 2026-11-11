package com.dearded.spektralab

import org.junit.Assert.*
import org.junit.Test

class PreviewPublicationTest {
    @Test fun newerPublicationWinsEvenWhenMainCallbacksArriveOutOfOrder() {
        val gate = PreviewPublication("a")
        gate.replaceSource(4)
        val old = gate.frame(4, gate.intent(), "live", "sRGB")
        val next = gate.frame(4, gate.request("b"), "live", "sRGB")
        assertTrue(gate.publish(next))
        assertFalse(gate.publish(old))
    }

    @Test fun sameEditHigherQualityWinsButANewEditCanStartWithLive() {
        val gate = PreviewPublication("a")
        gate.replaceSource(4)
        assertTrue(gate.publish(gate.frame(4, gate.intent(), "preview", "sRGB")))
        assertFalse(gate.publish(gate.frame(4, gate.intent(), "live", "sRGB")))
        assertTrue(gate.publish(gate.frame(4, gate.request("b"), "live", "sRGB")))
    }

    @Test fun requestRevisionDetectsEditRoundTripAndTemporaryOverride() {
        val gate = PreviewPublication("a")
        gate.replaceSource(4)
        val frame = gate.frame(4, gate.intent(), "full", "Display P3")
        gate.request("hidden")
        gate.request("a")
        assertFalse(gate.current(frame))
        assertFalse(gate.publish(frame))
    }

    @Test fun sourceReplacementClearsRetainedCacheAndRejectsReusedHandle() {
        val gate = PreviewPublication("a")
        gate.replaceSource(4)
        val frame = gate.frame(4, gate.intent(), "preview", "sRGB")
        gate.keep(frame)
        assertEquals("preview", gate.cachedTier(4))
        gate.replaceSource(4)
        assertNull(gate.cachedTier(4))
        assertFalse(gate.publish(frame))
    }

    @Test fun failedRenderDoesNotChangeRetainedTier() {
        val gate = PreviewPublication("a")
        gate.replaceSource(4)
        gate.keep(gate.frame(4, gate.intent(), "preview", "sRGB"))
        gate.frame(4, gate.intent(), "live", "sRGB") // Failed/cancelled: do not keep.
        assertEquals("preview", gate.cachedTier(4))
    }

    @Test fun releasedSessionCannotPublishOrRepack() {
        val gate = PreviewPublication("a")
        gate.replaceSource(4)
        val frame = gate.frame(4, gate.intent(), "live", "sRGB")
        gate.replaceSource(0)
        assertFalse(gate.publish(frame))
        assertNull(gate.cachedTier(0))
    }

    @Test fun staleLivePermissionIsExplicitAndNeverGrantedToRepack() {
        val gate = PreviewPublication("a")
        gate.replaceSource(4)
        val frame = gate.frame(4, gate.intent(), "live", "sRGB")
        gate.request("b")
        assertFalse(gate.publish(frame))
        assertTrue(gate.publish(frame, allowStaleLive = true))
    }
}
