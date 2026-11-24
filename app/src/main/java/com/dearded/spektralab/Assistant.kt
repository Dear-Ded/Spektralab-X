package com.dearded.spektralab

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.roundToInt

/*
 * 暗房助手, the darkroom assistant (docs/FRONTEND_ITERATION_2026-11-24.md C): say how a photo
 * should look, get a draft of light and colour over the current edit, compare it on the same photo,
 * fine-tune it on the usual dials, then apply it (one step to undo) or let it go. A tool in the
 * 调整 page, not a chat and not an image generator.
 *
 * This file is the UI and its contract only. The session -- requests, drafts, rendering, saving,
 * the service settings -- is codex's (advisor/session: DraftState, AdviceSessionController,
 * AdvisorRuntime), mapped onto [AssistantPort]. No port, no entry: nothing here makes up a result.
 * Internal builds only ([assistantBuildAllowed]).
 */

/** The assistant exists in internal (debug) builds only; tests may turn it off to check the public build. */
@Volatile internal var assistantBuildAllowed: Boolean = BuildConfig.DEBUG

/** The session behind the panel, provided where the editor is built; null = no assistant at all. */
internal val LocalAssistant = staticCompositionLocalOf<AssistantPort?> { null }

/**
 * What the assistant may change: the parameter registry `layer2.scalars.1` (codex's AdviceParameter,
 * same ids), with the names and dials of the 调整 page.
 */
