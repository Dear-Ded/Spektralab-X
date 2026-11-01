package com.dearded.spektralab

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel) {
    val ui by vm.ui.collectAsState()
    val jobs by vm.jobs.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var openMenu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }

    // Android 13+: the export's progress notification needs POST_NOTIFICATIONS.
    // Asked when an export starts; a refusal only hides the notification.
    val context = androidx.compose.ui.platform.LocalContext.current
    var queuedExport by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        queuedExport?.invoke()
        queuedExport = null
    }
    fun exportWithNotice(format: ExportFormat, size: ExportSize) {
        val missing = android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (missing) {
            queuedExport = { vm.export(format, size) }
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.export(format, size)
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let(vm::open)
    }
    // RAW files rarely carry an image/* type, so the document picker accepts anything.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(vm::open)
    }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SpektraLab", style = MaterialTheme.typography.titleMedium)
                        ui.source?.let { s ->
                            Text(
                                listOf(s.name, s.camera, "${s.width}×${s.height}").filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { openMenu = true }, enabled = ui.ready && !ui.exporting) {
                            Icon(Icons.Default.Add, contentDescription = "打开")
                        }
                        DropdownMenu(expanded = openMenu, onDismissRequest = { openMenu = false }) {
                            DropdownMenuItem(text = { Text("从相册选择") }, onClick = {
                                openMenu = false
                                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            DropdownMenuItem(text = { Text("从文件选择（RAW / DNG）") }, onClick = {
                                openMenu = false
                                pickFile.launch(arrayOf("*/*"))
                            })
                        }
                    }
                    Box {
                        IconButton(onClick = { exportMenu = true }, enabled = ui.hasImage && !ui.exporting) {
                            Icon(Icons.Default.Share, contentDescription = "导出")
                        }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            DropdownMenuItem(text = { Text("JPEG · 全尺寸") }, onClick = {
                                exportMenu = false; exportWithNotice(ExportFormat.JPEG, ExportSize.FULL)
                            })
                            DropdownMenuItem(text = { Text("JPEG · 分享尺寸（长边 3400）") }, onClick = {
                                exportMenu = false; exportWithNotice(ExportFormat.JPEG, ExportSize.SHARE)
                            })
                            DropdownMenuItem(text = { Text("16 位 TIFF · 全尺寸") }, onClick = {
                                exportMenu = false; exportWithNotice(ExportFormat.TIFF, ExportSize.FULL)
                            })
                        }
                    }
                    val active = jobs.count { it.active }
                    TextButton(onClick = { vm.showQueue(true) }) {
                        Text(if (active > 0) "处理中 $active" else "任务")
                    }
                    TextButton(onClick = vm::undo, enabled = ui.canUndo && !ui.exporting) { Text("撤销") }
                    TextButton(onClick = vm::redo, enabled = ui.canRedo && !ui.exporting) { Text("重做") }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val boxHeight = maxHeight
            val landscape = maxWidth > boxHeight
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Preview(ui, Modifier.weight(1f).fillMaxHeight(), onDetail = vm::loadDetail)
                    Controls(ui, vm, Modifier.width(360.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Preview(ui, Modifier.weight(1f).fillMaxWidth(), onDetail = vm::loadDetail)
                    Controls(ui, vm, Modifier.fillMaxWidth().height(boxHeight * 0.42f))
                }
            }
            ui.busy?.let { BusyOverlay(it) }
        }
    }

    ui.error?.let { error ->
        val clipboard = LocalClipboardManager.current
        val report = remember(error, ui.deviceSummary, ui.engineSummary) {
            "$error\n\n${BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
                "Android ${android.os.Build.VERSION.RELEASE}\n${ui.deviceSummary}\n${ui.engineSummary}"
        }
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("好") } },
            dismissButton = {
                // The copy carries the whole diagnostics report, not just the message.
                TextButton(onClick = { clipboard.setText(AnnotatedString(report + "\n\n" + vm.diagnostics())) }) {
                    Text("复制")
                }
            },
            title = { Text("出错了") },
            text = {
                // Scrollable and selectable: an engine error is often long and is
                // what a bug report needs verbatim.
                SelectionContainer {
                    Text(report, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                }
            },
        )
    }

    if (showSettings) SettingsDialog(ui, vm) { showSettings = false }
    if (ui.showQueue) QueueSheet(jobs, vm::cancelJob, vm::retryJob, vm::clearFinishedJobs) { vm.showQueue(false) }
    ui.detail?.let { DetailViewer(it, vm::closeDetail) }
}

