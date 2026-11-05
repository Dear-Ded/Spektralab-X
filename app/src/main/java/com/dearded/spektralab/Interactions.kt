package com.dearded.spektralab

import android.animation.ValueAnimator
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * The app's interaction kit (docs/UI2.md 3). Every screen presses, selects,
 * vibrates, snaps and goes back through these, so the feel is the same
 * everywhere; do not write another press scale or haptic table elsewhere.
 *
 *  - Press:      Modifier.pressScale(source)                    3.6
 *  - Select:     rememberSelectionStyle(selected) + Modifier.morphingPill(style, source)
 *  - Haptics:    View.haptic(HapticEvent.X), rememberHaptic()   3.5
 *  - Snap/fling: snapIndex(...) + Animatable.settle(target, velocity)  3.1, 3.7
 *  - Panels:     directionalSwap(forward), Expandable(open)      3.7
 *  - Back:       rememberBackProgress() + PredictiveBack(...) + Modifier.predictiveBack(progress)  3.8
 *  - Screens:    forwardThrough() / backThrough()                3.8
 *  - Motion off: rememberReduceMotion()                          3.9
 *
 * Gesture values (drag offsets, back progress) are read only inside
 * graphicsLayer / drawBehind lambdas here, so a 120 Hz drag re-runs the draw
 * phase and never recomposes (3.1).
 */

// --- springs not in Theme.kt's Motion ----------------------------------------

/** M3 Expressive slow effects (1.0 / 800): long fades, a scrim behind a full-screen move. */
fun <T> Motion.slowEffects() = spring<T>(dampingRatio = 1f, stiffness = 800f)

/** defaultSpatial for sizes, settling at one pixel (expand / collapse). */
private val SizeSpring = spring(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = IntSize.VisibilityThreshold)

/** The press scale (UI2.md 3.6). */
const val PressedScale = 0.96f

// --- press -------------------------------------------------------------------

/**
 * Shrinks to [pressedScale] while [interactionSource] is pressed and springs
 * back on release with a small overshoot past 1 (Motion.fastSpatial, 0.6 / 800).
 * Pass the same source to the clickable / selectable / toggleable:
 *
 * ```
 * val press = remember { MutableInteractionSource() }
 * Box(Modifier.pressScale(press).clickable(interactionSource = press, indication = ripple()) { ... })
 * ```
 *
 * The scale is read in the draw phase only. Put this before clip / background
 * so the whole control shrinks, ink included.
 */
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = PressedScale): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) pressedScale else 1f, Motion.fastSpatial(), label = "press")
    graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

// --- selection ---------------------------------------------------------------

/**
 * The animated look of a selectable control (UI2.md 1.2, 3.6). Selected is
 * told by an inverted fill (near-white, dark ink) and a corner change, never
 * by hue. Fill and ink move on defaultEffects (no overshoot), the corner on
 * fastSpatial (a slight bounce, like M3's ToggleButton).
 *
 * The getters read animation state: read [fill] and [corner] in a draw-phase
 * lambda (Modifier.morphingPill does) and [ink] where the text is drawn.
 */
@Stable
class SelectionStyle internal constructor(
    private val fillState: State<Color>,
    private val inkState: State<Color>,
    private val cornerState: State<Dp>,
) {
    val fill: Color get() = fillState.value
    val ink: Color get() = inkState.value
    val corner: Dp get() = cornerState.value
}

/**
 * [SelectionStyle] for [selected]. Defaults match SelectPill: rest on
 * Palette.Panel with 12 dp corners, selected on Palette.Accent with 20 dp
 * (a full pill at 40 dp tall). A disabled, unselected control dims its ink.
 */
@Composable
fun rememberSelectionStyle(
    selected: Boolean,
    enabled: Boolean = true,
    restFill: Color = Palette.Panel,
    selectedFill: Color = Palette.Accent,
    restCorner: Dp = 12.dp,
    selectedCorner: Dp = 20.dp,
): SelectionStyle {
    val fill = animateColorAsState(if (selected) selectedFill else restFill, Motion.defaultEffects(), label = "sel-fill")
    val ink = animateColorAsState(
        when {
            selected -> Palette.OnAccent
            enabled -> Palette.OnSurface
            else -> Palette.OnSurfaceSoft.copy(alpha = 0.45f)
        },
        Motion.defaultEffects(), label = "sel-ink",
    )
    val corner = animateDpAsState(if (selected) selectedCorner else restCorner, Motion.fastSpatial(), label = "sel-corner")
    return remember(fill, ink, corner) { SelectionStyle(fill, ink, corner) }
}

/**
 * Draws [style]'s fill clipped to its animated corner, and with an
 * [interactionSource] also the press scale, all in one layer and all in the
 * draw phase: a selection change or a press never recomposes the control.
 * Use instead of clip + background on a selectable pill, card or segment.
 */
