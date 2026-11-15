package com.dearded.spektralab

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * Opaque control material with a four-percent top lift and a quiet inset edge.
 * Clips content to [shape]; add the shared pressScale and input modifier separately.
 * Static drawing also works with reduced motion, without a shadow or outer glow.
 * [enabled] softens the material only; callers own input and text semantics.
 */
fun Modifier.instrumentSurface(
    shape: Shape,
    selected: Boolean = false,
    enabled: Boolean = true,
    containerColor: Color = if (selected) Palette.DarkroomDim else Palette.Control,
): Modifier = clip(shape).drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val fill = Brush.verticalGradient(
        listOf(
            lerp(containerColor, Color.White, if (enabled) 0.04f else 0.02f),
            containerColor,
            lerp(containerColor, Color.Black, 0.08f),
        ),
        endY = size.height.coerceAtLeast(1f),
    )
    val edge = if (selected) Palette.Darkroom.copy(alpha = if (enabled) 0.42f else 0.22f)
        else Color.White.copy(alpha = if (enabled) 0.055f else 0.03f)
    val highlight = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (enabled) 0.10f else 0.04f),
        0.55f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.16f),
        endY = size.height.coerceAtLeast(1f),
    )
    // Clipping removes the outer half of each stroke, leaving 0.75 / 0.5 dp inside.
    val edgeStroke = Stroke(width = 1.5.dp.toPx())
    val highlightStroke = Stroke(width = 1.dp.toPx())
    onDrawBehind {
        drawOutline(outline, fill)
        drawOutline(outline, edge, style = edgeStroke)
        drawOutline(outline, highlight, style = highlightStroke)
    }
}
