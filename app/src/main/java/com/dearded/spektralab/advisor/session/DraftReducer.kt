package com.dearded.spektralab.advisor.session

import com.dearded.spektralab.Catalog
import com.dearded.spektralab.Edit
import com.dearded.spektralab.advisor.domain.*
import com.dearded.spektralab.advisor.network.AdvisorTokenUsage

enum class DraftPhase { IDLE, READY, GENERATING, RENDERING, PREVIEWING, COMPARING, PREPARING_EXPORT, RESTORING, COMMITTING, APPLIED, SAVED, FAILED }
enum class AdviceNotice { NO_CHANGE, UNSUPPORTED, QUESTION_LIMIT, CANCELLED, CINEMATIC_PREVIEW_NOT_ANALYZED, CINEMATIC_NO_ADJUSTMENT }

data class AdviceScopePrompt(val id: Long, val text: String, internal val answer: String?)

data class DraftState(
    val phase: DraftPhase = DraftPhase.IDLE,
    val entry: EditSnapshot? = null,
    val draft: AdviceCandidate? = null,
    val pending: AdviceCandidate? = null,
    val ticket: RequestTicket? = null,
    val error: AdviceError? = null,
    val showingEntry: Boolean = false,
    val constraints: AdviceConstraints = AdviceConstraints.all(),
    val question: AdviceResult.Question? = null,
    val notice: AdviceNotice? = null,
    val usage: AdvisorTokenUsage? = null,
    val includeStatistics: Boolean = false,
    val inputRevision: Long = 0,
    val visible: Boolean = phase != DraftPhase.IDLE,
    val strengthPercent: Int? = null,
    val scopePrompt: AdviceScopePrompt? = null,
) {
    val edit: Edit? get() = draft?.edit ?: entry?.edit
    val hasPreview get() = phase == DraftPhase.PREVIEWING && draft != null
    val canApply get() = hasPreview &&
        (draft?.directionalLight == null || AdviceParameter.EXPOSURE in constraints.ranges) &&
        (strengthPercent == null || entry?.let { AdviceProjection.hasEffectiveChange(it.edit, checkNotNull(draft).edit) } == true)
    val canSetStrength get() = hasPreview && strengthPercent != null && question == null && scopePrompt == null &&
        (draft?.directionalLight == null || AdviceParameter.EXPOSURE in constraints.ranges)
}

/** Main-thread event state. Cancellation is optional; publication requires exact identity. */
class DraftReducer {
    var state = DraftState()
        private set
    private var generation = 0L
    private var draftRevision = 0L
    private data class StrengthAnchor(val base: Edit, val recommendation: Edit, val fields: Set<AdviceParameter>?)
    private var strengthAnchor: StrengthAnchor? = null
    private var manualOverrides = emptySet<AdviceParameter>()
    internal var strengthCinematicBase: EditSnapshot? = null
        private set

    fun begin(snapshot: EditSnapshot) {
        generation++
        draftRevision = 0
        strengthAnchor = null
        manualOverrides = emptySet()
        strengthCinematicBase = null
        state = DraftState(phase = DraftPhase.READY, entry = snapshot)
    }

    fun request(context: RequestTicket? = null, answering: Boolean = false): EditSnapshot {
        check(state.phase in setOf(DraftPhase.READY, DraftPhase.PREVIEWING, DraftPhase.GENERATING, DraftPhase.RENDERING))
        val entry = checkNotNull(state.entry)
        val ticket = (context ?: entry.ticket).copy(requestId = ++generation, draftRevision = draftRevision,
            constraintsRevision = state.constraints.revision, inputRevision = state.inputRevision)
        val snapshot = EditSnapshot(checkNotNull(state.edit), ticket)
        state = state.copy(phase = DraftPhase.GENERATING, ticket = ticket, pending = null, error = null,
            question = state.question.takeIf { answering }, scopePrompt = null, notice = null, usage = null)
        return snapshot
    }

    fun current(ticket: RequestTicket) = state.ticket == ticket &&
        state.phase in setOf(DraftPhase.GENERATING, DraftPhase.RENDERING, DraftPhase.PREVIEWING)

    fun received(candidate: AdviceCandidate, usage: AdvisorTokenUsage? = null): Boolean {
        if (!current(candidate.ticket) || state.phase != DraftPhase.GENERATING) return false
        state = state.copy(phase = DraftPhase.RENDERING, pending = candidate, question = null, usage = usage)
        return true
    }