internal enum class AssistantParam(
    val id: String, private val zh: String, val min: Double, val max: Double, val format: String,
    val tint: ((Float) -> Color)? = null,
) {
    EXPOSURE("layer2.exposure", "曝光", -3.0, 3.0, "%+.2f EV"),
    CONTRAST("layer2.contrast", "对比度", -50.0, 50.0, "%+.0f"),
    HIGHLIGHTS("layer2.highlights", "高光", -100.0, 100.0, "%+.0f"),
    SHADOWS("layer2.shadows", "阴影", -100.0, 100.0, "%+.0f"),
    TEMPERATURE("layer2.temperature", "色温", -100.0, 100.0, "%+.0f", DialTints.coolWarm),
    TINT("layer2.tint", "色调", -100.0, 100.0, "%+.0f", DialTints.greenMagenta),
    SATURATION("layer2.saturation", "饱和度", -100.0, 100.0, "%+.0f"),
    VIBRANCE("layer2.vibrance", "自然饱和度", -100.0, 100.0, "%+.0f");

    val label: String get() = tr(zh)

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** One parameter the draft changes: the value in the formal edit and in the draft. */
@Immutable
internal data class AssistantChange(val param: AssistantParam, val before: Double, val after: Double)

/** Where the session is (DraftPhase, folded to what the panel shows). */
internal enum class AssistantPhase {
    /** Waiting for a goal (READY), or nothing to show yet. */
    Ready,
    /** The model is working out parameters (GENERATING). */
    Generating,
    /** The engine renders the draft (RENDERING); the last preview stays on screen. */
    Rendering,
    /** A draft is on the photo (PREVIEWING). */
    Previewing,
    /** Switching 当前 / 建议, restoring, preparing an export or applying (COMPARING, RESTORING, PREPARING_EXPORT, COMMITTING). */
    Busy,
    /** Applied to the formal edit and being saved (APPLIED). */
    Applied,
    /** Applied and saved (SAVED). */
    Saved,
    /** The preview could not be restored (FAILED). */
    Failed,
}

/** A non-error outcome the backend reports (AdviceNotice). */
internal enum class AssistantNotice { NoChange, Unsupported, QuestionLimit, Cancelled, CinematicNotAnalyzed, CinematicNoAdjustment }

/** A failure, as the backend categorises it (AdviceError / AdvisorNetworkError), shown as it is. */
internal enum class AssistantError { Configuration, Auth, Address, RateLimit, Timeout, Network, Refused, Invalid, Render, Save, Preferences }

/** The side of the photo the backend measured as bright (DirectionalLight.brightSide). Measured, never guessed. */
internal enum class AssistantSide(private val zh: String) {
    Left("左"), Right("右"), Top("上"), Bottom("下");
    val label: String get() = tr(zh)
}

/** Which recommendation the user last asked for; the panel words a notice by it. */
internal enum class AssistantAsk { Goal, Cinematic, Directional }

/** The model asking before it proposes (AdviceResult.Question). */
@Immutable
internal data class AssistantQuestion(val prompt: String, val options: List<String>)

/** Everything the panel shows. */
@Immutable
internal data class AssistantView(
    /** A service and model are set up and ready to receive a request (AdvisorRuntimeState.requestSelection). */
    val configured: Boolean,
    /** Shown before anything is sent: which service and which model a request goes to. */
    val service: String = "",
    val model: String = "",
    val phase: AssistantPhase = AssistantPhase.Ready,
    /** The photo shows the formal edit (当前) rather than the draft (建议). */
    val showingCurrent: Boolean = false,
    val changes: List<AssistantChange> = emptyList(),
    /** 画面统计 for this round: off by default, never remembered. */
    val statistics: Boolean = false,
    /** 电影光影 can be asked for: statistics granted and a preview that can be analysed. */
    val cinematicReady: Boolean = false,
    val question: AssistantQuestion? = null,
    val notice: AssistantNotice? = null,
    val error: AssistantError? = null,
    val canApply: Boolean = false,
    /** DraftState.strengthPercent: 0 = the edit this round began with, 100 = the recommendation; null = no strength. */
    val strength: Int? = null,
    /** DraftState.canSetStrength: a rendered draft whose strength may move now. */
    val canSetStrength: Boolean = false,
    /** The draft came from a fixed offline trial (AdviceOrigin.Local), not from a model. */
    val local: Boolean = false,
    /** A directional-light draft: the side measured bright. */
    val lightFrom: AssistantSide? = null,
)

/** The fixed, offline trial adjustments offered when no service is set up. Not AI. */
internal enum class AssistantPreset(private val zh: String) {
    Brighten("提亮"), Soften("柔和");
    val label: String get() = tr(zh)
}

/** The session, as the panel drives it. Every call maps to one AdviceSessionController / AdvisorRuntime action. */
internal interface AssistantPort {
    val view: StateFlow<AssistantView>
    /** The panel opened on this photo (begin). */
    fun open()
    /** The panel closed: any draft not applied is let go and the formal edit restored. */
    fun close()
    /** Sends [goal] (generate); also a follow-up while a draft is shown. */
    fun generate(goal: String)
    /** 电影光影 (generateCinematicRecommendation). */
    fun cinematic()
    /** 方向光 (generateDirectionalLightRecommendation): only with 画面统计 granted this round. */
    fun directional()
    /** The draft's strength in percent (setStrength): renders the preview again, never asks the model. */
    fun setStrength(percent: Int)
    fun cancel()
    fun statistics(on: Boolean)
    /** 当前 (true) or 建议 (false) on the photo. */
    fun showCurrent(current: Boolean)
    /** A value fine-tuned on a dial (adjust). */
    fun adjust(param: AssistantParam, value: Double)
    fun apply()
    fun discard()
    /** Takes the last apply back (the editor's undo, one step). */
    fun undo()
    fun retrySave()
    /** An answer to [AssistantView.question]: one of its options, or the user's own words. */
    fun answer(text: String)
    /** A fixed offline trial (propose with local changes). */
    fun tryPreset(preset: AssistantPreset)
    /** Opens 设置 → 暗房助手. */
    fun openSettings()
}

/**
 * 调整's entry (Assistant.kt C2): the text action 暗房助手 beside 重置, or null -- in a public build, or
 * when no session is provided, there is no entry at all.
 */
@Composable
internal fun assistantEntry(enabled: Boolean, onOpen: () -> Unit): (@Composable () -> Unit)? {
    if (!assistantBuildAllowed || LocalAssistant.current == null) return null
    val view = LocalView.current
    return { QuietButton(tr("暗房助手"), enabled) { Feedback.play(view, Cue.Tab); onOpen() } }
}

// --- the panel ---------------------------------------------------------------------------------

/**
 * The assistant in place of the 调整 page's controls; the photo above does not move. [onClose]
 * returns to the segment that was open.
 */
@Composable
internal fun AssistantPanel(port: AssistantPort, enabled: Boolean, onClose: () -> Unit) {
    val v by port.view.collectAsState()
    val view = LocalView.current
    androidx.compose.runtime.LaunchedEffect(port) { port.open() }
    fun close() { port.close(); onClose() }
    androidx.activity.compose.BackHandler { close() }

    // Haptics follow what actually happened, not the taps that asked for it.
    val phase = v.phase
    var lastPhase by remember { mutableStateOf(phase) }
    androidx.compose.runtime.LaunchedEffect(phase, v.error) {
        when {
            v.error != null && v.error != AssistantError.Configuration -> Feedback.play(view, Cue.Error)
            phase == AssistantPhase.Previewing && lastPhase == AssistantPhase.Rendering -> Feedback.play(view, Cue.Segment)
        }
        lastPhase = phase
    }

    Column(Modifier.fillMaxWidth().testTag("assistant-panel"), verticalArrangement = Arrangement.spacedBy(RowGap)) {
        AssistantHeader(v) { close() }
        // Keyed by mode but carrying the whole state: a screen fading out keeps the state it was drawn
        // with (a draft's values stay on its dials while the next screen fades in).
        AnimatedContent(
            targetState = v,
            contentKey = { panelMode(it) },
            transitionSpec = { fadeIn(Motion.defaultEffects()) togetherWith fadeOut(Motion.fastEffects()) },
            label = "assistant",
        ) { shown ->
            Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
                when (panelMode(shown)) {
                    Mode.Offline -> OfflineTrial(port, enabled)
                    Mode.Compose -> Composer(port, shown, enabled)
                    Mode.Draft -> Draft(port, shown, enabled)
                    Mode.Done -> Done(port, shown, enabled)
                }
            }
        }
    }
}

