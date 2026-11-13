package com.dearded.spektralab

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Idle waits do not own the render lock. Co-created with GPT (OpenAI). */
internal class SelectionPreviewScheduler {
    private val renderLock = Mutex()

    suspend fun <T : Any> load(
        current: () -> Boolean,
        cached: () -> T?,
        shouldWait: () -> Boolean,
        render: suspend () -> T?,
    ): T? {
        if (!current()) return null
        cached()?.let { return it }
        delay(120)
        while (current()) {
            cached()?.let { return it }
            if (shouldWait()) { delay(150); continue }
            var retry = false
            val result = renderLock.withLock {
                if (!current()) return@withLock null
                cached()?.let { return@withLock it }
                // A foreground render may have started while this request was queued.
                if (shouldWait()) { retry = true; null } else render()
            }
            if (!retry) return result
        }
        return null
    }
}
