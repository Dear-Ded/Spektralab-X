package com.dearded.spektralab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The film base around the frames on a strip (UI.md appendix B, the edge strip's ground). */
private val FilmBase = Color(0xFF0E0D0C)

/** Light through a sprocket hole on the light table: a neutral grey, never the accent. */
private val SprocketLight = Color(0xFF6A6A72)

/** An undeveloped frame is shown in black and white, by Compose, without the engine (appendix D). */
private val Undeveloped = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * One roll (docs/UI.md appendix D): a horizontal 35 mm strip that snaps the
 * current frame to the centre, with sprocket holes, the model and frame
 * numbers on the edge, and a scrubber with one tick per frame; or the roll as
 * a 6-column contact sheet. A tap opens the loupe on the roll's frames.
 * The head and the floating bar are the table's own (ui-screens_research.md 1):
 * 片条 / 印样 in the bar's track, the frame's actions beside it, and 导出
 * as the round primary button.
 */
@Composable
fun RollView(
    table: LightTableViewModel,
    roll: Roll,
    onBack: () -> Unit,
    onOpen: (List<Frame>, Int) -> Unit,
    onExport: (List<Frame>) -> Unit,
    onContactSheet: () -> Unit = {},
) {
    // Back is LightTableNav's SeekBack: it scrubs this page back to the roll list (UI2.md 3.8).
    var contact by rememberSaveable { mutableStateOf(false) }
    // The frame at the strip's centre; the contact sheet and the actions use it too.
    var current by rememberSaveable(roll.id) { mutableStateOf(0) }
    val frames = roll.frames
    val at = current.coerceIn(0, frames.size - 1)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HeadHeight
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingBarSpace

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Box(Modifier.fillMaxSize().padding(top = top, bottom = bottom)) {
            if (contact) {
                ContactSheet(table, roll, onOpen = { i -> current = i; onOpen(frames, i) })
            } else {
                Strip(table, roll, start = at, onCentre = { current = it }, onOpen = { i -> onOpen(frames, i) })
            }
        }
        TableHead(
            title = roll.title,
            subtitle = roll.edgeLine,
            backLabel = tr("返回卷列表"),
            onBack = onBack,
            titleLeading = { Box(Modifier.size(6.dp).background(roll.process.color, CircleShape)); Spacer(Modifier.size(8.dp)) },
            trailing = {
                // A roll the user packed can be renamed or taken apart; an automatic one is the table's.
                val id = roll.manualId
                if (id != null) {
                    var menu by remember { mutableStateOf(false) }
                    var naming by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = tr("卷操作"))
                        }
                        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text(tr("改卷名")) },
                                onClick = { menu = false; naming = true })
                            androidx.compose.material3.DropdownMenuItem(text = { Text(tr("拆卷（照片回到自动分卷）")) },
                                onClick = { menu = false; table.dissolveRoll(id) })
                        }
                    }
                    if (naming) RollNameDialog(
                        title = tr("改卷名"), hint = tr("最多 40 个字"), initial = roll.name, confirm = tr("改名"),
                        onDismiss = { naming = false },
                        onDone = { name -> naming = false; table.renameRoll(id, name) },
                    )
                }
            },
        )
        FloatingBar(
            Modifier.align(Alignment.BottomCenter),
            scroll = true,
            trailing = {
                Spacer(Modifier.width(8.dp))
                ExportButton(onRoll = { onExport(frames) }, onContactSheet = onContactSheet)
            },
        ) {
            Segmented(
                options = listOf(tr("片条"), tr("印样")), selected = if (contact) 1 else 0,
                modifier = Modifier.width(132.dp),
            ) { i -> contact = i == 1 }
            QuietButton(tr("比较风格")) { table.compare(frames[at]) }
            QuietButton(tr("复制调整")) { table.copyLook(frames[at]) }
        }
    }
}

