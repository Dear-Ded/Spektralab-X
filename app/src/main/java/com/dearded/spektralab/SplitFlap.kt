package com.dearded.spektralab

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/*
 * The app's name on split-flap tiles (after React Bits' Split-Flap Text,
 * rebuilt from how it behaves, never from its source: MIT + Commons Clause
 * does not mix with GPL-3.0, ui2_reactbits.md 8.1). Used for the launch intro's
 * wordmark and the 关于 header only.
 *
 * Each letter is a grey tile in the bundled JetBrains Mono (equal widths, so
 * the row never jitters). It starts blank, flips through two other characters
 * and lands on its own; letters start 35 ms apart, left to right, 100 ms a
 * flip, so a 12-letter name has settled 685 ms after it starts. The upper half
 * folds down over the lower one about a thin hinge line, shaded as it turns
 * edge-on. Neutral greys and near-white ink only.
 *
 * One clock drives the word; each tile works out its own flip from it inside
 * draw and graphicsLayer lambdas, so a frame recomposes nothing and draws no
 * bitmap. With animations off the name is drawn settled.
 */

private const val FLIPS = 3
private const val FLIP_MS = 100
private const val STAGGER_MS = 35
private const val POOL = "AEKLMNRSTX-"

/** How long [text] takes to settle once it starts, in ms. */
fun splitFlapMs(text: String): Int = (text.length - 1).coerceAtLeast(0) * STAGGER_MS + FLIPS * FLIP_MS

/**
 * [text] on flap tiles. With [play] it flips in after [startDelayMs]; without
 * it (or with animations off) it is drawn settled. [skip] jumps to the settled
 * name at once (a tap on the intro). [onLanded] runs once when the last letter
 * lands on its own, never after a skip and never when nothing played.
 */
@Composable
fun SplitFlapText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
    play: Boolean = true,
    startDelayMs: Long = 0,
    skip: Boolean = false,
    onLanded: () -> Unit = {},
) {
    val total = splitFlapMs(text).toFloat()
    val moving = play && remember { motionAllowed() }
    val clock = remember { Animatable(if (moving) 0f else total) }
    val landed by rememberUpdatedState(onLanded)
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        delay(startDelayMs)
        clock.animateTo(total, tween(total.toInt(), easing = LinearEasing))
        if (!skip) landed()
    }
    LaunchedEffect(skip) { if (skip) clock.snapTo(total) }

    val ink = style.copy(fontFamily = UiMono, fontWeight = FontWeight.Medium, color = Palette.OnSurface)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // One tile fits the widest glyph of the face (mono: all the same) with a little air.
    val cell = remember(ink, density) { measurer.measure("M", ink) }
    val tileW = with(density) { cell.size.width.toDp() } + 6.dp
    val tileH = with(density) { cell.size.height.toDp() } + 8.dp
    Row(
        modifier.clearAndSetSemantics { contentDescription = text },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        text.forEachIndexed { i, c ->
            val seq = remember(text, i) { sequenceFor(c, i) }
            val glyphs = remember(seq, ink) { seq.map { measurer.measure(it.toString(), ink) } }
            FlapTile(glyphs, Modifier.size(tileW, tileH)) {
                ((clock.value - i * STAGGER_MS) / FLIP_MS).coerceIn(0f, FLIPS.toFloat())
            }
        }
    }
}

/** Blank, two characters from the pool (fixed per position, so every launch is the same), then the letter. */
private fun sequenceFor(c: Char, i: Int): String {
    val a = POOL[(i * 7 + 3) % POOL.length]
    val b = POOL[(i * 5 + 1) % POOL.length]
    return " " + a + b + c
}

private val TileFill = Palette.PanelHigh
private val Hinge = Palette.Background

/** One tile. [progress] is 0..FLIPS: whole numbers rest on a character, the fraction is the fall. */
@Composable
private fun FlapTile(glyphs: List<TextLayoutResult>, modifier: Modifier, progress: () -> Float) {
    val last = glyphs.size - 1
    Box(modifier.clip(Corners.xs).background(TileFill)) {
        // What stays put: the next character's top (uncovered by the falling flap) and the current one's bottom.
        Canvas(Modifier.matchParentSize()) {
            val p = progress()
            val k = floor(p).toInt().coerceIn(0, last)
            val next = (k + 1).coerceAtMost(last)
            val mid = size.height / 2f
            clipRect(bottom = mid) { glyph(glyphs[next]) }
            clipRect(top = mid) { glyph(glyphs[k]) }
            drawLine(Hinge, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        }
        // The flap: the current top falling to edge-on, then the next bottom coming down from it.
        Canvas(
            Modifier.matchParentSize().graphicsLayer {
                val p = progress()
                val k = floor(p).toInt()
                val f = p - k
                if (k >= last || f <= 0f) { alpha = 0f; return@graphicsLayer }
                alpha = 1f
                cameraDistance = 10f * density
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                // Falling picks up speed, as a flap under gravity does.
                val e = f * f
                rotationX = if (e < 0.5f) -180f * e else 180f * (1f - e)
            },
        ) {
            val p = progress()
            val k = floor(p).toInt().coerceIn(0, last)
            val f = p - floor(p)
            if (k >= last || f <= 0f) return@Canvas
            val e = f * f
            val mid = size.height / 2f
            // Darker as it turns edge-on: the light falls across it.
            val shade = 0.35f * sin((if (e < 0.5f) e else 1f - e) * PI.toFloat())
            if (e < 0.5f) clipRect(bottom = mid) {
                drawRect(TileFill); glyph(glyphs[k]); drawRect(Color.Black.copy(alpha = shade))
            } else clipRect(top = mid) {
                drawRect(TileFill); glyph(glyphs[(k + 1).coerceAtMost(last)]); drawRect(Color.Black.copy(alpha = shade))
            }
            drawLine(Hinge, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        }
    }
}

private fun DrawScope.glyph(t: TextLayoutResult) {
    drawText(t, topLeft = Offset((size.width - t.size.width) / 2f, (size.height - t.size.height) / 2f))
}
