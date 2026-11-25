package com.dearded.spektralab

import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/*
 * The physical parts every control is made of (preview/ui-feel.html, 拟物组件): one light from the
 * upper left, real materials, contrast kept low so it reads as real without glare.
 *
 *  - a key: a raised face, its top edge catching the light and its bottom edge in shade, a soft
 *    contact shadow; pressed it sinks 1 dp and the shadow tightens. Dark keys for choices, the ivory
 *    key for the chosen one.
 *  - a groove: a track pressed into the panel, its shadow under the top edge, a lit lip below.
 *  - a window: a recess cut into the top plate with a chamfer, under a cover glass.
 */

private val KeyDarkTop = Color(0xFF262624)
private val KeyDarkBottom = Color(0xFF1A1A19)
private val KeyIvoryTop = Color(0xFFF4F0E8)
private val KeyIvoryBottom = Color(0xFFD9D4C9)

internal fun roundRectPath(size: Size, r: Float, inset: Float = 0f) = Path().apply {
    addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(r)))
}

/** A key filling this DrawScope: [ivory] 0 dark .. 1 ivory (the chosen one), [press] 0..1 sunk. */
internal fun DrawScope.drawKey(r: Float, ivory: Float, press: Float, alpha: Float = 1f) {
    val d = density
    val sink = press * d
    // contact shadow, tighter as it sinks
    drawRoundRect(Color.Black.copy(alpha = (0.5f - 0.3f * press) * alpha), Offset(0f, (1.5f - press) * d),
        Size(size.width, size.height), CornerRadius(r))
    val top = lerp(KeyDarkTop, KeyIvoryTop, ivory)
    val bottom = lerp(KeyDarkBottom, KeyIvoryBottom, ivory)
    drawRoundRect(Brush.verticalGradient(listOf(top, bottom), startY = sink, endY = size.height + sink),
        Offset(0f, sink), size, CornerRadius(r), alpha = alpha)
    // the top edge in the light, the bottom edge in shade; pressed, the light goes off the top
    clipPath(roundRectPath(size, r).apply { translate(Offset(0f, sink)) }) {
        drawRect(Color.White.copy(alpha = (0.07f + 0.6f * ivory) * (1f - press) * alpha), Offset(0f, sink), Size(size.width, d))
        drawRect(Color.Black.copy(alpha = (0.45f - 0.27f * ivory) * alpha), Offset(0f, size.height + sink - d), Size(size.width, d))
        if (press > 0f) drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f * press), Color.Transparent),
            startY = sink, endY = sink + 4 * d), Offset(0f, sink), Size(size.width, 4 * d))
    }
}

/** A groove filling this DrawScope: a track pressed into the panel. */
internal fun DrawScope.drawGroove(r: Float) {
    val d = density
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF131312), Color(0xFF191918))), cornerRadius = CornerRadius(r))
    clipPath(roundRectPath(size, r)) {
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent), endY = 4 * d), size = Size(size.width, 4 * d))
        drawRect(Color(0x0BFFF8EC), Offset(0f, size.height - d), Size(size.width, d))
    }
    drawRoundRect(Color.Black.copy(alpha = 0.6f), cornerRadius = CornerRadius(r), style = androidx.compose.ui.graphics.drawscope.Stroke(0.5f * d))
}

/** A window cut into the plate over [size]: chamfer outside, recess inside. Draw before the contents. */
internal fun DrawScope.drawWindowCut(r: Float) {
    val d = density
    drawRoundRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.5f to Color.Black.copy(alpha = 0.1f),
        1f to Color(0x29FFF8EC), startY = -1.5f * d, endY = size.height + 1.5f * d),
        Offset(-1.5f * d, -1.5f * d), Size(size.width + 3 * d, size.height + 3 * d), CornerRadius(r + 1.5f * d))
    drawRoundRect(Color(0xFF070707), cornerRadius = CornerRadius(r))
}

/** The recess's shadow and the cover glass's reflection, over the contents. */
internal fun DrawScope.drawWindowGlass(r: Float) {
    val d = density
    clipPath(roundRectPath(size, r)) {
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent), endY = 10 * d), size = Size(size.width, 10 * d))
        drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent), endX = 6 * d), size = Size(6 * d, size.height))
        drawRect(Brush.linearGradient(0f to Color(0x0EFFFFFF), 0.45f to Color(0x05FFFFFF), 0.46f to Color.Transparent,
            start = Offset.Zero, end = Offset(size.width * 0.4f, size.height)))
    }
}

