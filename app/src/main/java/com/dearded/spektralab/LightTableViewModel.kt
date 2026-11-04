package com.dearded.spektralab

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FrameFilter(val label: String) {
    ALL("全部"), PICKS("选中"), REJECTS("淘汰"), EDITED("已编辑");

    fun keeps(f: Frame) = when (this) {
        ALL -> true
        PICKS -> f.pick > 0
        REJECTS -> f.pick < 0
        EDITED -> f.edited
    }
}

data class TableState(
    val filter: FrameFilter = FrameFilter.ALL,
    val columns: Int = 3,
    val selection: Set<String> = emptySet(),
    /** Photos still being read in by an import (0 = none running). */
    val importing: Int = 0,
    /** After a pick or a reject in the loupe, move on to the next frame. */
    val autoAdvance: Boolean = true,
    val message: String? = null,
)

/** The loupe's run of frames (by id, so a re-filtered grid does not move it) and where it opened. */
data class Loupe(val ids: List<String>, val start: Int)

/** The frame the editor has open for the light table, so its edits go back to that frame's sidecar. */
data class EditLink(val id: String, val name: String)

/**
 * The light table's state. Process-lifetime data (frames, sidecars) lives in
 * [FrameStore]; this holds the screen's choices and runs the work that must
 * not happen on the main thread.
 */
class LightTableViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("light_table", 0)
    private val host = (app as SpektraLabApp).host
    val store = FrameStore.of(app)
    val frames: StateFlow<List<Frame>> = store.frames

    private val _state = MutableStateFlow(TableState(
        columns = prefs.getInt("columns", 3).takeIf { it in COLUMNS } ?: 3,
        filter = runCatching { FrameFilter.valueOf(prefs.getString("filter", "ALL")!!) }.getOrDefault(FrameFilter.ALL),
        autoAdvance = prefs.getBoolean("auto_advance", true),
    ))
    val state: StateFlow<TableState> = _state

    private val _editing = MutableStateFlow<EditLink?>(null)
    val editing: StateFlow<EditLink?> = _editing

    private val _loupe = MutableStateFlow<Loupe?>(null)
    val loupe: StateFlow<Loupe?> = _loupe

    init {
        viewModelScope.launch(Dispatchers.IO) { store.load() }
    }

    fun openLoupe(frames: List<Frame>, index: Int) {
        if (index !in frames.indices) return
        _loupe.value = Loupe(frames.map { it.id }, index)
    }

    /** The loupe moved to [index]; reopening it after the editor comes back there. */
    fun loupeAt(index: Int) = _loupe.update { it?.copy(start = index) }

    fun closeLoupe() { _loupe.value = null }

    fun visible(all: List<Frame>, filter: FrameFilter) = if (filter == FrameFilter.ALL) all else all.filter(filter::keeps)

    // --- import ---------------------------------------------------------------

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(importing = uris.size) }
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                store.import(uris) { done -> _state.update { it.copy(importing = uris.size - done) } }
            }
            val again = uris.size - added
            show(when {
                added == 0 -> "这些照片已经在观片台上"
                again > 0 -> "已加入 $added 张，$again 张原本就在"
                else -> "已加入 $added 张"
            })
            _state.update { it.copy(importing = 0) }
        }
    }

    // --- view -------------------------------------------------------------------

    fun setColumns(n: Int) {
        if (n !in COLUMNS || n == _state.value.columns) return
        prefs.edit().putInt("columns", n).apply()
        _state.update { it.copy(columns = n) }
    }

    fun setFilter(f: FrameFilter) {
        prefs.edit().putString("filter", f.name).apply()
        _state.update { it.copy(filter = f) }
    }

    fun setAutoAdvance(on: Boolean) {
        prefs.edit().putBoolean("auto_advance", on).apply()
        _state.update { it.copy(autoAdvance = on) }
    }

    // --- marks ------------------------------------------------------------------------

    /** Picks (1), rejects (-1) or clears (0) [ids], and/or sets their stars; null leaves that mark alone. */
    fun rate(ids: Set<String>, pick: Int? = null, stars: Int? = null) {
        if (ids.isEmpty() || (pick == null && stars == null)) return
        viewModelScope.launch(Dispatchers.IO) { store.rate(ids, pick?.coerceIn(-1, 1), stars) }
    }

    // --- selection ----------------------------------------------------------------

    fun toggle(id: String) = _state.update {
        it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
    }

    /** A long-press drag: [base] (what was selected when it began) plus [ids]. */
    fun selectRange(base: Set<String>, ids: Collection<String>) = _state.update { it.copy(selection = base + ids) }

    fun selectAll(ids: Collection<String>) = _state.update { it.copy(selection = ids.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    /** Takes the selection off the table; sidecars stay, so a re-import brings the edits back. */
    fun removeSelected() {
        val ids = _state.value.selection
        if (ids.isEmpty()) return
        val inUse = host.queue.jobs.value.filter { it.state != JobState.DONE }.map { it.uri }.toSet()
        viewModelScope.launch(Dispatchers.IO) { store.remove(ids, inUse) }
        _state.update { it.copy(selection = emptySet()) }
        show("已从观片台移走 ${ids.size} 张（编辑记录保留）")
    }

    // --- editing --------------------------------------------------------------------

    /**
     * Opens [frame] in [editor] with its sidecar: the whole edit, geometry and
     * RAW white balance included. A frame without one opens the way any photo
     * does, carrying the editor's current look. False while the engine starts.
     */
    fun edit(frame: Frame, editor: EditorViewModel): Boolean {
        if (!editor.ui.value.ready) { show("引擎还在准备，稍等一下"); return false }
        _editing.value = EditLink(frame.id, frame.name)
        viewModelScope.launch {
            val car = withContext(Dispatchers.IO) { store.sidecar(frame.id) }
            val uri = Uri.parse(frame.uri)
            if (car != null) editor.openSaved(uri, car.edit, car.wbK, car.wbTint) else editor.open(uri)
        }
        return true
    }

    /** The editor opened another photo: its edits no longer belong to [link]'s frame. */
    fun unlink(link: EditLink) = _editing.compareAndSet(link, null)

    /** The editor has [link]'s frame open at [w] x [h] (its upright shape, which RAWs only tell once decoded). */
    fun opened(link: EditLink, w: Int, h: Int) {
        viewModelScope.launch(Dispatchers.IO) { store.setSize(link.id, w, h) }
    }

    fun save(link: EditLink, edit: Edit, wbK: Double, wbTint: Double) {
        viewModelScope.launch(Dispatchers.IO) { store.saveEdit(link.id, edit, wbK, wbTint) }
    }

    // --- messages -------------------------------------------------------------------

    fun show(text: String) = _state.update { it.copy(message = text) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        /** The grid's column counts, pinched through in this order. */
        val COLUMNS = listOf(2, 3, 4, 6)
    }
}
