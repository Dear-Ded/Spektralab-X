package com.dearded.spektralab

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.clipPath

/*
 * The 边框 entry's two levels (UI2.md 2.2): pick a kind first, then a border
 * of that kind. Fifty borders in one row was the "边框选择不清晰" complaint.
 * "无边框" stays in front of every group so taking a border off is one tap.
 * Ids not listed here (a border added later) land in 简洁留白 rather than
 * disappearing.
 */
internal object BorderGroups {
    val NAMES get() = listOf(tr("留白"), tr("胶片片边"), tr("拍立得"), tr("品牌数据条"))

    private val FILM = setOf(
        "scan135", "filed", "6x6", "645", "leak", "xpan", "edge135_name", "edge135_black", "edge135_num",
        "edge_6x7", "edge_6x8", "edge_6x9", "edge_6x17", "edge_half", "enlarger", "date_lcd", "date_data",
    )
    private val INSTANT = setOf(
        "instant_sq", "instant_mini", "instant_600", "instax_square", "instax_wide", "polaroid_go", "instax_mini_black",
    )
    private val BARS = setOf(
        "exif_bar", "exif_bar_dark", "logo_split", "logo_split_dark", "logo_center", "logo_line", "logo_film",
        "bar_split", "bar_frame", "bar_line", "bar_film",
    )

    fun groupOf(id: String): Int = when (id) {
        in FILM -> 1
        in INSTANT -> 2
        in BARS -> 3
        else -> 0
    }

    fun inGroup(g: Int): List<BorderPreset> = Borders.PRESETS.filter { it.id != "none" && groupOf(it.id) == g }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BorderPicker(
    current: String, enabled: Boolean,
    /** 胶片「无」: a film edge is drawn by the film render, so those styles cannot be picked; said under the row. */
    noFilm: Boolean = false,
    /** The photo as it is on screen, put inside each card's miniature; null draws a plain grey print. */
    photo: androidx.compose.ui.graphics.ImageBitmap? = null,
    onPick: (BorderPreset) -> Unit,
) {
    var group by rememberSaveable { mutableStateOf(BorderGroups.groupOf(current)) }
    PillRow {
        BorderGroups.NAMES.forEachIndexed { i, name ->
            SelectPill(selected = group == i, onClick = { group = i }, label = { Text(name) })
        }
    }
    // Each style as a miniature of the print it makes (this photo in its mat, strip or bar), in a row
    // that scrolls and snaps; 无 leads.
    val view = androidx.compose.ui.platform.LocalView.current
    val list = listOfNotNull(Borders.byId("none")) + BorderGroups.inGroup(group)
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
        items(list.size, key = { list[it].id }) { i ->
            val p = list[i]
            BorderCard(p, selected = current == p.id, enabled = enabled && !(noFilm && p.edge != null), photo = photo) {
                if (current != p.id) { Feedback.play(view, Cue.Pick); onPick(p) }
            }
        }
    }
    if (noFilm && BorderGroups.inGroup(group).any { it.edge != null })
        Hint(tr("没有选胶卷，带片边的款式不能选。"))
}

/** One border style: its miniature (88 dp) and its name under it; the chosen one ringed. */
@Composable
private fun BorderCard(p: BorderPreset, selected: Boolean, enabled: Boolean, photo: androidx.compose.ui.graphics.ImageBitmap?, onClick: () -> Unit) {
    val ring by androidx.compose.animation.animateColorAsState(if (selected) Palette.Accent else androidx.compose.ui.graphics.Color.Transparent,
        Motion.defaultEffects(), label = "border-ring")
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.width(88.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .selectable(selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.size(88.dp).clip(Corners.m)
            .background(Palette.Panel).border(2.dp, ring, Corners.m)) { borderMiniature(p, photo) }
        Text(p.label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            color = if (selected) Palette.OnSurface else Palette.OnSurfaceSoft,
            modifier = androidx.compose.ui.Modifier.padding(top = 6.dp))
    }
}

