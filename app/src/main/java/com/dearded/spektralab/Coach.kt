package com.dearded.spektralab

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * First-run guide (docs/UI.md 2.7): at most four hints, each pinned to the
 * control it is about and shown the first time that control is on screen,
 * one at a time. A tap anywhere moves on; 跳过全部 ends the guide for good,
 * and 设置 → 重新查看引导 brings it back.
 *
 * Hints never use fixed coordinates -- the last guide pointed at the wrong
 * side of the screen for exactly that reason. Each control reports its own
 * bounds through [coachAnchor]; a control scrolled away, on another page or
 * faded out by immersive mode simply has no hint until it is back.
 */
enum class CoachStep(val text: String) {
    STYLE("点风格套用，再点一次调强度"),
    HOLD("按住照片看原图"),
    RESET("双击滑杆归零"),
    SAVE("导出到相册"),
}

class CoachState(context: Context) {
    private val prefs = context.getSharedPreferences("settings", 0)
    internal val anchors = mutableStateMapOf<CoachStep, Rect>()
    private val seen = mutableStateMapOf<CoachStep, Boolean>().apply {
        for (s in CoachStep.entries) put(s, prefs.getBoolean(key(s), false))
    }
    private var skipped by mutableStateOf(prefs.getBoolean(SKIP, false))

    /** Set by the editor: a photo is open, nothing is loading, the chrome is up. */
    var allowed by mutableStateOf(false)

    /** The next hint whose control is on screen now, if any. */
    val current: CoachStep?
        get() = if (skipped || !allowed) null else CoachStep.entries.firstOrNull { s ->
            seen[s] != true && anchors[s]?.let { it.width > 1f && it.height > 1f } == true
        }

    fun done(step: CoachStep) {
        seen[step] = true
        prefs.edit().putBoolean(key(step), true).apply()
    }

    fun skipAll() {
        skipped = true
        prefs.edit().putBoolean(SKIP, true).apply()
    }

    fun replay() {
        skipped = false
        val e = prefs.edit().putBoolean(SKIP, false)
        for (s in CoachStep.entries) { seen[s] = false; e.putBoolean(key(s), false) }
        e.apply()
    }

    private fun key(s: CoachStep) = "coach_" + s.name.lowercase()

    private companion object { const val SKIP = "coach_skip" }
}

val LocalCoach = staticCompositionLocalOf<CoachState?> { null }

/** Reports this control's on-screen bounds as the target of [step]'s hint. */
fun Modifier.coachAnchor(step: CoachStep): Modifier = composed {
    val coach = LocalCoach.current ?: return@composed Modifier
    DisposableEffect(coach, step) { onDispose { coach.anchors.remove(step) } }
    // boundsInRoot is clipped by every parent, so a control scrolled out of
    // its column comes back empty and gets no hint.
    onGloballyPositioned { coach.anchors[step] = it.boundsInRoot() }
}

/** Provides [LocalCoach] to [content] and draws the hint over it. */
@Composable
fun CoachHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val coach = remember(context) { CoachState(context.applicationContext) }
    CompositionLocalProvider(LocalCoach provides coach) {
        Box(Modifier.fillMaxSize()) {
            content()
            CoachOverlay(coach)
        }
    }
}

@Composable
private fun CoachOverlay(coach: CoachState) {
    val step = coach.current
    // A short settle, so a hint never flashes up while a photo is still opening
    // or a page is sliding in.
    var shown by remember { mutableStateOf<CoachStep?>(null) }
    LaunchedEffect(step) {
        if (step == null) { shown = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(if (shown == null) 700 else 250)
        shown = step
    }
    val visible = shown != null && shown == step
    AnimatedVisibility(visible, enter = fadeIn(Motion.defaultEffects()), exit = fadeOut(Motion.fastEffects())) {
        val s = shown ?: return@AnimatedVisibility
        val target = coach.anchors[s] ?: return@AnimatedVisibility
        CoachMark(s, target, index = s.ordinal + 1, onNext = { coach.done(s) }, onSkip = coach::skipAll)
    }
}

@Composable
private fun CoachMark(step: CoachStep, target: Rect, index: Int, onNext: () -> Unit, onSkip: () -> Unit) {
    val density = LocalDensity.current
    val pad = with(density) { 6.dp.toPx() }
    val hole = Rect(target.left - pad, target.top - pad, target.right + pad, target.bottom + pad)
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(step) { detectTapGestures { onNext() } },
    ) {
        val full = with(density) { Rect(0f, 0f, maxWidth.toPx(), maxHeight.toPx()) }
        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(full)
                addRoundRect(RoundRect(hole, CornerRadius(12.dp.toPx())))
            }
            drawPath(path, Color.Black.copy(alpha = 0.62f))
            drawRoundRect(
                Palette.Accent, topLeft = hole.topLeft, size = hole.size,
                cornerRadius = CornerRadius(12.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()),
            )
        }
        // Below the control if it fits, else above, else over its middle
        // (the photo itself fills most of the screen).
        var bubble by remember { mutableStateOf(IntSize.Zero) }
        val gap = with(density) { 12.dp.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        val y = when {
            hole.bottom + gap + bubble.height <= full.bottom - margin -> hole.bottom + gap
            hole.top - gap - bubble.height >= full.top + margin -> hole.top - gap - bubble.height
            else -> hole.center.y - bubble.height / 2f
        }
        val x = (hole.center.x - bubble.width / 2f)
            .coerceIn(margin, (full.right - margin - bubble.width).coerceAtLeast(margin))
        Column(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .onSizeChanged { bubble = it }
                .widthIn(max = 280.dp)
                .background(Palette.PanelHighest, RoundedCornerShape(14.dp))
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(step.text, style = MaterialTheme.typography.bodyLarge, color = Palette.OnSurface)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("$index/${CoachStep.entries.size}",
                    style = MaterialTheme.typography.labelSmall, color = Palette.OnSurfaceSoft)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onSkip) { Text("跳过", color = Palette.OnSurface) }
            }
        }
    }
}
