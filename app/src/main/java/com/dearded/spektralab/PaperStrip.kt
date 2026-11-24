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
 * target print first and marked 推荐. 无 leads (no_film_research.md 1.4): a
 * slide scanned directly, a negative as its Digital Intermediate. Before 无 a
 * slide had "直接扫描" in front: the
 * engine's scan_film path, no paper at all, as upstream offers it.
 * Selected is the inverted fill plus a 2 dp ring (UI2.md 1.2).
 */
private data class PaperCard(val id: String?, val name: String, val note: String)

@Composable
internal fun PaperStrip(ui: UiState, enabled: Boolean,
                       preview: suspend (Edit) -> android.graphics.Bitmap? = { null }, onChange: (Edit) -> Unit) {
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
        add(PaperCard(null, tr("无"), when {
            positive -> tr("直接扫描")
            bw -> tr("需要相纸")
            else -> tr("数字中间片")
        }))
        for (p in papers) add(PaperCard(p.id, p.label,
            if (p.id == recommended) tr("推荐") else if (p.bw) tr("黑白相纸") else tr("彩色相纸")))
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)) {
        items(cards, key = { it.id ?: "_scan" }) { c ->
            val on = if (c.id == null) none else !none && e.paper == c.id
            // 无 is the same card as in the style and film strips (the photo itself, tagged 原图),
            // the size of the paper cards beside it.
            if (c.id == null) {
                val candidate = if (positive) e.copy(scanFilm = true) else e.copy(scanFilm = false, digitalIntermediate = true)
                val original = rememberSelectionPreview(ui, candidate, preview)
                NoneCard(selected = on, original = original, size = 88.dp,
                    subtitle = if (positive) tr("直接扫描") else if (bw) tr("需要相纸") else tr("数字中间片"),
                    enabled = enabled && !(bw && !positive)) {
                    if (!on) {
                        view.tick()
                        onChange(candidate)
                    }
                }
                return@items
            }
            // The photo printed on this paper (as the film cards show it on each film), a paper box on
            // its corner; the film's own print paper says 推荐 over it.
            val candidate = e.copy(scanFilm = false, digitalIntermediate = false, paper = c.id)
            val print = rememberSelectionPreview(ui, candidate, preview)
            ThumbCard(
                selected = on,
                image = print,
                title = c.name.removePrefix("Kodak ").removePrefix("Fujifilm ").removePrefix("Ilford "),
                subtitle = "",
                enabled = enabled,
                size = 88.dp,
                badge = if (c.id == recommended) tr("推荐") else null,
                overlay = { PaperBadge(bw, on, Modifier.align(androidx.compose.ui.Alignment.BottomStart)) },
            ) {
                if (!on) {
                    Feedback.play(view, Cue.Load)
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
