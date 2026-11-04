package com.dearded.spektralab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow

/**
 * The editing slider (docs/UI.md 2.3): a 3 dp track with a tick at the
 * default and the fill drawn from it (so a two-way slider fills from the
 * middle), a 20 dp thumb that grows to 24 dp and shows its value in a bubble
 * while dragged, and ↺ only once the value has moved.
 *
 * Gestures: a drag moves the thumb by the finger's travel, not to the finger;
 * holding the finger more than 40 dp above or below the track slows it to a
 * quarter for fine work; a tap beside the thumb steps one unit that way
 * (Lightroom); a double tap on the thumb or the label row resets. Haptics only
 * on reaching the default or an end. TalkBack reads it as an adjustable value
 * with a "重置" custom action.
 *
 * The value lives in this slider's own state, so the thumb follows the finger
 * without waiting for the model: the thumb, fill and bubble read it in the draw
 * phase, and per move only this slider's readout recomposes, not the page. The
 * model still hears every change through [onChange] (the preview has to
 * follow), and [onDrag] brackets a drag.
 */
@Composable
internal fun LabeledSlider(
    label: String, value: Double, min: Double, max: Double, default: Double,
    enabled: Boolean, format: String, onDrag: (Boolean) -> Unit = {}, onChange: (Double) -> Unit,
) {
    val view = LocalView.current
    val lo = min.toFloat()
    val hi = max.toFloat()
    val def = default.toFloat().coerceIn(lo, hi)
    val span = (hi - lo).coerceAtLeast(1e-6f)
    val step = remember(format, span) { stepFor(format, span) }
    // One state for the slider's life: the gesture loop keeps writing to it
    // across recompositions. A value from elsewhere (undo, a preset) replaces
    // it, except mid-drag, where the model's echo lags the finger.
    var local by remember { mutableFloatStateOf(value.toFloat().coerceIn(lo, hi)) }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(value, lo, hi) { if (!pressed) local = value.toFloat().coerceIn(lo, hi) }
    var lastThumbTap by remember { mutableLongStateOf(0L) }
    val onChangeLatest by rememberUpdatedState(onChange)
    val onDragLatest by rememberUpdatedState(onDrag)
    val recede = LocalSliderDrag.current
    val changed = abs(local - def) > 1e-6f

    fun text(v: Float) = String.format(Locale.ROOT, format, v.toDouble())

    /** Move to [v]; a tick on arriving at the default or an end. */
    fun moveTo(v: Float) {
        val before = local
        if (v == before) return
        val arrived = (v == def && before != def) || (v == lo || v == hi)
        if (arrived) view.tick(strong = true)
        local = v
        onChangeLatest(v.toDouble())
    }
    fun reset() {
        if (local == def) return
        view.tick(strong = true)
        local = def
        onChangeLatest(default)
    }

    Column {
        Row(
            Modifier.fillMaxWidth().pointerInput(enabled, def) {
                if (enabled) detectTapGestures(onDoubleTap = { reset() })
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(text(local), style = Readout,
                color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            // The space is kept when hidden, so the readout does not jump as ↺ comes and goes.
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (changed && enabled) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).clickable { reset() }
                            .semantics { contentDescription = "重置$label" },
                        contentAlignment = Alignment.Center,
                    ) { Text("↺", fontSize = 18.sp, color = Palette.OnSurfaceSoft) }
                }
            }
        }

        val measurer = rememberTextMeasurer()
        val bubbleStyle = TextStyle(fontSize = 12.sp, color = Palette.OnAccent, fontFeatureSettings = "tnum")
        Canvas(
            Modifier.fillMaxWidth().height(36.dp).systemGestureExclusion()
                .semantics {
                    contentDescription = label
                    stateDescription = text(local)
                    progressBarRangeInfo = ProgressBarRangeInfo(local, lo..hi)
                    if (!enabled) disabled()
                    else {
                        setProgress { target -> moveTo(snapToStep(target, lo, hi, step)); true }
                        customActions = listOf(CustomAccessibilityAction("重置") { reset(); true })
                    }
                }
                .pointerInput(enabled, lo, hi, def, step) {
                    if (!enabled) return@pointerInput
                    val r = 12.dp.toPx()
                    val fineBand = 40.dp.toPx()
                    val snap = span * 0.015f
                    val slop = viewConfiguration.touchSlop
                    fun xOf(v: Float) = r + (v - lo) / span * (size.width - 2 * r)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val onThumb = abs(down.position.x - xOf(local)) <= 24.dp.toPx()
                        val perPx = span / (size.width - 2 * r).coerceAtLeast(1f)
                        var dragging = false
                        var raw = local   // the unsnapped position, so the snap never sticks
                        var lastX = down.position.x
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            // The page's scroll took a vertical swipe first: it is not ours.
                            if (!dragging && c.isConsumed) return@awaitEachGesture
                            if (!dragging) {
                                if (abs(c.position.x - down.position.x) <= slop) continue
                                dragging = true
                                pressed = true
                                lastX = c.position.x
                                onDragLatest(true); recede(true)
                            }
                            val fine = abs(c.position.y - size.height / 2f) > fineBand
                            raw = (raw + (c.position.x - lastX) * perPx * (if (fine) 0.25f else 1f)).coerceIn(lo, hi)
                            lastX = c.position.x
                            moveTo(if (abs(raw - def) < snap) def else raw)
                            c.consume()
                        }
                        if (dragging) {
                            pressed = false
                            onDragLatest(false); recede(false)
                        } else if (onThumb) {
                            val now = down.uptimeMillis
                            if (now - lastThumbTap < viewConfiguration.doubleTapTimeoutMillis) { reset(); lastThumbTap = 0L }
                            else lastThumbTap = now
                        } else {
                            val dir = if (down.position.x > xOf(local)) 1 else -1
                            moveTo(snapToStep(local + dir * step, lo, hi, step))
                        }
                    }
                },
        ) {
            val r = 12.dp.toPx()
            val cy = size.height / 2f
            val track = 3.dp.toPx()
            val x0 = r
            val x1 = size.width - r
            fun xOf(v: Float) = x0 + (v - lo) / span * (x1 - x0)
            val fill = if (enabled) Palette.Accent else Palette.Outline
            drawRoundRect(if (enabled) Palette.Track else Palette.PanelHighest, Offset(x0, cy - track / 2), Size(x1 - x0, track), CornerRadius(track / 2))
            val xd = xOf(def)
            val xv = xOf(local)
            drawRect(fill, Offset(minOf(xd, xv), cy - track / 2), Size(abs(xv - xd), track))
            // The default's tick, 2 dp wide, standing proud of the track.
            drawRect(Palette.OnSurfaceSoft, Offset(xd - 1.dp.toPx(), cy - 5.dp.toPx()), Size(2.dp.toPx(), 10.dp.toPx()))
            val thumb = (if (pressed) 12.dp else 10.dp).toPx()
            drawCircle(if (enabled) Palette.OnSurface else Palette.Outline, thumb, Offset(xv, cy))
            if (pressed) {
                val layout = measurer.measure(text(local), bubbleStyle)
                val pad = 6.dp.toPx()
                val w = layout.size.width + 2 * pad
                val h = layout.size.height + pad
                val left = (xv - w / 2).coerceIn(0f, size.width - w)
                val top = cy - thumb - 6.dp.toPx() - h
                drawRoundRect(Palette.Accent, Offset(left, top), Size(w, h), CornerRadius(h / 2))
                drawText(layout, topLeft = Offset(left + pad, top + pad / 2))
            }
        }
    }
}

/** One step: a unit of the readout's last digit, at least 1/200 of the range, so a tap is visible. */
private fun stepFor(format: String, span: Float): Float {
    val decimals = Regex("""\.(\d+)f""").find(format)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val unit = 10.0.pow(-decimals).toFloat()
    return unit * ceil(span / 200f / unit).coerceAtLeast(1f)
}

private fun snapToStep(v: Float, lo: Float, hi: Float, step: Float): Float =
    (Math.round(v / step) * step).coerceIn(lo, hi)

/**
 * UI2.md 3.1: while a finger drags any slider, the top bar and the photo's
 * overlays fade, leaving the photo and the slider. The editor provides it.
 */
val LocalSliderDrag = androidx.compose.runtime.staticCompositionLocalOf<(Boolean) -> Unit> { {} }