/**
 * A switch: a groove with a weighty round knob that slides over with a little spring.
 * The groove fills ivory when on. 52 x 32 dp. Drawn only; the row is what toggles.
 */
@Composable
internal fun GrooveSwitch(checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val t by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "switch")
    Canvas(modifier.size(52.dp, 32.dp)) {
        val d = density
        val r = size.height / 2
        val a = if (enabled) 1f else 0.45f
        val on = t.coerceIn(0f, 1f)
        drawRoundRect(Brush.verticalGradient(listOf(lerp(Color(0xFF0D0D0C), Color(0xFFB9B1A2), on), lerp(Color(0xFF1A1A19), Color(0xFFE4DDD0), on))),
            cornerRadius = CornerRadius(r), alpha = a)
        clipPath(roundRectPath(size, r)) {
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f - 0.3f * on), Color.Transparent), endY = 4 * d), size = Size(size.width, 4 * d))
        }
        val kr = 13 * d
        val c = Offset(3 * d + kr + t * (size.width - 6 * d - 2 * kr), size.height / 2)
        drawCircle(Color.Black.copy(alpha = 0.5f * a), kr, c + Offset(0f, 2 * d))
        drawCircle(Brush.radialGradient(0f to Color(0xFFFDFBF6), 0.45f to Color(0xFFE2DDD2), 1f to Color(0xFFB7B1A5),
            center = c + Offset(-kr * 0.28f, -kr * 0.4f), radius = kr * 1.3f), kr, c, alpha = a)
        drawCircle(Color.Black.copy(alpha = 0.3f), kr, c, style = androidx.compose.ui.graphics.drawscope.Stroke(0.5f * d))
    }
}

/**
 * Any clickable surface as a key: [ivory] 1 for the primary (保存, 编辑, 导出, 下载并安装), 0 for the
 * rest. [corner] null rounds it fully. Pass the same [press] to the clickable (indication = null).
 */
internal fun Modifier.key(ivory: Float, press: androidx.compose.foundation.interaction.InteractionSource, corner: androidx.compose.ui.unit.Dp? = null): Modifier =
    composed {
        val down by press.collectIsPressedAsState()
        val sink by animateFloatAsState(if (down) 1f else 0f, Motion.fastEffects(), label = "key-press")
        drawBehind { drawKey(corner?.toPx() ?: (size.minDimension / 2), ivory, sink) }
    }

/** A text field's ground: a window cut into the panel (with [windowFieldColors] on an OutlinedTextField). */
internal fun Modifier.windowField(): Modifier = drawBehind {
    val r = 12.dp.toPx()
    drawWindowCut(r)
    drawRoundRect(Color(0xFF111110), cornerRadius = CornerRadius(r))
    clipPath(roundRectPath(size, r)) {
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent), endY = 8 * density), size = Size(size.width, 8 * density))
    }
}

@Composable
internal fun windowFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Accent.copy(alpha = 0.35f), unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent, errorBorderColor = Palette.Warning,
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
    cursorColor = Palette.Accent, focusedLabelColor = Palette.OnSurfaceSoft, unfocusedLabelColor = Palette.OnSurfaceSoft,
    focusedTextColor = Palette.OnSurface, unfocusedTextColor = Palette.OnSurface,
)

/** A check: a small well pressed into the panel, an ivory key with a tick in it when checked. 22 dp. */
@Composable
internal fun CheckKey(checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val t by animateFloatAsState(if (checked) 1f else 0f, Motion.fastEffects(), label = "check")
    Canvas(modifier.size(22.dp)) {
        val r = 6.dp.toPx()
        val a = if (enabled) 1f else 0.45f
        drawGroove(r)
        if (t > 0.01f) {
            val inset = 2.dp.toPx()
            drawContext.transform.translate(inset, inset)
            val inner = Size(size.width - 2 * inset, size.height - 2 * inset)
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFF4F0E8), Color(0xFFD9D4C9)), endY = inner.height),
                size = inner, cornerRadius = CornerRadius(r - inset), alpha = t * a)
            drawContext.transform.translate(-inset, -inset)
            val p = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.52f); lineTo(size.width * 0.44f, size.height * 0.68f); lineTo(size.width * 0.74f, size.height * 0.34f)
            }
            drawPath(p, Palette.OnAccent.copy(alpha = t * a), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}
