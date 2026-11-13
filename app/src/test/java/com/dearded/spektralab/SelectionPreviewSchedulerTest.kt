package com.dearded.spektralab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real scheduling and cancellation, co-created with GPT (OpenAI). */
class SelectionPreviewSchedulerTest {
    @Test fun cachedCardReturnsWhileAnotherRecipeWaitsForIdle() = runBlocking {
        withTimeout(5000) {
            val scheduler = SelectionPreviewScheduler()
            val waiting = CompletableDeferred<Unit>()
            val pending = launch {
                scheduler.load({ true }, { null as String? }, { waiting.complete(Unit); true }) {
                    error("busy request must not render")
                }
            }
            waiting.await()
            try {
                assertEquals("cached", withTimeout(1000) {
                    scheduler.load({ true }, { "cached" }, { true }) { error("must hit cache") }
                })
            } finally { pending.cancelAndJoin() }
        }
    }

    @Test fun aWaitingRecipeDoesNotBlockAnotherReadyRender() = runBlocking {
        withTimeout(5000) {
            val scheduler = SelectionPreviewScheduler()
            val waiting = CompletableDeferred<Unit>()
            val pending = launch {
                scheduler.load({ true }, { null as String? }, { waiting.complete(Unit); true }) { "wrong" }
            }
            waiting.await()
            try {
                assertEquals("rendered", scheduler.load({ true }, { null as String? }, { false }) { "rendered" })
            } finally { pending.cancelAndJoin() }
        }
    }

    @Test fun cachedCardAlsoBypassesAnUnrelatedRenderAlreadyHoldingTheLock() = runBlocking {
        withTimeout(5000) {
            val scheduler = SelectionPreviewScheduler()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val active = async {
                scheduler.load({ true }, { null as String? }, { false }) {
                    entered.complete(Unit); release.await(); "rendered"
                }
            }
            entered.await()
            try {
                assertEquals("cached", withTimeout(1000) {
                    scheduler.load({ true }, { "cached" }, { false }) { error("must hit cache") }
                })
            } finally { release.complete(Unit) }
            assertEquals("rendered", active.await())
        }
    }

    @Test fun concurrentRequestsForTheSameRecipeRecheckTheCacheAfterLocking() = runBlocking {
        withTimeout(5000) {
            val scheduler = SelectionPreviewScheduler()
            var cache: String? = null
            var calls = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val secondQueued = CompletableDeferred<Unit>()
            var secondReads = 0
            suspend fun request(second: Boolean = false) = scheduler.load({ true }, {
                if (second && ++secondReads == 2) secondQueued.complete(Unit)
                cache
            }, { false }) {
                calls++; entered.complete(Unit); release.await(); "once".also { cache = it }
            }
            val first = async { request() }
            entered.await()
            val second = async { request(second = true) }
            secondQueued.await()
            release.complete(Unit)
            assertEquals("once", first.await())
            assertEquals("once", second.await())
            assertEquals(1, calls)
        }
    }

    @Test fun staleSourceDoesNotReturnCachedPixelsOrRender() = runBlocking {
        val scheduler = SelectionPreviewScheduler()
        assertNull(scheduler.load({ false }, { "old source" }, { false }) { error("stale") })
    }

    @Test fun sourceChangeDuringIdleWaitDiscardsTheRequest() = runBlocking {
        withTimeout(5000) {
            val scheduler = SelectionPreviewScheduler()
            var current = true
            val waiting = CompletableDeferred<Unit>()
            val result = async {
                scheduler.load({ current }, { null as String? }, { waiting.complete(Unit); true }) { error("stale") }
            }
            waiting.await()
            current = false
            assertNull(result.await())
        }
    }

    @Test fun cancelledRenderReleasesTheLockForTheNextRequest() = runBlocking {
        withTimeout(5000) {
            val scheduler = SelectionPreviewScheduler()
            val entered = CompletableDeferred<Unit>()
            val first = launch {
                scheduler.load({ true }, { null as String? }, { false }) {
                    entered.complete(Unit); CompletableDeferred<String>().await()
                }
            }
            entered.await()
            first.cancelAndJoin()
            assertEquals("next", scheduler.load({ true }, { null as String? }, { false }) { "next" })
        }
    }

    @Test fun foregroundWorkStartingDuringLockContentionIsRecheckedBeforeRendering() = runBlocking {
        withTimeout(5000) {
            val scheduler = SelectionPreviewScheduler()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = async {
                scheduler.load({ true }, { null as String? }, { false }) {
                    entered.complete(Unit); release.await(); "first"
                }
            }
            entered.await()
            var busy = false
            var checks = 0
            val queued = CompletableDeferred<Unit>()
            val deferred = CompletableDeferred<Unit>()
            val next = async {
                scheduler.load({ true }, { null as String? }, {
                    checks++
                    if (checks == 1) queued.complete(Unit)
                    if (busy) deferred.complete(Unit)
                    busy
                }) { "next" }
            }
            queued.await()
            busy = true
            release.complete(Unit)
            assertEquals("first", first.await())
            deferred.await()
            assertFalse(next.isCompleted)
            busy = false
            assertEquals("next", next.await())
        }
    }
}
