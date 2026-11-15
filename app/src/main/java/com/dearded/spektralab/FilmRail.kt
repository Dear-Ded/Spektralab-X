package com.dearded.spektralab

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Contact-sheet edges stay outside the real thumbnails. A changed group [key]
 * unrolls in the draw phase; the first composition is already fully visible.
 */
@Composable
internal fun Modifier.filmRail(key: String? = null): Modifier {
    val reduceMotion = rememberReduceMotion()
    val reveal = remember { Animatable(1f) }
    var previousKey by remember { mutableStateOf(key) }
    LaunchedEffect(key, reduceMotion) {
        val changed = previousKey != key
        previousKey = key
        if (reduceMotion || !changed) {
            reveal.snapTo(1f)
        } else {
            // A new key cancels this effect, including any in-flight animation.
            reveal.snapTo(0.18f)
            reveal.animateTo(1f, tween(300, easing = androidx.compose.animation.core.CubicBezierEasing(.16f, 1f, .3f, 1f)))
        }
    }
    return drawWithContent {
        // Bypass the animated value immediately when motion is disabled.
        val width = size.width * if (reduceMotion) 1f else reveal.value
        val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
        clipRect(left = left, right = left + width) {
            this@drawWithContent.drawFilmRail()
        }
    }.padding(vertical = 9.dp)
}

private fun ContentDrawScope.drawFilmRail() {
    val rail = 9.dp.toPx()
    drawRect(Palette.Panel, size = Size(size.width, rail))
    drawRect(Palette.Panel, Offset(0f, size.height - rail), Size(size.width, rail))
    drawContent()
    val pitch = 14.dp.toPx()
    val hole = Size(6.dp.toPx(), 3.5.dp.toPx())
    var x = 4.dp.toPx()
    while (x + hole.width <= size.width) {
        for (edge in 0..1) {
            val y = if (edge == 0) 2.5.dp.toPx() else size.height - 6.dp.toPx()
            drawRoundRect(Color.Black, Offset(x, y - 0.5.dp.toPx()),
                Size(hole.width, hole.height + 0.5.dp.toPx()), CornerRadius(0.8.dp.toPx()))
            drawRoundRect(Palette.Track.copy(alpha = 0.65f), Offset(x, y), hole, CornerRadius(0.8.dp.toPx()))
            drawLine(Palette.OnSurface.copy(alpha = 0.16f),
                Offset(x + 1.dp.toPx(), y + hole.height), Offset(x + hole.width - 1.dp.toPx(), y + hole.height),
                0.5.dp.toPx())
        }
        x += pitch
    }
}
