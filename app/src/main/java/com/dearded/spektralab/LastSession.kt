package com.dearded.spektralab

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.File

/*
 * The editor as it was left (docs/USER_VOICES.md: 进设置或切后台后编辑丢失,
 * 记住上次的胶片): the open photo, its whole edit and RAW white balance, in
 * filesDir/last_session.json. Written a moment after each change, read once
 * when the engine is ready.
 *
 * The photo comes back only while the app still holds a read grant for it,
 * taken when it was opened (the photo picker and the document picker both
 * offer one). Without it -- a file manager's one-off URI, a photo deleted
 * since -- the film, paper and look come back on their own, which is what
 * 记住上次的胶片 asks for.
 */
class LastSession(private val context: Context) {
    data class Saved(val uri: Uri?, val edit: Edit, val wbK: Double, val wbTint: Double)

    private val file = File(context.filesDir, "last_session.json")
    private val prefs = context.getSharedPreferences("settings", 0)

    fun save(uri: Uri?, edit: Edit, wbK: Double, wbTint: Double) {
        val o = JSONObject()
            .put("format", FORMAT)
            .put("uri", uri?.toString() ?: "")
            .put("params", edit.toJson())
            .put("app", JSONObject()
                .put("cropAspect", edit.cropAspect)
                .put("frame", edit.frame.toJson())
                .put("borderId", edit.borderId)
                .put("look", edit.look.toJson()))
            .put("rawWb", JSONObject().put("k", wbK).put("tint", wbTint))
        runCatching {
            val part = File(file.parentFile, file.name + ".part")
            part.writeText(o.toString())
            if (!part.renameTo(file)) { file.delete(); part.renameTo(file) }
        }
    }

    fun load(): Saved? = runCatching {
        if (!file.isFile) return null
        val o = JSONObject(file.readText())
        if (o.optString("format") != FORMAT) return null
        val app = o.optJSONObject("app") ?: JSONObject()
        val fallback = Edit(
            cropAspect = app.optDouble("cropAspect", 0.0),
            frame = FrameSpec.fromJson(app.optJSONObject("frame")),
            borderId = app.optString("borderId", "none"),
            look = Look.fromJson(app.optJSONObject("look")),
        )
        val wb = o.optJSONObject("rawWb")
        Saved(
            uri = o.optString("uri").takeIf { it.isNotEmpty() }?.let(Uri::parse),
            edit = Edit.fromParams(o.optJSONObject("params") ?: JSONObject(), fallback),
            wbK = wb?.optDouble("k", 0.0) ?: 0.0,
            wbTint = wb?.optDouble("tint", 0.0) ?: 0.0,
        )
    }.getOrNull()

    /** Whether [uri] can still be opened: the grant is there and the file still exists. */
    fun readable(uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    /**
     * Keeps a read grant for the photo just opened and gives the previous
     * one back, unless the light table or an unfinished export still reads
     * it. Android caps persisted grants per app, so only one is held here.
     */
    fun hold(uri: Uri) {
        if (uri.scheme == "file") return
        val r = context.contentResolver
        val previous = prefs.getString(KEY_GRANT, null)
        if (previous == uri.toString()) return
        val taken = runCatching { r.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        prefs.edit().putString(KEY_GRANT, if (taken) uri.toString() else null).apply()
        if (previous != null && !inUse(previous)) {
            runCatching { r.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun inUse(uri: String): Boolean =
        FrameStore.of(context).holds(uri) ||
            context.getSharedPreferences("processing", 0).getStringSet("grants", emptySet()).orEmpty().contains(uri)

    private companion object {
        const val FORMAT = "spektralab-x/last-session"
        const val KEY_GRANT = "last_session_grant"
    }
}
