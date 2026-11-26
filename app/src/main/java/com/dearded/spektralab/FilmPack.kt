package com.dearded.spektralab

import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
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
    // Drawn in a 40 x 48 unit box (preview/ui-feel-parts.js cassette): printed tin round an upright
    // cylinder lit from the upper left, crimped steel caps, a ridged spool knob, the black felt light
    // trap, and the real leader out of it: full width, then the half-width tongue with its holes, in
    // the colour of the film's base. Chosen, it lifts and more leader comes out.
    val u = size.width / 40f
    val cw = 21f * u
    val ch = 33f * u
    val x = 3f * u
    val y = size.height - ch - 5f * u - 5f * u * lift
    // the contact shadow, softer and smaller as it lifts
    for (k in 0..2) drawOval(Color.Black.copy(alpha = (0.22f - 0.07f * lift) * (1f - k * 0.25f)),
        Offset(x - (1f + k) * u + lift * u, size.height - (5.4f + k * 0.6f) * u), Size(cw + (4f + 2f * k) * u - 2f * lift * u, (2.6f + k * 1.2f) * u))
    // the leader
    val base = when (kind) {
        FilmKind.NEGATIVE -> Color(0xFFB06834); FilmKind.SLIDE -> Color(0xFF464C54)
        FilmKind.CINE -> Color(0xFF28221E); FilmKind.MONO -> Color(0xFF464644)
    }
    val len = (5f + 11f * lift) * u
    val fy = y + 5f * u; val fh = ch - 10f * u; val fx = x + cw - u
    if (len > 0.5f * u) {
        val leader = Path().apply {
            moveTo(fx, fy); lineTo(fx + 2.5f * u, fy)
            cubicTo(fx + 5f * u, fy, fx + 5f * u, fy + fh * 0.5f, fx + 7f * u, fy + fh * 0.5f)
            lineTo(fx + len - 1.5f * u, fy + fh * 0.5f)
            quadraticTo(fx + len, fy + fh * 0.5f, fx + len, fy + fh * 0.62f)
            lineTo(fx + len, fy + fh - 1.2f * u)
            quadraticTo(fx + len, fy + fh, fx + len - 1.2f * u, fy + fh)
            lineTo(fx, fy + fh); close()
        }
        drawPath(leader, Brush.verticalGradient(0f to lerp(base, Color.White, 0.12f), 0.5f to base, 1f to lerp(base, Color.Black, 0.35f),
            startY = fy, endY = fy + fh), alpha = 0.94f)
        clipPath(leader) {
            drawRect(Color.White.copy(alpha = 0.16f), Offset(fx, fy + fh * 0.5f + 0.4f * u), Size(len, 0.5f * u))
            var px = fx + 2.2f * u
            while (px < fx + len - u) {
                drawRoundRect(Color.Black.copy(alpha = 0.45f), Offset(px, fy + fh - 2.4f * u), Size(1.3f * u, 1.6f * u), CornerRadius(0.4f * u))
                px += 2.6f * u
            }
        }
    }
    // the felt light trap, black velvet
    drawRoundRect(Color(0xFF0C0B0A), Offset(x + cw - 2.2f * u, y + 3f * u), Size(3.2f * u, ch - 6f * u), CornerRadius(u))
    drawRect(Color.White.copy(alpha = 0.05f), Offset(x + cw - 1.6f * u, y + 4f * u), Size(0.5f * u, ch - 8f * u))
    // the shell
    val sw = cw - 1.5f * u
    val shell = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(x, y + 3f * u, x + sw, y + ch - 3f * u, CornerRadius(1.6f * u))) }
    clipPath(shell) {
        drawRect(Brush.horizontalGradient(0f to lerp(kind.bodyDark, Color.Black, 0.45f), 0.14f to kind.bodyDark, 0.3f to lerp(kind.body, Color.White, 0.12f),
            0.36f to lerp(kind.body, Color.White, 0.32f), 0.42f to kind.body, 0.72f to kind.bodyDark, 1f to lerp(kind.bodyDark, Color.Black, 0.55f),
            startX = x, endX = x + sw), Offset(x, y + 3f * u), Size(sw, ch - 6f * u))
        drawRect(kind.band, Offset(x, y + ch * 0.6f), Size(cw, ch * 0.13f), blendMode = androidx.compose.ui.graphics.BlendMode.Multiply)
        // DX contacts: bare metal and paint, narrowing toward the shell's edges
        val bits = dx(speed)
        for (i in 0 until 12) {
            val cx = x + 2f * u + (i % 6) * 2.6f * u
            val t = (cx - x) / sw * 2f - 1f
            val c = kotlin.math.sqrt(maxOf(0.05f, 1f - t * t))
            val cy = y + 6f * u + (i / 6) * 2.6f * u
            if (bits[i]) drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFEFEAE0), Color(0xFFA29C90)), startY = cy, endY = cy + 2f * u),
                Offset(cx, cy), Size(2f * u * c, 2f * u), CornerRadius(0.35f * u))
            else drawRoundRect(Color(0xEB12110F), Offset(cx, cy), Size(2f * u * c, 2f * u), CornerRadius(0.35f * u))
        }
        if (speed != null) {
            val tl = measurer.measure(speed, style.copy(color = kind.ink, fontSize = (6.5f * u / density).sp))
            drawText(tl, topLeft = Offset(x + sw * 0.46f - tl.size.width / 2f, y + ch * 0.48f - tl.size.height * 0.75f))
        }
        // the light's soft reflection on the tin
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.28f), 1f to Color.Transparent,
            startX = x + cw * 0.26f, endX = x + cw * 0.44f), Offset(x, y), Size(cw, ch))
    }
    // crimped steel caps
    val cap = Brush.horizontalGradient(0f to Color(0xFF1E1D1B), 0.18f to Color(0xFF4B4943), 0.34f to Color(0xFFB9B5AC), 0.42f to Color(0xFFD9D5CC),
        0.6f to Color(0xFF77736B), 1f to Color(0xFF1C1B19), startX = x - 0.7f * u, endX = x + cw - 0.3f * u)
    for (cy in floatArrayOf(y, y + ch - 3.6f * u)) {
        drawRoundRect(cap, Offset(x - 0.7f * u, cy), Size(cw + 0.4f * u, 3.6f * u), CornerRadius(1.4f * u))
        drawRect(Color.White.copy(alpha = 0.22f), Offset(x - 0.2f * u, cy + 0.3f * u), Size(cw - 0.6f * u, 0.5f * u))
        drawRect(Color.Black.copy(alpha = 0.35f), Offset(x - 0.2f * u, cy + 3f * u), Size(cw - 0.6f * u, 0.6f * u))
    }
    // the spool's knob, ridged
    drawRoundRect(Brush.horizontalGradient(0f to Color(0xFF1B1A18), 0.35f to Color(0xFF8B877E), 0.5f to Color(0xFFA8A49B), 1f to Color(0xFF1D1C1A),
        startX = x + 6.5f * u, endX = x + 14f * u), Offset(x + 6.5f * u, y - 3.4f * u), Size(7.5f * u, 4f * u), CornerRadius(1.2f * u))
    for (r in 0 until 4) drawRect(Color.Black.copy(alpha = 0.35f), Offset(x + 7.5f * u + r * 1.7f * u, y - 3.4f * u), Size(0.5f * u, 4f * u))
    drawRect(Color.Black.copy(alpha = 0.4f), Offset(x, y + 3f * u), Size(sw, 0.6f * u))
}

