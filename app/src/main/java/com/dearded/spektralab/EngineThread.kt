package com.dearded.spektralab

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** The editor's engine thread, and the only one for engine-level calls; exports use [ExportThread]. */
object EngineThread {
    val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        // RAW decode and the engine's host-side setup want a large stack.
        Thread(null, r, "spektralab-engine", 64L * 1024 * 1024)
    }.asCoroutineDispatcher()
}
