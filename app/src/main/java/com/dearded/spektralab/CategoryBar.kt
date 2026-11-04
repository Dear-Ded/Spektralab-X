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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
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
 * The editor's floating toolbar (UI2.md 2.1, 2.2): one pill 64 dp tall, 12 dp
 * off the screen edges, holding the five entries in the order a print is made
 * (one-tap styles, the darkroom steps, adjustments, borders, framing).
 * The selected entry sits on an inverted near-white pill with dark ink and a
 * filled icon, so "selected" is told by tone and shape, never by colour alone.
 */
private val CATEGORIES = listOf("风格", "冲洗", "调整", "边框", "构图")
/** Which drawn icon each entry uses: a highlight, the enlarger, sliders, a film frame, crop corners. */
private val ICONS = intArrayOf(3, 2, 1, 0, 4)

@Composable
fun CategoryBar(selected: Int, enabled: Boolean, trailing: @Composable () -> Unit = {}, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().background(Palette.Background).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(64.dp)
                .background(Palette.Panel, RoundedCornerShape(32.dp))
                .border(1.dp, Palette.OutlineSoft, RoundedCornerShape(32.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CATEGORIES.forEachIndexed { i, label ->
                val on = i == selected
                val tint by animateColorAsState(
                    when {
                        on -> Palette.OnAccent
                        enabled -> Palette.OnSurface
                        else -> Palette.OnSurfaceSoft.copy(alpha = 0.38f)
                    },
                    Motion.defaultEffects(), label = "tint",
                )
                val fill by animateColorAsState(
                    if (on) Palette.Accent else Palette.Panel.copy(alpha = 0f), Motion.defaultEffects(), label = "fill")
                val corner by animateDpAsState(if (on) 28.dp else 16.dp, Motion.fastSpatial(), label = "corner")
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(fill, RoundedCornerShape(corner))
                        .selectable(
                            selected = on, role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                        ) { view.tick(); onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Canvas(Modifier.size(22.dp)) { icon(ICONS[i], tint, on) }
                    Spacer(Modifier.height(2.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
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