fun Modifier.morphingPill(
    style: SelectionStyle,
    interactionSource: InteractionSource? = null,
    pressedScale: Float = PressedScale,
): Modifier = composed {
    val pressed = interactionSource?.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed?.value == true) pressedScale else 1f, Motion.fastSpatial(), label = "pill-press")
    graphicsLayer {
        scaleX = scale.value; scaleY = scale.value
        shape = RoundedCornerShape(style.corner)
        clip = true
    }.drawBehind { drawRect(style.fill) }
}

// --- haptics -----------------------------------------------------------------

/** The events of UI2.md 3.5. Each maps to one HapticFeedbackConstants value with an API 29 fallback. */
enum class HapticEvent {
    /** A slider passes or snaps to its default. */
    Tick,
    /** A slider reaches either end. */
    Edge,
    /** A detent: a dial step, the panel snapping to a height, a strip frame, the grid's column count. */
    Segment,
    /** Reset, export done, a frame picked. */
    Confirm,
    /** A style applied, 下一步 in the darkroom steps. */
    Click,
    /** The strength panel opens, a panel drag starts. */
    GestureStart,
    /** A panel drag ends. */
    GestureEnd,
    /** Hold to compare, hold to multi-select. */
    LongPress,
    ToggleOn,
    ToggleOff,
    /** An error, a frame rejected. */
    Reject,
}

