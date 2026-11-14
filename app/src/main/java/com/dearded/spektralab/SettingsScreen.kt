package com.dearded.spektralab

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException

private val WORKING_EDGES get() = listOf(
    2048 to "2048 px",
    3072 to "3072 px",
    4096 to tr("4096 px（默认）"),
    6144 to "6144 px",
    0 to tr("原始尺寸"),
)

/** 照片四周's names, keyed as Theme.kt SURROUNDS. */
private val SURROUND_LABELS get() = mapOf("dark" to tr("深灰（默认）"), "grey18" to tr("18% 灰"), "black" to tr("黑"), "white" to tr("白"))

private val HIGHLIGHT_STOPS get() = listOf(0f to tr("关（默认）"), 1f to tr("1 档"), 2f to tr("2 档"))

/**
 * 设置 (ui-screens_research.md 2): a full page over the editor, not a sheet,
 * grouped from most to least used. Each group is one card of joined rows with
 * a 2 dp seam between them (M3 Expressive segmented list): the outer corners
 * 20 dp, the inner 4. A row names the setting, says in one line what it does,
 * and shows its current value; choosing among several values opens in place.
 * The back gesture shrinks the page under the finger.
 */
@Composable
fun SettingsScreen(ui: UiState, vm: EditorViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var back by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { events ->
        try {
            events.collect { back = it.progress }
            onClose()
        } catch (e: CancellationException) {
            back = 0f
            throw e
        }
    }
    Box(
        Modifier.fillMaxSize().zIndex(10f)
            .graphicsLayer {
                val k = 1f - 0.1f * back
                scaleX = k; scaleY = k
                shape = RoundedCornerShape((24 * back).dp); clip = back > 0f
            }
            .background(Palette.Background)
            // Same window as the editor: without a pointer node here, taps on blank
            // space would fall through to the editor underneath.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("返回编辑器"))
                }
            }
            Text(tr("设置"), style = MaterialTheme.typography.headlineSmall, color = Palette.OnSurface,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp))

            var languageError by remember { mutableStateOf(false) }
            SettingsGroup(tr("语言 / Language"), listOf<@Composable () -> Unit>({
                ChoiceRow(tr("语言 / Language"), tr("应用界面语言。切换后保留当前照片和编辑。"),
                    AppLocale.language.nativeName) {
                    Segmented(options = UiLanguage.entries.map { it.nativeName }, selected = AppLocale.language.ordinal) { i ->
                        languageError = !AppLocale.select(context, UiLanguage.entries[i])
                    }
                }
                if (languageError) Hint(tr("无法保存语言设置，请重试。"))
            }))

            // 编辑: how the editor starts.
            // UI2.md 0.1: the key is ui-visual's (Pill.kt EXPAND_ALL); the editor reads it with rememberExpandAll().
            val prefs = remember(context) { context.getSharedPreferences("settings", 0) }
            var expandAll by remember { mutableStateOf(prefs.getBoolean(EXPAND_ALL, false)) }
            val coach = LocalCoach.current
            SettingsGroup(tr("编辑"), buildList<@Composable () -> Unit> {
                add {
                    SettingRow(
                        tr("默认展开全部参数"), expandAll, true,
                        note = tr("冲洗和调整直接显示全部参数。"),
                    ) { expandAll = it; prefs.edit().putBoolean(EXPAND_ALL, it).apply() }
                }
                // ui-visual's key (Theme.kt SURROUND_KEY / SURROUNDS); the editor follows it while it changes.
                add {
                    var surround by remember { mutableStateOf(prefs.getString(SURROUND_KEY, "dark") ?: "dark") }
                    ChoiceRow(
                        tr("照片四周"),
                        tr("判断颜色用 18% 灰。"),
                        value = SURROUND_LABELS[surround] ?: SURROUND_LABELS.getValue("dark"),
                    ) {
                        PillRow {
                            for ((key, colour) in SURROUNDS) {
                                SelectPill(
                                    selected = surround == key,
                                    onClick = { surround = key; prefs.edit().putString(SURROUND_KEY, key).apply() },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(14.dp).background(colour, CircleShape)
                                                .border(1.dp, Palette.Outline, CircleShape))
                                            Spacer(Modifier.width(8.dp))
                                            Text(SURROUND_LABELS[key] ?: key)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                add { NewPhotoStocks(ui, vm) }
                // ui-motion_splash.md 6: LaunchIntro reads this at launch; off by itself when animations are off.
                add {
                    var intro by remember { mutableStateOf(prefs.getBoolean(LAUNCH_INTRO, true)) }
                    val motion = remember { motionAllowed() }
                    SettingRow(
                        tr("启动动画"), intro && motion, motion,
                        disabledReason = tr("系统已关闭动画，启动动画不会播放。"),
                    ) { intro = it; prefs.edit().putBoolean(LAUNCH_INTRO, it).apply() }
                }
                if (coach != null) add {
                    ActionRow(tr("重新查看引导"), tr("回到编辑器时再显示一次。")) { coach.replay(); onClose() }
                }
            })

            // 画质与导出: size, colour, what goes into the file.
            SettingsGroup(tr("画质与导出"), listOf<@Composable () -> Unit>(
                {
                    ChoiceRow(
                        tr("工作尺寸（长边）"),
                        tr("预览和导出都用这个尺寸，越大越慢。"),
                        value = WORKING_EDGES.firstOrNull { it.first == ui.workingEdge }?.second ?: "${ui.workingEdge} px",
                    ) {
                        PillRow {
                            for ((edge, label) in WORKING_EDGES) {
                                SelectPill(selected = ui.workingEdge == edge, onClick = { vm.setWorkingEdge(edge) },
                                    label = { Text(label) })
                            }
                        }
                    }
                },
                {
                    SettingRow(
                        tr("广色域导出（Display P3）"), ui.wideGamut, true,
                        note = tr("仅用于 JPEG；TIFF 色彩空间在保存菜单中选择。"),
                    ) { vm.setWideGamut(it) }
                },
                {
                    ChoiceRow(
                        tr("手机照片高光恢复"),
                        tr("展开手机照片中灰以上的高光，结果更亮更浓，和桌面版不同。"),
                        value = HIGHLIGHT_STOPS.firstOrNull { it.first == ui.highlightStops }?.second ?: tr("{0} 档", ui.highlightStops),
                    ) {
                        Segmented(
                            options = HIGHLIGHT_STOPS.map { it.second },
                            selected = HIGHLIGHT_STOPS.indexOfFirst { it.first == ui.highlightStops }.coerceAtLeast(0),
                        ) { i -> vm.setHighlightStops(HIGHLIGHT_STOPS[i].first) }
                    }
                },
                {
                    var on by remember { mutableStateOf(LocationSetting.enabled(context)) }
                    SettingRow(tr("导出保留拍摄位置"), on, true) {
                        on = it; LocationSetting.set(context, it)
                    }
                },
            ))

            // 我的风格: what the user brought in.
            SettingsGroup(tr("我的风格"), listOf<@Composable () -> Unit>({ Column(Modifier.padding(vertical = 8.dp)) { UserLibrarySection(vm) } }))

            // 更新
            SettingsGroup(tr("更新"), listOf<@Composable () -> Unit>(
                {
                    var auto by remember { mutableStateOf(Updater.autoCheck(context)) }
                    var wifi by remember { mutableStateOf(Updater.wifiDownload(context)) }
                    Column {
                        SettingRow(tr("自动检查更新"), auto, true,
                            note = tr("只连 GitHub 上本项目的发布页，不上传任何内容。")) { auto = it; Updater.setAutoCheck(context, it) }
                        SettingRow(tr("Wi-Fi 下自动下载新版本"), wifi, auto) { wifi = it; Updater.setWifiDownload(context, it) }
                    }
                },
                {
                    ActionRow(tr("立即检查更新"), tr("当前 {0}", BuildConfig.VERSION_NAME)) {
                        onClose(); Updater.show(); Updater.check(context, manual = true)
                    }
                },
            ))

            // 关于与诊断
            SettingsGroup(tr("关于与诊断"), listOf<@Composable () -> Unit>(
                { InfoRow(tr("设备"), ui.deviceSummary.ifEmpty { "—" }) },
                { InfoRow(tr("引擎"), ui.engineSummary.ifEmpty { "—" }) },
                {
                    ActionRow(tr("性能测试"), tr("测量本机的预览、切换风格、导出速度，给出工作尺寸建议。")) {
                        vm.startBenchmark()
                    }
                },
                {
                    val lines = vm.benchmarkLines()
                    if (lines.isNotEmpty()) Column(Modifier.padding(vertical = 12.dp)) {
                        lines.forEach { Hint(it, Modifier.padding(vertical = 3.dp)) }
                    }
                },
                {
                    val clipboard = LocalClipboardManager.current
                    var copied by remember { mutableStateOf(false) }
                    ActionRow(if (copied) tr("已复制诊断信息") else tr("复制诊断信息"), tr("报告问题时一起贴上，里面没有照片和位置。")) {
                        clipboard.setText(AnnotatedString(vm.diagnostics())); copied = true
                    }
                },
                { Column(Modifier.padding(vertical = 12.dp)) { AboutSection() } },
            ))
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One group: its name, then the rows in one container (ui_corners.md 5):
 * the container's outer corners are Corners.l, the rows inside are square,
 * a hairline between them, no seams.
 */
@Composable
private fun SettingsGroup(title: String, rows: List<@Composable () -> Unit>) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.OnSurfaceSoft,
        modifier = Modifier.padding(start = 4.dp, top = GroupGap, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().clip(Corners.l).background(Palette.Panel)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Palette.OutlineSoft))
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { row() }
        }
    }
}

