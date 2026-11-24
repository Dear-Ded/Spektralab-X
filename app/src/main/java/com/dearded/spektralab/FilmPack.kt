package com.dearded.spektralab

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Packaging on the film and paper cards (ui-polish): a stock is something you hold. The card keeps
 * its picture (this photo on that stock, the useful part); a 135 cassette sits on its lower left corner, in
 * the colours of its kind, with its speed, the DX contacts and the leader out of the felt lip. The
 * chosen film's cassette is lifted out a little and its leader drawn further. Original artwork:
 * colours by kind of film, no maker's logo or trade dress.
 *
 * And the darkroom's steps (胶卷 · 相纸 · 曝光 · 效果) run along a strip of film, in order.
 */

/** What kind of film a stock is, for its packaging colours and its process. */
internal enum class FilmKind(val body: Color, val bodyDark: Color, val band: Color, val ink: Color, val process: String) {
    NEGATIVE(Color(0xFFD8C39B), Color(0xFFB89D6E), Color(0xFF8A6A3C), Color(0xFF2A2016), "C-41"),
    SLIDE(Color(0xFFA7B8C6), Color(0xFF7E93A6), Color(0xFF3E5468), Color(0xFF152230), "E-6"),
    CINE(Color(0xFF4A4843), Color(0xFF2F2E2A), Color(0xFFB9A27A), Color(0xFFE9E2D2), "ECN-2"),
    MONO(Color(0xFFE4E0D6), Color(0xFFC3BEB2), Color(0xFF3A3834), Color(0xFF1D1B18), "B&W");

    companion object {
        fun of(s: Stock) = when {
            s.bw -> MONO
            s.positive -> SLIDE
            s.id.contains("vision3") || s.id.startsWith("cinestill") -> CINE
            else -> NEGATIVE
        }
    }
}

/** The speed printed on a stock's cassette: the number at the end of its id, if any. */
internal fun Stock.speed(): String? = Regex("""(\d{2,4})[a-z]*$""").find(id.substringBefore("_push"))?.groupValues?.get(1)

/**
 * A 135 cassette over a card's lower corner, [lift] 0..1 as it is chosen. Drawn in the card's
 * picture box; it covers only the corner, so the picture still reads.
 */
@Composable
internal fun CassetteBadge(stock: Stock, chosen: Boolean, modifier: Modifier = Modifier) {
    val kind = remember(stock.id) { FilmKind.of(stock) }
    val speed = remember(stock.id) { stock.speed() }
    val lift by animateFloatAsState(if (chosen) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 520f), label = "cassette")
    val measurer = rememberTextMeasurer()
    val style = remember { TextStyle(fontFamily = UiSans, fontWeight = FontWeight.Bold, fontSize = 7.sp) }
    Canvas(modifier.size(40.dp, 48.dp)) { cassette(kind, speed, lift, measurer, style) }
}

private fun DrawScope.cassette(kind: FilmKind, speed: String?, lift: Float, measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle) {
    val u = size.width / 40f
    val cw = 22f * u
    val ch = 34f * u
    val x = 3f * u
    val y = size.height - ch - 4f * u - 6f * u * lift
    // A shadow on the picture.
    drawOval(Color.Black.copy(alpha = 0.45f), Offset(x - 2f * u, size.height - 5f * u), Size(cw + 4f * u, 4f * u))
    // The leader out of the felt lip, to the right.
    val lead = (5f + 9f * lift) * u
    drawRect(Color(0xFF2B2722), Offset(x + cw, y + 9f * u), Size(lead, ch - 18f * u))
    // The tube: printed metal, lit from the left.
    drawRoundRect(Brush.horizontalGradient(0f to Color(0xFF1F1D1A), 0.18f to kind.bodyDark, 0.45f to kind.body, 0.8f to kind.bodyDark,
        1f to Color(0xFF26231F), startX = x, endX = x + cw), Offset(x, y + 3f * u), Size(cw, ch - 6f * u), CornerRadius(2f * u))
    drawRect(kind.band, Offset(x, y + ch * 0.58f), Size(cw, ch * 0.13f))
    // DX contacts: metal and black squares.
    val bits = dx(speed)
    for (i in 0 until 12) {
        drawRect(if (bits[i]) Color(0xFFCFCAC0) else Color(0xFF141311),
            Offset(x + 2f * u + (i % 6) * 3f * u, y + 6f * u + (i / 6) * 3f * u), Size(2.4f * u, 2.4f * u))
    }
    if (speed != null) {
        val t = measurer.measure(speed, style.copy(color = kind.ink, fontSize = (6.5f * u / density).sp))
        drawText(t, topLeft = Offset(x + (cw - t.size.width) / 2f, y + ch * 0.36f))
    }
    // End caps and the spool's knob.
    val cap = Brush.horizontalGradient(0f to Color(0xFF3A3833), 0.4f to Color(0xFF8D8980), 1f to Color(0xFF2C2A26), startX = x, endX = x + cw)
    drawRoundRect(cap, Offset(x - 0.7f * u, y), Size(cw + 1.4f * u, 3.6f * u), CornerRadius(1.4f * u))
    drawRoundRect(cap, Offset(x - 0.7f * u, y + ch - 3.6f * u), Size(cw + 1.4f * u, 3.6f * u), CornerRadius(1.4f * u))
    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF2A2824), Color(0xFF6F6B63), Color(0xFF26241F)), startX = x + 7f * u, endX = x + 15f * u),
        Offset(x + 7f * u, y - 3.5f * u), Size(8f * u, 4.2f * u), CornerRadius(1.2f * u))
    drawRect(Color.White.copy(alpha = 0.22f), Offset(x + cw * 0.42f, y + 4f * u), Size(0.9f * u, ch - 8f * u))
}

