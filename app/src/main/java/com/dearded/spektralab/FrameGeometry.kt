package com.dearded.spektralab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.min

/**
 * Pure functions for frame geometry calculations, extracted to enable testing
 * and ensure pixel-perfect alignment when comparing original vs. framed images.
 */
object FrameGeometry {
    /**
     * Compute where the photo appears on screen when displaying a framed image.
     *
     * @param containerWidth The screen/canvas width in pixels
     * @param containerHeight The screen/canvas height in pixels
     * @param imageWidth The photo width in pixels (before framing)
     * @param imageHeight The photo height in pixels (before framing)
     * @param frame The frame specification (margins, aspect, etc.)
     * @return Rectangle describing where the photo renders on screen, in screen pixels
     */
    fun photoRect(
        containerWidth: Float,
        containerHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
        frame: FrameSpec,
    ): Rect {
        val layout = frame.layout(imageWidth, imageHeight)
        // Scale factor to fit the framed card (photo + frame) into the container
        val scale = min(containerWidth / layout.width, containerHeight / layout.height)
        // Card offset to center it
        val cardOffsetX = (containerWidth - layout.width * scale) / 2f
        val cardOffsetY = (containerHeight - layout.height * scale) / 2f
        // Photo position within the card
        val photoLeft = cardOffsetX + layout.x * scale
        val photoTop = cardOffsetY + layout.y * scale
        val photoRight = photoLeft + imageWidth * scale
        val photoBottom = photoTop + imageHeight * scale
        return Rect(photoLeft, photoTop, photoRight, photoBottom)
    }

    /**
     * Compute the scale and offset needed to render a framed card centered in a container.
     *
     * @return Pair of (scale, offset) where scale is the uniform scaling factor and
     *         offset is the top-left corner of the scaled card in container coordinates
     */
    fun cardTransform(
        containerWidth: Float,
        containerHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
        frame: FrameSpec,
    ): Pair<Float, Offset> {
        val layout = frame.layout(imageWidth, imageHeight)
        val scale = min(containerWidth / layout.width, containerHeight / layout.height)
        val offsetX = (containerWidth - layout.width * scale) / 2f
        val offsetY = (containerHeight - layout.height * scale) / 2f
        return scale to Offset(offsetX, offsetY)
    }
}
