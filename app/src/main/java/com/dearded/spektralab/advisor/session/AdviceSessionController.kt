package com.dearded.spektralab.advisor.session

import com.dearded.spektralab.advisor.domain.*
import com.dearded.spektralab.advisor.editor.*
import com.dearded.spektralab.advisor.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One owner on Main. Local and network proposals share projection, rendering and commit boundaries. */
class AdviceSessionController(private val scope: CoroutineScope, private val editor: AdvisorEditorPort) {
    private enum class GoalMode { CUSTOM, CINEMATIC, DIRECTIONAL }
    private val reducer = DraftReducer()
    private val _state = MutableStateFlow(reducer.state)
    val state: StateFlow<DraftState> = _state
    private var task: Job? = null
    private var committedDraft: CommittedDraft? = null
    private val constraints get() = state.value.constraints
    private var connection: AdviceConnection? = null
    private val questions = LocalQuestionPolicy()
    private var goal: String? = null
    private var goalMode = GoalMode.CUSTOM
    private var operation = 0L
    private data class CinematicSample(val snapshot: EditSnapshot, val statistics: AdviceStatistics) {
        fun forAnswer(next: EditSnapshot): AdviceStatistics? = statistics.takeIf {
            next.copy(ticket = next.ticket.copy(requestId = snapshot.ticket.requestId)) == snapshot
        }
    }
    private var pendingCinematicSample: CinematicSample? = null
    private fun publish() { _state.value = reducer.state }

    fun begin() {
        if (state.value.phase in setOf(DraftPhase.COMMITTING, DraftPhase.APPLIED)) {
            reducer.showCommit(true)
            publish()
            return
        }
        if (state.value.phase !in setOf(DraftPhase.IDLE, DraftPhase.SAVED)) return
        task?.cancel()
        task = null
        committedDraft = null
        pendingCinematicSample = null
        questions.reset()
        goal = null
        operation++
        reducer.begin(editor.capture())
        publish()
    }

    /** Renders an explicit local parameter proposal. It never makes a network request. */
    fun propose(changes: List<ParameterChange>) {
        val frozen = changes.toList()
        questions.reset()
        goal = null
        submit { _, _, _ -> AdvisorReply(AdviceResult.Proposal(frozen), AdvisorTokenUsage()) }
    }

    internal fun tryLocalPreset(preset: LocalAdvicePreset) {
        if (state.value.phase !in setOf(DraftPhase.READY, DraftPhase.PREVIEWING)) return
        questions.reset()
        goal = null
        submit { snapshot, allowed, _ -> AdvisorReply(preset.suggest(snapshot.edit.look, allowed), AdvisorTokenUsage()) }
    }

    /** User correction keeps the original suggestion, rather than manufacturing a new AI result. */
    fun adjust(changes: List<ParameterChange>) {
        if (state.value.phase !in setOf(DraftPhase.READY, DraftPhase.PREVIEWING)) return
        val frozen = changes.toList()
        val previous = state.value.draft
        val proposal = previous?.let { if (it.manuallyAdjusted) it.correctionOf else it }
        questions.reset(); goal = null
        submit(origin = if (proposal == null) AdviceOrigin.Manual else proposal.origin,
            manuallyAdjusted = true, correctionOf = proposal) { _, _, _ ->
            AdvisorReply(AdviceResult.Proposal(frozen), AdvisorTokenUsage())
        }
    }

    /** Adjusts the last rendered recommendation locally. Busy sessions do not accept slider events. */
    fun setStrength(percent: Int) {
        require(percent in 0..100)
        val current = state.value
        if (!current.canSetStrength || current.strengthPercent == percent) return
        val entry = checkNotNull(current.entry)
        if (!editor.current(entry)) { invalidate(); return }
        val candidate = try { reducer.stageStrength(percent, editor.catalog()) }
            catch (e: AdviceFailure) { reducer.strengthRejected(e.code); publish(); return }
        if (candidate == null) { publish(); return }
        val previous = task
        val cinematicBase = reducer.strengthCinematicBase
        publish()
        task = scope.launch {
            val ticket = candidate.ticket
            try {
                previous?.join()
                if (!editor.current(entry) || !reducer.current(ticket)) return@launch
                renderPending(candidate, entry, cinematicBase, strengthPercent = percent)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (reducer.current(ticket) && editor.current(entry)) {
                    recoverRejectedRender(entry, ticket, failureCode(e, network = false))
                }
            } finally { publish() }
        }
    }