/** A DX-like checker from the speed: 12 cells, metal or black; the first of each row is always metal. */
private fun dx(speed: String?): BooleanArray {
    val n = speed?.toIntOrNull() ?: 100
    val v = (n * 2654435761L).toInt() xor 0x5a5
    return BooleanArray(12) { i -> i % 6 == 0 || ((v ushr i) and 1) == 1 }
}

/**
 * The darkroom's steps on a strip of film: frame numbers in the rebate, perforations top and
 * bottom, the step on screen a lit frame. Same contract as [Segmented].
 */
@Composable
internal fun FilmRail(steps: List<String>, selected: Int, enabled: Boolean, edited: (Int) -> Boolean, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val num = remember { TextStyle(fontFamily = UiMono, fontWeight = FontWeight.SemiBold, fontSize = 7.sp, color = Color(0xBFC99A4A)) }
    Box(modifier.fillMaxWidth().height(54.dp).clip(Corners.s)
        .background(Brush.verticalGradient(listOf(Color(0xFF141311), Color(0xFF1D1B18), Color(0xFF141311))))) {
        // Perforations and frame numbers along the rebate.
        Canvas(Modifier.fillMaxSize()) {
            val pw = 6.dp.toPx(); val ph = 4.5.dp.toPx(); val pitch = 10.5.dp.toPx()
            var x = 6.dp.toPx()
            while (x < size.width - 6.dp.toPx()) {
                for (yy in floatArrayOf(3.5.dp.toPx(), size.height - 3.5.dp.toPx() - ph)) drawRoundRect(Color(0xFF0A0A09), Offset(x, yy), Size(pw, ph), CornerRadius(1.4.dp.toPx()))
                x += pitch
            }
            val cell = (size.width - 12.dp.toPx()) / steps.size
            for (i in steps.indices) {
                val t = measurer.measure("▸ ${i + 1}", num)
                drawText(t, topLeft = Offset(6.dp.toPx() + i * cell + 2.dp.toPx(), 9.dp.toPx() - t.size.height / 2f + 1.dp.toPx()))
            }
        }
        // Each step takes the strip's whole height to the finger (54 dp); the frame drawn inside it is smaller.
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            steps.forEachIndexed { i, name ->
                val on = i == selected
                Box(
                    Modifier.weight(1f).fillMaxSize()
                        .selectable(on, enabled = enabled, role = Role.Tab) { if (!on) onSelect(i) }
                        .semantics { this.selected = on }
                        .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
                        .padding(vertical = 13.dp),
                ) {
                    Box(
                        Modifier.fillMaxSize().clip(Corners.xs)
                            .background(if (on) Brush.verticalGradient(listOf(Color(0xFFF4F0E8), Color(0xFFE6E1D7))) else Brush.verticalGradient(listOf(Color(0xFF262421), Color(0xFF262421)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(name, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (on) Palette.OnAccent else Palette.OnSurfaceSoft, maxLines = 1)
                        if (edited(i)) EditedDot(onFill = on, modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 4.dp))
                    }
                }
            }
        }
    }
}
