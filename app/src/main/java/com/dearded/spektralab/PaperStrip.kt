package com.dearded.spektralab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * 冲洗 ② as a strip of cards instead of a "相纸：xxx" button and a list
 * dialog (derivatives study, ui2_derivatives.md 1). Only papers that can print
 * the film are shown (colour with colour, B&W with B&W), the film's own
 * target print first and marked 推荐. A slide adds "直接扫描" in front: the
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
    val cards = buildList {
        if (film?.positive == true) add(PaperCard(null, "直接扫描", "不经过相纸，反转片原本的样子"))
        for (p in papers) add(PaperCard(p.id, p.label,
            if (p.id == recommended) "推荐 · 这卷胶片的搭档" else if (p.bw) "黑白相纸" else "彩色相纸"))
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        items(cards, key = { it.id ?: "_scan" }) { c ->
            val on = if (c.id == null) e.scanFilm else !e.scanFilm && e.paper == c.id
            TextCard(
                selected = on,
                title = c.name.removePrefix("Kodak ").removePrefix("Fujifilm ").removePrefix("Ilford "),
                subtitle = c.note,
                enabled = enabled,
            ) {
                if (!on) {
                    view.tick()
                    onChange(if (c.id == null) e.copy(scanFilm = true) else e.copy(scanFilm = false, paper = c.id))
                }
            }
        }
    }
}
