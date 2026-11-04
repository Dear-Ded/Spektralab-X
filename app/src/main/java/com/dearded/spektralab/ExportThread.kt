package com.dearded.spektralab

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The export's engine thread: ProcessingQueue opens, renders and releases its
 * job's session here, so a slider drag on [EngineThread] waits for at most one
 * GPU submit instead of a whole full-size render. Safe because each session
 * lives on one thread, the engine locks what sessions share (patch 0005 for
 * the print-LUT caches), and the error strings are per thread. Engine-level
 * calls (create, destroy, trimMemory, warmUp, recover) stay on EngineThread.
 * Background priority, so the CPU-side pack yields to the editor's.
 */
object ExportThread {
    val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        // The same large stack as EngineThread: RAW decode runs here too.
        Thread(null, {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "spektralab-export", 64L * 1024 * 1024)
    }.asCoroutineDispatcher()
}
