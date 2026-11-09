package com.dearded.spektralab

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Owns an uncommitted native handle, including cancellation on a dispatcher return. */
internal class SessionCandidate(
    private val dispatcher: CoroutineDispatcher,
    private val release: (Long) -> Unit,
) {
    private var handle = 0L

    /** Register in the same engine-thread call that creates the handle. */
    fun own(value: Long) {
        check(value != 0L && handle == 0L)
        handle = value
    }

    /** Install the prepared session and UI together; release the retired handle afterwards. */
    suspend fun commit(install: suspend (Long) -> Unit) {
        check(handle != 0L)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            install(handle)
            handle = 0L
        }
    }

    suspend fun close() {
        val abandoned = handle
        handle = 0L
        if (abandoned != 0L) withContext(NonCancellable) {
            withContext(dispatcher) { release(abandoned) }
        }
    }
}

internal suspend fun <T> withSessionCandidate(
    dispatcher: CoroutineDispatcher,
    release: (Long) -> Unit,
    block: suspend (SessionCandidate) -> T,
): T {
    val candidate = SessionCandidate(dispatcher, release)
    try {
        return block(candidate)
    } finally {
        candidate.close()
    }
}
