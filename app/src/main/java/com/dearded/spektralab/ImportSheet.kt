package com.dearded.spektralab

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.CircleShape
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Files waiting to be imported: from the picker in Settings, or opened or
 * shared from another app (MainActivity). [ImportHost] takes them one at a time.
 */
object ImportRequests {
    private val _uris = MutableStateFlow<List<Uri>>(emptyList())
    val uris: StateFlow<List<Uri>> = _uris

    fun add(list: List<Uri>) {
        if (list.isNotEmpty()) _uris.update { (it + list).distinct().take(StyleImports.MAX_BATCH) }
    }

    fun take(): Uri? {
        var head: Uri? = null
        _uris.update { l -> head = l.firstOrNull(); l.drop(1) }
        return head
    }
}

/**
 * The import sheet (docs/STYLES.md section 7): reads each requested file off
 * the main thread, previews a style on the open photo at its strength, lets the
 * user name and file it, and lists what could not be converted. A film is kept,
 * opened once on the engine, and removed again when the engine refuses it.
 * Sits beside the editor (MainActivity), so it works from any screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportHost(vm: EditorViewModel) {
    val context = LocalContext.current
    val library = remember(context) { UserLibrary.get(context) }
    val queue by ImportRequests.uris.collectAsState()
    val ui by vm.ui.collectAsState()
    // Not the effect's scope: a state change must never cancel a read halfway.
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ImportUi()) }
    // The edit before a style preview, put back when the import is cancelled.
    var before by remember { mutableStateOf<Edit?>(null) }

    val idle = state.pending == null && !state.busy && state.error == null
    LaunchedEffect(queue.size, idle, ui.ready) {
        if (!idle || !ui.ready) return@LaunchedEffect
        val uri = ImportRequests.take() ?: return@LaunchedEffect
        state = ImportUi(busy = true, left = ImportRequests.uris.value.size)
        val catalog = vm.ui.value.catalog
        scope.launch {
            state = try {
                val p = withContext(Dispatchers.IO) { library.read(uri, catalog) }
                ImportUi(pending = p, left = ImportRequests.uris.value.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportException) {
                ImportUi(error = e.message ?: "无法导入", left = ImportRequests.uris.value.size)
            } catch (e: Exception) {
                ImportUi(error = "读取失败：${e.message ?: e.javaClass.simpleName}", left = ImportRequests.uris.value.size)
            } catch (e: OutOfMemoryError) {
                ImportUi(error = "文件太大，内存不够", left = ImportRequests.uris.value.size)
            }
        }
    }

    val pending = state.pending
    var strength by remember(pending) { mutableStateOf(1.0) }
    var name by remember(pending) { mutableStateOf(pending?.look?.name.orEmpty()) }
    var category by remember(pending) { mutableStateOf(pending?.look?.category.orEmpty()) }

    fun preview(p: PendingImport, k: Double) {
        val base = before ?: return
        vm.applyStyle(p.look.toStyle(PREVIEW_ID, base, p.lutId).atStrength(k))
    }

    // A style shows on the open photo straight away, at full strength.
    LaunchedEffect(pending) {
        before = null
        if (pending?.kind == ImportKind.STYLE && vm.ui.value.hasImage) {
            before = vm.ui.value.edit
            preview(pending, 1.0)
        }
    }

    fun cancel() {
        val p = state.pending
        if (p != null) {
            before?.let(vm::update)
            scope.launch(Dispatchers.IO) { library.discard(p) }
        }
        before = null
        state = ImportUi()
    }

    if (state.pending == null && !state.busy && state.error == null) return

    ModalBottomSheet(onDismissRequest = { if (!state.busy) cancel() }, containerColor = Palette.Panel) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
        ) {
            // ui-screens_research.md 4: what is left in the batch, said plainly in the head's summary.
            val left = if (state.left > 0) "后面还有 ${state.left} 个" else ""
            when {
                state.busy -> {
                    PanelHeader("正在读取", summary = left)
                    // The working bar, never a spinner over the photo (UI2.md 1.1).
                    AuraBar(Modifier.padding(vertical = RowGap))
                }
                state.error != null -> {
                    PanelHeader("没能导入", summary = left)
                    Text(state.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurface)
                    Spacer(Modifier.height(RowGap))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        PrimaryPill(if (state.left > 0) "下一个" else "好") { state = ImportUi() }
                    }
                }
                pending != null && pending.kind == ImportKind.STYLE -> {
                    PanelHeader(
                        "导入风格", summary = listOf(pending.fileName, left).filter { it.isNotEmpty() }.joinToString(" · "),
                        hint = if (before != null) "已套用到当前照片。" else "打开一张照片后可以预览和调强度。",
                    )
                    OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                        label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                    // The categories already in use first, so a batch lands in one place; a new name below.
                    val known by library.styles.all.collectAsState()
                    val categories = remember(known) { known.map { it.category }.filter { it.isNotBlank() }.distinct() }
                    if (categories.isNotEmpty()) PillRow(Modifier.padding(bottom = 4.dp)) {
                        for (c in categories) SelectPill(selected = category == c, onClick = { category = c }, label = { Text(c) })
                    }
                    OutlinedTextField(value = category, onValueChange = { category = it.take(20) }, singleLine = true,
                        label = { Text(if (categories.isEmpty()) "分类" else "或新建分类") }, modifier = Modifier.fillMaxWidth())
                    if (before != null && pending.look.scope != StyleScope.FILM) {
                        Spacer(Modifier.height(RowGap))
                        LabeledSlider("强度", strength * 100, 0.0, 100.0, 100.0, true, "%.0f%%") { v ->
                            strength = (v / 100).coerceIn(0.0, 1.0)
                            preview(pending, strength)
                        }
                    }
                    ReportList(pending.report)
                    Buttons(onCancel = ::cancel, keep = "保存") {
                        val p = pending
                        scope.launch {
                            withContext(Dispatchers.IO) { library.saveStyle(p, name, category, strength) }
                            vm.showMessage("已保存风格「${name.trim().ifEmpty { p.look.name }}」")
                            before = null
                            state = ImportUi()
                        }
                    }
                }
                pending != null -> {
                    val recipe = pending.kind == ImportKind.FILM_RECIPE
                    PanelHeader(
                        if (recipe) "导入胶片方案" else "导入胶片数据",
                        summary = listOf(pending.fileName, left).filter { it.isNotEmpty() }.joinToString(" · "),
                    )
                    Text(pending.look.name, style = MaterialTheme.typography.bodyLarge, color = Palette.OnSurface)
                    Hint(
                        when {
                            recipe -> "基于 ${ui.catalog.film(pending.base)?.label ?: pending.base}，没有自己的光谱数据"
                            pending.stage == "printing" -> "相纸 · 完整数据，保存前会在引擎里试开一次"
                            else -> "胶片 · 完整数据，保存前会在引擎里试开一次"
                        },
                    )
                    ReportList(pending.report)
                    Buttons(onCancel = ::cancel, keep = "保存") {
                        val p = pending
                        state = state.copy(busy = true)
                        scope.launch {
                            val error = try {
                                withContext(Dispatchers.IO) { library.saveFilm(p) }
                                val catalog = vm.ui.value.catalog
                                val edit = vm.ui.value.edit
                                val (film, paper) = if (p.stage == "printing") edit.film to p.profileId else {
                                    val target = p.profileJson?.optJSONObject("info")?.optString("target_print")?.takeIf { it.isNotEmpty() }
                                        ?: catalog.film(p.base)?.targetPrint ?: edit.paper
                                    p.profileId to target
                                }
                                vm.trialStock(film, paper)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                e.message ?: e.javaClass.simpleName
                            }
                            if (error != null) {
                                withContext(Dispatchers.IO) { library.dropFilm(p.profileId) }
                                state = ImportUi(error = "引擎打不开这份数据，已删除：$error", left = state.left)
                                return@launch
                            }
                            // A recipe is also a one-tap style on its new film, under 胶片方案.
                            if (recipe) withContext(Dispatchers.IO) { library.saveStyle(p, p.look.name, p.look.category, 1.0) }
                            vm.reloadCatalog()
                            vm.showMessage("已添加「${p.look.name}」")
                            state = ImportUi()
                        }
                    }
                }
            }
        }
    }
}

/** What the import could not carry over exactly: approximations first, then what was left out. */
@Composable
private fun ReportList(report: ImportReport) {
    val items = report.unconverted.sortedBy { it.kind.ordinal }
    val mapped = report.items.size - items.size
    Spacer(Modifier.height(RowGap))
    // One line says how it went; the item list is behind a row, so the sheet stays short (UI2.md 2.4).
    Text(if (items.isEmpty()) "已转换 $mapped 项" else "${items.size} 项没能原样转换，另有 $mapped 项已对应",
        style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurface)
    if (items.isEmpty()) return
    var open by remember(report) { mutableStateOf(false) }
    QuietButton(if (open) "收起转换详情" else "查看转换详情", modifier = Modifier.padding(start = 0.dp)) { open = !open }
    if (open) {
        for (it in items.take(MAX_REPORT)) {
            val tag = if (it.kind == ImportReport.Kind.APPROXIMATED) "近似" else "未转换"
            Hint("$tag · ${it.what}" + if (it.why.isNotEmpty()) "：${it.why}" else "", Modifier.padding(top = 2.dp))
        }
        if (items.size > MAX_REPORT) Hint("…还有 ${items.size - MAX_REPORT} 项")
    }
}

