package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.AdviceError
import com.dearded.spektralab.advisor.domain.AdviceOrigin
import com.dearded.spektralab.advisor.domain.AdviceParameter
import com.dearded.spektralab.advisor.domain.BrightSide
import com.dearded.spektralab.advisor.domain.ParameterChange
import com.dearded.spektralab.advisor.domain.effectiveValueChanged
import com.dearded.spektralab.advisor.session.AdviceNotice
import com.dearded.spektralab.advisor.session.AdviceSessionController
import com.dearded.spektralab.advisor.session.AdvisorRuntimeState
import com.dearded.spektralab.advisor.session.DraftPhase
import com.dearded.spektralab.advisor.session.DraftState
import com.dearded.spektralab.advisor.session.LocalAdvicePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Editor-facing projection of the Main-owned session; never builds a provider request. */
internal class AssistantSessionPort(
    private val scope: CoroutineScope,
    private val controller: AdviceSessionController,
    runtime: StateFlow<AdvisorRuntimeState>,
    private val ui: StateFlow<UiState>,
    private val canBegin: () -> Boolean,
    private val canAnalyzePreview: () -> Boolean,
    private val undoEdit: () -> Unit,
    private val openAssistantSettings: () -> Unit,
) : AssistantPort {
    private var reopen: Job? = null

    override val view: StateFlow<AssistantView> = combine(controller.state, runtime, ui) { draft, route, _ ->
        draft.toAssistantView(route, canAnalyzePreview)
    }.stateIn(scope, SharingStarted.Eagerly, controller.state.value.toAssistantView(runtime.value, canAnalyzePreview))

    override fun open() {
        reopen?.cancel()
        if (controller.state.value.phase == DraftPhase.RESTORING) {
            val source = ui.value.previewSource
            reopen = scope.launch {
                val restored = controller.state.first { it.phase != DraftPhase.RESTORING }
                if (restored.phase == DraftPhase.IDLE && ui.value.previewSource == source && canBegin()) controller.begin()
            }
        } else if (canBegin()) controller.begin()
    }

    override fun close() {
        reopen?.cancel()
        reopen = null
        when (controller.state.value.phase) {
            DraftPhase.IDLE -> Unit
            DraftPhase.COMMITTING, DraftPhase.APPLIED, DraftPhase.SAVED -> controller.returnToEditing()
            else -> controller.discard()
        }
    }

    override fun generate(goal: String) = withCurrentEdit { controller.requestCustomScope(goal) }
    override fun cinematic() = withCurrentEdit { controller.generateCinematicRecommendation() }
    override fun directional() = withCurrentEdit { controller.generateDirectionalLightRecommendation() }
    override fun setStrength(percent: Int) = controller.setStrength(percent)
    override fun cancel() = controller.cancelRequest()
    override fun statistics(on: Boolean) = withCurrentEdit { controller.includeStatistics(on) }
    override fun showCurrent(current: Boolean) = controller.compare(showEntry = current)
    override fun adjust(param: AssistantParam, value: Double) =
        controller.adjust(listOf(ParameterChange(param.id, value)))
    override fun apply() = controller.apply()
    override fun discard() = controller.discardDraft()
    override fun undo() = undoEdit()
    override fun retrySave() = controller.retrySave()
    override fun answer(text: String) = controller.requestAnswerScope(text)
    override fun confirmScope(id: Long, wholePhoto: Boolean) {
        if (controller.state.value.scopePrompt?.id == id) controller.resolveScope(id, wholePhoto)
    }
    override fun tryPreset(preset: AssistantPreset) = withCurrentEdit {
        controller.tryLocalPreset(when (preset) {
            AssistantPreset.Brighten -> LocalAdvicePreset.BRIGHTEN
            AssistantPreset.Soften -> LocalAdvicePreset.SOFTEN
        })
    }
    override fun openSettings() = openAssistantSettings()

    private inline fun withCurrentEdit(action: () -> Unit) {
        // Editor history can invalidate the snapshot without dismissing the assistant.
        if (controller.state.value.phase == DraftPhase.IDLE && canBegin()) controller.begin()
        action()
    }
}