private enum class Mode { Offline, Compose, Draft, Done }

private fun panelMode(v: AssistantView) = when {
    v.phase == AssistantPhase.Applied || v.phase == AssistantPhase.Saved -> Mode.Done
    (v.changes.isNotEmpty() || v.strength != null) && v.phase != AssistantPhase.Ready -> Mode.Draft
    !v.configured -> Mode.Offline
    else -> Mode.Compose
}

/** Back, the name, and -- before anything is sent -- where a request would go. */
@Composable
private fun AssistantHeader(v: AssistantView, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).padding(end = 4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("返回调整"), tint = Palette.OnSurface)
        }
        Text(tr("暗房助手"), style = MaterialTheme.typography.titleMedium, color = Palette.OnSurface, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        val route = listOf(v.service, v.model).filter { it.isNotBlank() }.joinToString(" · ")
        if (v.local && panelMode(v) == Mode.Draft) Text(tr("本地示例"), style = MaterialTheme.typography.bodySmall,
            color = Palette.OnSurfaceSoft, modifier = Modifier.semantics { contentDescription = tr("本地示例，不是模型结果") })
        else if (v.configured && route.isNotEmpty()) Text(route, style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { contentDescription = tr("请求发往 {0}", route) })
    }
}

/** No service: two fixed trials, and the way to set one up. */
@Composable
private fun OfflineTrial(port: AssistantPort, enabled: Boolean) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (p in AssistantPreset.entries) TonalButton(p.label, enabled, Modifier.weight(1f).height(56.dp)) {
            Feedback.play(view, Cue.Pick); port.tryPreset(p)
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1f))
        QuietButton(tr("配置服务"), enabled) { port.openSettings() }
    }
}

