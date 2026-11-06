package com.dearded.spektralab

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The palette pad (docs/UI.md 2.8, after iPhone Photographic Styles): colour
 * across (muted left, rich right), tone up and down (up airy, down deep), and
 * under it the palette slider, the finish's strength. [Look.resolved] turns
 * the point into shadows / highlights / contrast / black point and saturation
 * / vibrance; the pad only moves Layer 2, so a drag re-packs and never develops.
 *
 * The drag is relative (gain 0.6, so the finger never covers the dot); hold
 * still for a long-press first and it drops to 0.2 for fine work. The dot snaps
 * to the centre and to the style's home point with a tick; a double tap goes
 * home. [onDrag] brackets a gesture, which the editor's history takes as one step.
 *
 * TalkBack gets two ordinary sliders (色调, 色彩) instead of the pad; "数值"
 * shows the same sliders to anyone.
 */
@Composable
internal fun PalettePad(look: Look, enabled: Boolean, onDrag: (Boolean) -> Unit, onChange: (Look) -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    val talkBack = remember(context) {
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
    }
    var numbers by remember { mutableStateOf(false) }
    val lookLatest by rememberUpdatedState(look)
    val onChangeLatest by rememberUpdatedState(onChange)
    val onDragLatest by rememberUpdatedState(onDrag)
    val recede = LocalSliderDrag.current
    val atHome = look.tone == look.toneHome && look.colour == look.colourHome

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            String.format(Locale.ROOT, "色调 %+.0f · 色彩 %+.0f", look.tone, look.colour),
            style = Readout, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        if (!talkBack) TextButton(onClick = { numbers = !numbers }) { Text(if (numbers) "调色盘" else "数值") }
        TextButton(
            onClick = { view.tick(strong = true); onChange(look.copy(tone = look.toneHome, colour = look.colourHome)) },
            enabled = enabled && !atHome,
        ) { Text("复位") }
    }

    if (talkBack || numbers) {
        LabeledSlider("色调", look.tone, -100.0, 100.0, look.toneHome, enabled, "%+.0f", onDrag) {
            onChange(lookLatest.copy(tone = it))
        }
        LabeledSlider("色彩", look.colour, -100.0, 100.0, look.colourHome, enabled, "%+.0f", onDrag) {
            onChange(lookLatest.copy(colour = it))
        }
    } else {
        // The dot lives here while a finger is down, so the drag never waits for the model.
        var tone by remember { mutableFloatStateOf(look.tone.toFloat()) }
        var colour by remember { mutableFloatStateOf(look.colour.toFloat()) }
        var pressed by remember { mutableStateOf(false) }
        var fine by remember { mutableStateOf(false) }
        LaunchedEffect(look.tone, look.colour) {
            if (!pressed) { tone = look.tone.toFloat(); colour = look.colour.toFloat() }
        }
        val homeT = look.toneHome.toFloat()
        val homeC = look.colourHome.toFloat()

        fun send(t: Float, c: Float) {
            if (t == tone && c == colour) return
            val snapped = (t == 0f && c == 0f) || (t == homeT && c == homeC)
            val wasSnapped = (tone == 0f && colour == 0f) || (tone == homeT && colour == homeC)
            if (snapped && !wasSnapped) view.tick(strong = true)
            tone = t; colour = c
            onChangeLatest(lookLatest.copy(tone = t.toDouble(), colour = c.toDouble()))
        }

        Canvas(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).height(116.dp).systemGestureExclusion()
                .clearAndSetSemantics {
                    contentDescription = "调色盘"
                    stateDescription = String.format(Locale.ROOT, "色调 %+.0f，色彩 %+.0f", tone, colour)
                    if (enabled) customActions = listOf(
                        CustomAccessibilityAction("回到默认") { send(homeT, homeC); true },
                    )
                }
                .pointerInput(enabled, homeT, homeC) {
                    if (!enabled) return@pointerInput
                    val slop = viewConfiguration.touchSlop
                    val snapR = 5f
                    var lastTap = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        var long = false
                        var last = down.position
                        // Unsnapped position, so a snap never sticks.
                        var rawT = tone
                        var rawC = colour
                        val dot = Offset((colour + 100f) / 200f * size.width, (100f - tone) / 200f * size.height)
                        val onDot = hypot(down.position.x - dot.x, down.position.y - dot.y) <= 48.dp.toPx()
                        // Pad units (-100..100) per pixel: the long side spans 200 at gain 1.
                        val unitsX = 200f / size.width
                        val unitsY = 200f / size.height
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            if (!moved) {
                                if (c.isConsumed) return@awaitEachGesture
                                val dx = c.position.x - down.position.x
                                val dy = c.position.y - down.position.y
                                if (hypot(dx, dy) > slop && !onDot && !long && abs(dy) > abs(dx)) return@awaitEachGesture
                                if (hypot(dx, dy) <= slop) {
                                    if (!long && c.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) {
                                        long = true; fine = true; view.tick()
                                    }
                                    continue
                                }
                                // A finger held perfectly still sends no events, so the hold is also judged here.
                                if (!long && c.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) {
                                    long = true; fine = true; view.tick()
                                }
                                moved = true
                                pressed = true
                                last = c.position
                                onDragLatest(true); recede(true)
                                continue
                            }
                            val gain = if (long) 0.2f else 0.6f
                            rawC = (rawC + (c.position.x - last.x) * unitsX * gain).coerceIn(-100f, 100f)
                            rawT = (rawT - (c.position.y - last.y) * unitsY * gain).coerceIn(-100f, 100f)
                            last = c.position
                            val (t, col) = when {
                                hypot(rawT - homeT, rawC - homeC) < snapR -> homeT to homeC
                                hypot(rawT, rawC) < snapR -> 0f to 0f
                                else -> rawT to rawC
                            }
                            send(t, col)
                            c.consume()
                        }
                        fine = false
                        if (moved) {
                            pressed = false
                            onDragLatest(false); recede(false)
                        } else if (!long) {
                            val now = down.uptimeMillis
                            if (now - lastTap < viewConfiguration.doubleTapTimeoutMillis) { send(homeT, homeC); lastTap = 0L }
                            else lastTap = now
                        }
                    }
                },
        ) {
            val corner = CornerRadius(10.dp.toPx())
            // Colour: grey on the left to a rich warm-to-cool sweep on the right.
            drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF6E6A66), Color(0xFFB0705A), Color(0xFFD08A3A))),
                cornerRadius = corner)
            // Tone: airy at the top, deep at the bottom.
            drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent,
                Color.Black.copy(alpha = 0.6f))), cornerRadius = corner)
            val grid = Color.White.copy(alpha = 0.18f)
            for (i in 1..3) {
                drawLine(grid, Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height), 1.dp.toPx())
                drawLine(grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1.dp.toPx())
            }
            fun at(t: Float, c: Float) = Offset((c + 100f) / 200f * size.width, (100f - t) / 200f * size.height)
            // The style's home point, a ring; the centre is the grid's crossing.
            if (homeT != 0f || homeC != 0f) drawCircle(Color.White.copy(alpha = 0.7f), 7.dp.toPx(), at(homeT, homeC),
                style = Stroke(1.5.dp.toPx()))
            val p = at(tone, colour)
            val r = (if (pressed) 12.dp else 10.dp).toPx()
            drawCircle(Color.Black.copy(alpha = 0.35f), r + 2.dp.toPx(), p)
            drawCircle(if (enabled) Palette.OnSurface else Palette.Outline, r, p)
            if (fine) drawCircle(Palette.Accent, r + 5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("淡", style = MaterialTheme.typography.labelSmall, color = Palette.OnSurfaceSoft)
            Text("上：通透 · 下：浓郁 · 长按后拖动可微调", style = MaterialTheme.typography.labelSmall, color = Palette.OnSurfaceSoft)
            Text("浓", style = MaterialTheme.typography.labelSmall, color = Palette.OnSurfaceSoft)
        }
    }

    Spacer(Modifier.padding(top = 4.dp))
    LabeledSlider("风格强度", look.palette, 0.0, 100.0, 100.0, enabled, "%.0f", onDrag) {
        onChange(lookLatest.copy(palette = it))
    }
}
