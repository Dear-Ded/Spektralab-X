package com.dearded.spektralab

/**
 * Undo/redo of whole edits. Edits closer together than [settleMs] join one
 * step, so a slider drag undoes as a single move. Not thread-safe: the editor
 * drives it from the main thread only.
 */
class EditHistory(private val settleMs: Long = 700L, private val capacity: Int = 50) {
    private data class Step(val edit: Edit, var adoption: PendingAssistantAdoption? = null)

    private val undo = ArrayDeque<Step>()
    private val redo = ArrayDeque<Step>()
    private var lastAt: Long? = null

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val undoTarget: Edit? get() = undo.lastOrNull()?.edit
    val redoTarget: Edit? get() = redo.lastOrNull()?.edit
    internal val undoAdoption get() = undo.lastOrNull()?.adoption
    internal val redoAdoption get() = redo.lastOrNull()?.adoption

    /** [before] -> [after] happened at [now] (uptime ms). */
    fun record(before: Edit, after: Edit, now: Long) {
        if (before == after) return
        val previous = lastAt
        if (undo.isEmpty() || previous == null || now - previous > settleMs) push(before)
        lastAt = now
        redo.clear()
    }

    /** A draft commit is one undo step, independent of adjustments on either side. */
    fun recordSeparate(before: Edit, after: Edit) {
        if (before == after) return
        push(before)
        lastAt = null
        redo.clear()
    }

    internal fun attachAdoption(adoption: PendingAssistantAdoption) {
        undo.last().adoption = adoption
    }

    private fun push(before: Edit) {
        undo.addLast(Step(before))
        while (undo.size > capacity) undo.removeFirst()
    }

    /** The edit to go back to, or null; [current] becomes redoable. */
    fun undo(current: Edit): Edit? {
        val previous = undo.removeLastOrNull() ?: return null
        redo.addLast(Step(current, previous.adoption))
        lastAt = null
        return previous.edit
    }

    fun redo(current: Edit): Edit? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(Step(current, next.adoption))
        lastAt = null
        return next.edit
    }

    fun clear() {
        undo.clear()
        redo.clear()
        lastAt = null
    }
}
