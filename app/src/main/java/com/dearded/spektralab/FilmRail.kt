package com.dearded.spektralab

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/** Contact-sheet edges stay outside the real selection thumbnails. */
internal fun Modifier.filmRail(): Modifier = drawWithContent {
    drawContent()
    val pitch = 14.dp.toPx()
    val hole = Size(5.dp.toPx(), 2.dp.toPx())
    var x = 4.dp.toPx()
    while (x + hole.width <= size.width) {
        for (y in floatArrayOf(1.dp.toPx(), size.height - 3.dp.toPx())) {
            drawRoundRect(Palette.Outline, Offset(x, y), hole, CornerRadius(1.dp.toPx()))
        }
        x += pitch
    }
}.padding(vertical = 6.dp)
