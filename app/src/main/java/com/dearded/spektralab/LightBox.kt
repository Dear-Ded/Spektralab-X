package com.dearded.spektralab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * The light table's light box (preview/ui-feel.html, scene 观片台): an object on a dark desk, not a
 * lit screen. An aluminium bezel; a panel lit from its edges like a real LED light box (5000 K,
 * brightest in the middle, falling off toward the frame; Kaiser Slimlite Plano: ~700 cd/m², CRI 95),
 * faint rows of LEDs behind its diffuser; the strip lies in a polyester sleeve on it. A touch switch
 * in its corner turns it on and off ([LIGHT_BOX], default on); off is the table's old black ground.
 *
 * The photos are never tinted: the light is only round them, through the perforations and the edge
 * print of the positive's black rebate.
 */

/** Settings key: the light box is lit (default true). */
internal const val LIGHT_BOX = "light_box"

/** The panel's light, middle to edge. Brighter than any white in the film, as a light box is. */
internal val PanelLight = Color(0xFFF6F2EA)
private val PanelMid = Color(0xFFECE7DD)
private val PanelEdge = Color(0xFFCFC9BD)
/** The positive's rebate on the light box: dense black, the light only through its holes and print. */
internal val RebateBlack = Color(0xFF050505)
/** The edge print with the light through it. */
internal val EdgeLit = Color(0xFFF3ECDD)

@Composable
internal fun rememberLightBox(): Pair<Boolean, () -> Unit> {
    val prefs = LocalContext.current.getSharedPreferences("settings", 0)
    var on by remember { mutableStateOf(prefs.getBoolean(LIGHT_BOX, true)) }
    return on to { on = !on; prefs.edit().putBoolean(LIGHT_BOX, on).apply() }
}

@Composable
internal fun LightBox(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val bezel = RoundedCornerShape(16.dp)
    val panel = RoundedCornerShape(9.dp)
    Box(
        modifier
            .shadow(18.dp, bezel, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(bezel)
            .background(Brush.linearGradient(listOf(Color(0xFF4B4945), Color(0xFF2A2926), Color(0xFF1D1C1A), Color(0xFF34322E))))
            .drawWithContent {
                drawContent()
                // the bezel's lit top edge and shaded bottom edge
                drawLine(Color.White.copy(alpha = 0.18f), Offset(16.dp.toPx(), 0.5f), Offset(size.width - 16.dp.toPx(), 0.5f), 1f)
                drawLine(Color.Black.copy(alpha = 0.6f), Offset(16.dp.toPx(), size.height - 0.5f), Offset(size.width - 16.dp.toPx(), size.height - 0.5f), 1f)
            }
            .padding(9.dp),
    ) {
        Box(
            Modifier.fillMaxSize().clip(panel)
                .drawWithContent {
                    if (on) {
                        drawRect(Brush.radialGradient(0f to PanelLight, 0.6f to PanelMid, 1f to PanelEdge,
                            center = center, radius = maxOf(size.width, size.height) * 0.62f))
                        // the diffuser: faint rows of LEDs behind it
                        val step = 22.dp.toPx()
                        var x = step; while (x < size.width) { drawRect(Color.Black.copy(alpha = 0.012f), Offset(x, 0f), Size(1f, size.height)); x += step }
                        var y = step; while (y < size.height) { drawRect(Color.White.copy(alpha = 0.02f), Offset(0f, y), Size(size.width, 1f)); y += step }
                    } else drawRect(Color(0xFF0B0B0A))
                    drawContent()
                    // the panel sits a little below the bezel
                    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent, endY = 4.dp.toPx()),
                        size = Size(size.width, 4.dp.toPx()))
                },
            contentAlignment = Alignment.Center,
        ) { content() }
        // the touch switch, in the bezel's corner
        Box(
            Modifier.align(Alignment.BottomEnd).padding(10.dp).size(40.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color.White.copy(alpha = if (on) 0.5f else 0.14f), Color.White.copy(alpha = 0.06f))))
                .clickable(role = Role.Switch) { Feedback.play(view, if (on) Cue.ToggleOff else Cue.ToggleOn); onToggle() }
                .semantics { contentDescription = if (on) tr("关灯箱") else tr("开灯箱") },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val ink = if (on) Color(0xFF3A3935) else Color(0xFFB9B4AA)
                val r = size.minDimension
                drawCircle(ink, r * 0.17f, style = Stroke(1.8.dp.toPx()))
                for (k in 0 until 8) {
                    val a = k * Math.PI / 4
                    drawLine(ink, center + Offset((cos(a) * r * 0.32f).toFloat(), (sin(a) * r * 0.32f).toFloat()),
                        center + Offset((cos(a) * r * 0.44f).toFloat(), (sin(a) * r * 0.44f).toFloat()), 1.8.dp.toPx())
                }
            }
        }
    }
}

/** A polyester sleeve over the strip on the box: its sheen and its pressed edges. Draw over the strip. */
internal fun Modifier.sleeve(on: Boolean): Modifier = if (!on) this else drawWithContent {
    drawContent()
    drawRect(Brush.linearGradient(0f to Color.Transparent, 0.18f to Color.White.copy(alpha = 0.10f), 0.3f to Color.Transparent,
        0.64f to Color.Transparent, 0.76f to Color.White.copy(alpha = 0.07f), 0.86f to Color.Transparent,
        start = Offset(0f, 0f), end = Offset(size.width * 0.3f, size.height)))
    drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f)
    drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
}

/**
 * A frame on the strip held a little curved in front of the light: [d] its distance from the
 * centre in frame widths. On a drum of radius R it narrows by cos φ, shrinks with depth and moves
 * in; the centre frame is untouched. No 3D rotation: the same in every renderer.
 */
internal fun Modifier.drum(d: () -> Float?): Modifier = graphicsLayer {
    val dd = d() ?: return@graphicsLayer
    val r = 3.2f
    val phi = (dd / r).coerceIn(-1.3f, 1.3f)
    val s = 6f / (6f + r * (1f - cos(phi)))
    scaleX = cos(phi) * s
    scaleY = s
    translationX = (r * sin(phi) - dd) * size.width
}.drawWithContent {
    drawContent()
    val dd = d() ?: return@drawWithContent
    val dim = min(0.45f, abs(dd) * 0.14f)
    if (dim > 0.005f) drawRect(Color.Black.copy(alpha = dim))
}
