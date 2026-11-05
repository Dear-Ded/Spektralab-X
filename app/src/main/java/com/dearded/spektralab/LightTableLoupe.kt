package com.dearded.spektralab

import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * The loupe (单张查看, docs/UI.md 2.9 and appendix C): one frame at a time,
 * swiped left and right. Swipe up picks, swipe down rejects, and with
 * auto-advance on the next frame slides in. Immersive by default; a tap
 * shows the top and bottom controls, and that choice holds across pages.
 * 编辑 opens the frame in the editor with its sidecar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LightTableLoupe(table: LightTableViewModel, loupe: Loupe, onEdit: (Frame) -> Unit, onClose: () -> Unit) {
    val all by table.frames.collectAsState()
    val state by table.state.collectAsState()
    // By id: a frame removed meanwhile drops out, the others keep their order.
    val frames = remember(all, loupe.ids) {
        val byId = all.associateBy { it.id }
        loupe.ids.mapNotNull(byId::get)
    }
    LaunchedEffect(frames.isEmpty()) { if (frames.isEmpty()) onClose() }
    if (frames.isEmpty()) return

    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = loupe.start.coerceIn(0, frames.size - 1)) { frames.size }
    LaunchedEffect(pager.currentPage) { table.loupeAt(pager.currentPage) }

    var chrome by rememberSaveable { mutableStateOf(false) }
    val canHide = immersiveAllowed()
    val showChrome = chrome || !canHide
    SystemBarsHidden(!showChrome)
    // Back first hides the controls; closing is LightTableNav's SeekBack, which scrubs the
    // frame back into its grid cell under the finger (UI2.md 3.8).
    BackHandler(enabled = showChrome && canHide) { chrome = false }

    val current = frames[pager.currentPage.coerceIn(0, frames.size - 1)]

    fun mark(frame: Frame, pick: Int) {
        table.rate(setOf(frame.id), pick = pick)
        // UI.md 1: CONFIRM for a positive commit, REJECT for a refusal.
        if (pick > 0) view.feel(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.VIRTUAL_KEY)
        else if (pick < 0) view.feel(HapticFeedbackConstants.REJECT, HapticFeedbackConstants.LONG_PRESS)
        val i = frames.indexOfFirst { it.id == frame.id }
        if (pick != 0 && state.autoAdvance && i >= 0 && i < frames.size - 1) {
            scope.launch { pager.animateScrollToPage(i + 1, animationSpec = Motion.defaultSpatial()) }
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Surround)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val px = with(LocalDensity.current) { max(maxWidth.roundToPx(), maxHeight.roundToPx()) }
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = 1,
                key = { frames[it].id },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                LoupePage(
                    frame = frames[page], px = px, shared = page == pager.currentPage,
                    onTap = { if (canHide) chrome = !chrome },
                    onPick = { mark(frames[page], 1) },
                    onReject = { mark(frames[page], -1) },
                )
            }
        }

        // Top: back, name and position, auto-advance. A fade rather than a bar,
        // so the photo's surround stays the neutral grey right up to the text.
        Row(
            Modifier.fillMaxWidth().chrome(showChrome)
                .background(Brush.verticalGradient(listOf(Palette.Background.copy(alpha = 0.85f), Color.Transparent)))
                .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .height(HeadHeight).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回网格")
            }
            Column(Modifier.weight(1f)) {
                Text(current.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pager.currentPage + 1} / ${frames.size}", style = Readout, color = Palette.OnSurfaceSoft)
            }
            // 自动下一张 as a whole-row switch would crowd the head; the pill says it in two words.
            SelectPill(
                selected = state.autoAdvance,
                onClick = {
                    val on = !state.autoAdvance
                    table.setAutoAdvance(on)
                    // TOGGLE_ON / OFF are API 34 (UI.md 1: CLOCK_TICK before).
                    view.performHapticFeedback(when {
                        android.os.Build.VERSION.SDK_INT < 34 -> HapticFeedbackConstants.CLOCK_TICK
                        on -> HapticFeedbackConstants.TOGGLE_ON
                        else -> HapticFeedbackConstants.TOGGLE_OFF
                    })
                },
                label = { Text("自动下一张") },
            )
        }

        // Bottom: the table's floating pill: reject, stars, pick; 编辑 as the round primary beside it.
        Box(Modifier.align(Alignment.BottomCenter).chrome(showChrome)) {
            FloatingBar(
                trailing = {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(Palette.Accent)
                            .clickable(role = Role.Button) { onEdit(current) },
                        contentAlignment = Alignment.Center,
                    ) { Text("编辑", style = MaterialTheme.typography.labelLarge, color = Palette.OnAccent) }
                },
            ) {
                MarkButton("✕", "淘汰", on = current.pick < 0) { mark(current, if (current.pick < 0) 0 else -1) }
                Spacer(Modifier.weight(1f))
                Stars(current.stars) { n ->
                    table.rate(setOf(current.id), stars = if (n == current.stars) 0 else n)
                    view.tick()
                }
                Spacer(Modifier.weight(1f))
                MarkButton("✓", "选中", on = current.pick > 0) { mark(current, if (current.pick > 0) 0 else 1) }
            }
        }
    }
}

