package com.dearded.spektralab

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** The app's screens. The editor stays the start screen; the light table is entered from it. */
enum class Screen { EDITOR, TABLE }

/**
 * The editor and the light table (观片台) under one activity. While the
 * editor has a light-table frame open, its edits are written back to that
 * frame's sidecar (debounced); opening any other photo ends the link.
 */
@OptIn(FlowPreview::class)
@Composable
fun AppScreens(editor: EditorViewModel) {
    val table: LightTableViewModel = viewModel()
    var screen by rememberSaveable { mutableStateOf(Screen.EDITOR) }
    // The editor was entered from the table, so back returns there.
    var fromTable by rememberSaveable { mutableStateOf(false) }
    val link by table.editing.collectAsState()
    val showQueue by remember(editor) { editor.ui.map { it.showQueue }.distinctUntilChanged() }.collectAsState(false)

    // The 正在处理 sheet lives in the editor (the notification asks for it).
    LaunchedEffect(showQueue) { if (showQueue) screen = Screen.EDITOR }

    LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        val frame = table.store.byId(l.id) ?: return@LaunchedEffect
        var baseline: Pair<Edit, Pair<Double, Double>>? = null
        editor.ui
            // Only the frame itself: right after the open call the previous photo is still on screen.
            .filter { it.busy == null && it.source?.name == frame.name }
            .map { Triple(it.edit, it.source!!, editor.openUri?.toString()) }
            .distinctUntilChanged()
            .debounce(600)
            .collect { (edit, src, open) ->
                if (open != frame.uri) { table.unlink(l); return@collect }
                val now = edit to (src.wbK to src.wbTint)
                if (baseline == null) {
                    // What it opened with: saving that would mark an untouched frame edited.
                    baseline = now
                    table.opened(l, src.width, src.height)
                } else if (now != baseline) {
                    baseline = now
                    table.save(l, edit, src.wbK, src.wbTint)
                }
            }
    }

    fun openInEditor(frame: Frame) {
        if (table.edit(frame, editor)) { fromTable = true; screen = Screen.EDITOR }
    }

    when (screen) {
        Screen.EDITOR -> {
            // Composed before the editor, so the editor's own back (leaving immersive) wins.
            BackHandler(enabled = fromTable) { fromTable = false; screen = Screen.TABLE }
            EditorScreen(editor, onLightTable = { screen = Screen.TABLE })
        }
        Screen.TABLE -> {
            // The loupe, a roll and a compare stay open under the editor: back from an edit returns there.
            val loupe by table.loupe.collectAsState()
            val nav by table.nav.collectAsState()
            val frames by table.frames.collectAsState()
            val catalog by remember(editor) { editor.ui.map { it.catalog }.distinctUntilChanged() }
                .collectAsState(editor.ui.value.catalog)
            val manual by table.manualRolls.rolls.collectAsState()
            val rolls = remember(frames, catalog, manual) { rollsOf(frames, catalog, manual) }
            val exportRoll = rememberNotifiedExport { list -> table.export(list, ExportFormat.JPEG, ExportSize.FULL, editor.ui.value) }
            val open = loupe
            val comparing = nav.compare?.let { id -> frames.firstOrNull { it.id == id } }
            val roll = nav.roll?.let { id -> rolls.firstOrNull { it.id == id } }
            when {
                comparing != null -> RollCompare(table, comparing, catalog, onClose = { table.compare(null) })
                open != null -> LightTableLoupe(table, open, onEdit = ::openInEditor, onClose = table::closeLoupe)
                nav.rolls && roll != null -> RollView(
                    table = table, roll = roll,
                    onBack = { table.openRoll(null) },
                    onOpen = table::openLoupe,
                    onExport = exportRoll,
                    onContactSheet = { table.exportContactSheet(roll, editor.ui.value.maxPixels) },
                )
                nav.rolls -> RollList(rolls, onOpen = { table.openRoll(it.id) }, onBack = { table.showRolls(false) })
                else -> LightTableScreen(
                    table = table,
                    onBack = { fromTable = false; screen = Screen.EDITOR },
                    onOpen = table::openLoupe,
                    selectionActions = { chosen -> BatchActions(table, editor, chosen); RollSelectionActions(table, chosen) },
                    topActions = { TextButton(onClick = { table.showRolls(true) }) { Text("按卷") } },
                )
            }
        }
    }
}

/**
 * [run] wrapped the way the editor's export is: on Android 13+ the progress
 * notification's permission is asked first; a refusal only hides the notification.
 */
@Composable
fun rememberNotifiedExport(run: (List<Frame>) -> Unit): (List<Frame>) -> Unit {
    val context = LocalContext.current
    var queued by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        queued?.invoke()
        queued = null
    }
    return { list ->
        val missing = android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (missing) {
            queued = { run(list) }
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else run(list)
    }
}
