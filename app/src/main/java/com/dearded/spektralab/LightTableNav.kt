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
            // The loupe stays open under the editor: back from an edit returns to the same page.
            val loupe by table.loupe.collectAsState()
            val open = loupe
            if (open != null) {
                LightTableLoupe(table, open, onEdit = ::openInEditor, onClose = table::closeLoupe)
            } else {
                LightTableScreen(
                    table = table,
                    onBack = { fromTable = false; screen = Screen.EDITOR },
                    onOpen = table::openLoupe,
                    selectionActions = { chosen -> BatchActions(table, editor, chosen) },
                )
            }
        }
    }
}
