package com.dearded.spektralab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** Accepted edits only, co-created with GPT (OpenAI). Ownership never depends on a UI collector. */
internal data class FrameEditSave(val id: String, val edit: Edit, val wbK: Double, val wbTint: Double)

@OptIn(ExperimentalCoroutinesApi::class)
internal class FrameEditAutosave(
    scope: CoroutineScope,
    private val onFailure: (Exception) -> Unit = {},
    private val write: (FrameEditSave) -> Unit,
) {
    private sealed interface Request {
        data class Save(val value: FrameEditSave) : Request
        data class Flush(val done: CompletableDeferred<Unit>? = null) : Request
    }

    private val requests = Channel<Request>(Channel.UNLIMITED)
    // The editor calls opened/changed on Main, in the same transaction as its accepted edit.
    private var accepted: FrameEditSave? = null
    val frameId: String? get() = accepted?.id

    private val worker = scope.launch(Dispatchers.IO) {
        val pending = LinkedHashMap<String, FrameEditSave>()
        var reportedFailure = false
        fun persist() {
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                write(iterator.next().value)
                iterator.remove()
            }
            reportedFailure = false
        }
        while (true) {
            val request = if (pending.isEmpty()) requests.receive() else select<Request?> {
                requests.onReceive { it }
                onTimeout(600) { null }
            }
            try {
                when (request) {
                    is Request.Save -> pending[request.value.id] = request.value
                    is Request.Flush -> { persist(); request.done?.complete(Unit) }
                    null -> persist()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!reportedFailure) { reportedFailure = true; onFailure(e) }
                (request as? Request.Flush)?.done?.completeExceptionally(e)
            }
        }
    }

    /** A new photo starts its baseline immediately; a successful re-decode saves WB/development changes. */
    fun opened(id: String?, edit: Edit, wbK: Double, wbTint: Double, reopening: Boolean) {
        val next = id?.let { FrameEditSave(it, edit, wbK, wbTint) }
        if (reopening && next != null && accepted?.id == id && next != accepted)
            requests.trySend(Request.Save(next))
        accepted = next
    }

    fun changed(edit: Edit) {
        val previous = accepted ?: return
        val next = previous.copy(edit = edit)
        if (next == previous) return
        accepted = next
        requests.trySend(Request.Save(next))
    }

    fun requestFlush() { requests.trySend(Request.Flush()) }

    /** A read of the same sidecar must wait for every previously accepted write. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        requests.send(Request.Flush(done))
        done.await()
    }

    suspend fun close() {
        try { flush() }
        finally { worker.cancelAndJoin(); requests.close() }
    }
}
