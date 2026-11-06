package com.dearded.spektralab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

/*
 * 冲洗 ② as a strip of cards instead of a "相纸：xxx" button and a list
 * dialog (derivatives study, ui2_derivatives.md 1). Only papers that can print
 * the film are shown (colour with colour, B&W with B&W), the film's own
 * target print first and marked 推荐. 无 leads (no_film_research.md 1.4): a
 * slide scanned directly, a negative as its Digital Intermediate. Before 无 a
 * slide had "直接扫描" in front: the
 * engine's scan_film path, no paper at all, as upstream offers it.
 * Selected is the inverted fill plus a 2 dp ring (UI2.md 1.2).
 */
private data class PaperCard(val id: String?, val name: String, val note: String)

@Composable
internal fun PaperStrip(ui: UiState, enabled: Boolean, onChange: (Edit) -> Unit) {
    val e = ui.edit
    val view = androidx.compose.ui.platform.LocalView.current
    val film = ui.catalog.film(e.film)
    val bw = film?.bw == true
    val recommended = film?.targetPrint
    val papers = ui.catalog.papers.filter { it.bw == bw }
        .sortedBy { if (it.id == recommended) 0 else 1 }
    val positive = film?.positive == true
    val none = e.paperNone(positive)
    val cards = buildList {
        add(PaperCard(null, "无", when {
            positive -> "直接扫描"
            bw -> "需要相纸"
            else -> "数字中间片"
        }))
        for (p in papers) add(PaperCard(p.id, p.label,
            if (p.id == recommended) "推荐" else if (p.bw) "黑白相纸" else "彩色相纸"))
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)) {
        items(cards, key = { it.id ?: "_scan" }) { c ->
            val on = if (c.id == null) none else !none && e.paper == c.id
            // 无 is the same card as in the style and film strips (the photo itself, tagged 原图),
            // a size down so it sits beside the text cards (UI2.md 7.2 point 5).
            if (c.id == null) {
                val original = androidx.compose.runtime.remember(ui.before) { ui.before?.asImageBitmap() }
                NoneCard(selected = on, original = original, size = 64.dp,
                    subtitle = if (positive) "直接扫描" else if (bw) "需要相纸" else "数字中间片",
                    enabled = enabled && !(bw && !positive)) {
                    if (!on) {
                        view.tick()
                        onChange(if (positive) e.copy(scanFilm = true) else e.copy(scanFilm = false, digitalIntermediate = true))
                    }
                }
                return@items
            }
            TextCard(
                selected = on,
                title = c.name.removePrefix("Kodak ").removePrefix("Fujifilm ").removePrefix("Ilford "),
                subtitle = c.note,
                enabled = enabled && !(c.id == null && bw && !positive),
            ) {
                if (!on) {
                    view.tick()
                    onChange(when {
                        c.id != null -> e.copy(scanFilm = false, digitalIntermediate = false, paper = c.id)
                        positive -> e.copy(scanFilm = true)
                        else -> e.copy(scanFilm = false, digitalIntermediate = true)
                    })
                }
            }
        }
    }
}
