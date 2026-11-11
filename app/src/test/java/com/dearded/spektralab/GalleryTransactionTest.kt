package com.dearded.spektralab

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryTransactionTest {
    private val pending = GalleryEntry(GalleryState.PENDING)
    private val published = GalleryEntry(GalleryState.PUBLISHED, "saved.jpg")
    private val gone = GalleryEntry(GalleryState.GONE)

    @Test fun failedQueryIsUnknown() {
        assertEquals(GalleryState.UNKNOWN, GalleryTransaction.read { error("provider offline") }.state)
    }

    @Test fun nullQueryIsUnknown() {
        assertEquals(GalleryState.UNKNOWN, GalleryTransaction.read { null }.state)
    }

    @Test fun confirmedMissingEntryIsGone() {
        assertEquals(gone, GalleryTransaction.read { gone })
    }

    @Test fun publishedEntryIsNeverDeleted() {
        assertEquals(published, GalleryTransaction.discardPending({ published }) { error("must not delete") })
    }

    @Test fun unknownEntryIsNeverDeleted() {
        assertEquals(GalleryState.UNKNOWN,
            GalleryTransaction.discardPending({ null }) { error("must not delete") }.state)
    }

    @Test fun missingEntryIsNotDeleted() {
        assertEquals(gone, GalleryTransaction.discardPending({ gone }) { error("must not delete") })
    }

    @Test fun positiveDeleteConfirmsGone() {
        assertEquals(gone, GalleryTransaction.discardPending({ pending }) { 1 })
    }

    @Test fun failedDeleteRetainsUncertainty() {
        assertEquals(GalleryState.UNKNOWN,
            GalleryTransaction.discardPending({ pending }) { error("delete unavailable") }.state)
    }

    @Test fun zeroDeleteWhileStillPendingIsUnknown() {
        assertEquals(GalleryState.UNKNOWN, GalleryTransaction.discardPending({ pending }) { 0 }.state)
    }

    @Test fun zeroDeleteCanObservePublicationWinning() {
        var reads = 0
        assertEquals(published, GalleryTransaction.discardPending({ if (reads++ == 0) pending else published }) { 0 })
    }

    @Test fun zeroDeleteCanConfirmAnotherRemoval() {
        var reads = 0
        assertEquals(gone, GalleryTransaction.discardPending({ if (reads++ == 0) pending else gone }) { 0 })
    }

    @Test fun queryFailureAfterZeroDeleteIsUnknown() {
        var reads = 0
        assertEquals(GalleryState.UNKNOWN,
            GalleryTransaction.discardPending({ if (reads++ == 0) pending else error("query unavailable") }) { 0 }.state)
    }
}
