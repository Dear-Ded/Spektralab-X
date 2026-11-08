package com.dearded.spektralab

import java.util.concurrent.Executor

/** Nonblocking, rate-limited sampling; NaN means no recent usable reading. */
internal class ThermalHeadroomSampler(
    private val clock: () -> Long,
    private val executor: Executor,
    private val query: () -> Float,
    private val intervalMs: Long = 10_000L,
    private val maxAgeMs: Long = 30_000L,
) {
    init {
        require(intervalMs > 0 && maxAgeMs >= intervalMs)
    }

    private val lock = Any()
    private var inFlight = false
    private var lastAttempt: Long? = null
    private var sampledAt: Long? = null
    private var value = Float.NaN

    fun headroom(): Float {
        val enqueue: Boolean
        val result: Float
        synchronized(lock) {
            val now = clock()
            val previous = lastAttempt
            enqueue = !inFlight && (previous == null || now - previous >= intervalMs)
            if (enqueue) inFlight = true
            val sampled = sampledAt
            result = if (sampled != null && now - sampled in 0 until maxAgeMs) value else Float.NaN
        }
        if (enqueue) {
            try {
                executor.execute {
                    val reading = runCatching(query).getOrDefault(Float.NaN)
                    synchronized(lock) {
                        val now = clock()
                        lastAttempt = now
                        if (reading.isFinite() && reading >= 0f) {
                            value = reading
                            sampledAt = now
                        }
                        inFlight = false
                    }
                }
            } catch (_: RuntimeException) {
                synchronized(lock) {
                    lastAttempt = clock()
                    inFlight = false
                }
            }
        }
        return result
    }
}
