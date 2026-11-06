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
