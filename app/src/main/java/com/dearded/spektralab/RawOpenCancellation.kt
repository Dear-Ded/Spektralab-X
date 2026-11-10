package com.dearded.spektralab

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Signals a blocking RAW open without waiting for its native dispatcher to return. */
internal suspend fun <T> withRawOpenCancellation(
    create: () -> Long = NativeEngine::createRawOpenCancellation,
    cancel: (Long) -> Unit = NativeEngine::cancelRawOpen,
    release: (Long) -> Unit = NativeEngine::releaseRawOpenCancellation,
    block: suspend (Long) -> T,
): T = coroutineScope {
    val token = create()
    check(token != 0L) { "Cannot allocate RAW cancellation token" }
    var watcher: kotlinx.coroutines.Job? = null
    try {
        // Unconfined cancellation runs even while EngineThread/ExportThread is
        // blocked in native decode. The callback only writes an atomic flag.
        watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                cancel(token)
            }
        }
        block(token)
    } finally {
        withContext(NonCancellable) {
            try {
                watcher?.cancelAndJoin()
            } finally {
                // The native call and the only concurrent flag writer are done.
                release(token)
            }
        }
    }
}