    fun configure(value: AdviceConnection?) {
        if (connection?.identity == value?.identity) return
        connection = value
        questions.reset(); goal = null
        pendingCinematicSample = null
        reducer.clearQuestion()
        cancelPendingPreview()
        publish()
    }

    /** Locks apply to the currently accepted draft values, never to an in-flight candidate. */
    fun allowParameters(parameters: Set<AdviceParameter>) {
        if (state.value.phase !in setOf(DraftPhase.READY, DraftPhase.PREVIEWING, DraftPhase.GENERATING, DraftPhase.RENDERING)) return
        val next = AdviceConstraints(parameters.associateWith { ParameterRange(it.minimum, it.maximum) }, constraints.revision + 1)
        cancelPendingPreview()
        questions.reset(); goal = null
        pendingCinematicSample = null
        reducer.constraints(next)
        publish()
    }

    /** Changing request contents invalidates the pending operation before a new send. */
    fun includeStatistics(value: Boolean) {
        if (value == state.value.includeStatistics || state.value.phase !in setOf(DraftPhase.READY,
                DraftPhase.PREVIEWING, DraftPhase.GENERATING, DraftPhase.RENDERING)) return
        cancelPendingPreview()
        questions.reset(); goal = null
        pendingCinematicSample = null
        reducer.statistics(value)
        publish()
    }

    /** A fresh explicit operation. Duplicate clicks during generation are single-flight. */
    fun generate(text: String) = generate(text, GoalMode.CUSTOM)

    fun generateCinematicRecommendation() = generate(CINEMATIC_RECOMMENDATION_GOAL, GoalMode.CINEMATIC)

    /** A separate recommendation: it never stacks with an uncommitted scalar draft. */
    fun generateDirectionalLightRecommendation() {
        if (state.value.phase != DraftPhase.READY || !state.value.includeStatistics) return
        generate("Strengthen the observed single bright-to-dark axis across the whole photo without changing its content.",
            GoalMode.DIRECTIONAL)
    }

    private fun generate(text: String, mode: GoalMode) {
        if (state.value.phase !in setOf(DraftPhase.READY, DraftPhase.PREVIEWING)) return
        require(text.isNotBlank() && text.codePointCount(0, text.length) <= 2000)
        val selected = connection ?: run { reducer.configurationRequired(); publish(); return }
        questions.reset(); goal = text
        goalMode = mode
        send(selected, text, null, mode)
    }

    fun answer(text: String) {
        if (state.value.phase !in setOf(DraftPhase.READY, DraftPhase.PREVIEWING) || state.value.question == null) return
        val selected = connection ?: return
        val currentGoal = goal ?: return
        val clarification = questions.answer(text)
        send(selected, currentGoal, clarification, goalMode)
    }

    private suspend fun sampleStatistics(snapshot: EditSnapshot, entry: EditSnapshot): AdviceStatistics =
        (try { editor.statistics(snapshot, entry) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }) ?: AdviceStatistics(snapshot.ticket.formalRevision)

    private fun send(selected: AdviceConnection, text: String, clarification: TaskClarification?, mode: GoalMode) {
        val includeStatistics = state.value.includeStatistics
        val entry = state.value.entry ?: return
        submit(selected.identity, selected.origin,
            answer = clarification, cinematic = mode == GoalMode.CINEMATIC, directional = mode == GoalMode.DIRECTIONAL) {
                snapshot, permissions, measured ->
            val statistics = measured ?: if (includeStatistics) sampleStatistics(snapshot, entry) else null
            currentCoroutineContext().ensureActive()
            if (!editor.current(entry) || !reducer.current(snapshot.ticket))
                throw AdviceFailure(AdviceError.STALE_RESULT)
            selected.generate(AdviceRequest.from(snapshot, permissions, text, statistics,
                brightSide = if (mode == GoalMode.DIRECTIONAL) checkNotNull(measured?.measuredBrightSide) else null), clarification)
        }
    }