    fun settled(ticket: RequestTicket, question: AdviceResult.Question?, notice: AdviceNotice?, usage: AdvisorTokenUsage?): Boolean {
        if (!current(ticket) || state.phase != DraftPhase.GENERATING) return false
        generation++
        state = state.copy(phase = if (state.draft == null) DraftPhase.READY else DraftPhase.PREVIEWING,
            ticket = null, pending = null, question = question, notice = notice, usage = usage, error = null)
        return true
    }

    internal fun askScope(prompt: AdviceScopePrompt) {
        state = state.copy(scopePrompt = prompt, question = state.question.takeIf { prompt.answer != null },
            notice = null, error = null)
    }

    internal fun declineScope() {
        state = state.copy(scopePrompt = null, question = null, notice = AdviceNotice.UNSUPPORTED, error = null)
    }

    fun clearQuestion() { state = state.copy(question = null, scopePrompt = null, notice = null) }
    fun constraints(value: AdviceConstraints) {
        state = state.copy(constraints = value, question = null, scopePrompt = null, notice = null)
    }
    fun configurationRequired() { state = state.copy(error = AdviceError.CONFIGURATION_REQUIRED) }
    fun statistics(value: Boolean) {
        state = state.copy(includeStatistics = value, inputRevision = state.inputRevision + 1,
            question = null, scopePrompt = null, notice = null)
    }

    internal fun stageStrength(percent: Int, catalog: Catalog): AdviceCandidate? {
        if (!state.canSetStrength || state.strengthPercent == percent) return null
        val anchor = checkNotNull(strengthAnchor)
        val previous = checkNotNull(state.draft)
        val entry = checkNotNull(state.entry)
        val ticket = previous.ticket.copy(requestId = ++generation, draftRevision = draftRevision,
            constraintsRevision = state.constraints.revision, inputRevision = state.inputRevision)
        val candidate = if (previous.directionalLight != null) {
            AdviceProjection.rescaleDirectional(entry, previous, percent, ticket)
        } else {
            val allowed = state.constraints.ranges.filterKeys { anchor.fields == null || it in anchor.fields }
            val changes = AdviceProjection.strengthChanges(anchor.base, anchor.recommendation, previous.edit, percent,
                allowed, manualOverrides)
            if (changes.isEmpty()) {
                state = state.copy(strengthPercent = percent, error = null)
                return null
            }
            AdviceProjection.rescale(previous, changes, state.constraints, catalog, ticket)
        }
        state = state.copy(phase = DraftPhase.RENDERING, ticket = ticket, pending = candidate, error = null, notice = null)
        return candidate
    }

    internal fun strengthRejected(error: AdviceError) { state = state.copy(error = error) }

    internal fun stageCinematicRetry(original: AdviceCandidate, base: EditSnapshot, supplied: Set<AdviceParameter>,
        catalog: Catalog): AdviceCandidate? {
        if (supplied.any { it in manualOverrides &&
                it.read(base.edit.look) != it.read(original.edit.look) }) return null
        val reduced = if (original.directionalLight != null) {
            AdviceProjection.rescaleDirectional(base, original, CINEMATIC_RETRY_PERCENT, original.ticket)
        } else {
            val changes = AdviceProjection.strengthChanges(base.edit, original.edit, original.edit, CINEMATIC_RETRY_PERCENT,
                state.constraints.ranges.filterKeys { it in supplied }, manualOverrides)
            if (changes.isEmpty()) return null
            AdviceProjection.rescale(original, changes, state.constraints, catalog, original.ticket)
        }
        if (!AdviceProjection.hasEffectiveChange(base.edit, reduced.edit)) return null
        state = state.copy(pending = reduced)
        return reduced
    }

    fun rendered(ticket: RequestTicket): Boolean = publishRendered(ticket)

    internal fun publishRendered(ticket: RequestTicket, suppliedFields: Set<AdviceParameter>? = null,
        cinematicBase: EditSnapshot? = null, strengthPercent: Int? = null,
        retryOf: AdviceCandidate? = null): Boolean {
        if (!current(ticket) || state.phase != DraftPhase.RENDERING) return false
        val candidate = state.pending ?: return false
        draftRevision++
        val netChange = candidate.edit != state.entry?.edit
        // A scaled draft at the entry edit is still reversible, but cannot be applied.
        val retainDraft = netChange || strengthPercent != null
        if (strengthPercent == null) {
            val supplied = suppliedFields ?: candidate.changes.mapNotNull { AdviceParameter.of(it.parameter) }.toSet()
            when {
                !netChange -> {
                    strengthAnchor = null
                    strengthCinematicBase = null
                    manualOverrides = emptySet()
                }
                candidate.manuallyAdjusted -> manualOverrides = manualOverrides + supplied
                else -> {
                    strengthAnchor = if (retryOf == null) StrengthAnchor(checkNotNull(state.entry).edit, candidate.edit, null)
                        else StrengthAnchor(checkNotNull(cinematicBase).edit, retryOf.edit, supplied)
                    strengthCinematicBase = cinematicBase
                    if (retryOf == null) manualOverrides = manualOverrides - supplied
                }
            }
        }
        state = state.copy(phase = if (retainDraft) DraftPhase.PREVIEWING else DraftPhase.READY,
            draft = candidate.takeIf { retainDraft }, pending = null, error = null, showingEntry = false,
            notice = if (netChange) null else AdviceNotice.NO_CHANGE,
            strengthPercent = strengthPercent ?: if (strengthAnchor == null) null else
                if (candidate.manuallyAdjusted) state.strengthPercent else if (retryOf == null) 100 else CINEMATIC_RETRY_PERCENT)
        return true
    }

