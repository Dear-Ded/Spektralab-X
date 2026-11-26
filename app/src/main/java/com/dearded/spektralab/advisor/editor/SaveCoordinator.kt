package com.dearded.spektralab.advisor.editor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred

enum class SaveTarget { SIDECAR, EDITOR_STATE }
data class AppliedReceipt(val commitId: String, val photoSessionId: Long, val revision: Long)
data class SaveReceipt(val commit: AppliedReceipt, val target: SaveTarget, val success: Boolean)

/** Observes the existing serial writer's exact snapshot, never starts a competing file write. */
class SaveCoordinator {
    suspend fun awaitSaved(commit: AppliedReceipt, target: SaveTarget, write: Deferred<Unit>): SaveReceipt {
        return try {
            write.await()
            SaveReceipt(commit, target, true)
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { SaveReceipt(commit, target, false) }
    }
}