    private fun submit(identity: AdviceConnectionIdentity? = null, origin: AdviceOrigin? = AdviceOrigin.Local,
        manuallyAdjusted: Boolean = false, correctionOf: AdviceCandidate? = null,
        answer: TaskClarification? = null, cinematic: Boolean = false, directional: Boolean = false,
        fetch: suspend (EditSnapshot, AdviceConstraints, AdviceStatistics?) -> AdvisorReply) {
        if (state.value.phase !in setOf(DraftPhase.READY, DraftPhase.PREVIEWING, DraftPhase.GENERATING, DraftPhase.RENDERING)) return
        val entry = state.value.entry ?: return
        if (!editor.current(entry)) { invalidate(); return }
        val previous = task
        if (state.value.draft?.directionalLight != null) return
        val previousSample = pendingCinematicSample.takeIf { answer != null }
        if (answer == null) pendingCinematicSample = null
        previous?.cancel()
        val frozenConstraints = constraints
        val includeStatistics = state.value.includeStatistics
        val snapshot = reducer.request(identity?.attach(entry.ticket), answering = answer != null)
        publish()
        task = scope.launch {
            try {
                previous?.join()
                if (!editor.current(entry) || !reducer.current(snapshot.ticket)) return@launch
                if (directional) {
                    if (!AdviceProjection.canPlaceDirectionalLight(snapshot.edit, frozenConstraints)) {
                        reducer.settled(snapshot.ticket, null, AdviceNotice.CINEMATIC_NO_ADJUSTMENT, null)
                        return@launch
                    }
                    val measured = sampleStatistics(snapshot, entry)
                    val side = measured.measuredBrightSide
                    if (side == null) {
                        reducer.settled(snapshot.ticket, null, if (measured.available) AdviceNotice.CINEMATIC_NO_ADJUSTMENT
                            else AdviceNotice.CINEMATIC_PREVIEW_NOT_ANALYZED, null)
                        return@launch
                    }
                    val reply = fetch(snapshot, AdviceConstraints(emptyMap(), frozenConstraints.revision), measured)
                    currentCoroutineContext().ensureActive()
                    if (!editor.current(entry) || !reducer.current(snapshot.ticket)) return@launch
                    when (val result = reply.result) {
                        is AdviceResult.Proposal -> {
                            val light = result.directionalLight ?: throw AdviceFailure(AdviceError.SCHEMA_MISMATCH)
                            val candidate = AdviceProjection.directionalCandidate(snapshot, light, side, origin)
                            if (!reducer.received(candidate, reply.usage)) return@launch
                            publish()
                            renderPending(candidate, entry, entry)
                        }
                        AdviceResult.NoChange -> reducer.settled(snapshot.ticket, null, AdviceNotice.NO_CHANGE, reply.usage)
                        AdviceResult.Unsupported -> reducer.settled(snapshot.ticket, null, AdviceNotice.UNSUPPORTED, reply.usage)
                        is AdviceResult.Question -> throw AdviceFailure(AdviceError.SCHEMA_MISMATCH)
                    }
                    return@launch
                }
                // A/B comparison can change the visible frame without changing the question's draft.
                val statistics = if (cinematic && includeStatistics)
                    previousSample?.forAnswer(snapshot) ?: sampleStatistics(snapshot, entry)
                else null
                val permissions = if (cinematic) cinematicConstraints(snapshot, frozenConstraints, statistics?.values) else frozenConstraints
                val reply = if (cinematic && permissions.ranges.isEmpty()) null
                    else fetch(snapshot, permissions, statistics)
                currentCoroutineContext().ensureActive()
                if (!editor.current(entry) || !reducer.current(snapshot.ticket)) return@launch
                if (reply == null) {
                    pendingCinematicSample = null
                    val notice = if (statistics?.values == null) AdviceNotice.CINEMATIC_PREVIEW_NOT_ANALYZED
                        else AdviceNotice.CINEMATIC_NO_ADJUSTMENT
                    reducer.settled(snapshot.ticket, null, notice, null)
                    return@launch
                }
                val result = reply.result
                if (result !is AdviceResult.Proposal) {
                    val question = (result as? AdviceResult.Question)?.takeIf { questions.offer(it) }
                    val notice = when (result) {
                        AdviceResult.NoChange -> AdviceNotice.NO_CHANGE
                        AdviceResult.Unsupported -> AdviceNotice.UNSUPPORTED
                        is AdviceResult.Question -> if (question == null) AdviceNotice.QUESTION_LIMIT else null
                        else -> null
                    }
                    if (reducer.settled(snapshot.ticket, question, notice, reply.usage))
                        pendingCinematicSample = statistics?.takeIf { question != null }?.let { CinematicSample(snapshot, it) }
                    return@launch
                }
                val candidate = AdviceProjection.candidate(snapshot, result.changes, permissions, editor.catalog(),
                    snapshot.ticket, origin, manuallyAdjusted, correctionOf)
                if (!reducer.received(candidate, reply.usage)) return@launch
                pendingCinematicSample = null
                publish()
                val supplied = result.changes.mapNotNull { AdviceParameter.of(it.parameter) }.toSet()
                renderPending(candidate, entry, snapshot.takeIf { cinematic }, supplied)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (reducer.current(snapshot.ticket)) {
                    val restored = renderFallback(entry)
                    if (!editor.current(entry) || !reducer.current(snapshot.ticket)) return@launch
                    val retryAnswer = restored && answer != null && e is AdvisorNetworkFailure && when (e.category) {
                        AdvisorNetworkError.SERVER, AdvisorNetworkError.TRANSPORT, AdvisorNetworkError.TIMEOUT,
                        AdvisorNetworkError.RATE_LIMIT -> true
                        else -> false
                    }
                    if (reducer.failed(snapshot.ticket, failureCode(e, identity != null && state.value.phase == DraftPhase.GENERATING),
                            retainQuestion = retryAnswer)) {
                        if (retryAnswer) questions.retry(checkNotNull(answer).question)
                        else pendingCinematicSample = null
                        if (!restored) { reducer.restoring(); reducer.restored(false) }
                    }
                }
            } finally { publish() }
        }
    }