/** 取消 as a quiet word, the keep action as the near-white primary pill at the right (UI2.md 1.1 Primary). */
@Composable
private fun Buttons(onCancel: () -> Unit, keep: String, onKeep: () -> Unit) {
    Spacer(Modifier.height(GroupGap))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        QuietButton("取消", onClick = onCancel)
        Spacer(Modifier.width(8.dp))
        PrimaryPill(keep, onClick = onKeep)
    }
}

/** The primary action: near-white, full pill, 48 dp, dark ink. */
@Composable
private fun PrimaryPill(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick, shape = Corners.full, modifier = Modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/**
 * Settings' 我的风格 section: import, share the open edit as a .spklook, and
 * the imported styles and films with a way to remove each. One call site.
 */
@Composable
fun UserLibrarySection(vm: EditorViewModel) {
    val context = LocalContext.current
    val library = remember(context) { UserLibrary.get(context) }
    val ui by vm.ui.collectAsState()
    val styles by library.styles.all.collectAsState()
    val scope = rememberCoroutineScope()
    var films by remember { mutableStateOf(library.profiles.list().map { it.nameWithoutExtension }) }
    var sharing by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        ImportRequests.add(uris)
    }
    Text(
        "支持 .spklook、.cube、Lightroom 预设等。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("导入…") }
        TextButton(onClick = { sharing = true }, enabled = ui.hasImage) { Text("分享当前风格") }
    }
    for (s in styles) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${s.name} · ${s.category}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch(Dispatchers.IO) { library.deleteStyle(s.id) } }) { Text("删除") }
        }
    }
    for (id in films) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(ui.catalog.film(id)?.label ?: ui.catalog.paper(id)?.label ?: id, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { library.dropFilm(id) }
                    films = films - id
                    vm.reloadCatalog()
                }
            }, enabled = ui.edit.film != id && ui.edit.paper != id) { Text("删除") }
        }
    }

    if (sharing) {
        var name by remember { mutableStateOf("我的风格") }
        val scopes = StyleScope.entries.filter { it != StyleScope.FILM || !ui.edit.noFilm }
        var shareScope by remember { mutableStateOf(if (ui.edit.noFilm) StyleScope.FILTER else StyleScope.COMBINATION) }
        AlertDialog(
            onDismissRequest = { sharing = false },
            title = { Text("分享当前风格") },
            text = {
                Column {
                    StepRow("分享范围", scopes.map { it.label }, scopes.indexOf(shareScope), true) { shareScope = scopes[it] }
                    Text("打包成 .spklook，可在另一张照片上继续使用。照片本身、裁切、边框、局部蒙版和 RAW 显影不会带上。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                        label = { Text("名称") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    sharing = false
                    val selectedScope = shareScope
                    scope.launch {
                        val file = vm.exportLook(name, selectedScope) ?: return@launch
                        val uri = FileProvider.getUriForFile(context, context.packageName + SHARE_AUTHORITY, file)
                        val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .apply { clipData = android.content.ClipData.newRawUri("", uri) }
                        context.startActivity(Intent.createChooser(send, "分享风格"))
                    }
                }) { Text("分享") }
            },
            dismissButton = { TextButton(onClick = { sharing = false }) { Text("取消") } },
        )
    }
}

private const val PREVIEW_ID = "import_preview"
private const val MAX_REPORT = 40
/** The FileProvider authority suffix (AndroidManifest.xml, res/xml/share_paths.xml). */
internal const val SHARE_AUTHORITY = ".share"