/**
 * A box of photographic paper over a card's lower corner, [chosen] lifting its lid a little: a
 * flat box, the lid's band in the colour of its kind (colour paper warm, black-and-white grey), the
 * sheet's white edge showing under the lid. Original artwork, no maker's trade dress.
 */
@Composable
internal fun PaperBadge(bw: Boolean, chosen: Boolean, modifier: Modifier = Modifier) {
    val lift by animateFloatAsState(if (chosen) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 520f), label = "paper")
    Canvas(modifier.size(40.dp, 34.dp)) {
        val u = size.width / 40f
        val x = 4f * u
        val w = 30f * u
        val h = 16f * u
        val y = size.height - h - 5f * u
        drawOval(Color.Black.copy(alpha = 0.42f), Offset(x - 2f * u, size.height - 5f * u), Size(w + 4f * u, 3.6f * u))
        // The box.
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF2C2A26), Color(0xFF1C1B18)), startY = y, endY = y + h), Offset(x, y), Size(w, h), CornerRadius(1.6f * u))
        // The sheet's edge, then the lid, raised by [lift].
        val ly = y - 3.2f * u * lift
        drawRect(Color(0xFFF3EFE6), Offset(x + 1.2f * u, y - 0.8f * u - 1.6f * u * lift), Size(w - 2.4f * u, 1.6f * u + 1.6f * u * lift))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF3A3833), Color(0xFF262420)), startY = ly, endY = ly + 7f * u), Offset(x - 0.6f * u, ly), Size(w + 1.2f * u, 7f * u), CornerRadius(1.6f * u))
        drawRect(if (bw) Color(0xFF9C9A94) else Color(0xFFC48A4A), Offset(x - 0.6f * u, ly + 4.6f * u), Size(w + 1.2f * u, 1.4f * u))
        drawRect(Color.White.copy(alpha = 0.16f), Offset(x + 2f * u, ly + 1f * u), Size(w - 4f * u, 0.8f * u))
    }
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
