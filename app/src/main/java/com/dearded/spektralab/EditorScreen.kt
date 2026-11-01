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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel) {
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var openMenu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }

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
                                exportMenu = false; vm.export(ExportFormat.JPEG, ExportSize.FULL)
                            })
                            DropdownMenuItem(text = { Text("JPEG · 分享尺寸（长边 3400）") }, onClick = {
                                exportMenu = false; vm.export(ExportFormat.JPEG, ExportSize.SHARE)
                            })
                            DropdownMenuItem(text = { Text("16 位 TIFF · 全尺寸") }, onClick = {
                                exportMenu = false; vm.export(ExportFormat.TIFF, ExportSize.FULL)
                            })
                        }
                    }
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
                    Preview(ui, Modifier.weight(1f).fillMaxHeight())
                    Controls(ui, vm, Modifier.width(360.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Preview(ui, Modifier.weight(1f).fillMaxWidth())
                    Controls(ui, vm, Modifier.fillMaxWidth().heightIn(max = boxHeight * 0.45f))
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
}

@Composable
private fun Preview(ui: UiState, modifier: Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var holding by remember { mutableStateOf(false) }
    Box(modifier.background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
        // Held down: the frame as it went into the engine, before any film.
        val image = if (holding && ui.before != null) ui.before else ui.image
        if (image != null) {
            val bitmap = remember(image) { image.asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
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
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y,
                    ),
            )
        } else if (ui.ready && !ui.hasImage) {
            Text(
                "点右上角 + 打开照片\n支持 JPEG / PNG / HEIC 和相机 RAW（DNG、CR3、NEF、ARW、RAF…）",
                color = Color.LightGray,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
        }
        if (ui.rendering) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
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
                        StockPicker("胶片", ui.catalog.films, e.film, enabled) { vm.pickFilm(it) }
                        SwitchRow("自动曝光", e.autoExposure, enabled) { vm.update(e.copy(autoExposure = it)) }
                        LabeledSlider("曝光补偿", e.exposureEv, -4.0, 4.0, 0.0, enabled, "%+.1f EV") {
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
                        LabeledSlider("放大曝光", e.printExposure, 0.2, 3.0, 1.0, enabled, "×%.2f") {
                            vm.update(e.copy(printExposure = it))
                        }
                        LabeledSlider("黄滤镜", e.yShift, -0.5, 0.5, 0.0, enabled && !e.scanFilm, "%+.2f") {
                            vm.update(e.copy(yShift = it))
                        }
                        LabeledSlider("品红滤镜", e.mShift, -0.5, 0.5, 0.0, enabled && !e.scanFilm, "%+.2f") {
                            vm.update(e.copy(mShift = it))
                        }
                    }
                    3 -> {
                        val src = ui.source
                        fun reframe(next: Edit) =
                            if (src == null) next else Edit.centredCrop(next, src.width, src.height, next.cropAspect)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.update(reframe(e.copy(quarterTurns = (e.quarterTurns + 3) % 4))) },
                                enabled = enabled) { Text("向左转") }
                            OutlinedButton(onClick = { vm.update(reframe(e.copy(quarterTurns = (e.quarterTurns + 1) % 4))) },
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
                                    enabled = enabled,
                                )
                            }
                        }
                        Text(
                            "旋转和裁剪在胶片之前完成，颗粒按裁剪后的画面计算。长按看到的原图不带裁剪。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        SwitchRow("颗粒", e.grainActive, enabled) { vm.update(e.copy(grainActive = it)) }
                        LabeledSlider("颗粒强度", e.grainAmount, 0.0, 2.0, 1.0, enabled && e.grainActive, "%.2f") {
                            vm.update(e.copy(grainAmount = it))
                        }
                        SwitchRow("光晕（Halation）", e.halationActive, enabled) { vm.update(e.copy(halationActive = it)) }
                        LabeledSlider("光晕强度", e.halationAmount, 0.0, 4.0, 1.0, enabled && e.halationActive, "%.2f") {
                            vm.update(e.copy(halationAmount = it))
                        }
                        SwitchRow("相纸眩光", e.glareActive, enabled) { vm.update(e.copy(glareActive = it)) }
                        LabeledSlider("眩光强度", e.glareAmount, 0.0, 5.0, 1.0, enabled && e.glareActive, "%.2f") {
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
    enabled: Boolean, format: String, onChange: (Double) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(String.format(Locale.ROOT, format, value), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { onChange(default) }, enabled = enabled && value != default) { Text("重置") }
        }
        Slider(
            value = value.toFloat().coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = min.toFloat()..max.toFloat(),
            enabled = enabled,
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
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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
                    "基于 SpektraLab（GPL-3.0）的安卓移植，个人自用。胶片与相纸数据来自 spektrafilm（CC BY-SA 4.0）；" +
                        "RAW 解码使用 LibRaw（LGPL-2.1）。许可证文本随应用附带。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}
