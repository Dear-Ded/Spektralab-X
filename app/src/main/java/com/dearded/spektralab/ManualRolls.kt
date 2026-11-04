package com.dearded.spektralab

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A roll the user packed: a name and its frames (by id) in order, at most [Roll.LENGTH]. */
@Immutable
data class ManualRoll(val id: String, val name: String, val frames: List<String>, val createdAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("created", createdAt)
        .put("frames", JSONArray().also { a -> frames.forEach { a.put(it) } })

    companion object {
        fun fromJson(o: JSONObject): ManualRoll? = runCatching {
            val a = o.getJSONArray("frames")
            ManualRoll(o.getString("id"), o.optString("name", "我的卷"),
                (0 until a.length()).map { a.getString(it) }.distinct().take(Roll.LENGTH), o.optLong("created"))
        }.getOrNull()
    }
}

/**
 * 手动装卷 (docs/UI.md appendix D, phase 2): the user's own rolls, kept in
 * `filesDir/Sidecars/rolls.json` beside the frame index. A frame belongs to at
 * most one manual roll, and a manual roll wins over the automatic ones: its
 * frames leave their film-and-day roll. Removing a frame from the table
 * leaves its id here; rollsOf skips ids no longer on the table.
 *
 * Every function that touches files is IO-thread only.
 */
class ManualRolls private constructor(context: Context) {
    private val file = File(File(context.filesDir, "Sidecars"), "rolls.json")
    private val lock = Any()
    private val _rolls = MutableStateFlow<List<ManualRoll>>(emptyList())
    val rolls: StateFlow<List<ManualRoll>> = _rolls
    @Volatile private var loaded = false

    fun load(): Unit = synchronized(lock) {
        if (loaded) return
        loaded = true
        _rolls.value = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ManualRoll::fromJson) }
        }.getOrDefault(emptyList())
    }

    /**
     * Packs [ids] (in this order) into a new roll called [name]; they leave any
     * roll they were in. Past 36 frames the rest go into 卷 2, 卷 3 ... Returns
     * how many rolls were made.
     */
    fun pack(ids: List<String>, name: String): Int = edit { list ->
        val take = ids.distinct()
        val now = System.currentTimeMillis()
        val chunks = take.chunked(Roll.LENGTH)
        val made = chunks.mapIndexed { i, c ->
            ManualRoll("r$now-$i", if (chunks.size > 1) "$name · 卷 ${i + 1}" else name, c, now + i)
        }
        withoutFrames(list, take.toSet()) + made to made.size
    }

    /**
     * Moves [ids] to the end of roll [to]. What does not fit in its 36 frames
     * stays where it was; returns how many moved.
     */
    fun move(ids: List<String>, to: String): Int = edit { list ->
        val target = list.firstOrNull { it.id == to } ?: return@edit list to 0
        val room = Roll.LENGTH - target.frames.size
        val moving = ids.distinct().filter { it !in target.frames }.take(room.coerceAtLeast(0))
        if (moving.isEmpty()) return@edit list to 0
        val rest = withoutFrames(list, moving.toSet())
        rest.map { if (it.id == to) it.copy(frames = it.frames + moving) else it } to moving.size
    }

    /** Takes [ids] out of their manual rolls, back into the automatic ones. */
    fun release(ids: Collection<String>): Int = edit { list ->
        val set = ids.toSet()
        val n = list.sumOf { r -> r.frames.count { it in set } }
        withoutFrames(list, set) to n
    }

    /** 拆卷: the roll is gone and its frames go back to the automatic rolls. */
    fun dissolve(id: String): Int = edit { list -> list.filter { it.id != id } to 1 }

    fun rename(id: String, name: String): Int = edit { list ->
        val n = name.trim().take(40)
        if (n.isEmpty()) return@edit list to 0
        list.map { if (it.id == id) it.copy(name = n) else it } to 1
    }

    // Empty rolls are dropped: a roll whose last frame moved away is no roll.
    private fun withoutFrames(list: List<ManualRoll>, ids: Set<String>) =
        list.map { r -> r.copy(frames = r.frames.filter { it !in ids }) }.filter { it.frames.isNotEmpty() }

    private fun edit(change: (List<ManualRoll>) -> Pair<List<ManualRoll>, Int>): Int = synchronized(lock) {
        load()
        val (next, n) = change(_rolls.value)
        if (next != _rolls.value) {
            _rolls.value = next
            write()
        }
        n
    }

    // A kill mid-write leaves the old file, never half of the new one.
    private fun write() {
        runCatching {
            file.parentFile?.mkdirs()
            val a = JSONArray().also { arr -> _rolls.value.forEach { arr.put(it.toJson()) } }
            val part = File(file.parentFile, file.name + ".part")
            part.writeText(a.toString())
            if (!part.renameTo(file)) { file.delete(); part.renameTo(file) }
        }
    }

    companion object {
        @Volatile private var instance: ManualRolls? = null
        fun of(context: Context): ManualRolls =
            instance ?: synchronized(this) { instance ?: ManualRolls(context.applicationContext).also { instance = it } }
    }
}
