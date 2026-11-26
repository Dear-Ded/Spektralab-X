package com.dearded.spektralab

import com.dearded.spektralab.advisor.editor.AppliedReceipt
import com.dearded.spektralab.advisor.editor.SaveCoordinator
import com.dearded.spektralab.advisor.editor.SaveReceipt
import com.dearded.spektralab.advisor.editor.SaveTarget
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdvisorSaveReceiptTest {
    private val commit = AppliedReceipt("one", 1, 3)

    @Test fun saveAcknowledgementWaitsForTheExactCommitWrite() = runBlocking {
        val write = CompletableDeferred<Unit>()
        val receipt = async(start = CoroutineStart.UNDISPATCHED) {
            SaveCoordinator().awaitSaved(commit, SaveTarget.SIDECAR, write)
        }
        assertFalse(receipt.isCompleted)
        write.complete(Unit)
        assertEquals(SaveReceipt(commit, SaveTarget.SIDECAR, true), receipt.await())
    }

    @Test fun writerFailureNeverAcknowledgesASave() = runBlocking {
        val write = CompletableDeferred<Unit>()
        write.completeExceptionally(IOException("synthetic full disk"))
        assertEquals(SaveReceipt(commit, SaveTarget.EDITOR_STATE, false),
            SaveCoordinator().awaitSaved(commit, SaveTarget.EDITOR_STATE, write))
    }
}
