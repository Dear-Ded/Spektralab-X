package com.dearded.spektralab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RollCompare(table: LightTableViewModel, frame: Frame, catalog: Catalog, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val view = LocalView.current
    val base by rememberSidecarEdit(table.store, frame)
    val start = base ?: Edit()
    val groups = remember(catalog) { Style.GROUPS.filter { g -> Style.ALL.any { it.group == g && catalog.film(it.film) != null } } }
    var group by rememberSaveable { mutableStateOf(groups.firstOrNull() ?: "") }
    // null = the frame as it is now (its own edit, or as shot when never edited).
    var chosen by rememberSaveable(frame.id) { mutableStateOf<String?>(null) }
    val styles = remember(group, catalog) { Style.ALL.filter { it.group == group && catalog.film(it.film) != null } }
    fun editOf(s: Style?) = if (s == null) base else s.applyTo(start, catalog)
    val picked = Style.ALL.firstOrNull { it.id == chosen }

    Scaffold(
        containerColor = Palette.Background,
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Surface),
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                title = {
                    Column {
                        Text("比较风格", style = MaterialTheme.typography.titleMedium)
                        Text(frame.name, style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = Palette.Surface) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        picked?.let { "${it.name} · ${it.inspiredBy.ifEmpty { it.group }}" } ?: "当前效果",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = Palette.OnSurfaceSoft, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Button(
                        onClick = {
                            val s = picked ?: return@Button
                            val e = s.applyTo(start, catalog) ?: return@Button
                            table.saveLook(frame, e, s.name)
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                            onClose()
                        },
                        enabled = picked != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Amber, contentColor = Palette.OnAmber),
                    ) { Text("用这个") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // The chosen look, large; the photo itself switches at once (UI.md 0.6).
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.5f).background(Palette.Surround)) {
                val px = with(LocalDensity.current) { maxWidth.roundToPx() }
                CompareImage(frame, editOf(picked), px, priority = { -1 }, ContentScale.Fit)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (g in groups) FilterChip(selected = g == group, onClick = { group = g }, label = { Text(g) })
            }
            val grid = rememberLazyGridState()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cell = (maxWidth - 16.dp * 2 - 8.dp * 2) / 3
                val px = with(LocalDensity.current) { cell.roundToPx() }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = grid,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // The first card is the frame as it stands, so every style is judged against it.
                    val cards: List<Style?> = listOf<Style?>(null) + styles
                    itemsIndexed(cards, key = { _, s -> s?.id ?: "current" }, contentType = { _, _ -> "style" }) { i, s ->
                        val on = s?.id == chosen
                        val shape = RoundedCornerShape(6.dp)
                        Column(
                            Modifier.clickable { chosen = s?.id; view.tick() }
                                .semantics { contentDescription = s?.name ?: "当前效果"; selected = on },
                        ) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(Palette.Panel)
                                    .border(2.dp, if (on) Palette.Amber else Color.Transparent, shape),
                            ) {
                                CompareImage(frame, editOf(s), px, { abs(i - grid.firstVisibleItemIndex) }, ContentScale.Crop)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(s?.name ?: "当前", style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (on) Palette.Amber else Palette.OnSurface)
                            if (s != null) Text(shortFilmName(catalog.film(s.film)?.label ?: s.film), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                color = Palette.OnSurfaceSoft)
                        }
                    }
                }
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
