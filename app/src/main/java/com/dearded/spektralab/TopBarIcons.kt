package com.dearded.spektralab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Undo / redo arrows, drawn on the same 24-unit grid and stroke as the
 * category bar (material-icons-core has no undo glyph, and the extended set
 * is deprecated; docs/UI.md appendix A). [redo] mirrors the undo arrow.
 */
@Composable
fun HistoryIcon(redo: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val u = size.width / 24f
        fun x(v: Float) = (if (redo) 24f - v else v) * u
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // The shaft: out to the right, a U-turn, back along the bottom.
        val shaft = Path().apply {
            moveTo(x(5f), 9f * u)
            lineTo(x(14f), 9f * u)
            cubicTo(x(20.5f), 9f * u, x(20.5f), 19f * u, x(14f), 19f * u)
            lineTo(x(9f), 19f * u)
        }
        drawPath(shaft, tint, style = stroke)
        val head = Path().apply {
            moveTo(x(8.5f), 5.5f * u); lineTo(x(5f), 9f * u); lineTo(x(8.5f), 12.5f * u)
        }
        drawPath(head, tint, style = stroke)
    }
}