/** 导出 as the bar's round primary button, its menu naming what comes out. */
@Composable
private fun ExportButton(onRoll: () -> Unit, onContactSheet: () -> Unit) {
    Box {
        var menu by remember { mutableStateOf(false) }
        Box(
            Modifier.size(64.dp).clip(Corners.full).background(Palette.Accent)
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { menu = true }
                .semantics { contentDescription = tr("导出") },
            contentAlignment = Alignment.Center,
        ) { Text(tr("导出"), style = MaterialTheme.typography.labelLarge, color = Palette.OnAccent) }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text(tr("导出本卷（每张一个 JPEG）")) },
                onClick = { menu = false; onRoll() })
            androidx.compose.material3.DropdownMenuItem(text = { Text(tr("导出印样（整卷一张）")) },
                onClick = { menu = false; onContactSheet() })
        }
    }
}


@Composable
private fun Strip(table: LightTableViewModel, roll: Roll, start: Int, onCentre: (Int) -> Unit, onOpen: (Int) -> Unit) {
    val frames = roll.frames
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
    // The frame whose centre is nearest the viewport's: the one the strip has snapped to.
    val centre by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: start
        }
    }
    val currentCentre by rememberUpdatedState(centre)
    LaunchedEffect(list) {
        // One segment tick per frame crossed, never a continuous buzz (UI.md 1 and appendix D).
        snapshotFlow { centre }.distinctUntilChanged().drop(1).collect { i -> onCentre(i); view.tick(strong = true) }
    }
    LaunchedEffect(Unit) { if (start > 0) list.scrollToItem(start) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The strip's height leaves room for the edges, the name line and the scrubber.
        val imageH = minOf(maxWidth * 0.62f / 1.5f, maxHeight - 160.dp).coerceAtLeast(96.dp)
        val sidePad = (maxWidth - imageH * 1.5f) / 2
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Box(Modifier.fillMaxWidth().background(FilmBase)) {
                LazyRow(
                    state = list,
                    flingBehavior = rememberSnapFlingBehavior(list, SnapPosition.Center),
                    contentPadding = PaddingValues(horizontal = sidePad.coerceAtLeast(0.dp)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(frames, key = { _, f -> f.id }, contentType = { _, _ -> "frame" }) { i, f ->
                        StripFrame(
                            table, roll, f, i, imageH,
                            priority = { abs(i - currentCentre) },
                            onClick = {
                                if (i == currentCentre) onOpen(i)
                                else scope.launch { list.animateScrollToItem(i) }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                frames[centre.coerceIn(0, frames.size - 1)].name,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Scrubber(frames.size, centre) { i -> scope.launch { list.animateScrollToItem(i) } }
        }
    }
}

/**
 * One frame on the strip: a landscape frame takes the full 36 x 24 gate, a
 * portrait one half of it (18 x 24), and the picture is always upright. The
 * edges carry the sprocket holes, the model on the top edge and the frame
 * numbers (00, 00A, 1, 1A ...) on the bottom one, in the process colour.
 */
@Composable
private fun StripFrame(
    table: LightTableViewModel, roll: Roll, f: Frame, index: Int, imageH: Dp,
    priority: () -> Int, onClick: () -> Unit,
) {
    val wide = f.landscape
    val gateW = if (wide) imageH * 1.5f else imageH * 0.75f
    val px = with(LocalDensity.current) { maxOf(gateW, imageH).roundToPx() }
    val ink = roll.process.color
    Column(
        Modifier.width(gateW + 4.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = tr("第 {0} 格，", frameNumber(index)) + describe(f) },
    ) {
        EdgeText(if (index % 2 == 1 || index == 0) roll.edgeModel else "", "", ink)
        Sprockets(holes = if (wide) 8 else 4)
        Box(Modifier.fillMaxWidth().height(imageH).padding(horizontal = 2.dp).background(Color.Black)) {
            FrameImage(table, f, px, priority, ContentScale.Crop)
            FrameMarks(f, selected = false)
        }
        Sprockets(holes = if (wide) 8 else 4)
        // Numbers only on odd frames and 00 (appendix D), the half mark at each frame's trailing edge.
        EdgeText(if (index == 0 || index % 2 == 1) frameNumber(index) else "", halfMark(index), ink)
    }
}

@Composable
private fun EdgeText(centre: String, trailing: String, ink: Color) {
    Box(Modifier.fillMaxWidth().height(12.dp).padding(horizontal = 4.dp)) {
        if (centre.isNotEmpty()) Text(centre, Modifier.align(Alignment.Center), style = EdgeType, color = ink, maxLines = 1)
        if (trailing.isNotEmpty()) Text(trailing, Modifier.align(Alignment.CenterEnd), style = EdgeType, color = ink.copy(alpha = 0.7f), maxLines = 1)
    }
}

/** A row of 135 perforations: eight per full frame, as on the film. */
@Composable
private fun Sprockets(holes: Int) {
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val pitch = size.width / holes
        val w = pitch * 0.5f
        val h = size.height * 0.62f
        val y = (size.height - h) / 2
        for (k in 0 until holes) {
            drawRoundRect(SprocketLight, Offset(k * pitch + (pitch - w) / 2, y), Size(w, h), CornerRadius(h * 0.25f))
        }
    }
}

/** The frame as the light table shows it: through its own film once developed (idle path), else in black and white. */
@Composable
private fun FrameImage(table: LightTableViewModel, f: Frame, px: Int, priority: () -> Int, scale: ContentScale) {
    val plain by rememberThumbnail(f, px)
    val edit by rememberSidecarEdit(table.store, f)
    val developed by rememberFilmThumb(f, edit, px, priority)
    val b = developed ?: plain ?: return
    val image = remember(b) { b.asImageBitmap() }
    Image(
        image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = scale,
        colorFilter = if (!f.edited) Undeveloped else null,
        alpha = if (f.pick < 0) 0.4f else 1f,
    )
}

/** One tick per frame; a tap or a drag jumps there. Shows "current / total". */
@Composable
private fun Scrubber(count: Int, current: Int, onJump: (Int) -> Unit) {
    val jump by rememberUpdatedState(onJump)
    var last by remember { mutableStateOf(-1) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(32.dp)
                .semantics { contentDescription = tr("第 {0} 格，共 {1} 格", current + 1, count) }
                .pointerInput(count) {
                    fun indexAt(x: Float) = ((x / size.width) * count).toInt().coerceIn(0, count - 1)
                    detectTapGestures { jump(indexAt(it.x)) }
                }
                .pointerInput(count) {
                    fun indexAt(x: Float) = ((x / size.width) * count).toInt().coerceIn(0, count - 1)
                    detectHorizontalDragGestures(onDragEnd = { last = -1 }) { change, _ ->
                        val i = indexAt(change.position.x)
                        if (i != last) { last = i; jump(i) }
                    }
                },
        ) {
            val step = size.width / count
            for (k in 0 until count) {
                val on = k == current
                val h = if (on) size.height * 0.8f else if (k % 5 == 0) size.height * 0.5f else size.height * 0.32f
                val x = step * k + step / 2
                drawLine(
                    if (on) Palette.Accent else Palette.OnSurfaceSoft.copy(alpha = 0.6f),
                    Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2),
                    strokeWidth = if (on) 3.dp.toPx() else 1.dp.toPx(),
                )
            }
        }
        Text("${current + 1} / $count", Modifier.fillMaxWidth(), style = Readout, color = Palette.OnSurfaceSoft,
            textAlign = TextAlign.Center)
    }
}

/** The roll as a 6-column contact sheet (UI.md 2.9), each cell numbered as on the strip. */
@Composable
private fun ContactSheet(table: LightTableViewModel, roll: Roll, onOpen: (Int) -> Unit) {
    val grid = rememberLazyGridState()
    BoxWithConstraints(Modifier.fillMaxSize().background(FilmBase)) {
        val cell = (maxWidth - 8.dp * 2 - 4.dp * 5) / 6
        val px = with(LocalDensity.current) { cell.roundToPx() }
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            state = grid,
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(roll.frames, key = { _, f -> f.id }, contentType = { _, _ -> "frame" }) { i, f ->
                Column(Modifier.clickable { onOpen(i) }.semantics { contentDescription = tr("第 {0} 格，", frameNumber(i)) + describe(f) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(2.dp)).background(Color.Black)) {
                        FrameImage(table, f, px, { abs(i - grid.firstVisibleItemIndex) },
                            if (f.landscape) ContentScale.Crop else ContentScale.Fit)
                    }
                    Text(frameNumber(i), style = EdgeType, color = roll.process.color, modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center)
                }
            }
        }
    }
}

