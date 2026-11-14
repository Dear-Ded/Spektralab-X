package com.dearded.spektralab

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The app's screens. The editor stays the start screen; the light table is entered from it. */
enum class Screen { EDITOR, TABLE }

/**
 * The editor and the light table (观片台) under one activity. The editor
 * owns sidecar saving independently of the composed screens.
 */
@Composable
fun AppScreens(editor: EditorViewModel) {
    val table: LightTableViewModel = viewModel()
    var screen by rememberSaveable { mutableStateOf(Screen.EDITOR) }
    // The editor was entered from the table, so back returns there.
    var fromTable by rememberSaveable { mutableStateOf(false) }
    val navigation = rememberCoroutineScope()
    val showQueue by remember(editor) { editor.ui.map { it.showQueue }.distinctUntilChanged() }.collectAsState(false)

    fun returnToTable() {
        navigation.launch {
            try {
                editor.flushFrameEdits()
                fromTable = false
                screen = Screen.TABLE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                editor.showMessage(tr("保存编辑记录失败：{0}", e.message.orEmpty()))
            }
        }
    }

    // The 正在处理 sheet lives in the editor (the notification asks for it).
    LaunchedEffect(showQueue) { if (showQueue) screen = Screen.EDITOR }
    // A photo shared or opened from another app lands in the editor: show it there, and
    // back no longer returns to the table, whose frame it did not come from (bughunt_main.md 4).
    LaunchedEffect(editor) {
        editor.externalOpens.collect { fromTable = false; screen = Screen.EDITOR }
    }

    // The light table's own state, read here so the back gesture knows which page is showing.
    val loupe by table.loupe.collectAsState()
    val nav by table.nav.collectAsState()
    val frames by table.frames.collectAsState()
    // Only a catalog change recomposes here, not every editor state change.
    val editorUi = editor.ui.collectAsState()
    val catalog by remember(editorUi) { androidx.compose.runtime.derivedStateOf { editorUi.value.catalog } }
    val manual by table.manualRolls.rolls.collectAsState()
    val rolls = remember(frames, catalog, manual, AppLocale.language) { rollsOf(frames, catalog, manual) }
    val comparing = nav.compare?.let { id -> frames.firstOrNull { it.id == id } }
    val roll = nav.roll?.let { id -> rolls.firstOrNull { it.id == id } }
    val page = pageOf(nav.rolls, roll != null, loupe != null, comparing != null)
    val selecting by remember(table) { table.state.map { it.selection.isNotEmpty() }.distinctUntilChanged() }
        .collectAsState(false)

    // UI2.md 3.7 / 3.8: the switch is one SeekableTransitionState, so a back
    // gesture scrubs it under the finger. Opening the table from the editor is a
    // step deeper (forwardThrough); returning either way is backThrough.
    var backward by remember { mutableStateOf(false) }
    fun leaveTable() { backward = true; fromTable = false; screen = Screen.EDITOR }
    val outer = rememberSeekableNav(screen)
    // Composed before the screens, so their own backs (immersive, panel, 1:1, a selection) win.
    // With nowhere in the app to go back to (the editor opened on its own) nothing is
    // registered: the system keeps back, and with it the back-to-home animation.
    val outerBack = when {
        screen == Screen.EDITOR && fromTable -> Screen.TABLE
        screen == Screen.TABLE && page.root && !selecting -> Screen.EDITOR
        else -> null
    }
    SeekBack(outer, outerBack, onStart = { backward = true }) {
        if (screen == Screen.EDITOR) { backward = true; returnToTable() } else leaveTable()
    }
    rememberTransition(outer, label = "screen").AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (backward) backThrough() else forwardThrough() },
    ) { s ->
        when (s) {
            Screen.EDITOR -> EditorScreen(editor, onLightTable = { backward = false; returnToTable() })
            Screen.TABLE -> TablePages(
                table = table, editor = editor, page = page, rolls = rolls, catalog = catalog,
                loupe = loupe, comparing = comparing, roll = roll,
                onEdit = { f -> if (table.edit(f, editor)) { backward = false; fromTable = true; screen = Screen.EDITOR } },
                onLeave = ::leaveTable,
            )
        }
    }
}

/** The light table's pages, deepest last; [root] pages hand back to the editor. */
private enum class TablePage(val depth: Int, val root: Boolean = false) {
    GRID(0, true), ROLLS(0, true), ROLL(1), LOUPE(2), COMPARE(3)
}

/** Which page shows, in the order the table always used (a compare over the loupe over a roll). */
private fun pageOf(rolls: Boolean, roll: Boolean, loupe: Boolean, compare: Boolean) = when {
    compare -> TablePage.COMPARE
    loupe -> TablePage.LOUPE
    rolls && roll -> TablePage.ROLL
    rolls -> TablePage.ROLLS
    else -> TablePage.GRID
}