    fun failed(ticket: RequestTicket, error: AdviceError?, retainQuestion: Boolean = false, notice: AdviceNotice? = null): Boolean {
        if (!current(ticket)) return false
        generation++
        state = state.copy(phase = if (state.draft == null) DraftPhase.READY else DraftPhase.PREVIEWING,
            pending = null, ticket = null, error = error, notice = notice, showingEntry = state.draft == null,
            question = state.question.takeIf { retainQuestion })
        return true
    }

    fun restoring() {
        generation++
        state = state.copy(phase = DraftPhase.RESTORING, pending = null, ticket = null, scopePrompt = null)
    }

    fun viewing(export: Boolean = false, retainQuestion: Boolean = false) {
        generation++
        state = state.copy(phase = if (export) DraftPhase.PREPARING_EXPORT else DraftPhase.COMPARING,
            pending = null, ticket = null, error = null,
            scopePrompt = state.scopePrompt.takeUnless { export },
            question = state.question.takeUnless { state.phase == DraftPhase.GENERATING && !retainQuestion })
    }

    fun viewed(showEntry: Boolean, success: Boolean, cancelled: Boolean = false) {
        if (state.phase !in setOf(DraftPhase.COMPARING, DraftPhase.PREPARING_EXPORT)) return
        state = state.copy(phase = if (success) {
            if (state.draft == null) DraftPhase.READY else DraftPhase.PREVIEWING
        } else DraftPhase.FAILED, showingEntry = if (success) showEntry else state.showingEntry,
            question = state.question.takeIf { success },
            scopePrompt = state.scopePrompt.takeIf { success },
            notice = if (success && cancelled) AdviceNotice.CANCELLED else state.notice,
            usage = state.usage.takeUnless { cancelled },
            error = if (success) null else AdviceError.RENDER_FAILED)
    }

    fun restored(success: Boolean, keepSession: Boolean = false) {
        if (state.phase != DraftPhase.RESTORING) return
        state = if (!success) state.copy(phase = DraftPhase.FAILED, error = AdviceError.RENDER_FAILED)
            else if (!keepSession) DraftState()
            else {
                val previous = state
                begin(checkNotNull(previous.entry))
                state.copy(constraints = previous.constraints, includeStatistics = previous.includeStatistics,
                    inputRevision = previous.inputRevision, showingEntry = true)
            }
    }

    fun committing(): AdviceCandidate {
        check(state.canApply)
        generation++
        state = state.copy(phase = DraftPhase.COMMITTING, ticket = null, scopePrompt = null, question = null)
        return checkNotNull(state.draft)
    }

    internal fun canLearnAdoption(): Boolean = state.strengthPercent == 100 && manualOverrides.isEmpty() &&
        state.draft?.let { it.origin is AdviceOrigin.Model && !it.manuallyAdjusted && it.directionalLight == null &&
            it.edit == strengthAnchor?.recommendation } == true

    fun applied() {
        check(state.phase == DraftPhase.COMMITTING)
        state = state.copy(phase = DraftPhase.APPLIED, showingEntry = false)
    }

    internal fun showCommit(visible: Boolean) {
        check(state.phase == DraftPhase.COMMITTING || state.phase == DraftPhase.APPLIED)
        state = state.copy(visible = visible)
    }

    fun saved(success: Boolean) {
        if (state.phase != DraftPhase.APPLIED) return
        state = state.copy(phase = if (success) DraftPhase.SAVED else DraftPhase.APPLIED,
            error = if (success) null else AdviceError.SAVE_FAILED)
    }

    fun retryingSave() {
        check(state.phase == DraftPhase.APPLIED && state.error == AdviceError.SAVE_FAILED)
        state = state.copy(error = null)
    }

    fun invalidate() {
        generation++
        state = DraftState()
    }
}

private const val CINEMATIC_RETRY_PERCENT = 50