    private suspend fun renderPending(candidate: AdviceCandidate, entry: EditSnapshot, cinematicBase: EditSnapshot?,
        supplied: Set<AdviceParameter>? = null, strengthPercent: Int? = null) {
        val ticket = candidate.ticket
        val netChange = candidate.edit != entry.edit
        var rendered = when {
            cinematicBase != null && netChange -> editor.renderCinematicCandidate(candidate, entry, cinematicBase)
            (if (netChange) editor.renderCandidate(candidate, entry) else editor.restore(entry)) -> AdvisorRenderResult.SHOWN
            else -> AdvisorRenderResult.FAILED
        }
        if (!editor.current(entry) || !reducer.current(ticket)) return
        var visible = candidate
        if (rendered == AdvisorRenderResult.HIGHLIGHTS_CLIPPED && strengthPercent == null && cinematicBase != null) {
            val reduced = reducer.stageCinematicRetry(candidate, cinematicBase, supplied.orEmpty(), editor.catalog())
            if (reduced != null) {
                visible = reduced
                rendered = editor.renderReducedCinematicCandidate(reduced, entry, cinematicBase)
                if (!editor.current(entry) || !reducer.current(ticket)) return
            }
        }
        if (rendered == AdvisorRenderResult.SHOWN) {
            if (reducer.publishRendered(ticket, suppliedFields = supplied, cinematicBase = cinematicBase,
                    strengthPercent = strengthPercent, retryOf = candidate.takeIf { visible !== it })) {
                if (visible.edit != entry.edit) editor.offered(visible, entry) else if (strengthPercent == null) editor.endPreview()
            }
        } else recoverRejectedRender(entry, ticket, AdviceError.RENDER_FAILED,
            AdviceNotice.CINEMATIC_NO_ADJUSTMENT.takeIf {
                rendered == AdvisorRenderResult.HIGHLIGHTS_CLIPPED || rendered == AdvisorRenderResult.NO_IMPROVEMENT
            })
    }

