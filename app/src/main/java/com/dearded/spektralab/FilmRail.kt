package com.dearded.spektralab

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Contact-sheet edges stay outside the real selection thumbnails. */
internal fun Modifier.filmRail(): Modifier = drawWithContent {
    val rail = 9.dp.toPx()
    drawRect(Palette.Panel, size = Size(size.width, rail))
    drawRect(Palette.Panel, Offset(0f, size.height - rail), Size(size.width, rail))
    drawContent()
    val pitch = 14.dp.toPx()
    val hole = Size(6.dp.toPx(), 3.5.dp.toPx())
    var x = 4.dp.toPx()
    while (x + hole.width <= size.width) {
        for (y in floatArrayOf(2.5.dp.toPx(), size.height - 6.dp.toPx())) {
            drawRoundRect(Color.Black, Offset(x, y - 0.5.dp.toPx()),
                Size(hole.width, hole.height + 0.5.dp.toPx()), CornerRadius(0.8.dp.toPx()))
            drawRoundRect(Palette.Track.copy(alpha = 0.65f), Offset(x, y), hole, CornerRadius(0.8.dp.toPx()))
            drawLine(Palette.OnSurface.copy(alpha = 0.16f),
                Offset(x + 1.dp.toPx(), y + hole.height), Offset(x + hole.width - 1.dp.toPx(), y + hole.height),
                0.5.dp.toPx())
        }
        x += pitch
    }
}.padding(vertical = 9.dp)