/** One frame, fitted, following a vertical drag until it passes the pick / reject threshold. */
@Composable
private fun LoupePage(frame: Frame, px: Int, shared: Boolean, onTap: () -> Unit, onPick: () -> Unit, onReject: () -> Unit) {
    val thumb by rememberThumbnail(frame, px)
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = describe(frame)
                // Swipes are not reachable under TalkBack: the marks are actions too.
                customActions = listOf(
                    CustomAccessibilityAction("选中") { onPick(); true },
                    CustomAccessibilityAction("淘汰") { onReject(); true },
                )
            }
            .pointerInput(frame.id) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(frame.id) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + dy) }
                    },
                    onDragEnd = {
                        val y = offset.value
                        when {
                            y < -threshold -> onPick()
                            y > threshold -> onReject()
                        }
                        scope.launch { offset.animateTo(0f, Motion.defaultSpatial()) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, Motion.defaultSpatial()) } },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let { b ->
            val image = remember(b) { b.asImageBitmap() }
            Image(
                image, contentDescription = null, contentScale = ContentScale.Fit,
                // UI2.md 3.7: the page on screen is the grid cell grown large (Transitions.kt);
                // before the drag offset, so a pick or reject swipe moves the image, not the match.
                modifier = Modifier.fillMaxSize().sharedFrame(frame.id, enabled = shared).graphicsLayer {
                    translationY = offset.value
                    // The frame fades a little as it nears a decision, so the threshold is felt.
                    alpha = 1f - (abs(offset.value) / (threshold * 4)).coerceIn(0f, 0.4f)
                },
            )
        }
        // A frame already marked says so in the corner, as in the grid.
        Box(Modifier.align(Alignment.TopStart).padding(top = 72.dp, start = 12.dp)) {
            when {
                frame.pick > 0 -> LoupeBadge("✓ 选中")
                frame.pick < 0 -> LoupeBadge("✕ 淘汰")
            }
        }
    }
}

@Composable
private fun LoupeBadge(text: String) {
    Text(
        text,
        Modifier.background(Palette.Background.copy(alpha = 0.6f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        color = Palette.OnSurface, fontSize = 13.sp,
    )
}

/** On is shown by fill and colour together (UI.md 0.5), never by colour alone. */
@Composable
private fun MarkButton(glyph: String, label: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.size(56.dp).clickable(onClick = onClick).semantics { contentDescription = if (on) "$label，已开启" else label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(28.dp).background(if (on) Palette.Accent else Palette.PanelHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, color = if (on) Palette.OnAccent else Palette.OnSurface, fontSize = 14.sp) }
        Text(label, fontSize = 12.sp, color = Palette.OnSurfaceSoft)
    }
}

/** 0-5 stars; tapping the current count clears it. */
@Composable
private fun Stars(stars: Int, onSet: (Int) -> Unit) {
    Row {
        for (n in 1..5) {
            Box(
                Modifier.size(width = 32.dp, height = 48.dp).clickable { onSet(n) }
                    .semantics { contentDescription = "$n 星" + if (n == stars) "，当前" else "" },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (n <= stars) "★" else "☆", fontSize = 20.sp,
                    color = if (n <= stars) Palette.Accent else Palette.OnSurfaceSoft)
            }
        }
    }
}
