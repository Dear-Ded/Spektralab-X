package com.dearded.spektralab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private val WheelInset = 12.dp

/** Screen coordinates: zero is right, positive angles turn clockwise. */
internal fun colorWheelZoneAt(position: Offset, center: Offset, radius: Float, previous: Zone): Zone {
    if (radius <= 0f) return previous
    val delta = position - center
    val distance = delta.getDistance()
    val hue = if (distance < 0.0001f) previous.hue else
        (atan2(delta.y.toDouble(), delta.x.toDouble()) * 180.0 / PI + 360.0) % 360.0
    return previous.copy(hue = hue, sat = (distance / radius * 100.0).coerceIn(0.0, 100.0))
}

internal fun colorWheelThumbAt(value: Zone, center: Offset, radius: Float): Offset {
    val angle = value.hue * PI / 180.0
    val reach = radius.coerceAtLeast(0f) * (value.sat / 100.0).coerceIn(0.0, 1.0)
    return center + Offset((cos(angle) * reach).toFloat(), (sin(angle) * reach).toFloat())
}

/** A 192 dp wheel; numeric H/S/L sliders can be supplied by the calling panel. */
@Composable
internal fun ColorWheel(
    label: String,
    value: Zone,
    enabled: Boolean,
    onDrag: (Boolean) -> Unit,
    onChange: (Zone) -> Unit,
) {
    val view = LocalView.current
    var local by remember(label) { mutableStateOf(value) }
    var dragging by remember(label) { mutableStateOf(false) }
    val valueLatest by rememberUpdatedState(value)
    val onChangeLatest by rememberUpdatedState(onChange)
    val onDragLatest by rememberUpdatedState(onDrag)
    val recedeLatest by rememberUpdatedState(LocalSliderDrag.current)
    LaunchedEffect(value, label) { if (!dragging) local = value }

    val english = AppLocale.language == UiLanguage.ENGLISH
    val description = if (english) "$label colour wheel" else "$label 色轮"
    val resetLabel = tr("重置")
    val sweep = remember {
        Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan,
            Color.Blue, Color.Magenta, Color.Red))
    }
    val neutral = remember {
        Brush.radialGradient(listOf(Color(0xFFBFC0C3), Color(0x00BFC0C3)))
    }

    fun send(next: Zone) {
        if (next == local) return
        when {
            next.sat == 0.0 && local.sat > 0.0 -> view.haptic(HapticEvent.Tick)
            next.sat == 100.0 && local.sat < 100.0 -> view.haptic(HapticEvent.Edge)
            dragging && next.sat > 6.0 &&
                (kotlin.math.floor(next.hue / 15.0) != kotlin.math.floor(local.hue / 15.0) ||
                    kotlin.math.floor(next.sat / 10.0) != kotlin.math.floor(local.sat / 10.0)) ->
                view.haptic(HapticEvent.Detent)
        }
        local = next
        onChangeLatest(next)
    }

    fun discrete(next: Zone) {
        if (!enabled || dragging || next == local) return
        try {
            onDragLatest(true)
            recedeLatest(true)
            send(next)
        } finally {
            try { onDragLatest(false) } finally { recedeLatest(false) }
        }
    }

    fun resetSaturation() = discrete(local.copy(sat = 0.0, lum = valueLatest.lum))

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Palette.OnSurface else Palette.OnSurfaceSoft,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { resetSaturation() }, enabled = enabled && !dragging && local.sat != 0.0,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = if (english) "Reset $label saturation" else "重置${label}饱和度"
                },
            ) { Text(resetLabel) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // The marker keeps 12 dp of room beyond the colour circle, including at the rim.
            val side = minOf(216.dp, maxWidth)
            Canvas(
                Modifier.size(side)
                    .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
                    .drawWithCache {
                        val radius = (size.minDimension / 2f - WheelInset.toPx()).coerceAtLeast(0f)
                        val origin = Offset(size.width / 2f, size.height / 2f)
                        val fine = 0.75.dp.toPx()
                        val ring = Stroke(fine)
                        // Geometry stays cached while the cursor moves.
                        val ticks = Array(48) { index ->
                            val angle = index * PI / 24.0
                            val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                            val length = (if (index % 12 == 0) 6.dp else if (index % 6 == 0) 4.dp else 2.dp).toPx()
                            (origin + direction * (radius + 3.dp.toPx())) to
                                (origin + direction * (radius + 3.dp.toPx() + length))
                        }
                        onDrawBehind {
                            if (radius > 0f) {
                                drawCircle(Palette.Surface, radius + 10.dp.toPx(), origin)
                                drawCircle(Palette.OutlineSoft, radius + 10.dp.toPx(), origin, style = ring)
                                ticks.forEachIndexed { index, (from, to) ->
                                    drawLine(
                                        when {
                                            index == 0 -> Palette.Darkroom
                                            index % 12 == 0 -> Palette.OnSurfaceSoft
                                            index % 6 == 0 -> Palette.Track
                                            else -> Palette.Outline
                                        }, from, to, fine, cap = StrokeCap.Round,
                                    )
                                }
                            }
                        }
                    }
                    .semantics {
                        contentDescription = description
                        stateDescription = String.format(Locale.ROOT,
                            if (english) "Hue %.0f degrees, saturation %.1f percent, luminance %+.0f"
                            else "色相 %.0f 度，饱和度 %.1f%%，明亮度 %+.0f",
                            local.hue, local.sat, local.lum)
                        progressBarRangeInfo = ProgressBarRangeInfo(local.sat.toFloat().coerceIn(0f, 100f), 0f..100f)
                        if (!enabled) disabled() else {
                            setProgress { target ->
                                if (!target.isFinite()) false else {
                                    discrete(local.copy(sat = target.toDouble().coerceIn(0.0, 100.0), lum = valueLatest.lum))
                                    true
                                }
                            }
                            customActions = listOf(
                                CustomAccessibilityAction(resetLabel) { resetSaturation(); true },
                                CustomAccessibilityAction(if (english) "Decrease hue" else "减小色相") {
                                    discrete(local.copy(hue = (local.hue + 359.0) % 360.0, lum = valueLatest.lum)); true
                                },
                                CustomAccessibilityAction(if (english) "Increase hue" else "增大色相") {
                                    discrete(local.copy(hue = (local.hue + 1.0) % 360.0, lum = valueLatest.lum)); true
                                },
                            )
                        }
                    }
                    .pointerInput(enabled, label, side) {
                        if (!enabled) return@pointerInput
                        val inset = WheelInset.toPx()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = (minOf(size.width, size.height) / 2f - inset).coerceAtLeast(0f)
                        if (radius == 0f) return@pointerInput
                        val slop = viewConfiguration.touchSlop
                        var lastTapAt = 0L
                        var lastTapPosition = Offset.Zero
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (down.isConsumed || (down.position - center).getDistance() > radius) {
                                lastTapAt = 0L
                                return@awaitEachGesture
                            }
                            val onHandle = (down.position - colorWheelThumbAt(local, center, radius)).getDistance() <= 24.dp.toPx()
                            var started = false
                            var released = false
                            var upAt = 0L
                            var upPosition = down.position
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.isConsumed || event.changes.count { it.pressed } > 1) {
                                        lastTapAt = 0L
                                        break
                                    }
                                    if (!change.pressed) {
                                        released = true
                                        upAt = change.uptimeMillis
                                        upPosition = change.position
                                        break
                                    }
                                    if (!started) {
                                        val delta = change.position - down.position
                                        if (delta.getDistance() <= slop) continue
                                        // Vertical swipes on the background belong to the scrolling panel.
                                        if (!onHandle && abs(delta.y) > abs(delta.x)) {
                                            lastTapAt = 0L
                                            break
                                        }
                                        started = true
                                        dragging = true
                                        lastTapAt = 0L
                                        onDragLatest(true)
                                        recedeLatest(true)
                                    }
                                    change.consume()
                                    send(colorWheelZoneAt(change.position, center, radius, local.copy(lum = valueLatest.lum)))
                                }
                                // A single tap does not edit; two nearby taps only clear saturation.
                                if (!started && released && upAt - down.uptimeMillis <= viewConfiguration.longPressTimeoutMillis &&
                                    (upPosition - down.position).getDistance() <= slop) {
                                    if (lastTapAt != 0L && down.uptimeMillis - lastTapAt in 0L..viewConfiguration.doubleTapTimeoutMillis &&
                                        (down.position - lastTapPosition).getDistance() <= slop * 2f) {
                                        lastTapAt = 0L
                                        resetSaturation()
                                    } else {
                                        lastTapAt = upAt
                                        lastTapPosition = upPosition
                                    }
                                } else lastTapAt = 0L
                            } finally {
                                if (started) {
                                    dragging = false
                                    try { onDragLatest(false) } finally { recedeLatest(false) }
                                }
                            }
                        }
                    },
            ) {
                val radius = (size.minDimension / 2f - WheelInset.toPx()).coerceAtLeast(0f)
                if (radius <= 0f) return@Canvas
                inset(WheelInset.toPx()) {
                    drawCircle(sweep, radius)
                    drawCircle(neutral, radius)
                    drawCircle(Palette.Surround.copy(alpha = 0.12f), radius)
                }
                val fine = 0.75.dp.toPx()
                drawCircle(Palette.OnSurface.copy(alpha = 0.32f), radius, center, style = Stroke(fine))
                for (fraction in 1..2) {
                    drawCircle(Palette.Surface.copy(alpha = 0.22f), radius * fraction / 3f,
                        center, style = Stroke(1.5.dp.toPx()))
                    drawCircle(Palette.OnSurface.copy(alpha = 0.16f), radius * fraction / 3f,
                        center, style = Stroke(fine))
                }
                val rim = colorWheelThumbAt(local.copy(sat = 100.0), center, radius)
                val marker = colorWheelThumbAt(local, center, radius)
                drawLine(Palette.Surface.copy(alpha = 0.6f), center, rim, 2.dp.toPx())
                drawLine(Palette.OnSurface.copy(alpha = 0.58f), center, rim, fine)
                val cross = 4.dp.toPx()
                drawLine(Palette.Surface, center - Offset(cross, 0f), center + Offset(cross, 0f), 2.dp.toPx())
                drawLine(Palette.Surface, center - Offset(0f, cross), center + Offset(0f, cross), 2.dp.toPx())
                drawLine(Palette.OnSurface, center - Offset(cross, 0f), center + Offset(cross, 0f), fine)
                drawLine(Palette.OnSurface, center - Offset(0f, cross), center + Offset(0f, cross), fine)
                drawCircle(Palette.Surface, 1.5.dp.toPx(), center)
                val markerRadius = (if (dragging) 10.dp else 8.dp).toPx()
                // Dark outer edge and white inner ring stay legible over every hue.
                drawCircle(Palette.Surround, markerRadius + 1.dp.toPx(), marker)
                drawCircle(Palette.OnSurface, markerRadius - 1.dp.toPx(), marker)
                drawCircle(Palette.Surface, markerRadius - 3.dp.toPx(), marker)
                drawCircle(if (dragging) Palette.Darkroom else Palette.OnSurface, 1.5.dp.toPx(), marker)
            }
        }
        Text(tr("色相 {0}° · 饱和度 {1}%", String.format(Locale.ROOT, "%.0f", local.hue),
            String.format(Locale.ROOT, "%.1f", local.sat)),
            style = Readout, color = Palette.OnSurfaceSoft)
    }
}