/**
 * The print a style makes, drawn small: paper of the style's colour, its margins (top, bottom,
 * sides, or the card's aspect), round corners, a keyline, a film strip with holes for edge styles,
 * a caption bar's line for caption styles. A sketch of the layout, not the engine's render.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.borderMiniature(p: BorderPreset, photo: androidx.compose.ui.graphics.ImageBitmap?) {
    val f = p.frame
    val pad = 10.dp.toPx()
    val box = androidx.compose.ui.geometry.Rect(pad, pad, size.width - pad, size.height - pad)
    val imgAspect = photo?.let { it.width.toFloat() / it.height } ?: 1.5f
    // The print with its margins, as fractions of the picture's short edge.
    val short = 1f
    var pw = imgAspect.coerceAtLeast(1f) * short
    var ph = short / imgAspect.coerceAtMost(1f)
    if (imgAspect < 1f) { pw = short; ph = short / imgAspect }
    var cw = pw + 2f * f.side * short
    var ch = ph + (f.top + f.bottom) * short
    if (p.edge != null) { cw = pw * 1.04f; ch = ph * 1.42f }
    if (f.canvasAspect > 0f) { if (cw / ch < f.canvasAspect) cw = ch * f.canvasAspect else ch = cw / f.canvasAspect }
    val k = minOf(box.width / cw, box.height / ch)
    val card = androidx.compose.ui.geometry.Rect(box.center.x - cw * k / 2f, box.center.y - ch * k / 2f, box.center.x + cw * k / 2f, box.center.y + ch * k / 2f)
    val px = card.left + (cw - pw) * k / 2f
    val py = if (p.edge != null || f.canvasAspect > 0f) card.top + (ch - ph) * k / 2f else card.top + f.top * short * k
    val pic = androidx.compose.ui.geometry.Rect(px, py, px + pw * k, py + ph * k)
    val paper = androidx.compose.ui.graphics.Color(f.paper)
    // Shadow, then the paper (or the film strip).
    drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f), card.topLeft + androidx.compose.ui.geometry.Offset(0f, 1.5.dp.toPx()), card.size)
    if (p.edge != null) {
        drawRect(androidx.compose.ui.graphics.Color(0xFF16140F), card.topLeft, card.size)
        val hw = card.width / 11f
        for (row in listOf(card.top + (pic.top - card.top) * 0.35f, pic.bottom + (card.bottom - pic.bottom) * 0.3f)) {
            var x = card.left + hw * 0.4f
            while (x < card.right - hw * 0.5f) {
                drawRoundRect(androidx.compose.ui.graphics.Color(0xFF6E6A60), androidx.compose.ui.geometry.Offset(x, row), androidx.compose.ui.geometry.Size(hw * 0.55f, (card.bottom - pic.bottom) * 0.35f),
                    androidx.compose.ui.geometry.CornerRadius(1f))
                x += hw
            }
        }
    } else if (p.id != "none") drawRect(paper, card.topLeft, card.size)
    // The picture.
    val r = f.radius * minOf(pic.width, pic.height)
    if (photo != null) {
        clipPath(androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(pic, androidx.compose.ui.geometry.CornerRadius(r))) }) {
            drawImage(photo, dstOffset = androidx.compose.ui.unit.IntOffset(pic.left.toInt(), pic.top.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(pic.width.toInt().coerceAtLeast(1), pic.height.toInt().coerceAtLeast(1)))
        }
    } else drawRoundRect(androidx.compose.ui.graphics.Color(0xFF6A665E), pic.topLeft, pic.size, androidx.compose.ui.geometry.CornerRadius(r))
    if (f.keyline > 0f) {
        val g = (f.keylineGap * minOf(pw, ph) * k).coerceAtLeast(1.5f)
        val ink = if (paper.luminance() > 0.5f) androidx.compose.ui.graphics.Color(0xFF2A2824) else androidx.compose.ui.graphics.Color(0xFFDAD5CB)
        drawRect(ink, pic.topLeft - androidx.compose.ui.geometry.Offset(g, g), androidx.compose.ui.geometry.Size(pic.width + 2 * g, pic.height + 2 * g),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
    }
    if (f.caption) {
        val ink = if (paper.luminance() > 0.5f) androidx.compose.ui.graphics.Color(0x992A2824) else androidx.compose.ui.graphics.Color(0x99DAD5CB)
        val y = pic.bottom + (card.bottom - pic.bottom) * 0.5f
        drawRect(ink, androidx.compose.ui.geometry.Offset(pic.left, y - 1f), androidx.compose.ui.geometry.Size(pic.width * 0.35f, 2f))
        drawRect(ink, androidx.compose.ui.geometry.Offset(pic.right - pic.width * 0.18f, y - 1f), androidx.compose.ui.geometry.Size(pic.width * 0.18f, 2f))
    }
}
