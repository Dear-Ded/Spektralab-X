package com.dearded.spektralab

import androidx.compose.animation.core.*
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** No expanding ink: input changes only a narrow reflective edge. */
internal object TactileIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = EdgeNode(interactionSource)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun equals(other: Any?): Boolean = other === this
}

private class EdgeNode(val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val presses = mutableSetOf<PressInteraction.Press>()
    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { event ->
                when (event) {
                    is PressInteraction.Press -> presses.add(event)
                    is PressInteraction.Release -> presses.remove(event.press)
                    is PressInteraction.Cancel -> presses.remove(event.press)
                }
                invalidateDraw()
            }
        }
    }
    override fun onDetach() { presses.clear() }
    override fun ContentDrawScope.draw() {
        drawContent()
        if (presses.isNotEmpty()) {
            val inset = .375.dp.toPx()
            drawRoundRect(Brush.verticalGradient(listOf(Palette.BorderHighlightTop, Palette.BorderShadowBottom)),
                Offset(inset, inset), Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f)),
                CornerRadius(8.dp.toPx()), style = Stroke(.75.dp.toPx()))
        }
    }
}

@Composable
internal fun SpecularButton(
    text: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    selected: Boolean = false, onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).pressScale(source)
        .instrumentSurface(Corners.s, selected, enabled)
        .clickable(enabled = enabled, interactionSource = source, indication = TactileIndication,
            role = Role.Button, onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge,
            color = if (selected) Palette.Darkroom else Palette.OnSurface)
    }
}

/** A completed hold commits once; cancellation/disposal always clears the progress. */
@Composable
internal fun HoldCommitButton(
    text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onCommit: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val action by rememberUpdatedState(onCommit)
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var holding by remember { mutableStateOf(false) }
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .instrumentSurface(Corners.s, enabled = enabled)
        .drawHoldProgress { progress.value }
        .semantics {
            role = Role.Button
            if (!enabled) disabled()
            // Assistive activation is an explicit confirmation, without requiring a timed gesture.
            onClick { if (enabled) { view.haptic(HapticEvent.Confirm); action(); true } else false }
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val job = scope.launch {
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(900, easing = LinearEasing))
                    view.haptic(HapticEvent.Confirm)
                    action()
                }
                holding = true
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || change.isConsumed || change.position.x !in 0f..size.width.toFloat() ||
                            change.position.y !in 0f..size.height.toFloat() || event.changes.count { it.pressed } > 1) break
                        change.consume()
                    }
                } finally {
                    job.cancel()
                    holding = false
                    scope.launch { progress.snapTo(0f) }
                }
            }
        }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(if (holding) tr("\u84c4\u529b\u4e2d\uff0c\u677e\u5f00\u53d6\u6d88") else text,
            style = MaterialTheme.typography.labelLarge, color = Palette.OnSurface)
    }
}

private fun Modifier.drawHoldProgress(progress: () -> Float): Modifier = drawWithContent {
    drawRect(Palette.Darkroom.copy(alpha = .25f), size = Size(size.width * progress().coerceIn(0f, 1f), size.height))
    drawContent()
}
