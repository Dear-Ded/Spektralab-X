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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow

/**
 * A precision ruler: moving ticks under a fixed pointer, numeric entry and
 * a default detent. The existing API keeps engine and history callbacks intact.
 *
 * Gestures: a drag moves the scale by the finger's travel;
 * holding the finger more than 40 dp above or below the track slows it to a
 * quarter for fine work; a tap beside the pointer steps one readout unit;
 * a double tap on the pointer or the label row resets. Soft detents are rate
 * limited; the default and end stops have distinct feedback. TalkBack reads it as an adjustable value
 * with a "重置" custom action.
 *
 * The value lives in this slider's own state, so the scale follows the finger
 * without waiting for the model. Tick positions are read in the draw phase. The
 * model still hears every change through [onChange] (the preview has to
 * follow), and [onDrag] brackets a drag.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun LabeledSlider(
    label: String, value: Double, min: Double, max: Double, default: Double,
    enabled: Boolean, format: String, onDrag: (Boolean) -> Unit = LocalParameterDrag.current,
    /** Shown under the track while the slider is off: why it cannot be used (UI2.md 4, question 3). */
    disabledReason: String = "",
    /** Words under the two ends of the track, e.g. 偏黄 / 偏蓝: which way each end moves the picture. */
    ends: Pair<String, String>? = null,
    onChange: (Double) -> Unit,
) {
    val view = LocalView.current
    val lo = min.toFloat()
    val hi = max.toFloat()
    val def = default.toFloat().coerceIn(lo, hi)
    val span = (hi - lo).coerceAtLeast(1e-6f)
    val step = remember(format) { stepFor(format) }
    val exposureScale = format.contains("EV") && span <= 20f
    val majorStep = remember(span, step, exposureScale) { if (exposureScale) 1f else rulerMajorStep(span, step) }
    val subdivisions = if (exposureScale) 3 else 5
    val detentStep = maxOf(step, majorStep / subdivisions)
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rulerBed = remember {
        Brush.verticalGradient(listOf(Palette.Background, Palette.Surface, Palette.Background))
    }
    val tickPaint = remember(context, density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = UiFonts.typeface(context, UiFonts.MONO, 400)
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = with(density) { 12.sp.toPx() }
        }
    }
    val tickLabels = remember(lo, hi, majorStep) {
        val decimals = if (majorStep < 1f) ceil(-kotlin.math.log10(majorStep)).toInt().coerceIn(0, 4) else 0
        val first = kotlin.math.floor(lo / majorStep).toInt()
        val last = ceil(hi / majorStep).toInt()
        (first..last).associateWith { i ->
            val number = i * majorStep
            String.format(Locale.ROOT, if (number > 0f && lo < 0f) "%+.${decimals}f" else "%.${decimals}f", number)
        }
    }
    // One state for the slider's life: the gesture loop keeps writing to it
    // across recompositions. A value from elsewhere (undo, a preset) replaces
    // it, except mid-drag, where the model's echo lags the finger.
    var local by remember { mutableFloatStateOf(value.toFloat().coerceIn(lo, hi)) }
    var pressed by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
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
        when {
            v == lo || v == hi -> view.haptic(HapticEvent.Edge)
            (before < def && v >= def) || (before > def && v <= def) -> view.haptic(HapticEvent.Tick)
            kotlin.math.floor(v / detentStep) != kotlin.math.floor(before / detentStep) -> view.haptic(HapticEvent.Detent)
        }
        local = v
        onChangeLatest(v.toDouble())
    }
    fun reset() {
        if (local == def) return
        view.haptic(HapticEvent.Confirm)
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
            // UI2.md 7.2 point 6: name left, readout right in tabular figures; a moved
            // value turns white 92 % and gets the changed dot, never a hue.
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Palette.OnSurface else Palette.OnSurfaceSoft.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f))
            // The ↺ at the row's end is the changed mark (ui2_icecream.md 1); the dot is left
            // only for a moved value that cannot be reset right now (the slider is off).
            if (changed && !enabled) { EditedDot(); androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp)) }
            // Tap the readout to type a value (Silverphase RulerParameterRow, upstream ScrubSlider).
            Box(
                Modifier.heightIn(min = 48.dp).clip(Corners.m)
                    .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button) { typing = true }
                    .semantics { contentDescription = tr("输入{0}", label) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text(local), style = Readout,
                    color = if (changed && enabled) Palette.OnSurface else Palette.OnSurfaceSoft)
            }
            // The space is kept when hidden, so the readout does not jump as ↺ comes and goes.
            // A visible reset for double-tap (UI2.md 3.3): 48 dp, in white 92 % so it reads as "moved".
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (changed && enabled) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button) { reset() }
                            .semantics { contentDescription = tr("重置{0}", label) },
                        contentAlignment = Alignment.Center,
                    ) { Text("↺", fontSize = 18.sp, color = Palette.OnSurface) }
                }
            }
        }

        Canvas(
            Modifier.fillMaxWidth().height(64.dp).systemGestureExclusion()
                .semantics {
                    contentDescription = label
                    stateDescription = text(local)
                    progressBarRangeInfo = ProgressBarRangeInfo(local, lo..hi)
                    if (!enabled) disabled()
                    else {
                        setProgress { target ->
                            if (!target.isFinite()) false
                            else { moveTo(snapToStep(target, lo, hi, step)); true }
                        }
                        customActions = listOf(CustomAccessibilityAction(tr("重置")) { reset(); true })
                    }
                }
                .pointerInput(enabled, lo, hi, def, step) {
                    if (!enabled) return@pointerInput
                    val fineBand = 40.dp.toPx()
                    val snap = minOf(step / 2f, span * 0.002f)
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.isConsumed) return@awaitEachGesture
                        val onThumb = abs(down.position.x - size.width / 2f) <= 24.dp.toPx()
                        val perPx = span * 0.4f / size.width.coerceAtLeast(1)
                        var dragging = false
                        var released = false
                        var raw = local   // the unsnapped position, so the snap never sticks
                        var lastX = down.position.x
                        try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) { released = true; break }
                            if (event.changes.count { it.pressed } > 1) break
                            // The page's scroll took a vertical swipe first: it is not ours.
                            if (!dragging && c.isConsumed) return@awaitEachGesture
                            if (!dragging) {
                                if (abs(c.position.x - down.position.x) <= slop) continue
                                dragging = true
                                pressed = true
                                lastX = down.position.x + (if (c.position.x > down.position.x) slop else -slop)
                                onDragLatest(true); recede(true)
                            }
                            val fine = abs(c.position.y - size.height / 2f) > fineBand
                            raw = (raw - (c.position.x - lastX) * perPx * (if (fine) 0.25f else 1f)).coerceIn(lo, hi)
                            lastX = c.position.x
                            moveTo(if (abs(raw - def) < snap) def else raw)
                            c.consume()
                        }
                        } finally {
                        if (dragging) {
                            pressed = false
                            try { onDragLatest(false) } finally { recede(false) }
                        }
                        }
                        if (!released) return@awaitEachGesture
                        if (!dragging && onThumb) {
                            val now = down.uptimeMillis
                            if (lastThumbTap != 0L && now - lastThumbTap < viewConfiguration.doubleTapTimeoutMillis) { reset(); lastThumbTap = 0L }
                            else lastThumbTap = now
                        } else if (!dragging) {
                            val dir = if (down.position.x > size.width / 2f) 1 else -1
                            moveTo(snapToStep(local + dir * step, lo, hi, step))
                        }
                    }
                },
        ) {
            val visible = span * 0.4f
            val pixelsPerUnit = size.width / visible
            val tickUnit = detentStep
            val first = kotlin.math.floor((local - visible / 2) / tickUnit).toInt()
            val last = ceil((local + visible / 2) / tickUnit).toInt()
            val cy = 23.dp.toPx()
            drawRoundRect(rulerBed,
                cornerRadius = CornerRadius(3.dp.toPx()))
            drawLine(Palette.OutlineSoft, Offset(0f, 1.dp.toPx()), Offset(size.width, 1.dp.toPx()), 0.5.dp.toPx())
            drawLine(Palette.OutlineSoft, Offset(0f, size.height - 1.dp.toPx()),
                Offset(size.width, size.height - 1.dp.toPx()), 0.5.dp.toPx())
            for (i in first..last) {
                val v = i * tickUnit
                if (v < lo - tickUnit * 0.01f || v > hi + tickUnit * 0.01f) continue
                val x = size.width / 2 + (v - local) * pixelsPerUnit
                val edgeFade = (minOf(x, size.width - x) / 20.dp.toPx()).coerceIn(0f, 1f)
                val major = i % subdivisions == 0
                val h = (if (major) 22.dp else 12.dp).toPx()
                val ink = if (enabled) Palette.OnSurface.copy(alpha = (if (major) 0.82f else 0.40f) * edgeFade)
                    else Palette.Track.copy(alpha = 0.45f * edgeFade)
                drawLine(Color.Black.copy(alpha = 0.7f * edgeFade), Offset(x + 0.5.dp.toPx(), cy - h / 2),
                    Offset(x + 0.5.dp.toPx(), cy + h / 2 + 0.5.dp.toPx()), 1.5.dp.toPx())
                drawLine(ink, Offset(x, cy - h / 2), Offset(x, cy + h / 2),
                    (if (major) 1.2.dp else 0.75.dp).toPx())
                if (major) {
                    tickPaint.color = android.graphics.Color.argb(((if (enabled) 210 else 100) * edgeFade).toInt(), 210, 210, 214)
                    val label = tickLabels[i / subdivisions].orEmpty()
                    val halfText = tickPaint.measureText(label) / 2
                    if (x - halfText >= 2.dp.toPx() && x + halfText <= size.width - 2.dp.toPx())
                        drawContext.canvas.nativeCanvas.drawText(label, x, 54.dp.toPx(), tickPaint)
                }
            }
            val xd = size.width / 2 + (def - local) * pixelsPerUnit
            if (xd in 0f..size.width) drawCircle(Palette.OnSurfaceSoft, 1.5.dp.toPx(), Offset(xd, 5.dp.toPx()))
            val needle = if (enabled) Palette.Darkroom else Palette.Track
            drawRoundRect(Palette.Background, Offset(size.width / 2 - 3.dp.toPx(), cy - 16.dp.toPx()),
                Size(6.dp.toPx(), 32.dp.toPx()), CornerRadius(2.dp.toPx()))
            drawRoundRect(needle, Offset(size.width / 2 - 1.dp.toPx(), cy - 15.dp.toPx()),
                Size(2.dp.toPx(), 30.dp.toPx()), CornerRadius(1.dp.toPx()))
            drawLine(needle.copy(alpha = 0.5f), Offset(size.width / 2 - 4.dp.toPx(), 7.dp.toPx()),
                Offset(size.width / 2 + 4.dp.toPx(), 7.dp.toPx()), 1.dp.toPx())
        }
        // Neutral words, never a hue: the direction is told in text (UI2.md 1.1).
        if (ends != null) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(ends.first, style = MaterialTheme.typography.labelSmall, color = Palette.OnSurfaceSoft)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Text(ends.second, style = MaterialTheme.typography.labelSmall, color = Palette.OnSurfaceSoft)
        }
        if (!enabled && disabledReason.isNotBlank()) Hint(disabledReason)
    }

    // Parse the complete entry so a comma or a Unicode minus cannot silently
    // become a different value. Out-of-range entries remain editable.
    if (typing) {
        val entry = androidx.compose.foundation.text.input.rememberTextFieldState(
            initialText = String.format(Locale.ROOT, "%.4f", local.toDouble()).trimEnd('0').trimEnd('.'))
        val parsed = entry.text.toString().trim().replace('\u2212', '-').replace('\uFF0D', '-')
            .replace('\uFF0B', '+').replace('\uFF0E', '.').toFloatOrNull()?.takeIf { it.isFinite() }
        val valid = parsed != null && parsed in lo..hi
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text(label) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        state = entry,
                        lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                        inputTransformation = androidx.compose.foundation.text.input.InputTransformation {
                            if (length > 12) revertAllChanges()
                        },
                        isError = !valid,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!valid) Text(tr("请输入范围内的有效数值"),
                        color = Palette.Warning, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp))
                    Text(tr("范围 {0} 到 {1}，默认 {2}", text(lo), text(hi), text(def)),
                        style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft,
                        modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = valid, onClick = {
                    parsed?.takeIf { it in lo..hi }?.let { moveTo(it) }
                    typing = false
                }) { Text(tr("应用数值")) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { typing = false }) { Text(tr("取消")) } },
        )
    }
}

