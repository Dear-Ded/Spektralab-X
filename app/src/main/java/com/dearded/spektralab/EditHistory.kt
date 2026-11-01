package com.dearded.spektralab

/**
 * Undo/redo of whole edits. Edits closer together than [settleMs] join one
 * step, so a slider drag undoes as a single move. Not thread-safe: the editor
 * drives it from the main thread only.
 */
class EditHistory(private val settleMs: Long = 700L, private val capacity: Int = 50) {
    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()
    private var lastAt = 0L

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** [before] -> [after] happened at [now] (uptime ms). */
    fun record(before: Edit, after: Edit, now: Long) {
        if (before == after) return
        if (undo.isEmpty() || now - lastAt > settleMs) {
            undo.addLast(before)
            while (undo.size > capacity) undo.removeFirst()
        }
        lastAt = now
        redo.clear()
    }

    /** The edit to go back to, or null; [current] becomes redoable. */
    fun undo(current: Edit): Edit? {
        val previous = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        lastAt = 0L
        return previous
    }

    fun redo(current: Edit): Edit? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        lastAt = 0L
        return next
    }

    fun clear() {
        undo.clear()
        redo.clear()
        lastAt = 0L
    }
}