/** The goal, 画面统计 for this round, 电影光影 and 生成草稿; while generating, 取消. */
@Composable
private fun Composer(port: AssistantPort, v: AssistantView, enabled: Boolean) {
    val view = LocalView.current
    var goal by rememberSaveable { mutableStateOf("") }
    // A recommendation waiting for the user's yes to 画面统计 (nothing is sent until then), and the last one asked.
    var consent by rememberSaveable { mutableStateOf<AssistantAsk?>(null) }
    var asked by rememberSaveable { mutableStateOf(AssistantAsk.Goal) }
    val working = v.phase == AssistantPhase.Generating || v.phase == AssistantPhase.Rendering
    val ready = enabled && !working && v.phase == AssistantPhase.Ready
    fun ask(a: AssistantAsk) {
        asked = a
        Feedback.play(view, Cue.Pick)
        when (a) {
            AssistantAsk.Goal -> port.generate(goal.trim())
            AssistantAsk.Cinematic -> port.cinematic()
            AssistantAsk.Directional -> port.directional()
        }
    }
    fun recommend(a: AssistantAsk) { if (v.statistics) ask(a) else consent = a }
    // The yes turns 画面统计 on; the request goes once the session says it is on, not before.
    androidx.compose.runtime.LaunchedEffect(v.statistics, consent) {
        val a = consent ?: return@LaunchedEffect
        if (v.statistics) { consent = null; ask(a) }
    }
    v.question?.let { Question(port, it, enabled && !working) }
    EdgeLit(active = v.phase == AssistantPhase.Generating, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GoalField(goal, { goal = it }, tr("修图目标"), enabled && !working, minHeight = 88.dp) {
                if (goal.isNotBlank()) ask(AssistantAsk.Goal)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatisticsChip(v.statistics, enabled && !working) { consent = null; port.statistics(it) }
                Spacer(Modifier.weight(1f))
                if (working) TonalButton(tr("取消"), enabled) { Feedback.play(view, Cue.Release); port.cancel() }
                else PrimaryButton(tr("生成草稿"), enabled && goal.isNotBlank()) { ask(AssistantAsk.Goal) }
            }
        }
    }
    // The two recommendations: no goal to type, the photo's own light and colour are the brief.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Recommendation(tr("电影光影"), AssistantAsk.Cinematic, consent, ready, Modifier.weight(1f)) { recommend(AssistantAsk.Cinematic) }
        Recommendation(tr("方向光"), AssistantAsk.Directional, consent, ready, Modifier.weight(1f)) { recommend(AssistantAsk.Directional) }
    }
    val pending = consent
    if (pending != null) Consent(enabled, onAllow = { port.statistics(true) }, onCancel = { consent = null })
    else Sends(v.statistics)
    Status(port, v, asked, retry = { if (goal.isNotBlank()) port.generate(goal.trim()) })
}

/** A recommendation button: tonal; the one waiting for consent reads as chosen. */
@Composable
private fun Recommendation(text: String, ask: AssistantAsk, consent: AssistantAsk?, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val chosen = consent == ask
    Box(
        modifier.heightIn(min = 48.dp).pressScale(press).graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(Corners.m).background(if (chosen) Palette.PanelHighest else Palette.PanelHigh)
            .border(1.dp, if (chosen) Palette.Outline else Palette.OutlineSoft, Corners.m)
            .clickable(enabled = enabled, interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.OnSurface, maxLines = 1) }
}

/**
 * Asked before a recommendation is sent without 画面统计: what it needs and what it never sends, one line,
 * and the yes. Nothing goes out from here except the switch; the request follows once it is on.
 */
@Composable
private fun Consent(enabled: Boolean, onAllow: () -> Unit, onCancel: () -> Unit) {
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().clip(Corners.m).background(Palette.Panel).border(1.dp, Palette.OutlineSoft, Corners.m)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("需要本次的亮度与颜色分布，不发送照片"), style = MaterialTheme.typography.bodySmall, color = Palette.OnSurface,
            modifier = Modifier.weight(1f))
        QuietButton(tr("取消"), enabled) { onCancel() }
        PrimaryButton(tr("允许"), enabled) { Feedback.play(view, Cue.ToggleOn); onAllow() }
    }
}

/** What a request carries, before it is sent: one line that follows 画面统计. The photo itself never goes. */
@Composable
private fun Sends(statistics: Boolean) {
    val text = if (statistics) tr("发送 目标文字、参数、亮度与颜色分布 · 不发送照片") else tr("发送 目标文字、参数 · 不发送照片")
    Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().semantics { contentDescription = text })
}

