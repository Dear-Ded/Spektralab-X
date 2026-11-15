package com.dearded.spektralab

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.nativeCanvas

/** Display-only latent image, bounded to the photograph. Never part of Edit or an export. */
internal fun Modifier.latentPreview(
    width: Int, height: Int, frame: FrameSpec, amount: () -> Float,
): Modifier = drawWithCache {
    val r = FrameGeometry.photoRect(size.width, size.height, width, height, frame)
    val bounds = android.graphics.RectF(r.left, r.top, r.right, r.bottom)
    val coefficients = FloatArray(20)
    val matrix = android.graphics.ColorMatrix()
    val paint = android.graphics.Paint()
    onDrawWithContent {
        drawContent()
        val a = amount().coerceIn(0f, 1f)
        if (a > 0.001f) {
            val gain = 1f - 2f * a
            coefficients[0] = gain; coefficients[6] = gain; coefficients[12] = gain
            coefficients[4] = 255f * a; coefficients[9] = 255f * a; coefficients[14] = 255f * a
            coefficients[18] = 1f
            matrix.set(coefficients)
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
            val canvas = drawContext.canvas.nativeCanvas
            val saved = canvas.save()
            canvas.clipRect(bounds)
            canvas.saveLayer(bounds, paint)
            drawContent()
            canvas.restoreToCount(saved)
        }
    }
}
