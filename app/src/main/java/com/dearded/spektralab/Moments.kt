package com.dearded.spektralab

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color

/*
 * Moments (tools/work/ui2_reactbits.md 3.4): the few places where the app
 * finishes something the user asked for. Rebuilt from the behaviour of React
 * Bits components, never from their source (MIT + Commons Clause does not mix
 * with GPL-3.0, ui2_reactbits.md 1).
 *
 * Every moment follows the same rules: it is bound to real work (no step waits
 * for an animation), it never draws on or next to the photo, it is one neutral
 * ink (no hue), and with animations off it shows the end state at once.
 */

/**
 * The 保存 button's icon (after React Bits' StatusMark): a check at rest; once
 * an export from this button is in the queue, a thin ring whose arc is the
 * job's real progress; when the file is in the gallery the check draws itself
 * in (fastEffects, under 250 ms) with a CONFIRM tick, and a failure shows the
 * check again with a REJECT tick. The editor goes on working throughout.
 *
 * [jobs] is the queue, [since] the time the button last queued something
 * (0 = nothing yet): only jobs created after it are this button's.
 */
@Composable
fun SaveMark(jobs: List<ProcessingJob>, since: Long, size: Dp = 18.dp) {
    val job = if (since <= 0L) null else jobs.filter { it.createdAt >= since - 50 }.maxByOrNull { it.createdAt }
    val ink = LocalContentColor.current
    val haptic = rememberHaptic()
    // The check's drawn length, 0..1. It stays 1 at rest.
    val check = remember { Animatable(1f) }
    var seenActive by remember { mutableStateOf<Long?>(null) }
    val active = job?.active == true
    LaunchedEffect(job?.id, job?.state) {
        val j = job ?: return@LaunchedEffect
        when {
            j.active -> { seenActive = j.id; check.snapTo(0f) }
            // Only a job this mark watched running gets the ending; an old one shows the plain check.
            seenActive == j.id && j.state == JobState.DONE -> {
                seenActive = null
                haptic(HapticEvent.Confirm)
                check.snapTo(0f)
                check.animateTo(1f, Motion.fastEffects())
            }
            seenActive == j.id -> {
                seenActive = null
                if (j.state == JobState.FAILED) haptic(HapticEvent.Reject)
                check.snapTo(1f)
            }
        }
    }
    // Real progress, smoothed only between the queue's own updates.
    val arc by animateFloatAsState(if (active) job!!.progress.coerceIn(0f, 1f) else 0f, Motion.defaultEffects(), label = "save-arc")
    val ring by animateFloatAsState(if (active) 1f else 0f, Motion.fastEffects(), label = "save-ring")
    val path = remember { Path() }
    val piece = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = 2f * u
        if (ring > 0f) {
            val inset = w / 2f + 2f * u
            val box = androidx.compose.ui.geometry.Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            val tl = Offset(inset, inset)
            drawArc(ink.copy(alpha = 0.3f * ring), 0f, 360f, false, tl, box, style = Stroke(w))
            // A sliver from the start, so a queued job reads as "started", not as an empty ring.
            val sweep = 360f * maxOf(arc, 0.04f)
            drawArc(ink.copy(alpha = ring), -90f, sweep, false, tl, box, style = Stroke(w, cap = StrokeCap.Round))
        }
        val c = check.value
        if (ring < 1f && c > 0f) {
            path.reset()
            path.moveTo(5f * u, 12.5f * u)
            path.lineTo(10f * u, 17.5f * u)
            path.lineTo(19f * u, 7f * u)
            measure.setPath(path, false)
            piece.reset()
            measure.getSegment(0f, measure.length * c, piece, true)
            drawPath(piece, ink.copy(alpha = 1f - ring), style = Stroke(w * 1.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * The editor's and the light table's messages (after React Bits' SwipeToast),
 * in place of SnackbarHost: a pill at the bottom with a thin line along its
 * lower edge that shortens as its time runs out. Pull it down to dismiss it;
 * it follows the finger 1:1 and, let go past a third of its height or flung,
 * leaves on defaultSpatial, else springs back. Under a finger it never times
 * out. TalkBack's recommended timeout applies, and it reads as a polite live
 * region with a dismiss action. With animations off the line stays full and
 * the message still goes when its time is up.
 */
@Composable
fun SwipeNotice(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val data = state.currentSnackbarData
    // The last message, so it can still draw while it leaves.
    var shown by remember { mutableStateOf<SnackbarData?>(null) }
    if (data != null) shown = data
    val a11y = LocalAccessibilityManager.current
    val off = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    var height by remember { mutableFloatStateOf(1f) }
    var held by remember { mutableStateOf(false) }
    var startAt by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(0L) }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(data) {
        val d = data ?: return@LaunchedEffect
        drag.snapTo(0f)
        val base = when (d.visuals.duration) {
            SnackbarDuration.Short -> 4000L
            SnackbarDuration.Long -> 10000L
            SnackbarDuration.Indefinite -> Long.MAX_VALUE
        }
        total = if (base == Long.MAX_VALUE) base else a11y?.calculateRecommendedTimeoutMillis(
            base, containsIcons = false, containsText = true, containsControls = d.visuals.actionLabel != null,
        ) ?: base
        startAt = SystemClock.uptimeMillis()
        if (total == Long.MAX_VALUE) return@LaunchedEffect
        // Frames only drive the line; the timeout itself is a plain delay, so it holds with animations off.
        val frames = if (!off) launch { while (true) withFrameMillis { frame = it } } else null
        delay(total)
        snapshotFlow { held }.first { !it }
        frames?.cancel()
        d.dismiss()
    }

    val dragState = rememberDraggableState { dy ->
        // Down only, 1:1; upwards it stops at its place.
        scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) }
    }
    AnimatedVisibility(
        visible = data != null,
        modifier = modifier,
        enter = slideInVertically(Motion.defaultSpatialOffset()) { it } + fadeIn(Motion.defaultEffects()),
        exit = fadeOut(Motion.fastEffects()),
        label = "notice",
    ) {
        val d = shown ?: return@AnimatedVisibility
        val shape = Corners.m
        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier
                    .widthIn(max = 600.dp).fillMaxWidth()
                    .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
                    .graphicsLayer {
                        translationY = drag.value
                        alpha = 1f - (drag.value / height).coerceIn(0f, 1f) * 0.6f
                    }
                    .clip(shape)
                    .background(Palette.PanelHighest)
                    .drawWithContent {
                        drawContent()
                        if (total != Long.MAX_VALUE && total > 0) {
                            frame // read here, so only the draw phase repeats
                            val left = if (off || held) 1f
                                else 1f - ((SystemClock.uptimeMillis() - startAt).toFloat() / total).coerceIn(0f, 1f)
                            val w = 2.dp.toPx()
                            drawRect(
                                Palette.OnSurface.copy(alpha = 0.7f),
                                topLeft = Offset(0f, size.height - w),
                                size = androidx.compose.ui.geometry.Size(size.width * left, w),
                            )
                        }
                    }
                    .draggable(
                        dragState, Orientation.Vertical,
                        onDragStarted = { held = true },
                        onDragStopped = { v ->
                            held = false
                            if (drag.value > height / 3f || v > 1200f) {
                                drag.settle(height * 2f, v)
                                d.dismiss()
                            } else drag.settle(0f, v)
                        },
                    )
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        dismiss { d.dismiss(); true }
                    }
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    d.visuals.message, style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurface,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                d.visuals.actionLabel?.let { label ->
                    TextButton(onClick = { d.performAction() }) { Text(label, color = Palette.OnSurface) }
                }
            }
        }
    }
}

/**
 * The editor's "still developing" line along the photo's top edge, in place of
 * the coloured AuraBar there (ui2_reactbits.md 8.2, rule A: nothing coloured
 * next to a print being judged). A 2 dp neutral track with one short
 * near-white segment passing along it, no hue. With animations off the track
 * shows alone, still. The coloured AuraBar stays where no photo is on screen.
 */
@Composable
fun WorkLine(modifier: Modifier = Modifier) {
    val moving = remember { motionAllowed() }
    val clock = if (moving) {
        rememberInfiniteTransition(label = "work-line").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "t",
        )
    } else null
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawRect(Color.White.copy(alpha = 0.12f))
        val t = clock?.value ?: return@Canvas
        val seg = size.width * 0.22f
        val x = -seg + (size.width + seg) * t
        drawRect(Color(0xFFF2F2F2).copy(alpha = 0.7f), Offset(x, 0f), androidx.compose.ui.geometry.Size(seg, size.height))
    }
}
