package com.dearded.spektralab

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for frame geometry calculations, ensuring pixel-perfect alignment
 * when comparing original vs. framed images (EditorScreen hold-to-compare).
 */
class FrameGeometryTest {
    private fun assertRectEquals(expected: Rect, actual: Rect, tolerance: Float = 0.01f) {
        assert(abs(expected.left - actual.left) < tolerance) {
            "Left mismatch: expected ${expected.left}, got ${actual.left}"
        }
        assert(abs(expected.top - actual.top) < tolerance) {
            "Top mismatch: expected ${expected.top}, got ${actual.top}"
        }
        assert(abs(expected.right - actual.right) < tolerance) {
            "Right mismatch: expected ${expected.right}, got ${actual.right}"
        }
        assert(abs(expected.bottom - actual.bottom) < tolerance) {
            "Bottom mismatch: expected ${expected.bottom}, got ${actual.bottom}"
        }
    }

    @Test
    fun `no frame - photo fills container with letterbox`() {
        val frame = FrameSpec() // empty frame
        val containerW = 1080f
        val containerH = 1920f
        val imageW = 4000
        val imageH = 3000 // 4:3 landscape photo in 9:16 portrait container

        val rect = FrameGeometry.photoRect(containerW, containerH, imageW, imageH, frame)

        // Photo should be letterboxed horizontally to fit container
        // Scale = min(1080/4000, 1920/3000) = min(0.27, 0.64) = 0.27
        val expectedScale = 0.27f
        val expectedWidth = imageW * expectedScale
        val expectedHeight = imageH * expectedScale
        val expectedLeft = (containerW - expectedWidth) / 2f
        val expectedTop = (containerH - expectedHeight) / 2f

        assertRectEquals(
            Rect(expectedLeft, expectedTop, expectedLeft + expectedWidth, expectedTop + expectedHeight),
            rect
        )
    }

    @Test
    fun `thin white border - photo rect accounts for margin`() {
        val frame = FrameSpec(id = "thin_white", top = 0.02f, bottom = 0.02f, side = 0.02f)
        val containerW = 1080f
        val containerH = 1920f
        val imageW = 4000
        val imageH = 3000

        val rect = FrameGeometry.photoRect(containerW, containerH, imageW, imageH, frame)

        // Frame adds 2% of short edge (3000) on each side = 60px margins
        // Card becomes 4120 x 3120
        // Scale = min(1080/4120, 1920/3120) = min(0.262, 0.615) = 0.262
        // Photo on card: offset by 60px margin * scale
        val layout = frame.layout(imageW, imageH)
        assertEquals(4120, layout.width)
        assertEquals(3120, layout.height)
        assertEquals(60, layout.x)
        assertEquals(60, layout.y)

        val scale = containerW / layout.width // width is limiting
        val cardOffsetX = 0f
        val cardOffsetY = (containerH - layout.height * scale) / 2f
        val photoLeft = cardOffsetX + layout.x * scale
        val photoTop = cardOffsetY + layout.y * scale

        assertRectEquals(
            Rect(photoLeft, photoTop, photoLeft + imageW * scale, photoTop + imageH * scale),
            rect
        )
    }

    @Test
    fun `instant card - portrait photo with heavy bottom margin`() {
        // Instax Mini proportions: 0.13 top/side, 0.39 bottom
        val frame = FrameSpec(id = "instax_mini", top = 0.13f, bottom = 0.39f, side = 0.087f,
            paper = FrameSpec.PAPER_INSTANT)
        val containerW = 1080f
        val containerH = 1920f
        val imageW = 3000
        val imageH = 4000 // 3:4 portrait

        val rect = FrameGeometry.photoRect(containerW, containerH, imageW, imageH, frame)

        val layout = frame.layout(imageW, imageH)
        // Short edge = 3000, top = 390, bottom = 1170, side = 261
        // Card: 3522 x 5560
        assertEquals(3522, layout.width)
        assertEquals(5560, layout.height)

        val scale = containerW / layout.width
        val cardOffsetY = (containerH - layout.height * scale) / 2f
        val photoLeft = layout.x * scale
        val photoTop = cardOffsetY + layout.y * scale

        assertRectEquals(
            Rect(photoLeft, photoTop, photoLeft + imageW * scale, photoTop + imageH * scale),
            rect
        )
    }

    @Test
    fun `canvas aspect padding - 1x1 square from landscape`() {
        val frame = FrameSpec(id = "pad_1x1", top = 0.03f, bottom = 0.03f, side = 0.03f, canvasAspect = 1f)
        val containerW = 1080f
        val containerH = 1920f
        val imageW = 6000
        val imageH = 4000 // 3:2 landscape

        val rect = FrameGeometry.photoRect(containerW, containerH, imageW, imageH, frame)

        val layout = frame.layout(imageW, imageH)
        // Short edge = 4000, margin = 120px each side
        // Before padding: 6240 x 4240
        // After 1:1 padding: 6240 x 6240 (adds vertical space)
        assertEquals(6240, layout.width)
        assertEquals(6240, layout.height)

        val scale = containerW / layout.width
        val cardOffsetY = (containerH - layout.height * scale) / 2f
        val photoLeft = layout.x * scale
        val photoTop = cardOffsetY + layout.y * scale

        assertRectEquals(
            Rect(photoLeft, photoTop, photoLeft + imageW * scale, photoTop + imageH * scale),
            rect
        )
    }