@Composable
private fun Preview(ui: UiState, modifier: Modifier, onDetail: () -> Unit = {}) {
    var scale by remember(ui.source) { mutableFloatStateOf(1f) }
    var offset by remember(ui.source) { mutableStateOf(Offset.Zero) }
    var holding by remember { mutableStateOf(false) }
    Box(modifier.background(Palette.Surround), contentAlignment = Alignment.Center) {
        // Held down: the frame as it went into the engine, before any film.
        val image = if (holding && ui.before != null) ui.before else ui.image
        if (image != null) {
            val bitmap = remember(image) { image.asImageBitmap() }
            val gestures = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { scale = 1f; offset = Offset.Zero },
                            onLongPress = { holding = true },
                            onPress = {
                                tryAwaitRelease()
                                holding = false
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            // Keep the point under the fingers fixed, and the image on screen.
                            val next = (scale * zoom).coerceIn(1f, 8f)
                            val q = centroid - Offset(size.width / 2f, size.height / 2f)
                            var o = q + pan - (q - offset) * (next / scale)
                            val maxX = size.width * (next - 1f) / 2f
                            val maxY = size.height * (next - 1f) / 2f
                            o = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                            scale = next
                            offset = if (next == 1f) Offset.Zero else o
                        }
                    }
                    // Lambda form: read in the draw phase, so a pinch frame does not recompose.
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    }
            // The original is shown bare; the print gets its paper frame.
            val frame = if (holding && ui.before != null) FrameSpec() else ui.edit.frame
            val caption = remember(ui.exif, ui.edit.film, ui.catalog) {
                Caption.of(ui.exif, ui.catalog.film(ui.edit.film)?.label ?: "")
            }
            FramedImage(bitmap, frame, caption, gestures)
        } else if (ui.ready && !ui.hasImage) {
            Text(
                "点右上角 + 打开照片\n支持 JPEG / PNG / HEIC 和相机 RAW（DNG、CR3、NEF、ARW、RAF…）",
                color = Color.LightGray,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
        }
        // Shown only after 400 ms, so quick drag renders never flicker a bar.
        val showProgress by androidx.compose.runtime.produceState(false, ui.rendering) {
            value = false
            if (ui.rendering) { kotlinx.coroutines.delay(400); value = true }
        }
        if (showProgress) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        // Histogram of what is on screen (the print, or the original while held).
        if (image != null) {
            var showHistogram by remember { mutableStateOf(false) }
            val histogram by androidx.compose.runtime.produceState<Histogram?>(null, image, showHistogram) {
                value = if (showHistogram)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Histogram.of(image) }
                else null
            }
            Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Row {
                    TextButton(onClick = { showHistogram = !showHistogram }) {
                        Text(if (showHistogram) "隐藏直方图" else "直方图", color = Color.White.copy(alpha = 0.8f))
                    }
                    TextButton(onClick = onDetail, enabled = !ui.loadingDetail && ui.imageTier.isNotEmpty()) {
                        Text(if (ui.loadingDetail) "渲染中…" else "1:1", color = Color.White.copy(alpha = 0.8f))
                    }
                }
                histogram?.let { h ->
                    HistogramView(h, Modifier.width(160.dp).height(80.dp).background(Color.Black.copy(alpha = 0.5f)))
                    if (h.clippedLow > 0.005f || h.clippedHigh > 0.005f) {
                        Text(
                            String.format(Locale.ROOT, "暗部溢出 %.1f%% · 高光溢出 %.1f%%", h.clippedLow * 100, h.clippedHigh * 100),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        if (ui.image != null && ui.imageTier.isNotEmpty()) {
            Text(
                when {
                    holding && ui.before != null -> "原图"
                    ui.imageTier == "live" -> "快速预览 · 长按看原图"
                    else -> "预览 · 长按看原图"
                },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
    }
}

@Composable
private fun BusyOverlay(text: String) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text, color = Color.White)
        }
    }
}

