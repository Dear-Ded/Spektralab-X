package com.dearded.spektralab

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 比较风格: one frame through every style of a group at once (user research:
 * people agonise over which look). The frame's own edit is the base, so its
 * crop and white balance stay; each card is developed on the idle low-res
 * path, nearest the top first. Tapping a card shows it large above the grid;
 * 用这个 writes that style into the frame's sidecar. Nothing is applied until then.
 */
@Composable
fun RollCompare(table: LightTableViewModel, frame: Frame, catalog: Catalog, onClose: () -> Unit) {
    // Back is LightTableNav's SeekBack: it scrubs the compare closed (UI2.md 3.8).
    val view = LocalView.current
    val base by rememberSidecarEdit(table.store, frame)
    val start = base ?: Edit.NONE
    val groups = remember(catalog) { Style.GROUPS.filter { g -> Style.ALL.any { it.group == g && catalog.film(it.film) != null } } }
    var group by rememberSaveable { mutableStateOf(groups.firstOrNull() ?: "") }
    // null = the frame as it is now (its own edit, or as shot when never edited).
    var chosen by rememberSaveable(frame.id) { mutableStateOf<String?>(null) }
    val styles = remember(group, catalog) { Style.ALL.filter { it.group == group && catalog.film(it.film) != null } }
    fun editOf(s: Style?) = if (s == null) base else s.applyTo(start, catalog)
    val picked = Style.ALL.firstOrNull { it.id == chosen }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HeadHeight
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingBarSpace

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Column(Modifier.fillMaxSize().padding(top = top)) {
            // The chosen look, large; the photo itself switches at once (UI.md 0.6).
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.5f).background(Palette.Surround)) {
                val px = with(LocalDensity.current) { maxWidth.roundToPx() }
                CompareImage(frame, editOf(picked), px, priority = { -1 }, ContentScale.Fit)
            }
            PillRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                for (g in groups) SelectPill(selected = g == group, onClick = { group = g }, label = { Text(g) })
            }
            val grid = rememberLazyGridState()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cell = (maxWidth - 16.dp * 2 - 8.dp * 2) / 3
                val px = with(LocalDensity.current) { cell.roundToPx() }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = grid,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottom + 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(RowGap),
                ) {
                    // The first card is the frame as it stands, so every style is judged against it.
                    val cards: List<Style?> = listOf<Style?>(null) + styles
                    itemsIndexed(cards, key = { _, s -> s?.id ?: "current" }, contentType = { _, _ -> "style" }) { i, s ->
                        val on = s?.id == chosen
                        // Corners.kt: the card's frame l, the picture inside it m; selected adds a 2 dp white ring and a check.
                        val shape = Corners.m
                        Column(
                            Modifier.clip(Corners.l).clickable { chosen = s?.id; view.tick() }
                                .semantics { contentDescription = s?.name ?: tr("当前效果"); selected = on },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(Palette.Panel)
                                    .border(2.dp, if (on) Palette.Accent else Color.Transparent, shape),
                            ) {
                                CompareImage(frame, editOf(s), px, { abs(i - grid.firstVisibleItemIndex) }, ContentScale.Crop)
                                if (on) Box(
                                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(Palette.Accent, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { Text("✓", color = Palette.OnAccent, style = MaterialTheme.typography.labelSmall) }
                            }
                            Spacer(Modifier.height(6.dp))
                            // The chosen card's name sits on the inverted fill.
                            Text(s?.name ?: tr("当前"), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) Palette.OnAccent else Palette.OnSurface,
                                modifier = if (on) Modifier.background(Palette.Accent, Corners.s)
                                    .padding(horizontal = 8.dp, vertical = 1.dp) else Modifier)
                            if (s != null) Text(shortFilmName(catalog.film(s.film)?.label ?: s.film), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                color = Palette.OnSurfaceSoft)
                        }
                    }
                }
            }
        }
        TableHead(title = tr("比较风格"), subtitle = frame.name, backLabel = tr("返回"), onBack = onClose)
        FloatingBar(
            Modifier.align(Alignment.BottomCenter),
            trailing = {
                Spacer(Modifier.width(8.dp))
                val can = picked != null
                Box(
                    Modifier.height(64.dp).clip(Corners.full)
                        .background(if (can) Palette.Accent else Palette.PanelHigh)
                        .clickable(enabled = can, role = Role.Button) {
                            val s = picked ?: return@clickable
                            val e = s.applyTo(start, catalog) ?: return@clickable
                            table.saveLook(frame, e, s.name)
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                            onClose()
                        }
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tr("用这个"), style = MaterialTheme.typography.labelLarge,
                        color = if (can) Palette.OnAccent else Palette.OnSurfaceSoft)
                }
            },
        ) {
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(picked?.name ?: tr("当前效果"), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Hint(picked?.let { it.inspiredBy.ifEmpty { it.group } } ?: tr("点一张卡片，在上面看大图"))
            }
        }
    }
}

/** [frame] through [edit] once the idle path has it; the plain thumbnail meanwhile (or for no edit). */
@Composable
private fun CompareImage(frame: Frame, edit: Edit?, px: Int, priority: () -> Int, scale: ContentScale) {
    val plain by rememberThumbnail(frame, px)
    val developed by rememberFilmThumb(frame, edit, px, priority)
    val b = developed ?: plain ?: return
    val image = remember(b) { b.asImageBitmap() }
    Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = scale)
}