/** Taps and accessibility can reach the readout's last digit, including 0.01 EV. */
private fun stepFor(format: String): Float {
    val decimals = Regex("""\.(\d+)f""").find(format)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val unit = 10.0.pow(-decimals).toFloat()
    return unit
}

private fun rulerMajorStep(span: Float, step: Float): Float {
    val target = span / 10f
    val decade = 10.0.pow(kotlin.math.floor(kotlin.math.log10(target.toDouble()))).toFloat()
    val unit = target / decade
    return maxOf(step, decade * when { unit <= 1f -> 1f; unit <= 2f -> 2f; unit <= 5f -> 5f; else -> 10f })
}

private fun snapToStep(v: Float, lo: Float, hi: Float, step: Float): Float =
    (Math.round(v / step) * step).coerceIn(lo, hi)

/**
 * UI2.md 3.1: while a finger drags any slider, the top bar and the photo's
 * overlays fade, leaving the photo and the slider. The editor provides it.
 */
val LocalSliderDrag = androidx.compose.runtime.staticCompositionLocalOf<(Boolean) -> Unit> { {} }
internal val LocalParameterDrag = androidx.compose.runtime.staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * 欠曝 / 正常 / 过曝 from the print on screen (ui2_icecream.md 6.6): the mean of the
 * luminance bins, with clipping deciding first. Rough on purpose: it says which way to
 * look, it does not meter. Bins are encoded values (Histogram), so the mean of a
 * well-exposed print sits around the middle.
 */
internal fun exposureVerdict(h: Histogram): String {
    if (h.clippedHigh > 0.05f && h.clippedHigh > h.clippedLow) return tr("偏亮，高光有溢出")
    if (h.clippedLow > 0.10f && h.clippedLow > h.clippedHigh) return tr("偏暗，暗部有溢出")
    var n = 0L; var sum = 0.0
    h.y.forEachIndexed { i, c -> n += c; sum += c * (i + 0.5) }
    if (n == 0L) return tr("正常")
    val mean = sum / n / Histogram.BINS
    return when {
        mean < 0.28 -> tr("偏暗")
        mean > 0.68 -> tr("偏亮")
        else -> tr("正常")
    }
}
