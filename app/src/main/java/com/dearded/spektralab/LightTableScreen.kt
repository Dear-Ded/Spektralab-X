package com.dearded.spektralab

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The light table's grid (docs/UI.md 2.9): the frames on the table, newest
 * capture first, 2 / 3 / 4 / 6 columns by pinching. A long press starts a
 * selection and dragging from it selects the run of frames in between.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightTableScreen(
    table: LightTableViewModel,
    onBack: () -> Unit,
    onOpen: (List<Frame>, Int) -> Unit,
    selectionActions: @Composable (List<Frame>) -> Unit = {},
    topActions: @Composable () -> Unit = {},
) {
    val frames by table.frames.collectAsState()
    val state by table.state.collectAsState()
    val visible = remember(frames, state.filter) { table.visible(frames, state.filter) }
    val snackbar = remember { SnackbarHostState() }
    val selecting = state.selection.isNotEmpty()

    // The system pickers need no permission; the document picker also takes RAWs.
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { table.import(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { table.import(it) }
    val addFromGallery = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val addFromFiles = { pickFiles.launch(arrayOf("*/*")) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            table.consumeMessage()
        }
    }
    BackHandler { if (selecting) table.clearSelection() else onBack() }

    Scaffold(
        containerColor = Palette.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Surface),
                navigationIcon = {
                    IconButton(onClick = { if (selecting) table.clearSelection() else onBack() }) {
                        if (selecting) Icon(Icons.Default.Close, contentDescription = "取消选择")
                        else Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回编辑器")
                    }
                },
                title = {
                    if (selecting) Text("已选 ${state.selection.size}", style = MaterialTheme.typography.titleMedium)
                    else Column {
                        Text("观片台", style = MaterialTheme.typography.titleMedium)
                        Text("${frames.size} 张", style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft)
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = { table.selectAll(visible.map { it.id }) }) { Text("全选") }
                    } else {
                        topActions()
                        Box {
                            var menu by remember { mutableStateOf(false) }
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.Add, contentDescription = "添加照片") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("从相册添加") }, onClick = { menu = false; addFromGallery() })
                                DropdownMenuItem(text = { Text("从文件添加（RAW / DNG）") }, onClick = { menu = false; addFromFiles() })
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selecting) {
                Surface(color = Palette.Surface) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp).horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val chosen = visible.filter { it.id in state.selection }
                        selectionActions(chosen)
                        TextButton(onClick = table::removeSelected) { Text("移出观片台") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.importing > 0) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Amber)
            if (frames.isEmpty()) {
                EmptyTable(addFromGallery, addFromFiles)
                return@Column
            }
            FilterRow(frames, state.filter, table::setFilter, state.filmThumbs, table::setFilmThumbs)
            if (visible.isEmpty()) {
                Text("没有${state.filter.label}的照片", Modifier.fillMaxWidth().padding(32.dp),
                    color = Palette.OnSurfaceSoft, textAlign = TextAlign.Center)
                return@Column
            }
            FrameGrid(table, visible, state, onOpen)
        }
    }
}

@Composable
private fun FrameGrid(table: LightTableViewModel, visible: List<Frame>, state: TableState, onOpen: (List<Frame>, Int) -> Unit) {
    val view = LocalView.current
    val gridState = rememberLazyGridState()
    val ids = remember(visible) { visible.map { it.id } }
    val currentIds by rememberUpdatedState(ids)
    val currentSelection by rememberUpdatedState(state.selection)
    val currentColumns by rememberUpdatedState(state.columns)
    val selecting = state.selection.isNotEmpty()

    // Dragging near the top or bottom edge during a drag-select scrolls the grid.
    var autoScroll by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(autoScroll) {
        if (autoScroll != 0f) while (isActive) { gridState.scrollBy(autoScroll); delay(10) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 2.dp
        val cellPx = with(LocalDensity.current) { ((maxWidth - gap * (state.columns - 1)) / state.columns).roundToPx() }
        LazyVerticalGrid(
            columns = GridCells.Fixed(state.columns),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            contentPadding = PaddingValues(bottom = 24.dp),
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
                            gridState.indexAt(at)?.let { i ->
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
                                    y < edge -> -8.dp.toPx()
                                    else -> 0f
                                }
                                gridState.indexAt(change.position)?.let { i ->
                                    val ids = currentIds
                                    if (i < ids.size && start < ids.size) {
                                        table.selectRange(base, ids.subList(minOf(start, i), maxOf(start, i) + 1))
                                    }
                                }
                            }
                        },
                    )
                },
        ) {
            items(visible, key = { it.id }, contentType = { "frame" }) { f ->
                val selected = f.id in state.selection
                FrameCell(
                    frame = f, px = cellPx, selected = selected,
                    film = if (state.filmThumbs) rememberSidecarEdit(table.store, f).value else null,
                    // Nearest the top of the screen first, read only when the worker chooses
                    // (reading the scroll position here would recompose every cell per frame).
                    priority = { kotlin.math.abs(currentIds.indexOf(f.id) - gridState.firstVisibleItemIndex) },
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

private fun LazyGridState.indexAt(at: Offset): Int? {
    val p = at.round()
    return layoutInfo.visibleItemsInfo.firstOrNull { IntRect(it.offset, it.size).contains(p) }?.index
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
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(Palette.Panel)
            .then(if (selected) Modifier.border(2.dp, Palette.Amber, shape) else Modifier)
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
        if (frame.edited) {
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(6.dp).background(Palette.Amber, CircleShape))
        }
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
                Modifier.align(Alignment.BottomEnd).size(20.dp).background(Palette.Amber, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("✓", color = Palette.OnAmber, fontSize = 12.sp) }
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

@Composable
private fun FilterRow(
    frames: List<Frame>, current: FrameFilter, onPick: (FrameFilter) -> Unit,
    filmThumbs: Boolean, onFilmThumbs: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (f in FrameFilter.entries) {
            val n = if (f == FrameFilter.ALL) frames.size else frames.count(f::keeps)
            FilterChip(selected = f == current, onClick = { onPick(f) }, label = { Text("${f.label} $n") })
        }
        // UI.md 2.9: thumbnails as shot, or through each frame's own film (developed only when idle).
        FilterChip(selected = filmThumbs, onClick = { onFilmThumbs(!filmThumbs) },
            label = { Text(if (filmThumbs) "缩略图：胶片" else "缩略图：原图") })
    }
}

@Composable
private fun EmptyTable(addFromGallery: () -> Unit, addFromFiles: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("把要挑的照片放上观片台", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "放上来不会冲洗任何照片；编辑记录只存在本应用里，不往相册写文件。",
            style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = addFromGallery,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Amber, contentColor = Palette.OnAmber)) {
            Text("从相册添加")
        }
        TextButton(onClick = addFromFiles) { Text("从文件添加（RAW / DNG）") }
    }
}

/** One haptic event from the map in docs/UI.md 1: [api30] on Android 11+, else [fallback]. */
internal fun View.feel(api30: Int, fallback: Int) {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) api30 else fallback)
}