private fun DraftState.toAssistantView(runtime: AdvisorRuntimeState, canAnalyzePreview: () -> Boolean): AssistantView {
    val selection = runtime.requestSelection
    val before = entry?.edit?.look
    val after = draft?.edit?.look
    val changes = if (before == null || after == null) emptyList() else {
        val effectiveBefore = before.resolved()
        val effectiveAfter = after.resolved()
        // Dials read and write stored values, but only list scalars that changed the render.
        AdviceParameter.entries.mapNotNull { parameter ->
            val uiParameter = checkNotNull(AssistantParam.of(parameter.id))
            if (!effectiveValueChanged(parameter.read(effectiveBefore), parameter.read(effectiveAfter))) null
            else AssistantChange(uiParameter, parameter.read(before), parameter.read(after))
        }
    }
    return AssistantView(
        configured = selection != null,
        service = selection?.first?.label.orEmpty(),
        model = selection?.second?.remoteModelId.orEmpty(),
        phase = when (phase) {
            DraftPhase.IDLE, DraftPhase.READY -> AssistantPhase.Ready // The panel itself is mounted by the editor.
            DraftPhase.GENERATING -> AssistantPhase.Generating
            DraftPhase.RENDERING -> AssistantPhase.Rendering
            DraftPhase.PREVIEWING -> AssistantPhase.Previewing
            DraftPhase.COMPARING, DraftPhase.RESTORING, DraftPhase.PREPARING_EXPORT, DraftPhase.COMMITTING -> AssistantPhase.Busy
            DraftPhase.APPLIED -> AssistantPhase.Applied
            DraftPhase.SAVED -> AssistantPhase.Saved
            DraftPhase.FAILED -> AssistantPhase.Failed
        },
        showingCurrent = showingEntry,
        changes = changes,
        statistics = includeStatistics,
        cinematicReady = includeStatistics && canAnalyzePreview(),
        question = question?.let { AssistantQuestion(it.prompt, it.options.map { option -> option.label }) },
        scope = scopePrompt?.let { AssistantScope(it.id, it.text) },
        notice = when (notice) {
            null -> null
            AdviceNotice.NO_CHANGE -> AssistantNotice.NoChange
            AdviceNotice.UNSUPPORTED -> AssistantNotice.Unsupported
            AdviceNotice.QUESTION_LIMIT -> AssistantNotice.QuestionLimit
            AdviceNotice.CANCELLED -> AssistantNotice.Cancelled
            AdviceNotice.CINEMATIC_PREVIEW_NOT_ANALYZED -> AssistantNotice.CinematicNotAnalyzed
            AdviceNotice.CINEMATIC_NO_ADJUSTMENT -> AssistantNotice.CinematicNoAdjustment
        },
        error = when (error) {
            null -> null
            AdviceError.CONFIGURATION_REQUIRED -> AssistantError.Configuration
            AdviceError.NETWORK_AUTH -> AssistantError.Auth
            AdviceError.NETWORK_ADDRESS -> AssistantError.Address
            AdviceError.NETWORK_RATE_LIMIT -> AssistantError.RateLimit
            AdviceError.NETWORK_TIMEOUT -> AssistantError.Timeout
            AdviceError.NETWORK_FAILED -> AssistantError.Network
            AdviceError.PROVIDER_REFUSED -> AssistantError.Refused
            AdviceError.RENDER_FAILED -> AssistantError.Render
            AdviceError.SAVE_FAILED -> AssistantError.Save
            AdviceError.INVALID_JSON, AdviceError.SCHEMA_MISMATCH, AdviceError.INVALID_STATUS_PAYLOAD,
            AdviceError.UNKNOWN_PARAMETER, AdviceError.DUPLICATE_PARAMETER, AdviceError.OUT_OF_RANGE,
            AdviceError.LOCK_CONFLICT, AdviceError.NO_EFFECT, AdviceError.STALE_RESULT,
            AdviceError.PROVIDER_INCOMPLETE, AdviceError.PROVIDER_INVALID, AdviceError.OUTPUT_TOO_LARGE -> AssistantError.Invalid
        },
        canApply = canApply,
        strength = strengthPercent,
        canSetStrength = canSetStrength,
        local = draft?.origin == AdviceOrigin.Local,
        lightFrom = when (draft?.directionalLight?.brightSide) {
            null -> null
            BrightSide.LEFT -> AssistantSide.Left
            BrightSide.RIGHT -> AssistantSide.Right
            BrightSide.TOP -> AssistantSide.Top
            BrightSide.BOTTOM -> AssistantSide.Bottom
        },
    )
}
