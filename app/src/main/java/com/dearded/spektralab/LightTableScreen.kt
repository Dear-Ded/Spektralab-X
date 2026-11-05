package com.dearded.spektralab

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** The head's own height, under the status bar. */
internal val HeadHeight = 56.dp

/**
 * The light table's grid (docs/UI.md 2.9, ui-screens_research.md 1): the
 * frames on the table, newest capture first, 2 / 3 / 4 / 6 columns by
 * pinching. The grid runs edge to edge under a see-through head and the
 * floating bar (网格 / 按卷 and the round 添加). A long press starts a
 * selection and dragging from it selects the run in between; while frames
 * are selected the bar carries what can be done with them.
 *
 * Back here only clears a selection. Leaving the table is LightTableNav's,
 * which follows the finger back to the editor.
 */
@Composable
fun LightTableScreen(
    table: LightTableViewModel,
    onBack: () -> Unit,
    onOpen: (List<Frame>, Int) -> Unit,
    onRolls: () -> Unit,
    selectionActions: @Composable (List<Frame>) -> Unit = {},
) {
    val frames by table.frames.collectAsState()
    val state by table.state.collectAsState()
    val visible = remember(frames, state.filter) { table.visible(frames, state.filter) }
    val snackbar = remember { SnackbarHostState() }
    val selecting = state.selection.isNotEmpty()
    val add = rememberAddPhotos(table)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            table.consumeMessage()
        }
    }
    BackHandler(enabled = selecting) { table.clearSelection() }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HeadHeight
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        if (frames.isEmpty()) {
            EmptyTable(add)
        } else {
            FrameGrid(table, visible, state, onOpen, top) {
                FilterRow(frames, state.filter, table::setFilter, state.filmThumbs, table::setFilmThumbs)
                if (visible.isEmpty()) {
                    Text("没有${state.filter.label}的照片", Modifier.fillMaxWidth().padding(32.dp),
                        color = Palette.OnSurfaceSoft, textAlign = TextAlign.Center)
                }
            }
        }

        TableHead(
            title = if (selecting) "已选 ${state.selection.size}" else "观片台",
            subtitle = if (selecting || frames.isEmpty()) "" else "${frames.size} 张 · 双指缩放改列数",
            selecting = selecting,
            onBack = { if (selecting) table.clearSelection() else onBack() },
            trailing = {
                if (selecting) QuietButton("全选") { table.selectAll(visible.map { it.id }) }
            },
        )
        // Reading photos in: the working bar under the head, never a halo round a photo (UI2.md 1.1).
        if (state.importing > 0) AuraBar(Modifier.padding(top = top))

        Box(Modifier.align(Alignment.BottomCenter)) {
            if (frames.isNotEmpty()) {
                AnimatedVisibility(!selecting, enter = barIn(), exit = barOut()) {
                    TableBar(rolls = false, onGrid = {}, onRolls = onRolls, add = add)
                }
            }
            AnimatedVisibility(selecting, enter = barIn(), exit = barOut()) {
                val chosen = visible.filter { it.id in state.selection }
                FloatingBar(scroll = true) {
                    selectionActions(chosen)
                    QuietButton("移出观片台", onClick = table::removeSelected)
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingBarSpace),
        )
    }
}

/** The bar comes up from under the screen's edge and leaves the same way (UI2.md 3.2: panels on the default spring). */
internal fun barIn() = slideInVertically(Motion.defaultSpatialOffset()) { it } + fadeIn(Motion.defaultEffects())
internal fun barOut() = slideOutVertically(Motion.defaultSpatialOffset()) { it } + fadeOut(Motion.fastEffects())

/**
 * The see-through head over the grid: back (or ✕ while selecting), the title
 * and one quiet line, a fade from the ground down so frames pass under it
 * without a hard edge. Shared by the grid and the roll list.
 */
@Composable
internal fun TableHead(
    title: String,
    subtitle: String,
    selecting: Boolean = false,
    backLabel: String = "返回编辑器",
    onBack: () -> Unit,
    /** Drawn before the title on its line, e.g. a roll's process dot. */
    titleLeading: @Composable () -> Unit = {},
    /** The subtitle's style, when it is not a plain hint (a roll's edge line). */
    subtitleStyle: androidx.compose.ui.text.TextStyle? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Palette.Background, Palette.Background.copy(alpha = 0.92f), Color.Transparent)))
            .statusBarsPadding()
            .height(HeadHeight)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            if (selecting) Icon(Icons.Default.Close, contentDescription = "取消选择")
            else Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                titleLeading()
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (subtitle.isNotEmpty()) {
                if (subtitleStyle != null) Text(subtitle, style = subtitleStyle, color = Palette.OnSurfaceSoft, maxLines = 1)
                else Hint(subtitle)
            }
        }
        trailing()
    }
}

