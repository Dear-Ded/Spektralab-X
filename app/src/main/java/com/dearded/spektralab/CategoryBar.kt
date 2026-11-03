package com.dearded.spektralab

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The editor's bottom category bar (docs/UI.md 2.2): line icon plus a two-
 * character label, the way Lightroom, Darkroom and the Xiaomi gallery editor
 * lay it out. The selected item is amber **and** carries a pill under its
 * icon, so "selected" never rests on colour alone (Halide's lesson).
 */
private val CATEGORIES = listOf("胶片", "调整", "冲印", "效果", "构图")

@Composable
fun CategoryBar(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Palette.Surface),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CATEGORIES.forEachIndexed { i, label ->
            val on = i == selected
            val tint by animateColorAsState(
                when {
                    on -> Palette.Amber
                    enabled -> Palette.OnSurfaceSoft
                    else -> Palette.OnSurfaceSoft.copy(alpha = 0.38f)
                },
                Motion.defaultEffects(), label = "tint",
            )
            val pill by animateDpAsState(if (on) 20.dp else 0.dp, Motion.fastSpatial(), label = "pill")
            Column(
                Modifier.weight(1f).fillMaxHeight().selectable(
                    selected = on, role = Role.Tab,
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { if (!on) { view.tick(); onSelect(i) } },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Canvas(Modifier.size(24.dp)) { icon(i, tint, on) }
                Spacer(Modifier.height(3.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
                Spacer(Modifier.height(3.dp))
                Box(Modifier.width(pill).height(3.dp).background(Palette.Amber, RoundedCornerShape(2.dp)))
            }
        }
    }
}

/** Five line icons on a 24-unit grid, stroke weight to match Material Symbols 300. */
private fun DrawScope.icon(index: Int, c: Color, filled: Boolean) {
    val u = size.width / 24f
    val stroke = Stroke(width = 1.6f * u)
    when (index) {
        0 -> { // film frame with sprocket holes
            drawRoundRect(c, Offset(3 * u, 5 * u), Size(18 * u, 14 * u), CornerRadius(2 * u), style = stroke)
            for (k in 0 until 4) {
                val x = (5.5f + k * 3.8f) * u
                drawRect(c, Offset(x, 6.6f * u), Size(1.8f * u, 1.6f * u))
                drawRect(c, Offset(x, 15.8f * u), Size(1.8f * u, 1.6f * u))
            }
            if (filled) drawRect(c.copy(alpha = 0.35f), Offset(5 * u, 9.4f * u), Size(14 * u, 5.2f * u))
        }
        1 -> { // three sliders
            listOf(6f to 15f, 12f to 8f, 18f to 13f).forEach { (y, x) ->
                drawLine(c, Offset(3 * u, y * u), Offset(21 * u, y * u), stroke.width)
                drawCircle(if (filled) c else Palette.Surface, 2.2f * u, Offset(x * u, y * u))
                drawCircle(c, 2.2f * u, Offset(x * u, y * u), style = stroke)
            }
        }
        2 -> { // enlarger lamp over a sheet of paper
            val lamp = Path().apply {
                moveTo(8 * u, 3 * u); lineTo(16 * u, 3 * u); lineTo(18 * u, 9 * u); lineTo(6 * u, 9 * u); close()
            }
            if (filled) drawPath(lamp, c) else drawPath(lamp, c, style = stroke)
            drawLine(c, Offset(12 * u, 9 * u), Offset(12 * u, 12.5f * u), stroke.width)
            drawRoundRect(c, Offset(4 * u, 14 * u), Size(16 * u, 7 * u), CornerRadius(1 * u), style = stroke)
        }
        3 -> { // a highlight with its halation rays
            val o = Offset(12 * u, 12 * u)
            if (filled) drawCircle(c, 3.6f * u, o) else drawCircle(c, 3.6f * u, o, style = stroke)
            for (k in 0 until 8) {
                val a = Math.PI * k / 4
                val r0 = 6.2f * u; val r1 = (if (k % 2 == 0) 9.5f else 8.2f) * u
                drawLine(c, Offset(o.x + r0 * cos(a).toFloat(), o.y + r0 * sin(a).toFloat()),
                    Offset(o.x + r1 * cos(a).toFloat(), o.y + r1 * sin(a).toFloat()), stroke.width)
            }
        }
        else -> { // crop corners
            val p = Path().apply {
                moveTo(7 * u, 2 * u); lineTo(7 * u, 17 * u); lineTo(22 * u, 17 * u)
                moveTo(2 * u, 7 * u); lineTo(17 * u, 7 * u); lineTo(17 * u, 22 * u)
            }
            drawPath(p, c, style = stroke)
            if (filled) drawRect(c.copy(alpha = 0.35f), Offset(7 * u, 7 * u), Size(10 * u, 10 * u))
        }
    }
}
