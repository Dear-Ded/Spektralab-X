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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.*

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
    containerColor: Color = Palette.Control,
): Modifier = composed {
    val reduced = rememberReduceMotion()
    val angle = if (selected && enabled && !reduced) {
        val transition = rememberInfiniteTransition(label = "specular-edge")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "edge-angle")
    } else null
    clip(shape).drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val fill = Brush.verticalGradient(
        listOf(
            lerp(containerColor, Color.White, if (enabled) 0.06f else 0.02f),
            lerp(containerColor, Color.Black, 0.12f),
        ),
        endY = size.height.coerceAtLeast(1f),
    )
    val edge = if (selected) Palette.Darkroom.copy(alpha = if (enabled) 0.30f else 0.15f)
        else Palette.BorderSubtle
    val highlight = Brush.verticalGradient(
        0f to Palette.BorderHighlightTop.copy(alpha = if (enabled) .22f else .04f),
        0.55f to Color.Transparent,
        1f to Palette.BorderShadowBottom,
        endY = size.height.coerceAtLeast(1f),
    )
    // Clipping removes the outer half of each stroke, leaving 0.75 / 0.5 dp inside.
    val edgeStroke = Stroke(width = 1.5.dp.toPx())
    val highlightStroke = Stroke(width = 1.5.dp.toPx())
    val shader = android.graphics.SweepGradient(size.width / 2f, size.height / 2f,
        intArrayOf(Palette.Darkroom.copy(alpha = .30f).toArgb(), Palette.Darkroom.copy(alpha = .30f).toArgb(),
            Palette.Darkroom.toArgb(), Palette.Darkroom.copy(alpha = .30f).toArgb(), Palette.Darkroom.copy(alpha = .30f).toArgb()),
        floatArrayOf(0f, .425f, .50f, .575f, 1f))
    val sweep = object : androidx.compose.ui.graphics.ShaderBrush() {
        override fun createShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader = shader
    }
    val matrix = android.graphics.Matrix()
    onDrawBehind {
        drawOutline(outline, fill)
        drawOutline(outline, edge, style = edgeStroke)
        drawOutline(outline, highlight, style = highlightStroke)
        if (selected && enabled) {
            // Rotate the shader's coordinates; the control outline itself stays fixed.
            matrix.setRotate(angle?.value ?: 0f, size.width / 2f, size.height / 2f)
            shader.setLocalMatrix(matrix)
            drawOutline(outline, sweep, style = edgeStroke)
        }
    }
    }
}