@Composable
private fun FrameGrid(
    table: LightTableViewModel,
    visible: List<Frame>,
    state: TableState,
    onOpen: (List<Frame>, Int) -> Unit,
    top: Dp,
    header: @Composable () -> Unit,
) {
    val view = LocalView.current
    val gridState = rememberLazyGridState()
    val ids = remember(visible) { visible.map { it.id } }
    val currentIds by rememberUpdatedState(ids)
    val currentSelection by rememberUpdatedState(state.selection)
    val currentColumns by rememberUpdatedState(state.columns)
    val selecting = state.selection.isNotEmpty()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingBarSpace

    // Dragging near the top or bottom edge during a drag-select scrolls the grid.
    var autoScroll by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(autoScroll) {
        if (autoScroll != 0f) while (isActive) { gridState.scrollBy(autoScroll); delay(10) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 4 dp between cells and at the sides: with rounded cells a 2 dp seam reads as broken.
        val gap = 4.dp
        val cellPx = with(LocalDensity.current) {
            ((maxWidth - gap * (state.columns + 1)) / state.columns).roundToPx()
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(state.columns),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            contentPadding = PaddingValues(start = gap, end = gap, top = top, bottom = bottom),
            modifier = Modifier
                .fillMaxSize()
                // Two fingers change the column count and never scroll or select.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoom = 1f
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                zoom *= event.calculateZoom()
                                val cols = LightTableViewModel.COLUMNS
                                val i = cols.indexOf(currentColumns)
                                val next = when {
                                    zoom > 1.25f -> i - 1   // fingers apart: bigger cells
                                    zoom < 0.8f -> i + 1
                                    else -> i
                                }
                                if (next != i) {
                                    zoom = 1f
                                    if (next in cols.indices) { table.setColumns(cols[next]); view.tick(strong = true) }
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    var base = emptySet<String>()
                    var start = -1
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            gridState.frameAt(at, currentIds)?.let { i ->
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                start = i
                                base = currentSelection
                                table.selectRange(base, listOf(currentIds[i]))
                            }
                        },
                        onDragEnd = { start = -1; autoScroll = 0f },
                        onDragCancel = { start = -1; autoScroll = 0f },
                        onDrag = { change, _ ->
                            if (start >= 0) {
                                val y = change.position.y
                                val edge = 64.dp.toPx()
                                autoScroll = when {
                                    y > size.height - edge -> 8.dp.toPx()
                                    y < top.toPx() + edge -> -8.dp.toPx()
                                    else -> 0f
                                }
                                gridState.frameAt(change.position, currentIds)?.let { i ->
                                    val ids = currentIds
                                    if (start < ids.size) {
                                        table.selectRange(base, ids.subList(minOf(start, i), maxOf(start, i) + 1))
                                    }
                                }
                            }
                        },
                    )
                },
        ) {
            // The filters scroll away with the frames, as Google Photos' date pill does.
            item(key = HEADER, span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Column { header() }
            }
            items(visible, key = { it.id }, contentType = { "frame" }) { f ->
                val selected = f.id in state.selection
                FrameCell(
                    frame = f, px = cellPx, selected = selected,
                    film = if (state.filmThumbs) rememberSidecarEdit(table.store, f).value else null,
                    // Nearest the top of the screen first, read only when the worker chooses
                    // (reading the scroll position here would recompose every cell per frame).
                    priority = { kotlin.math.abs(currentIds.indexOf(f.id) + 1 - gridState.firstVisibleItemIndex) },
                    onClick = {
                        if (selecting) table.toggle(f.id)
                        else onOpen(visible, visible.indexOf(f))
                    },
                    // TalkBack's long-click action; a finger's long press is the grid's drag-select.
                    onSelect = { table.selectRange(currentSelection, listOf(f.id)) },
                )
            }
        }
    }
}

private const val HEADER = "header"

/** The frame under [at], as its index in [ids]; null over the filters or a gap. */
private fun LazyGridState.frameAt(at: Offset, ids: List<String>): Int? {
    val p = at.round()
    val key = layoutInfo.visibleItemsInfo.firstOrNull { IntRect(it.offset, it.size).contains(p) }?.key as? String
    return key?.let(ids::indexOf)?.takeIf { it >= 0 }
}