    private suspend fun recoverRejectedRender(entry: EditSnapshot, ticket: RequestTicket, error: AdviceError,
        notice: AdviceNotice? = null) {
        val restored = renderFallback(entry)
        if (!editor.current(entry) || !reducer.current(ticket)) return
        if (restored && notice != null) reducer.failed(ticket, null, notice = notice)
        else reducer.failed(ticket, error)
        if (!restored) { reducer.restoring(); reducer.restored(false) }
    }

    private fun failureCode(error: Exception, network: Boolean): AdviceError = when (error) {
        is AdviceFailure -> error.code
        is CredentialUnavailable -> AdviceError.NETWORK_AUTH
        is AdvisorNetworkFailure -> when (error.category) {
            AdvisorNetworkError.AUTH -> AdviceError.NETWORK_AUTH
            AdvisorNetworkError.NOT_FOUND, AdvisorNetworkError.REDIRECT -> AdviceError.NETWORK_ADDRESS
            AdvisorNetworkError.RATE_LIMIT -> AdviceError.NETWORK_RATE_LIMIT
            AdvisorNetworkError.TIMEOUT -> AdviceError.NETWORK_TIMEOUT
            AdvisorNetworkError.TOO_LARGE -> AdviceError.OUTPUT_TOO_LARGE
            else -> AdviceError.NETWORK_FAILED
        }
        is AdvisorProtocolFailure -> when (error.category) {
            AdvisorCompletionError.INCOMPLETE -> AdviceError.PROVIDER_INCOMPLETE
            AdvisorCompletionError.REFUSED -> AdviceError.PROVIDER_REFUSED
            else -> AdviceError.PROVIDER_INVALID
        }
        else -> if (network) {
            if (error is IllegalArgumentException) AdviceError.CONFIGURATION_REQUIRED else AdviceError.NETWORK_FAILED
        } else AdviceError.RENDER_FAILED
    }

    private suspend fun renderFallback(entry: EditSnapshot): Boolean = try {
        val draft = reducer.state.draft
        if (draft != null && draft.edit != entry.edit) {
            editor.renderCandidate(draft, entry).also { success ->
                if (success) editor.offered(draft, entry)
            }
        } else editor.restore(entry)
    } catch (e: CancellationException) { throw e } catch (_: Exception) { false }

    fun discard() = discard(keepSession = false)

    internal fun discardDraft() = discard(keepSession = true)