@Composable
private fun Controls(ui: UiState, vm: EditorViewModel, modifier: Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    val e = ui.edit
    val enabled = ui.hasImage && !ui.exporting
    Surface(modifier, tonalElevation = 2.dp) {
        Column {
            TabRow(selectedTabIndex = tab) {
                listOf("胶片", "放大冲印", "效果", "构图").forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (tab) {
                    0 -> {
                        FilmStrip(ui, enabled) { vm.pickFilm(it) }
                        PresetRow(ui, vm, enabled)
                        StockPicker("胶片", ui.catalog.films, e.film, enabled) { vm.pickFilm(it) }
                        SwitchRow("自动曝光", e.autoExposure, enabled) { vm.update(e.copy(autoExposure = it)) }
                        LabeledSlider("曝光补偿", e.exposureEv, -4.0, 4.0, 0.0, enabled, "%+.1f EV", onDrag = vm::setInteracting) {
                            vm.update(e.copy(exposureEv = it))
                        }
                        ui.source?.takeIf { it.kind == "raw" && it.asShotK > 0 }?.let { s ->
                            WhiteBalance(s, enabled) { k, t -> vm.setWhiteBalance(k, t) }
                        }
                        Text("画幅", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((label, mm) in FORMATS) {
                                FilterChip(
                                    selected = kotlin.math.abs(e.filmFormatMm - mm) < 0.5,
                                    onClick = { vm.update(e.copy(filmFormatMm = mm)) },
                                    label = { Text(label) },
                                    enabled = enabled,
                                )
                            }
                        }
                    }
                    1 -> {
                        if (e.scanFilm) {
                            Text(
                                "反转片直接扫描，不经过相纸。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            StockPicker("相纸", ui.catalog.papers, e.paper, enabled) { vm.update(e.copy(paper = it)) }
                        }
                        LabeledSlider("放大曝光", e.printExposure, 0.2, 3.0, 1.0, enabled, "×%.2f", onDrag = vm::setInteracting) {
                            vm.update(e.copy(printExposure = it))
                        }
                        LabeledSlider("黄滤镜", e.yShift, -0.5, 0.5, 0.0, enabled && !e.scanFilm, "%+.2f", onDrag = vm::setInteracting) {
                            vm.update(e.copy(yShift = it))
                        }
                        LabeledSlider("品红滤镜", e.mShift, -0.5, 0.5, 0.0, enabled && !e.scanFilm, "%+.2f", onDrag = vm::setInteracting) {
                            vm.update(e.copy(mShift = it))
                        }
                    }
                    3 -> {
                        val src = ui.source
                        // With the film edge on, the crop must stay the gate's shape in the new orientation.
                        fun reframe(next: Edit) = when {
                            src == null -> next
                            next.filmEdge -> Edit.withFilmEdge(next.copy(cropX = 0.0, cropY = 0.0, cropW = 1.0, cropH = 1.0),
                                src.width, src.height, on = true)
                            else -> Edit.centredCrop(next, src.width, src.height, next.cropAspect)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.update(reframe(e.turned(3))) },
                                enabled = enabled) { Text("向左转") }
                            OutlinedButton(onClick = { vm.update(reframe(e.turned(1))) },
                                enabled = enabled) { Text("向右转") }
                        }
                        SwitchRow("水平翻转", e.flipH, enabled) { vm.update(e.copy(flipH = it)) }
                        Text("裁剪比例（居中）", style = MaterialTheme.typography.labelLarge)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for ((label, aspect) in ASPECTS) {
                                FilterChip(
                                    selected = kotlin.math.abs(e.cropAspect - aspect) < 1e-3,
                                    onClick = { vm.update(reframe(e.copy(cropAspect = aspect))) },
                                    label = { Text(label) },
                                    enabled = enabled && !e.filmEdge,
                                )
                            }
                        }
                        Text(
                            "旋转和裁剪在胶片之前完成，颗粒按裁剪后的画面计算。长按看到的原图不带裁剪。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("边框样式", style = MaterialTheme.typography.labelLarge)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (p in Borders.PRESETS) {
                                FilterChip(
                                    selected = e.borderId == p.id,
                                    onClick = { if (src != null) vm.update(Edit.withBorder(e, p, src.width, src.height)) },
                                    label = { Text(p.label) },
                                    enabled = enabled && src != null,
                                )
                            }
                        }
                        Borders.byId(e.borderId)?.let { p ->
                            Text(p.hint, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (e.filmEdge) {
                            Text("片边", style = MaterialTheme.typography.labelLarge)
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for ((v, label) in listOf("strip" to "整条底片", "filed" to "锉边")) {
                                    FilterChip(selected = e.edgeMode == v, onClick = { vm.update(e.copy(edgeMode = v)) },
                                        label = { Text(label) }, enabled = enabled)
                                }
                                for ((v, label) in listOf("white" to "扫描 · 白孔", "black" to "相纸 · 黑孔")) {
                                    FilterChip(selected = e.edgeHoles == v, onClick = { vm.update(e.copy(edgeHoles = v)) },
                                        label = { Text(label) }, enabled = enabled)
                                }
                            }
                            LabeledSlider("边缘雾化", e.edgeFog, 0.0, 3.0, 1.0, enabled, "%.1f", onDrag = vm::setInteracting) {
                                vm.update(e.copy(edgeFog = it))
                            }
                            LabeledSlider("片轴漏光", e.edgeLeaks, 0.0, 3.0, 0.0, enabled, "%.1f", onDrag = vm::setInteracting) {
                                vm.update(e.copy(edgeLeaks = it))
                            }
                        }
                        if (!e.frame.isNone) {
                            Text("相纸", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                                for (c in FrameSpec.PAPERS) {
                                    val on = e.frame.paper == c
                                    Box(
                                        Modifier.size(30.dp)
                                            .background(Color(c), androidx.compose.foundation.shape.CircleShape)
                                            .border(if (on) 2.dp else 1.dp,
                                                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                androidx.compose.foundation.shape.CircleShape)
                                            .clickable(enabled = enabled) { vm.update(e.copy(frame = e.frame.copy(paper = c))) },
                                    )
                                }
                            }
                            // A data bar has only a bottom band; every other card scales as a whole.
                            val bar = e.frame.side == 0f && e.frame.top == 0f
                            val cur = if (bar) e.frame.bottom else maxOf(e.frame.side, e.frame.top)
                            val presetFrame = Borders.byId(e.borderId)?.frame
                            val def = presetFrame?.let { if (bar) it.bottom else maxOf(it.side, it.top) } ?: cur
                            LabeledSlider("边宽", cur.toDouble(), 0.01, 0.3, def.toDouble(), enabled, "%.2f") { v ->
                                val f = e.frame
                                val next = if (bar) f.copy(bottom = v.toFloat()) else {
                                    val r = (v / cur.coerceAtLeast(0.001f)).toFloat()
                                    f.copy(side = f.side * r, top = f.top * r, bottom = f.bottom * r)
                                }
                                vm.update(e.copy(frame = next))
                            }
                            SwitchRow("参数文字", e.frame.caption, enabled) { vm.update(e.copy(frame = e.frame.copy(caption = it))) }
                            Text("相纸在导出 JPEG 时加上；16 位 TIFF 只含胶片本身。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SwitchRow("胶片边框", e.filmEdge, enabled && src != null) { on ->
                            if (src != null) vm.update(Edit.withFilmEdge(e, src.width, src.height, on).let {
                                if (!on && Borders.byId(e.borderId)?.edge != null && e.frame.isNone) it.copy(borderId = "none") else it
                            })
                        }
                        if (e.filmEdge && src != null) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for ((id, label, _) in Edit.EDGE_FORMATS) {
                                    FilterChip(
                                        selected = e.edgeFormat == id,
                                        onClick = {
                                            var next = Edit.withFilmEdge(e, src.width, src.height, true, id)
                                            if (!Edit.dateBackFits(id)) next = next.copy(dateImprint = false)
                                            vm.update(next)
                                        },
                                        label = { Text(label) },
                                        enabled = enabled,
                                    )
                                }
                            }
                            Text(
                                "边框打开时画面会裁成片门的比例；裁剪比例按钮暂时不可用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val dateOk = !e.filmEdge || Edit.dateBackFits(e.edgeFormat)
                        SwitchRow("日期戳", e.dateImprint, enabled && dateOk && e.dateText.isNotBlank()) {
                            vm.update(e.copy(dateImprint = it))
                        }
                        Text(
                            when {
                                e.dateText.isBlank() -> "这张照片没有拍摄时间，不能加日期戳。"
                                !dateOk -> "宽幅画幅没有日期后背。"
                                else -> "日期：${e.dateText}（取自拍摄时间）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        SwitchRow("颗粒", e.grainActive, enabled) { vm.update(e.copy(grainActive = it)) }
                        LabeledSlider("颗粒强度", e.grainAmount, 0.0, 2.0, 1.0, enabled && e.grainActive, "%.2f", onDrag = vm::setInteracting) {
                            vm.update(e.copy(grainAmount = it))
                        }
                        SwitchRow("光晕（Halation）", e.halationActive, enabled) { vm.update(e.copy(halationActive = it)) }
                        LabeledSlider("光晕强度", e.halationAmount, 0.0, 4.0, 1.0, enabled && e.halationActive, "%.2f", onDrag = vm::setInteracting) {
                            vm.update(e.copy(halationAmount = it))
                        }
                        SwitchRow("相纸眩光", e.glareActive, enabled) { vm.update(e.copy(glareActive = it)) }
                        LabeledSlider("眩光强度", e.glareAmount, 0.0, 5.0, 1.0, enabled && e.glareActive, "%.2f", onDrag = vm::setInteracting) {
                            vm.update(e.copy(glareAmount = it))
                        }
                    }
                }
            }
        }
    }
}

private val FORMATS = listOf("半格" to 24.0, "135" to 35.0, "645" to 56.0, "6×7" to 70.0, "4×5" to 127.0)

@Composable
private fun LabeledSlider(
    label: String, value: Double, min: Double, max: Double, default: Double,
    enabled: Boolean, format: String, onDrag: (Boolean) -> Unit = {}, onChange: (Double) -> Unit,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    // The thumb follows the finger from local state; the model hears every
    // change, but a busy main thread never makes the thumb lag behind.
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val span = (max - min).toFloat()
    val snap = span * 0.015f
    val onChangeLatest by androidx.compose.runtime.rememberUpdatedState(onChange)
    Column {
        Row(
            Modifier.fillMaxWidth().pointerInput(enabled, default) {
                if (enabled) detectTapGestures(onDoubleTap = { view.tick(strong = true); local = default.toFloat(); onChangeLatest(default) })
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(String.format(Locale.ROOT, format, local.toDouble()), style = Readout,
                color = if (kotlin.math.abs(local - default.toFloat()) < 1e-6f) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary)
            TextButton(onClick = { view.tick(strong = true); local = default.toFloat(); onChange(default) },
                enabled = enabled && kotlin.math.abs(local - default.toFloat()) > 1e-6f) { Text("重置") }
        }
        Slider(
            value = local.coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { raw ->
                // Snap onto the default and tick when crossing it or hitting an end.
                val v = if (kotlin.math.abs(raw - default.toFloat()) < snap) default.toFloat() else raw
                val before = local
                val crossed = (before - default.toFloat()) * (v - default.toFloat()) <= 0f && before != v
                if ((crossed && v == default.toFloat()) || (v == min.toFloat() && before != v) || (v == max.toFloat() && before != v))
                    view.tick(strong = true)
                local = v
                onDrag(true)
                onChange(v.toDouble())
            },
            onValueChangeFinished = { onDrag(false) },
            valueRange = min.toFloat()..max.toFloat(),
            enabled = enabled,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Palette.OnSurface,
                activeTrackColor = Palette.Amber,
                inactiveTrackColor = Palette.PanelHighest,
                disabledActiveTrackColor = Palette.Outline,
                disabledInactiveTrackColor = Palette.PanelHigh,
            ),
            modifier = Modifier.systemGestureExclusion(),
        )
    }
}

/**
 * RAW white balance. It is applied at decode, so a change re-opens the file:
 * the sliders move freely and commit only when released.
 */
@Composable
private fun WhiteBalance(s: SourceInfo, enabled: Boolean, onCommit: (Double, Double) -> Unit) {
    val currentK = if (s.wbK > 0) s.wbK else s.asShotK
    val currentTint = if (s.wbK > 0) s.wbTint else s.asShotTint
    var k by remember(s) { mutableFloatStateOf(currentK.toFloat().coerceIn(2000f, 12000f)) }
    var tint by remember(s) { mutableFloatStateOf(currentTint.toFloat().coerceIn(-150f, 150f)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("白平衡", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                String.format(Locale.ROOT, "%.0f K · 色调 %+.0f", k, tint),
                style = MaterialTheme.typography.labelMedium,
            )
            TextButton(onClick = { onCommit(0.0, 0.0) }, enabled = enabled && s.wbK > 0) { Text("原始") }
        }
        Slider(
            value = k, onValueChange = { k = it },
            onValueChangeFinished = { onCommit(k.toDouble(), tint.toDouble()) },
            valueRange = 2000f..12000f, enabled = enabled,
        )
        Slider(
            value = tint, onValueChange = { tint = it },
            onValueChangeFinished = { onCommit(k.toDouble(), tint.toDouble()) },
            valueRange = -150f..150f, enabled = enabled,
        )
        Text(
            "在解码时应用，松手后重新解码 RAW，需要几秒。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun StockPicker(label: String, stocks: List<Stock>, selected: String, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = stocks.firstOrNull { it.id == selected }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("$label：${current?.label ?: selected}", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("取消") } },
            title = { Text("选择$label") },
            text = {
                LazyColumn {
                    items(stocks, key = { it.id }) { stock ->
                        Row(
                            Modifier.fillMaxWidth().clickable { open = false; onPick(stock.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stock.label, modifier = Modifier.weight(1f))
                            val tags = listOfNotNull(
                                if (stock.positive) "反转" else null,
                                if (stock.bw) "黑白" else null,
                            )
                            if (tags.isNotEmpty()) {
                                Text(
                                    tags.joinToString(" "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (stock.id == selected) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
        )
    }
}

/** The film cards: each film on the open photo. Films whose card has not rendered yet show their name only. */
@Composable
private fun FilmStrip(ui: UiState, enabled: Boolean, onPick: (String) -> Unit) {
    if (!ui.hasImage || ui.catalog.films.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(ui.catalog.films, key = { it.id }) { film ->
            val selected = film.id == ui.edit.film
            Column(
                Modifier.width(96.dp).clickable(enabled = enabled) { onPick(film.id) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(96.dp).background(Color(0xFF222222))
                        .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    val card = ui.filmCards[film.id]
                    if (card != null) {
                        val bitmap = remember(card) { card.asImageBitmap() }
                        Image(bitmap, contentDescription = film.label, contentScale = ContentScale.Crop,
                              modifier = Modifier.fillMaxSize())
                    } else {
                        Text("·", color = Color(0xFF55555A), style = MaterialTheme.typography.titleLarge)
                    }
                }
                Text(
                    film.label.removePrefix("Kodak ").removePrefix("Fujifilm ").removePrefix("Ilford "),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private val ASPECTS = listOf(
    "原始" to 0.0, "3:2" to 1.5, "2:3" to 2.0 / 3.0, "4:3" to 4.0 / 3.0, "3:4" to 0.75,
    "1:1" to 1.0, "16:9" to 16.0 / 9.0, "XPan 65:24" to 65.0 / 24.0,
)

private val WORKING_EDGES = listOf(
    2048 to "2048 px（最快）",
    3072 to "3072 px",
    4096 to "4096 px（推荐）",
    6144 to "6144 px",
    0 to "原始尺寸（受 GPU 上限约束）",
)

@Composable
private fun SettingsDialog(ui: UiState, vm: EditorViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("工作尺寸（长边）", style = MaterialTheme.typography.labelLarge)
                Text(
                    "打开照片时先缩到这个尺寸再处理，导出也是这个尺寸。越大越慢、越占内存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for ((edge, label) in WORKING_EDGES) {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setWorkingEdge(edge) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = ui.workingEdge == edge, onClick = { vm.setWorkingEdge(edge) })
                        Text(label)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("设备", style = MaterialTheme.typography.labelLarge)
                Text(ui.deviceSummary.ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall)
                Text(ui.engineSummary.ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall)
                val clipboard = LocalClipboardManager.current
                var copied by remember { mutableStateOf(false) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(vm.diagnostics())); copied = true }) {
                    Text(if (copied) "已复制诊断信息" else "复制诊断信息")
                }
                Spacer(Modifier.height(12.dp))
                Text("关于", style = MaterialTheme.typography.labelLarge)
                Text("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodySmall)
                Text(
                    "基于 SpektraLab（GPL-3.0）的非官方安卓移植。胶片与相纸数据来自 spektrafilm（CC BY-SA 4.0）；" +
                        "RAW 解码使用 LibRaw（LGPL-2.1）。许可证文本随应用附带。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun PresetRow(ui: UiState, vm: EditorViewModel, enabled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadPresets() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(onClick = { menu = true }, enabled = enabled && ui.presets.isNotEmpty()) {
                Text(if (ui.presets.isEmpty()) "还没有预设" else "套用预设（${ui.presets.size}）")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (p in ui.presets) {
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = { menu = false; vm.applyPreset(p) },
                        trailingIcon = {
                            TextButton(onClick = { menu = false; deleting = p.name }) { Text("删除") }
                        },
                    )
                }
            }
        }
        TextButton(onClick = { naming = true }, enabled = enabled) { Text("存为预设") }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            confirmButton = {
                TextButton(onClick = { naming = false; vm.savePreset(name) }, enabled = name.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("取消") } },
            title = { Text("存为预设") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                        label = { Text("名称") },
                    )
                    Text(
                        "保存胶片、相纸、放大和效果参数；曝光、裁剪和白平衡属于单张照片，不保存。同名会覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = { TextButton(onClick = { deleting = null; vm.deletePreset(target) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
            title = { Text("删除预设") },
            text = { Text("删除“$target”？") },
        )
    }
}

/**
 * The full render at one image pixel per screen pixel, to judge grain and
 * halation, which the fitted preview cannot show. Pan with one finger.
 */
@Composable
private fun DetailViewer(bitmap: android.graphics.Bitmap, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        val h = rememberScrollState()
        val v = rememberScrollState()
        // Start in the middle of the frame, where the subject usually is.
        // maxValue is Int.MAX_VALUE until the first layout, so wait for the real range.
        LaunchedEffect(bitmap) {
            androidx.compose.runtime.snapshotFlow { h.maxValue to v.maxValue }
                .first { (a, b) -> a != Int.MAX_VALUE && b != Int.MAX_VALUE }
            h.scrollTo(h.maxValue / 2)
            v.scrollTo(v.maxValue / 2)
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.fillMaxSize().horizontalScroll(h).verticalScroll(v)) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = with(density) { Modifier.width(bitmap.width.toDp()).height(bitmap.height.toDp()) },
                )
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.5f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("1:1 · ${bitmap.width}×${bitmap.height}", color = Color.White,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = onClose) { Text("关闭", color = Color.White) }
            }
        }
    }
}

/**
 * The print on its paper frame, scaled to fit. With no frame it is a plain
 * fitted image. Drawn rather than composed, so the frame costs no bitmap.
 */
@Composable
private fun FramedImage(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    frame: FrameSpec,
    caption: Caption,
    modifier: Modifier,
) {
    if (frame.isNone) {
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
        return
    }
    androidx.compose.foundation.Canvas(modifier) {
        val l = frame.layout(bitmap.width, bitmap.height)
        val k = minOf(size.width / l.width, size.height / l.height)
        val ox = (size.width - l.width * k) / 2f
        val oy = (size.height - l.height * k) / 2f
        drawRect(Color(frame.paper), topLeft = Offset(ox, oy),
            size = androidx.compose.ui.geometry.Size(l.width * k, l.height * k))
        drawImage(
            bitmap,
            dstOffset = androidx.compose.ui.unit.IntOffset(Math.round(ox + l.x * k), Math.round(oy + l.y * k)),
            dstSize = androidx.compose.ui.unit.IntSize(Math.round(bitmap.width * k), Math.round(bitmap.height * k)),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
        )
        if (frame.caption) drawIntoCanvas { c ->
            val n = c.nativeCanvas
            n.save()
            n.translate(ox, oy)
            n.scale(k, k)
            FrameSpec.drawCaption(n, frame, l, bitmap.width, bitmap.height, caption)
            n.restore()
        }
    }
}