/** The draft on the photo: 当前 / 建议, the changed values on the usual dials, 放弃 / 应用, and a follow-up. */
@Composable
private fun Draft(port: AssistantPort, v: AssistantView, enabled: Boolean) {
    val view = LocalView.current
    val working = v.phase == AssistantPhase.Generating || v.phase == AssistantPhase.Rendering
    val idle = enabled && v.phase == AssistantPhase.Previewing
    Segmented(listOf(tr("当前"), tr("建议")), if (v.showingCurrent) 0 else 1, enabled && !working,
        modifier = Modifier.semantics { stateDescription = if (v.showingCurrent) tr("照片显示当前编辑") else tr("照片显示建议") }) { i ->
        Feedback.play(view, Cue.Tab); port.showCurrent(i == 0)
    }
    // A directional draft says which side was measured bright; the masks follow from it.
    v.lightFrom?.let { side ->
        Text(tr("方向光 · 亮侧在{0}", side.label), style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurface,
            modifier = Modifier.semantics { contentDescription = tr("方向光，测得亮侧在{0}", side.label) })
    }
    // Strength on the camera dial: 0 % is the edit this round began with, 100 % the recommendation.
    // Turning it renders the preview again; the model is not asked.
    // The session takes a strength only between renders (canSetStrength); the dial keeps where the finger
    // is and the latest value goes as soon as the session can take it, so a quick drag ends where it stopped.
    var wanted by remember { mutableStateOf<Int?>(null) }
    androidx.compose.runtime.LaunchedEffect(v.canSetStrength, v.strength, wanted) {
        val w = wanted ?: return@LaunchedEffect
        if (w == v.strength) wanted = null else if (v.canSetStrength) port.setStrength(w)
    }
    v.strength?.let { pct ->
        val dialOn = enabled && !v.showingCurrent && (v.canSetStrength || wanted != null)
        LabeledSlider(tr("强度"), (wanted ?: pct).toDouble(), 0.0, 100.0, 100.0, dialOn, "%.0f%%",
            stops = listOf(50.0)) { x ->
            val p = x.roundToInt().coerceIn(0, 100)
            wanted = p
            if (v.canSetStrength) port.setStrength(p)
        }
    }
    // The changed values as a carousel over one dial, as on the 调整 page; the formal value is a firm detent.
    if (v.changes.isNotEmpty()) KnobRow("assistant", v.changes.map { c ->
        Knob(c.param.id, c.param.label, c.after, c.param.min, c.param.max, c.after, c.param.format,
            short = { x -> String.format(Locale.ROOT, if (c.param == AssistantParam.EXPOSURE) "%+.1f" else "%+.0f", x).replace("-", "−") },
            tint = c.param.tint, stops = listOf(c.before)) { port.adjust(c.param, it) }
    }, idle && !v.showingCurrent, null)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuietButton(tr("放弃"), enabled && !working) { Feedback.play(view, Cue.Release); port.discard() }
        Spacer(Modifier.weight(1f))
        if (working) TonalButton(tr("取消"), enabled) { Feedback.play(view, Cue.Release); port.cancel() }
        else PrimaryButton(tr("应用"), idle && v.canApply) { Feedback.play(view, Cue.Saved); port.apply() }
    }
    v.question?.let { Question(port, it, idle) }
    // A follow-up goes to the model: not on a local trial, and a directional draft never stacks another.
    if (v.configured && !v.local && v.lightFrom == null) {
        var more by rememberSaveable { mutableStateOf("") }
        EdgeLit(active = v.phase == AssistantPhase.Generating, modifier = Modifier.fillMaxWidth()) {
            GoalField(more, { more = it }, tr("继续调整"), idle, minHeight = 48.dp) {
                if (more.isNotBlank()) { Feedback.play(view, Cue.Pick); port.generate(more.trim()); more = "" }
            }
        }
    }
    Status(port, v, AssistantAsk.Goal, retry = null)
}

/** Applied: undo in one step; while it saves, its state; a failed save can be tried again. */
@Composable
private fun Done(port: AssistantPort, v: AssistantView, enabled: Boolean) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when {
                v.error == AssistantError.Save -> tr("已应用，保存失败")
                v.phase == AssistantPhase.Saved -> tr("已应用并保存")
                else -> tr("已应用，正在保存")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (v.error == AssistantError.Save) Palette.Warning else Palette.OnSurface,
            modifier = Modifier.weight(1f),
        )
        if (v.error == AssistantError.Save) TonalButton(tr("重试"), enabled) { port.retrySave() }
        QuietButton(tr("撤销"), enabled) { Feedback.play(view, Cue.Rewind); port.undo() }
    }
}

/** The model's question before it proposes: its options as pills, or the user's own answer. */
@Composable
private fun Question(port: AssistantPort, q: AssistantQuestion, enabled: Boolean) {
    var own by remember(q) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(q.prompt, style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurface)
        if (q.options.isNotEmpty()) PillRow {
            for (o in q.options) SelectPill(selected = false, onClick = { port.answer(o) }, label = { Text(o) }, enabled = enabled)
        }
        GoalField(own, { own = it }, tr("回答"), enabled, minHeight = 48.dp) { if (own.isNotBlank()) port.answer(own.trim()) }
    }
}

