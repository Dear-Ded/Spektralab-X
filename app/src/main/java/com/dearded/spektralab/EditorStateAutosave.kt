package com.dearded.spektralab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** One accepted source/edit/WB transaction, co-created with GPT (OpenAI). */
internal data class EditorStateSave(
    val uri: String,
    val frameId: String?,
    val name: String,
    val raw: Boolean,
    val edit: Edit,
    val wbK: Double,
    val wbTint: Double,
) {
    fun toJson(): String {
        val frame = Frame("", uri, name, 0, 0, "", raw)
        return Sidecar(edit, wbK, wbTint, System.currentTimeMillis()).toJson(frame)
            .put("uri", uri).put("frameId", frameId).toString()
    }
}

/** A process-owned serial writer: a flush bypasses debounce and cannot be overtaken by an old save. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class EditorStateAutosave(
    scope: CoroutineScope,
    private val onFailure: (Exception) -> Unit = {},
    private val write: (EditorStateSave) -> Unit,
) {
    private sealed interface Request {
        data class Save(val value: EditorStateSave) : Request
        data class Flush(val done: CompletableDeferred<Unit>? = null) : Request
    }

    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val worker = scope.launch(Dispatchers.IO) {
        var pending: EditorStateSave? = null
        var reportedFailure = false
        while (true) {
            val request = if (pending == null) requests.receive() else select<Request?> {
                requests.onReceive { it }
                onTimeout(400) { null }
            }
            if (request is Request.Save) { pending = request.value; continue }
            try {
                pending?.let(write)
                pending = null
                reportedFailure = false
                (request as? Request.Flush)?.done?.complete(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!reportedFailure) { reportedFailure = true; onFailure(e) }
                (request as? Request.Flush)?.done?.completeExceptionally(e)
            }
        }
    }

    fun changed(snapshot: EditorStateSave) { requests.trySend(Request.Save(snapshot)) }
    fun requestFlush() { requests.trySend(Request.Flush()) }

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
