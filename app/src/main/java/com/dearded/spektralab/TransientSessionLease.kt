package com.dearded.spektralab

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** Serializes candidate replacement and export lifetimes, not resident bytes. */
internal class TransientSessionLease {
    private val mutex = Mutex()

    /** Call outside native dispatchers/locks; finish native cleanup inside [block]. */
    suspend fun <T> withLease(onWaiting: () -> Unit = {}, block: suspend () -> T): T {
        if (!mutex.tryLock()) {
            onWaiting()
            mutex.lock()
        }
        try {
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