/** The constant for [event] on this device, or null when it has none (GestureEnd before API 30). */
fun hapticConstant(event: HapticEvent): Int? {
    val sdk = Build.VERSION.SDK_INT
    return when (event) {
        HapticEvent.Tick -> HapticFeedbackConstants.CLOCK_TICK
        HapticEvent.Edge ->
            if (sdk >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK
        HapticEvent.Segment ->
            if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        HapticEvent.Confirm ->
            if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        HapticEvent.Click -> HapticFeedbackConstants.CONTEXT_CLICK
        HapticEvent.GestureStart ->
            if (sdk >= 30) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.CONTEXT_CLICK
        HapticEvent.GestureEnd -> if (sdk >= 30) HapticFeedbackConstants.GESTURE_END else null
        HapticEvent.LongPress -> HapticFeedbackConstants.LONG_PRESS
        HapticEvent.ToggleOn ->
            if (sdk >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
        HapticEvent.ToggleOff ->
            if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
        HapticEvent.Reject ->
            if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }
}

/**
 * Plays [event] (UI2.md 3.5). Goes through View.performHapticFeedback, so it
 * follows the system's touch-feedback switch and needs no VIBRATE permission.
 * Once per event: never call this on every frame of a drag, only when a
 * threshold or detent is crossed.
 */
fun View.haptic(event: HapticEvent) {
    hapticConstant(event)?.let { performHapticFeedback(it) }
}

/** [View.haptic] on the current view, for use from click lambdas: `val haptic = rememberHaptic(); ... haptic(HapticEvent.Click)`. */
@Composable
fun rememberHaptic(): (HapticEvent) -> Unit {
    val view = LocalView.current
    return remember(view) { { e: HapticEvent -> view.haptic(e) } }
}

// --- snap and fling ----------------------------------------------------------

/**
 * Which of [anchors] (ascending, in px) a released drag settles on. The
 * nearest anchor to [position] wins; a fling faster than [flingVelocity]
 * (px/s) that would land back on [from] goes one anchor further in the
 * direction of [velocity] (positive = towards larger values). So a slow drag
 * settles where the finger left it and a flick always moves one detent.
 *
 * For a panel whose anchors are heights, pass the finger's vertical velocity
 * negated (a finger moving up makes the panel taller).
 */
fun snapIndex(anchors: List<Float>, position: Float, velocity: Float, from: Int, flingVelocity: Float = 1500f): Int {
    require(anchors.isNotEmpty()) { "no anchors" }
    var best = anchors.indices.minBy { abs(anchors[it] - position) }
    if (abs(velocity) > flingVelocity && best == from) {
        best = (from + if (velocity > 0f) 1 else -1).coerceIn(anchors.indices)
    }
    return best
}

/**
 * Springs to [target] starting at the finger's [velocity] (px/s), so the
 * motion carries on at the speed the finger left it (UI2.md 3.1). With
 * animations off it lands at once.
 */
suspend fun Animatable<Float, AnimationVector1D>.settle(
    target: Float,
    velocity: Float,
    spec: AnimationSpec<Float> = Motion.defaultSpatial(),
) {
    animateTo(target, spec, initialVelocity = velocity)
}

// --- panel content -----------------------------------------------------------

/**
 * The swap between a panel's pages (the five editor entries, steps,
 * segments): the new page comes in from the side it lies on, an eighth of
 * the width, and fades in; the old one leaves the other way and fades out
 * faster (UI2.md 3.7). Use as AnimatedContent's transitionSpec:
 * `transitionSpec = { directionalSwap(targetState > initialState) }`.
 * The size does not animate: panels keep their height when the page changes.
 */
fun directionalSwap(forward: Boolean): ContentTransform {
    val dir = if (forward) 1 else -1
    return (slideInHorizontally(Motion.defaultSpatialOffset()) { it / 8 * dir } + fadeIn(Motion.defaultEffects())) togetherWith
        (slideOutHorizontally(Motion.defaultSpatialOffset()) { -it / 8 * dir } + fadeOut(Motion.fastEffects()))
}

/**
 * 更多参数 and anything else that opens in place: the height grows from the
 * top on defaultSpatial and the content fades in on defaultEffects; closing
 * fades out fast and shrinks back. Content is laid out in a Column.
 */
@Composable
fun Expandable(
    open: Boolean,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = open,
        modifier = modifier,
        enter = expandVertically(SizeSpring, expandFrom = Alignment.Top) + fadeIn(Motion.defaultEffects()),
        exit = fadeOut(Motion.fastEffects()) + shrinkVertically(SizeSpring, shrinkTowards = Alignment.Top),
        label = "expandable",
    ) {
        Column(verticalArrangement = verticalArrangement, content = content)
    }
}

// --- screens -----------------------------------------------------------------

/**
 * Going deeper (editor → light table, grid → roll): the new screen grows in
 * from 92 % while the old one grows to 108 % and fades out fast. The
 * "fade through" of the Android predictive-back guide, run forwards.
 */
fun forwardThrough(): ContentTransform =
    (fadeIn(Motion.defaultEffects()) + scaleIn(Motion.defaultSpatial(), initialScale = 0.92f)) togetherWith
        (fadeOut(Motion.fastEffects()) + scaleOut(Motion.defaultSpatial(), targetScale = 1.08f))

/**
 * Coming back: the screen underneath shrinks in from 110 % to 100 % while the
 * leaving one shrinks to 90 % and fades out by about a third of the way
 * (developer.android.com predictive back, "fade through").
 */
fun backThrough(): ContentTransform =
    (fadeIn(Motion.defaultEffects()) + scaleIn(Motion.defaultSpatial(), initialScale = 1.1f)) togetherWith
        (fadeOut(Motion.fastEffects()) + scaleOut(Motion.defaultSpatial(), targetScale = 0.9f))

// --- predictive back ---------------------------------------------------------

/** STANDARD_DECELERATE, PathInterpolator(0, 0, 0, 1): the guide says to feed back progress through it, never raw. */
private val BackEase = CubicBezierEasing(0f, 0f, 0f, 1f)

/**
 * A back gesture's progress, 0 at rest to 1 at the commit point, already
 * eased, plus the edge it started from. Read [value] only in draw-phase
 * lambdas ([Modifier.predictiveBack] does).
 */
@Stable
class BackProgress internal constructor() {
    internal val anim = Animatable(0f)
    val value: Float get() = anim.value
    /** BackEventCompat.EDGE_LEFT or EDGE_RIGHT (EDGE_NONE for a 3-button back). */
    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        internal set
}

@Composable
fun rememberBackProgress(): BackProgress = remember { BackProgress() }

/**
 * A back handler whose gesture can be watched (UI2.md 3.8). While the finger
 * moves, [progress] follows it 1:1; releasing past the threshold calls
 * [onBack]; cancelling springs [progress] back to 0 (fastSpatial).
 *
 * After a commit [progress] stays where the finger left it when the content
 * leaves with it (a screen, the 1:1 viewer), and springs back to 0 when
 * [resetOnCommit] is true (content that stays, such as a panel that folds).
 *
 * Register only while there is something in the app to go back to: an
 * always-enabled handler at the root turns off the system's back-to-home
 * animation.
 */
@Composable
fun PredictiveBack(
    enabled: Boolean = true,
    progress: BackProgress? = null,
    resetOnCommit: Boolean = false,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val commit by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { e ->
                if (progress != null) {
                    progress.edge = e.swipeEdge
                    progress.anim.snapTo(BackEase.transform(e.progress.coerceIn(0f, 1f)))
                }
            }
            commit()
            if (progress != null && resetOnCommit) scope.launch { progress.anim.animateTo(0f, Motion.defaultSpatial()) }
        } catch (e: CancellationException) {
            if (progress != null) scope.launch { progress.anim.animateTo(0f, Motion.fastSpatial()) }
            throw e
        }
    }
}

/**
 * The predictive-back preview of a full-screen surface: scales to
 * [minScale] (90 %), shifts away from the swiped edge by up to
 * width / 20 − 8 dp, and rounds its corners, all in the draw phase.
 */
fun Modifier.predictiveBack(progress: BackProgress, minScale: Float = 0.9f, corner: Dp = 28.dp): Modifier =
    graphicsLayer {
        val p = progress.value
        val k = 1f - (1f - minScale) * p
        scaleX = k; scaleY = k
        val shift = (size.width / 20f - 8.dp.toPx()).coerceAtLeast(0f)
        translationX = shift * p * if (progress.edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        if (p > 0f) {
            shape = RoundedCornerShape(corner * p)
            clip = true
        }
    }

// --- motion off --------------------------------------------------------------

/**
 * True while the system's "remove animations" is on (animator duration scale
 * 0), re-read on every resume. Springs and transitions already jump to their
 * end then; check this only in hand-written frame loops (withFrameNanos,
 * infinite transitions) and stop them (UI2.md 3.9).
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var off by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) off = !ValueAnimator.areAnimatorsEnabled()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return off
}