/** A name and one line on the left, a chevron on the right; the whole row is the button. */
@Composable
private fun ActionRow(label: String, note: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurface)
            if (note.isNotBlank()) Hint(note)
        }
        Chevron(0f)
    }
}

/** A fact, not a control: the name over its value. */
@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurface)
        Hint(value)
    }
}

/**
 * A setting with several values: collapsed it shows the current one on the
 * right (the upstream's "a closed section says what is set"); tapped, the
 * choices open under it in place.
 */
@Composable
private fun ChoiceRow(label: String, note: String, value: String, choices: @Composable () -> Unit) {
    var open by rememberSaveable(label) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) tr("已展开") else tr("已收起") }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurface)
                if (!open) Text(value, style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val turn by animateFloatAsState(if (open) 90f else 0f, Motion.defaultSpatial(), label = "choice-turn")
            Chevron(turn)
        }
        AnimatedVisibility(open, enter = expandVertically(Motion.defaultSpatial()) + fadeIn(Motion.defaultEffects()),
            exit = shrinkVertically(Motion.defaultSpatial()) + fadeOut(Motion.fastEffects())) {
            Column(Modifier.padding(bottom = 12.dp)) {
                Hint(note, Modifier.padding(bottom = 10.dp))
                choices()
            }
        }
    }
}