/** What a page's back returns to inside the table (null: back leaves the table). */
private fun underOf(page: TablePage, rolls: Boolean, roll: Boolean, loupe: Boolean): TablePage? = when (page) {
    TablePage.COMPARE -> pageOf(rolls, roll, loupe, false)
    TablePage.LOUPE -> pageOf(rolls, roll, false, false)
    TablePage.ROLL -> TablePage.ROLLS
    TablePage.GRID, TablePage.ROLLS -> null
}

/** The last loupe, compare and roll shown, so a page that is leaving can still draw itself. */
private class LastShown { var loupe: Loupe? = null; var comparing: Frame? = null; var roll: Roll? = null }

/**
 * The light table inside a SharedTransitionLayout (Transitions.kt): a frame
 * opened from the grid or a roll flies into the loupe and back
 * (Modifier.sharedFrame), pages a level apart move by fade through, and the
 * back gesture scrubs each switch. The grid, the roll list and a roll keep
 * their scroll position while a deeper page is open, so a frame flies back to
 * the cell it came from.
 */
@Composable
private fun TablePages(
    table: LightTableViewModel, editor: EditorViewModel, page: TablePage,
    rolls: List<Roll>, catalog: Catalog, loupe: Loupe?, comparing: Frame?, roll: Roll?,
    onEdit: (Frame) -> Unit, onLeave: () -> Unit,
) {
    val nav by table.nav.collectAsState()
    val last = remember { LastShown() }
    if (loupe != null) last.loupe = loupe
    if (comparing != null) last.comparing = comparing
    if (roll != null) last.roll = roll
    val exportRoll = rememberNotifiedExport { list -> table.export(list, ExportFormat.JPEG, ExportSize.FULL, editor.ui.value) }
    val holder = rememberSaveableStateHolder()
    val state = rememberSeekableNav(page)
    // Back inside the table: compare, loupe and roll each close to what is under them.
    SeekBack(state, underOf(page, nav.rolls, roll != null, loupe != null)) {
        when (page) {
            TablePage.COMPARE -> table.compare(null)
            TablePage.LOUPE -> table.closeLoupe()
            TablePage.ROLL -> table.openRoll(null)
            TablePage.GRID, TablePage.ROLLS -> {}
        }
    }
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        val shared = this
        // The pages draw on Palette ground without a Surface: give them light ink, or bare Text and Icons fall back to black.
        CompositionLocalProvider(LocalSharedScope provides shared, androidx.compose.material3.LocalContentColor provides Palette.OnSurface) {
            rememberTransition(state, label = "table").AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val a = initialState
                    val b = targetState
                    when {
                        // The frame itself carries the motion; the pages only cross-fade.
                        a == TablePage.LOUPE || b == TablePage.LOUPE ->
                            fadeIn(Motion.defaultEffects()) togetherWith fadeOut(Motion.fastEffects())
                        b.depth > a.depth -> forwardThrough()
                        b.depth < a.depth -> backThrough()
                        else -> fadeIn(Motion.defaultEffects()) togetherWith fadeOut(Motion.fastEffects())
                    }
                },
            ) { p ->
                val scope = this
                CompositionLocalProvider(LocalNavScope provides scope) {
                    // The loupe and the compare start fresh each time; the others keep their scroll.
                    if (p.depth < TablePage.LOUPE.depth) holder.SaveableStateProvider(p) {
                        TablePageContent(p, table, editor, rolls, last, exportRoll, onLeave)
                    } else when (p) {
                        TablePage.COMPARE -> last.comparing?.let { RollCompare(table, it, catalog, onClose = { table.compare(null) }) }
                        TablePage.LOUPE -> last.loupe?.let { LightTableLoupe(table, it, onEdit = onEdit, onClose = table::closeLoupe) }
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun TablePageContent(
    p: TablePage, table: LightTableViewModel, editor: EditorViewModel, rolls: List<Roll>,
    last: LastShown, exportRoll: (List<Frame>) -> Unit, onLeave: () -> Unit,
) {
    when (p) {
        TablePage.ROLL -> last.roll?.let { r ->
            RollView(
                table = table, roll = r,
                onBack = { table.openRoll(null) },
                onOpen = table::openLoupe,
                onExport = exportRoll,
                onContactSheet = { table.exportContactSheet(r, editor.ui.value.maxPixels) },
            )
        }
        TablePage.ROLLS -> RollList(
            table, rolls,
            onOpen = { table.openRoll(it.id) },
            onGrid = { table.showRolls(false) },
            onBack = onLeave,
        )
        TablePage.GRID -> LightTableScreen(
            table = table,
            onBack = onLeave,
            onOpen = table::openLoupe,
            onRolls = { table.showRolls(true) },
            selectionActions = { chosen -> BatchActions(table, editor, chosen); RollSelectionActions(table, chosen) },
        )
        TablePage.LOUPE, TablePage.COMPARE -> {}
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
