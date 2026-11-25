package com.dearded.spektralab.advisor.editor

import com.dearded.spektralab.Catalog
import com.dearded.spektralab.Edit
import com.dearded.spektralab.advisor.domain.*
import kotlinx.coroutines.Deferred

data class CommittedDraft(val applied: AppliedReceipt, val target: SaveTarget, val saved: Deferred<Unit>,
    internal val recordAdoption: ((SaveReceipt) -> Unit)? = null)

enum class AdvisorRenderResult { SHOWN, HIGHLIGHTS_CLIPPED, NO_IMPROVEMENT, FAILED }

interface AdvisorEditorPort {
    fun capture(): EditSnapshot
    fun catalog(): Catalog
    fun current(snapshot: EditSnapshot): Boolean
    suspend fun render(edit: Edit, entry: EditSnapshot): Boolean
    suspend fun renderCandidate(candidate: AdviceCandidate, entry: EditSnapshot): Boolean = render(candidate.edit, entry)
    suspend fun renderCinematicCandidate(candidate: AdviceCandidate, entry: EditSnapshot, base: EditSnapshot): AdvisorRenderResult =
        AdvisorRenderResult.FAILED
    suspend fun renderReducedCinematicCandidate(candidate: AdviceCandidate, entry: EditSnapshot, base: EditSnapshot): AdvisorRenderResult =
        AdvisorRenderResult.FAILED
    suspend fun restore(entry: EditSnapshot): Boolean
    /** Called after a current candidate has been published to the preview. */
    fun offered(candidate: AdviceCandidate, entry: EditSnapshot) { }
    /** No matching current-draft sample is represented as unavailable, never as a previous sample. */
    suspend fun statistics(snapshot: EditSnapshot, entry: EditSnapshot): AdviceStatistics? = null
    /** Called only after restoration, or when a real editor command supersedes this session. */
    fun endPreview()
    suspend fun commit(candidate: AdviceCandidate, entry: EditSnapshot, learnAdoption: Boolean): CommittedDraft
    /** Reuses the existing writer only while this exact commit is still the formal edit. */
    fun retrySave(applied: AppliedReceipt): Deferred<Unit>
}
