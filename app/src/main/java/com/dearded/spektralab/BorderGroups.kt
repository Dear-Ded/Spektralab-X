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

/*
 * The 边框 entry's two levels (UI2.md 2.2): pick a kind first, then a border
 * of that kind. Fifty borders in one row was the "边框选择不清晰" complaint.
 * "无边框" stays in front of every group so taking a border off is one tap.
 * Ids not listed here (a border added later) land in 简洁留白 rather than
 * disappearing.
 */
internal object BorderGroups {
    val NAMES = listOf("简洁留白", "胶片片边", "拍立得", "品牌数据条")

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
    onPick: (BorderPreset) -> Unit,
) {
    var group by rememberSaveable { mutableStateOf(BorderGroups.groupOf(current)) }
    PillRow {
        BorderGroups.NAMES.forEachIndexed { i, name ->
            val count = BorderGroups.inGroup(i).size
            SelectPill(selected = group == i, onClick = { group = i }, label = { Text("$name $count") })
        }
    }
    // shots_review_1 S1: every style of the group in view, row after row, not one sideways line.
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Borders.byId("none")?.let { none ->
            SelectPill(selected = current == "none", onClick = { onPick(none) }, label = { Text(none.label) }, enabled = enabled)
        }
        for (p in BorderGroups.inGroup(group)) {
            SelectPill(selected = current == p.id, onClick = { onPick(p) }, label = { Text(p.label) },
                enabled = enabled && !(noFilm && p.edge != null))
        }
    }
    if (noFilm && BorderGroups.inGroup(group).any { it.edge != null })
        Hint("胶片是「无」：片边要有胶片才画得出来，带片边的款式暂时不能选。")
}