@Composable
private fun FrameCell(
    frame: Frame, px: Int, selected: Boolean, film: Edit?, priority: () -> Int,
    onClick: () -> Unit, onSelect: () -> Unit,
) {
    val plain by rememberThumbnail(frame, px)
    // 胶片 thumbnails show the frame's own edit once the idle path has developed it; the plain one until then.
    val developed by rememberFilmThumb(frame, film, px, priority)
    val thumb = developed ?: plain
    // Selected steps in a little and takes the white ring (UI2.md 1.2), so the choice reads at a glance.
    val scale by animateFloatAsState(if (selected) 0.92f else 1f, Motion.fastSpatial(), label = "cell-select")
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // UI2.md 3.7: the cell grows into the loupe and shrinks back (Transitions.kt).
            .sharedFrame(frame.id, shape)
            .clip(shape)
            .background(Palette.Panel)
            .semantics {
                contentDescription = describe(frame)
                this.selected = selected
                onLongClick(label = "多选") { onSelect(); true }
            }
            // A plain click: a long press belongs to the grid, which drag-selects from it.
            .clickable(onClick = onClick),
    ) {
        thumb?.let { b ->
            val image = remember(b) { b.asImageBitmap() }
            // A rejected frame stays visible but steps back.
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, alpha = if (frame.pick < 0) 0.4f else 1f)
        }
        if (selected) Box(Modifier.fillMaxSize().border(2.dp, Palette.Accent, shape))
        FrameMarks(frame, selected)
    }
}

/** Marks never rely on colour alone (docs/UI.md 0.5): each has its own glyph or shape. */
@Composable
internal fun FrameMarks(frame: Frame, selected: Boolean) {
    Box(Modifier.fillMaxSize().padding(4.dp)) {
        when {
            frame.pick > 0 -> MarkBadge("✓", Modifier.align(Alignment.TopStart))
            frame.pick < 0 -> MarkBadge("✕", Modifier.align(Alignment.TopStart))
        }
        if (frame.edited) EditedDot(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        if (frame.stars > 0) {
            Text(
                "★${frame.stars}",
                Modifier.align(Alignment.BottomStart)
                    .background(Palette.Background.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                color = Palette.OnSurface, fontSize = 12.sp,
            )
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(20.dp).background(Palette.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("✓", color = Palette.OnAccent, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun MarkBadge(glyph: String, modifier: Modifier) {
    Box(
        modifier.size(20.dp).background(Palette.Background.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Palette.OnSurface, fontSize = 12.sp) }
}

internal fun describe(f: Frame): String = buildString {
    append(f.name)
    when {
        f.pick > 0 -> append("，已选中")
        f.pick < 0 -> append("，已淘汰")
    }
    if (f.stars > 0) append("，${f.stars} 星")
    if (f.edited) append("，已编辑")
}

/**
 * Which frames show, each pill with its count, then the thumbnail mode as one
 * named switch. Its name does not change with its state: on is the inverted
 * pill, as everywhere (UI2.md 1.2).
 */
@Composable
private fun FilterRow(
    frames: List<Frame>, current: FrameFilter, onPick: (FrameFilter) -> Unit,
    filmThumbs: Boolean, onFilmThumbs: (Boolean) -> Unit,
) {
    PillRow(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        for (f in FrameFilter.entries) {
            val n = if (f == FrameFilter.ALL) frames.size else frames.count(f::keeps)
            SelectPill(selected = f == current, onClick = { onPick(f) }, label = { Text("${f.label} $n") })
        }
        Spacer(Modifier.width(8.dp))
        // UI.md 2.9: thumbnails as shot, or through each frame's own film (developed only when idle).
        SelectPill(selected = filmThumbs, onClick = { onFilmThumbs(!filmThumbs) }, label = { Text("胶片缩略图") })
    }
}

/** The empty table: the glow UI2.md 1.1 allows on an empty page, one primary action and the RAW route under it. */
@Composable
private fun EmptyTable(add: AddPhotos) {
    Box(Modifier.fillMaxSize()) {
        Aura(Modifier.fillMaxSize(), strength = 0.5f)
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("把要挑的照片放上观片台", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "在这里挑片、打星、按卷整理，再批量冲洗导出。放上来不会冲洗任何照片；编辑记录只存在本应用里，不往相册写文件。",
                style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurfaceSoft, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = add.gallery,
                modifier = Modifier.height(56.dp),
                contentPadding = PaddingValues(horizontal = 28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
            ) { Text("从相册添加", style = MaterialTheme.typography.titleSmall) }
            Spacer(Modifier.height(8.dp))
            QuietButton("从文件添加（RAW / DNG）", onClick = add.files)
        }
    }
}

/** One haptic event from the map in docs/UI.md 1: [api30] on Android 11+, else [fallback]. */
internal fun View.feel(api30: Int, fallback: Int) {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) api30 else fallback)
}
