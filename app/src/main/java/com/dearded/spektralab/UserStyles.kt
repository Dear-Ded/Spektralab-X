package com.dearded.spektralab

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * An imported style: the .spklook it came as, plus the id of its colour table
 * in [LutStore] when it has one. Applied through [Style.user] (Styles.kt), so
 * the style row and EditorViewModel.applyStyle need nothing of their own.
 */
data class UserStyle(val id: String, val look: SpkLook, val lutId: String = "", val added: Long = 0) {
    val name get() = look.name
    val category get() = look.category.ifBlank { SpkLook.DEFAULT_CATEGORY }

    /** The finish at [strength] (0..1): Layer 2 and the table both scale, film and print do not. */
    fun finish(strength: Double): Look {
        val full = look.layer2.copy(lut = lutId, lutStrength = if (lutId.isEmpty()) 1.0 else look.lut?.strength ?: 1.0)
        val k = strength.coerceIn(0.0, 1.0)
        return if (k >= 1.0) full else full.finishAt(k)
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("lut", lutId).put("added", added).put("look", look.toJson())

    companion object {
        val ID = Regex("us_[a-z0-9_]{1,48}")

        fun fromJson(o: JSONObject): UserStyle? {
            val id = o.optString("id").takeIf(ID::matches) ?: return null
            val look = o.optJSONObject("look") ?: return null
            val lut = o.optString("lut").takeIf(LutStore.ID::matches).orEmpty()
            return runCatching {
                UserStyle(id, SpkLook.fromJson(look, ImportReport(), tr("风格")), lut, o.optLong("added"))
            }.getOrNull()
        }
    }
}

/** Imported styles in filesDir/user/styles: `<id>.json` and an optional `<id>.jpg` thumbnail. Plain Kotlin. */
class UserStyles(private val dir: File) {
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<UserStyle>> = _all

    fun newId(): String = "us_" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "_" + (1000..9999).random()

    fun save(style: UserStyle, thumbJpeg: ByteArray? = null) {
        require(UserStyle.ID.matches(style.id)) { "bad style id ${style.id}" }
        dir.mkdirs()
        thumbJpeg?.let { writeAtomicBytes(File(dir, "${style.id}.jpg"), it) }
        writeAtomicText(File(dir, "${style.id}.json"), style.toJson().toString())
        _all.value = load()
    }

    /** Removes [id]; returns it so the caller can drop its table too. */
    fun delete(id: String): UserStyle? {
        if (!UserStyle.ID.matches(id)) return null
        val gone = _all.value.firstOrNull { it.id == id }
        java.nio.file.Files.deleteIfExists(File(dir, "$id.json").toPath())
        File(dir, "$id.jpg").delete()
        _all.value = load()
        return gone
    }

    fun thumb(id: String): File? = if (UserStyle.ID.matches(id)) File(dir, "$id.jpg").takeIf { it.isFile } else null

    /** Whether any style still uses [lutId]. */
    fun usesLut(lutId: String) = _all.value.any { it.lutId == lutId }

    private fun load(): List<UserStyle> =
        dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
            .filter { it.length() <= MAX_FILE }
            .mapNotNull { f -> runCatching { UserStyle.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedBy { it.added }

    private companion object { const val MAX_FILE = SpkPackage.MAX_LOOK }
}