    @Test
    fun `mat with keyline - gallery mat with hairline border`() {
        val frame = FrameSpec(id = "mat_keyline", top = 0.12f, bottom = 0.12f, side = 0.12f,
            keyline = 0.0025f, keylineGap = 0.025f)
        val containerW = 1080f
        val containerH = 1920f
        val imageW = 4000
        val imageH = 3000

        val rect = FrameGeometry.photoRect(containerW, containerH, imageW, imageH, frame)

        // The photo position should not be affected by keyline/gap - those are drawn around it
        val layout = frame.layout(imageW, imageH)
        val scale = containerW / layout.width.coerceAtLeast(1f)
        val cardOffsetY = (containerH - layout.height * scale) / 2f
        val photoLeft = layout.x * scale
        val photoTop = cardOffsetY + layout.y * scale

        assertRectEquals(
            Rect(photoLeft, photoTop, photoLeft + imageW * scale, photoTop + imageH * scale),
            rect
        )
    }

    @Test
    fun `cardTransform matches photoRect scaling`() {
        val frame = FrameSpec(top = 0.05f, bottom = 0.08f, side = 0.05f)
        val containerW = 1080f
        val containerH = 1920f
        val imageW = 4000
        val imageH = 3000

        val (scale, offset) = FrameGeometry.cardTransform(containerW, containerH, imageW, imageH, frame)
        val rect = FrameGeometry.photoRect(containerW, containerH, imageW, imageH, frame)

        val layout = frame.layout(imageW, imageH)
        // Photo position = card offset + (photo offset within card) * scale
        val expectedPhotoLeft = offset.x + layout.x * scale
        val expectedPhotoTop = offset.y + layout.y * scale

        assertEquals(expectedPhotoLeft, rect.left, 0.01f)
        assertEquals(expectedPhotoTop, rect.top, 0.01f)
    }

    @Test
    fun `portrait composition with bottom caption bar`() {
        val frame = FrameSpec(id = "bar_split", bottom = 0.12f, caption = true, captionLayout = "split")
        val containerW = 1080f
        val containerH = 1920f
        val imageW = 3000
        val imageH = 4000

        val rect = FrameGeometry.photoRect(containerW, containerH, imageW, imageH, frame)

        val layout = frame.layout(imageW, imageH)
        // Short edge = 3000, bottom margin = 360
        // Card: 3000 x 4360
        assertEquals(3000, layout.width)
        assertEquals(4360, layout.height)
        assertEquals(0, layout.x)
        assertEquals(0, layout.y)

        val scale = containerW / layout.width
        val cardOffsetY = (containerH - layout.height * scale) / 2f

        assertRectEquals(
            Rect(0f, cardOffsetY, imageW * scale, cardOffsetY + imageH * scale),
            rect
        )
    }

    @Test
    fun `wide container landscape photo no frame`() {
        val frame = FrameSpec()
        val containerW = 2560f
        val containerH = 1440f // 16:9 landscape container
        val imageW = 6000
        val imageH = 4000 // 3:2 landscape

        val rect = FrameGeometry.photoRect(containerW, containerH, imageW, imageH, frame)

        // Photo limited by height: scale = 1440/4000 = 0.36
        val scale = 0.36f
        val width = imageW * scale
        val height = imageH * scale
        val left = (containerW - width) / 2f
        val top = 0f

        assertRectEquals(Rect(left, top, left + width, top + height), rect)
    }

    @Test
    fun `square photo with 9x16 padding in portrait container`() {
        val frame = FrameSpec(id = "pad_9x16", top = 0.03f, bottom = 0.03f, side = 0.03f,
            canvasAspect = 9f / 16f)
        val containerW = 1080f
        val containerH = 1920f
        val imageW = 4000
        val imageH = 4000 // 1:1 square

        val rect = FrameGeometry.photoRect(containerW, containerH, imageW, imageH, frame)

        val layout = frame.layout(imageW, imageH)
        // Short edge = 4000, margin = 120
        // Before padding: 4240 x 4240
        // Target 9:16 = 0.5625 → width/height should be 0.5625
        // Add horizontal padding to reach 9:16
        // 4240 / newHeight = 9/16 → newHeight = 4240 * 16/9 = 7538
        assertEquals(7538, layout.height)

        val scale = containerW / layout.width.coerceAtLeast(1f)
        val cardOffsetY = (containerH - layout.height * scale) / 2f
        val photoLeft = layout.x * scale
        val photoTop = cardOffsetY + layout.y * scale

        assertRectEquals(
            Rect(photoLeft, photoTop, photoLeft + imageW * scale, photoTop + imageH * scale),
            rect
        )
    }
}