    private fun discard(keepSession: Boolean) {
        if (state.value.phase in setOf(DraftPhase.COMMITTING, DraftPhase.APPLIED)) return
        if (keepSession && state.value.phase in setOf(DraftPhase.RESTORING, DraftPhase.SAVED)) return
        val entry = state.value.entry ?: return
        pendingCinematicSample = null
        questions.reset(); goal = null
        val previous = task
        val operation = ++this.operation
        previous?.cancel()
        reducer.restoring()
        publish()
        task = scope.launch {
            previous?.join()
            val restored = try { editor.restore(entry) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (operation != this@AdviceSessionController.operation) return@launch
            // Close hands back ownership even on failure so the editor can render the formal edit.
            if (restored || !keepSession) editor.endPreview()
            reducer.restored(restored, keepSession)
            publish()
        }
    }

    /** Comparing and exporting retain the last valid draft, but show the confirmed formal render. */
    fun compare(showEntry: Boolean) {
        if (!state.value.hasPreview) return
        view(showEntry, export = false)
    }

    fun prepareExport(onReady: () -> Unit) {
        if (state.value.phase in setOf(DraftPhase.IDLE, DraftPhase.APPLIED, DraftPhase.SAVED)) {
            onReady(); return
        }
        if (state.value.phase !in setOf(DraftPhase.READY, DraftPhase.PREVIEWING, DraftPhase.GENERATING,
            DraftPhase.RENDERING, DraftPhase.COMPARING, DraftPhase.FAILED)) return
        view(showEntry = true, export = true, onReady = onReady)
    }

    private fun view(showEntry: Boolean, export: Boolean, cancelled: Boolean = false, onReady: () -> Unit = {}) {
        val entry = state.value.entry ?: return
        if (!editor.current(entry)) { invalidate(); return }
        val phase = state.value.phase
        val interrupted = cancelled || (export && (phase == DraftPhase.GENERATING || phase == DraftPhase.RENDERING))
        if (phase == DraftPhase.GENERATING) {
            if (interrupted) state.value.question?.let(questions::retry)
            else pendingCinematicSample = null
        }
        val target = if (showEntry) entry.edit else state.value.draft?.edit ?: entry.edit
        val previous = task
        previous?.cancel()
        val operation = ++this.operation
        reducer.viewing(export, retainQuestion = interrupted)
        publish()
        task = scope.launch {
            previous?.join()
            val success = try { if (showEntry || target == entry.edit) editor.restore(entry) else state.value.draft?.let {
                editor.renderCandidate(it, entry) } ?: editor.render(target, entry) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (operation != this@AdviceSessionController.operation || !editor.current(entry)) return@launch
            reducer.viewed(showEntry, success, cancelled = interrupted)
            if (success && !showEntry && target != entry.edit) reducer.state.draft?.let { editor.offered(it, entry) }
            publish()
            if (success) onReady()
        }
    }

    fun cancelRequest() = cancelPendingPreview(cancelled = true)

    private fun cancelPendingPreview(cancelled: Boolean = false) {
        if (state.value.phase !in setOf(DraftPhase.GENERATING, DraftPhase.RENDERING)) return
        view(showEntry = state.value.draft == null, export = false, cancelled = cancelled)
    }

    fun apply() {
        if (!state.value.canApply) return
        val entry = checkNotNull(state.value.entry)
        if (!editor.current(entry)) { invalidate(); return }
        val candidate = reducer.committing()
        pendingCinematicSample = null
        val operation = ++this.operation
        publish()
        task = scope.launch {
            try {
                val committed = withContext(NonCancellable) { editor.commit(candidate, entry) }
                if (operation != this@AdviceSessionController.operation) return@launch
                committedDraft = committed
                reducer.applied()
                publish()
                val receipt = SaveCoordinator().awaitSaved(committed.applied, committed.target, committed.saved)
                if (operation != this@AdviceSessionController.operation) return@launch
                reducer.saved(receipt.success)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (operation != this@AdviceSessionController.operation) return@launch
                reducer.restoring()
                val restored = try { editor.restore(entry) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                if (restored) editor.endPreview()
                reducer.restored(restored)
            } finally { publish() }
        }
    }

    fun retrySave() {
        if (state.value.phase != DraftPhase.APPLIED || state.value.error != AdviceError.SAVE_FAILED) return
        val committed = committedDraft ?: return
        val operation = ++this.operation
        reducer.retryingSave()
        publish()
        task = scope.launch {
            val success = try {
                SaveCoordinator().awaitSaved(committed.applied, committed.target,
                    editor.retrySave(committed.applied)).success
            } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (operation != this@AdviceSessionController.operation) return@launch
            reducer.saved(success)
            publish()
        }
    }

    fun returnToEditing() {
        when (state.value.phase) {
            DraftPhase.COMMITTING, DraftPhase.APPLIED -> { reducer.showCommit(false); publish() }
            DraftPhase.SAVED -> invalidate()
            else -> Unit
        }
    }

    /** External edits/config changes invalidate immediately, even when values later match again. */
    fun invalidate() {
        operation++
        task?.cancel()
        task = null
        committedDraft = null
        pendingCinematicSample = null
        questions.reset()
        goal = null
        editor.endPreview()
        reducer.invalidate()
        publish()
    }

}