/** The outcome as the backend reports it: a notice, or an error with 重试 when trying again can help. */
@Composable
private fun Status(port: AssistantPort, v: AssistantView, asked: AssistantAsk, retry: (() -> Unit)?) {
    val text = when {
        v.phase == AssistantPhase.Generating -> tr("正在生成建议")
        v.phase == AssistantPhase.Rendering -> tr("正在预览调整")
        v.phase == AssistantPhase.Failed -> tr("预览无法恢复")
        v.error != null -> errorText(v.error)
        v.notice != null -> noticeText(v.notice, asked)
        else -> null
    } ?: return
    val isError = v.error != null || v.phase == AssistantPhase.Failed
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (isError) Palette.Warning else Palette.OnSurfaceSoft,
            modifier = Modifier.weight(1f).semantics { this.contentDescription = text })
        when {
            v.error == AssistantError.Configuration || v.error == AssistantError.Auth || v.error == AssistantError.Address ->
                QuietButton(tr("配置服务")) { port.openSettings() }
            v.error != null && v.error != AssistantError.Save && retry != null -> QuietButton(tr("重试"), onClick = retry)
        }
    }
}

private fun errorText(e: AssistantError) = when (e) {
    AssistantError.Configuration -> tr("服务未配置")
    AssistantError.Auth -> tr("密钥无效")
    AssistantError.Address -> tr("服务地址无效")
    AssistantError.RateLimit -> tr("请求太频繁")
    AssistantError.Timeout -> tr("请求超时")
    AssistantError.Network -> tr("网络连接失败")
    AssistantError.Refused -> tr("服务拒绝了这次请求")
    AssistantError.Invalid -> tr("返回的结果无法使用")
    AssistantError.Render -> tr("预览渲染失败")
    AssistantError.Save -> tr("保存失败")
    AssistantError.Preferences -> tr("无法读取本地偏好")
}

// The session reports a directional request's outcome with the cinematic notices; the ask words them.
private fun noticeText(n: AssistantNotice, asked: AssistantAsk) = when (n) {
    AssistantNotice.NoChange -> tr("没有需要调整的地方")
    AssistantNotice.Unsupported -> tr("这类调整还做不到")
    AssistantNotice.QuestionLimit -> tr("追问次数已用完")
    AssistantNotice.Cancelled -> tr("已取消")
    AssistantNotice.CinematicNotAnalyzed -> tr("预览还不能分析")
    AssistantNotice.CinematicNoAdjustment ->
        if (asked == AssistantAsk.Directional) tr("没有明确的光向") else tr("没有合适的电影光影")
}

// --- small parts ------------------------------------------------------------------------------

/** A text field in the panel's style: Layer1, a hairline edge, 48 dp at least; [label] shows while it is empty. */
@Composable
private fun GoalField(
    text: String, onText: (String) -> Unit, label: String, enabled: Boolean,
    minHeight: androidx.compose.ui.unit.Dp, onSend: () -> Unit,
) {
    BasicTextField(
        value = text, onValueChange = { if (it.length <= 2000) onText(it) }, enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) Palette.OnSurface else Palette.OnSurfaceSoft),
        cursorBrush = SolidColor(Palette.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = minHeight).clip(Corners.m).background(Palette.Panel)
                    .border(1.dp, Palette.OutlineSoft, Corners.m).padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (text.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge, color = Palette.OnSurfaceSoft.copy(alpha = 0.7f))
                inner()
            }
        },
    )
}

/** 画面统计: a switch for this round only, as a pill that reads on or off. */
@Composable
private fun StatisticsChip(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val view = LocalView.current
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.heightIn(min = 48.dp).clip(Corners.m)
            .background(if (on) Palette.PanelHighest else Palette.Panel)
            .border(1.dp, if (on) Palette.Outline else Palette.OutlineSoft, Corners.m)
            .toggleable(on, press, ripple(), enabled, Role.Switch) { Feedback.play(view, if (it) Cue.ToggleOn else Cue.ToggleOff); onChange(it) }
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (on) Palette.Accent else Palette.Track, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(tr("画面统计"), style = MaterialTheme.typography.labelLarge, color = Palette.OnSurface)
    }
}

/** The one primary action of a panel (生成草稿, 应用): the near-white pill of 保存. */
@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 48.dp)
            .pressScale(press).graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(Corners.m).background(Palette.Accent)
            .clickable(enabled = enabled, interactionSource = press, indication = ripple(color = Palette.OnAccent), role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.OnAccent, maxLines = 1)
    }
}