/** A right-pointing chevron, turned by [degrees] (90 = open, pointing down). */
@Composable
private fun Chevron(degrees: Float) {
    Canvas(Modifier.size(20.dp).graphicsLayer { rotationZ = degrees }) {
        val u = size.width / 20f
        val w = 1.6f * u
        drawLine(Palette.OnSurfaceSoft, Offset(8 * u, 5 * u), Offset(13 * u, 10 * u), w, StrokeCap.Round)
        drawLine(Palette.OnSurfaceSoft, Offset(13 * u, 10 * u), Offset(8 * u, 15 * u), w, StrokeCap.Round)
    }
}

/** 新照片的胶片和相纸: 无 (the photo as it is), remember the last pair, or a fixed pair picked from the lists. */
@Composable
private fun NewPhotoStocks(ui: UiState, vm: EditorViewModel) {
    val mode = ui.newPhotoMode
    val fixed = mode == EditorViewModel.NEW_FIXED
    val value = if (mode == EditorViewModel.NEW_NONE) tr("无，打开就是原图") else if (!fixed) tr("记住上次用的") else listOf(
        ui.catalog.film(ui.defaultFilm)?.label ?: ui.defaultFilm,
        ui.catalog.paper(ui.defaultPaper)?.label ?: ui.defaultPaper,
    ).joinToString(" · ")
    var picking by remember { mutableStateOf<String?>(null) }
    ChoiceRow(tr("新照片的胶片和相纸"), tr("只对没编辑过的照片生效。"), value) {
        Segmented(options = listOf(tr("无"), tr("记住上次用的"), tr("固定为")),
            selected = when (mode) { EditorViewModel.NEW_NONE -> 0; EditorViewModel.NEW_REMEMBER -> 1; else -> 2 }) { i ->
            if (i == 0) vm.setNewPhotoNone()
            else if (i == 1) vm.setNewPhotoStocks("", "")
            else vm.setNewPhotoStocks(ui.defaultFilm.ifEmpty { ui.edit.film }, ui.defaultPaper.ifEmpty { ui.edit.paper })
        }
        if (fixed) {
            Spacer(Modifier.height(RowGap))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton(tr("胶片：") + (ui.catalog.film(ui.defaultFilm)?.label ?: ui.defaultFilm),
                    modifier = Modifier.weight(1f)) { picking = "film" }
                TonalButton(tr("相纸：") + (ui.catalog.paper(ui.defaultPaper)?.label ?: ui.defaultPaper),
                    modifier = Modifier.weight(1f)) { picking = "paper" }
            }
        }
    }
    when (picking) {
        "film" -> StockSheet(tr("新照片的胶片"), ui.catalog.films, ui.defaultFilm, { picking = null }) {
            vm.setNewPhotoStocks(it, ui.defaultPaper)
        }
        "paper" -> StockSheet(tr("新照片的相纸"), ui.catalog.papers, ui.defaultPaper, { picking = null }) {
            vm.setNewPhotoStocks(ui.defaultFilm, it)
        }
    }
}

/** A stock list in a sheet: one 56 dp row per stock, the current one on the inverted fill. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StockSheet(title: String, stocks: List<Stock>, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.PanelHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            PanelHeader(title)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(stocks, key = { it.id }) { s ->
                    val on = s.id == selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Corners.m)
                            .background(if (on) Palette.Accent else Palette.Panel)
                            .clickable(role = Role.RadioButton) { onPick(s.id); onDismiss() }
                            .semantics { contentDescription = s.label + if (on) tr("，当前") else "" }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
                            color = if (on) Palette.OnAccent else Palette.OnSurface)
                        val tags = listOfNotNull(if (s.positive) tr("反转") else null, if (s.bw) tr("黑白") else null)
                        if (tags.isNotEmpty()) Text(tags.joinToString(" "), style = MaterialTheme.typography.labelSmall,
                            color = if (on) Palette.OnAccent else Palette.OnSurfaceSoft)
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}
