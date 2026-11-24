package com.dearded.spektralab

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * One photo on the light table. The provider URI identifies an import;
 * only an explicit relink checks byte equality.
 */
@Immutable
data class Frame(
    val id: String,
    val uri: String,
    val name: String,
    val size: Long,
    /** Capture time the provider reports, else its modification time; ms, 0 = unknown. */
    val date: Long,
    val mime: String,
    val raw: Boolean,
    /** Upright pixel size (EXIF rotation applied); 0 when the provider does not say (most RAWs). */
    val width: Int = 0,
    val height: Int = 0,
    /** EXIF DateTimeOriginal, `yyyy:MM:dd HH:mm:ss`, for a pasted date back. */
    val exifDate: String = "",
    /** 1 picked, -1 rejected, 0 neither. */
    val pick: Int = 0,
    val stars: Int = 0,
    /** A sidecar with an edit exists. */
    val edited: Boolean = false,
    /** The sidecar's film id, for rolls ("" = not developed yet). */
    val film: String = "",
    val editedAt: Long = 0,
    val addedAt: Long = 0,
    /** A persistable read grant is held, so the frame still opens after a restart. */
    val persisted: Boolean = true,
    val sourceMember: String? = null,
    val sourceSha256: String? = null,
) {
    val dimsKnown get() = width > 0 && height > 0
    val landscape get() = !dimsKnown || width >= height

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("uri", uri).put("name", name).put("size", size).put("date", date)
        .put("mime", mime).put("raw", raw).put("w", width).put("h", height).put("exifDate", exifDate)
        .put("pick", pick).put("stars", stars).put("edited", edited).put("film", film)
        .put("editedAt", editedAt).put("addedAt", addedAt)
        .put("sourceMember", sourceMember).put("sourceSha256", sourceSha256)

    companion object {
        fun fromJson(o: JSONObject): Frame? = runCatching {
            Frame(
                id = o.getString("id"), uri = o.getString("uri"), name = o.optString("name"),
                size = o.optLong("size", -1), date = o.optLong("date"), mime = o.optString("mime"),
                raw = o.optBoolean("raw"), width = o.optInt("w"), height = o.optInt("h"),
                exifDate = o.optString("exifDate"), pick = o.optInt("pick").coerceIn(-1, 1),
                stars = o.optInt("stars").coerceIn(0, 5), edited = o.optBoolean("edited"),
                film = o.optString("film"), editedAt = o.optLong("editedAt"), addedAt = o.optLong("addedAt"),
                sourceMember = o.opt("sourceMember") as? String, sourceSha256 = o.opt("sourceSha256") as? String,
            )
        }.getOrNull()

        /** The URI stays private; its digest is only a sidecar filename. */
        internal fun idOf(uri: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
            return "uri-" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}

internal sealed interface SourceReauthorization {
    data class Restored(val frame: Frame) : SourceReauthorization
    data object DifferentDocument : SourceReauthorization
    data object Removed : SourceReauthorization
    data object Unavailable : SourceReauthorization
}

internal sealed interface SourceRelink {
    data class Linked(val frame: Frame) : SourceRelink
    data object UnverifiedOriginal : SourceRelink
    data object DifferentDocument : SourceRelink
    data object AlreadyAssociated : SourceRelink
    data object Removed : SourceRelink
    data object Unavailable : SourceRelink
}

/**
 * A frame's edit, as kept in its sidecar: the engine parameters by their wire
 * names (as upstream's desktop sidecars keep them), the app-side fields beside
 * them, and the RAW white balance chosen at decode.
 */
data class Sidecar(val edit: Edit, val wbK: Double = 0.0, val wbTint: Double = 0.0, val updated: Long = 0) {
    fun toJson(frame: Frame): JSONObject = JSONObject()
        .put("format", FORMAT).put("version", VERSION)
        .put("source", JSONObject().put("name", frame.name).put("size", frame.size).put("date", frame.date))
        .put("params", edit.toJson())
        .put("app", JSONObject()
            .put("cropAspect", edit.cropAspect)
            .put("frame", edit.frame.toJson())
            .put("borderId", edit.borderId)
            .put("look", edit.look.toJson())
            .put("sceneCurve", edit.sceneCurve.toJson())
            .put("edgeRealName", edit.edgeRealName)
            .put("dateOrder", edit.dateOrder)
            .put("dateWithData", edit.dateWithData))
        .put("rawWb", JSONObject().put("k", wbK).put("tint", wbTint))
        .put("rating", JSONObject().put("pick", frame.pick).put("stars", frame.stars))
        .put("updated", updated)

    companion object {
        const val FORMAT = "spektralab-x/sidecar"
        const val VERSION = 1

        /** Missing keys take a fresh [Edit]'s values, so later versions can add fields. */
        fun fromJson(o: JSONObject): Sidecar? = runCatching {
            if (o.optString("format") != FORMAT) return null
            val app = o.optJSONObject("app") ?: JSONObject()
            val fallback = Edit(
                cropAspect = app.optDouble("cropAspect", 0.0),
                frame = FrameSpec.fromJson(app.optJSONObject("frame")),
                borderId = app.optString("borderId", "none"),
                look = Look.fromJson(app.optJSONObject("look")),
                sceneCurve = SceneCurve.fromJson(app.opt("sceneCurve")),
                edgeRealName = app.optBoolean("edgeRealName", true),
                dateOrder = app.optString("dateOrder", "japan"),
                dateWithData = app.optBoolean("dateWithData", false),
            )
            val wb = o.optJSONObject("rawWb")
            Sidecar(
                edit = Edit.fromParams(o.optJSONObject("params") ?: JSONObject(), fallback),
                wbK = wb?.optDouble("k", 0.0) ?: 0.0,
                wbTint = wb?.optDouble("tint", 0.0) ?: 0.0,
                updated = o.optLong("updated"),
            )
        }.getOrNull()
    }
}

/**
 * The light table's frames: an index (`filesDir/Sidecars/index.json`) and one
 * sidecar per edited frame (`<id>.json`), all in app-private storage, never
 * next to the user's photos. Removing a frame keeps its sidecar, so importing
 * the same photo again brings its edit back. One store per process.
 *
 * Every function that touches files is IO-thread only.
 */
class FrameStore internal constructor(private val context: Context) {
    private val dir = File(context.filesDir, "Sidecars")
    private val indexFile = File(dir, "index.json")
    private val lock = SourceGrantLock.monitor
    private val _frames = MutableStateFlow<List<Frame>>(emptyList())
    val frames: StateFlow<List<Frame>> = _frames
    private var completeIndex = false
    @Volatile private var loaded = false

    fun load(): Unit = synchronized(lock) {
        if (loaded) return
        loaded = true
        val list = runCatching {
            val array = if (indexFile.exists()) JSONArray(indexFile.readText()) else JSONArray()
            val entries = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Frame::fromJson) }
            completeIndex = entries.size == array.length() && entries.distinctBy { it.id }.size == entries.size
            entries
        }.getOrDefault(emptyList())
        val held = heldGrants()
        _frames.value = list.map { it.copy(persisted = Uri.parse(it.uri).scheme == "file" || it.uri in held) }
    }

    fun byId(id: String) = _frames.value.firstOrNull { it.id == id }

    private fun currentMember(target: Frame): Frame? = byId(target.id)?.takeIf {
        it.uri == target.uri && it.sourceMember == target.sourceMember
    }

    /** A picker grant for the same document does not migrate the index or edit. */
    internal fun reauthorizeSource(target: Frame, picked: Uri): SourceReauthorization {
        if (picked.scheme != "content" || picked.toString() != target.uri) return SourceReauthorization.DifferentDocument
        val resolver = context.contentResolver
        return try {
            resolver.openInputStream(picked)?.close() ?: return SourceReauthorization.Unavailable
            synchronized(lock) {
                load()
                val current = currentMember(target) ?: return@synchronized SourceReauthorization.Removed
                resolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val restored = current.copy(persisted = true)
                _frames.value = _frames.value.map { if (it.id == restored.id) restored else it }
                SourceReauthorization.Restored(restored)
            }
        } catch (_: IOException) {
            SourceReauthorization.Unavailable
        } catch (_: SecurityException) {
            SourceReauthorization.Unavailable
        }
    }

    /** Explicit request while the original is readable. A known fingerprint is never replaced. */
    internal fun rememberSourceIdentity(target: Frame): Boolean {
        val current = synchronized(lock) {
            load()
            if (!completeIndex) return false
            currentMember(target)
        } ?: return false
        if (current.sourceSha256 != null) return true
        val digest = sourceDigest(Uri.parse(current.uri)) ?: return false
        return synchronized(lock) {
            val latest = currentMember(target) ?: return@synchronized false
            if (latest.sourceSha256 != null) return@synchronized latest.sourceSha256 == digest
            commitIndex(_frames.value.map { if (it.id == latest.id) it.copy(sourceSha256 = digest) else it })
            true
        }
    }

    internal fun relinkSource(target: Frame, picked: Uri, inUse: () -> Set<String>): SourceRelink {
        val replacement = picked.toString()
        if (picked.scheme != "content" || replacement == target.uri) return SourceRelink.DifferentDocument
        val current = synchronized(lock) {
            load()
            if (!completeIndex) return SourceRelink.Unavailable
            currentMember(target)
        } ?: return SourceRelink.Removed
        val expected = current.sourceSha256 ?: return SourceRelink.UnverifiedOriginal
        val actual = sourceDigest(picked) ?: return SourceRelink.Unavailable
        if (actual != expected) return SourceRelink.DifferentDocument
        return synchronized(lock) {
            val latest = currentMember(target) ?: return@synchronized SourceRelink.Removed
            if (_frames.value.any { it.uri == replacement } || File(dir, "${Frame.idOf(replacement)}.json").exists())
                return@synchronized SourceRelink.AlreadyAssociated

            // The new slot is not visible to imports unless the index publishes it.
            val moved = latest.copy(id = "relink-${UUID.randomUUID()}", uri = replacement, persisted = true)
            val oldEdit = File(dir, "${latest.id}.json")
            val newEdit = File(dir, "${moved.id}.json")
            val grantWasHeld = replacement in heldGrants()
            var acquired = false
            var published = false
            try {
                context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                acquired = true
                if (oldEdit.isFile) writeAtomicText(newEdit, oldEdit.readText())
                commitIndex(_frames.value.map { if (it.id == latest.id) moved else it })
                published = true
            } catch (_: IOException) {
                return@synchronized SourceRelink.Unavailable
            } catch (_: SecurityException) {
                return@synchronized SourceRelink.Unavailable
            } finally {
                if (!published) {
                    newEdit.delete()
                    if (acquired && !grantWasHeld) releaseReadGrant(replacement)
                }
            }
            releaseUnusedGrants(listOf(latest), inUse)
            SourceRelink.Linked(moved)
        }
    }

    private fun sourceDigest(uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** True while a frame on the table reads [uri], so its grant must not be released by anyone else. */
    fun holds(uri: String): Boolean {
        load()
        return _frames.value.any { it.uri == uri }
    }

    /** Records [id]'s upright shape once the editor has opened it (most RAWs report none before). */
    fun setSize(id: String, width: Int, height: Int): Unit = synchronized(lock) {
        val f = byId(id) ?: return
        if (width <= 0 || height <= 0 || (f.width == width && f.height == height)) return
        commitIndex(_frames.value.map { if (it.id == id) it.copy(width = width, height = height) else it })
    }

    /**
     * Adds [uris] (the photo picker's), newest capture first. An existing URI
     * keeps its index entry and edit. Returns how many were new.
     */
    fun import(uris: List<Uri>, progress: (Int) -> Unit = {}): Int {
        load()
        val loader = SourceLoader(context)
        var added = 0
        val now = System.currentTimeMillis()
        val incoming = ArrayList<Frame>()
        uris.forEachIndexed { i, uri ->
            describe(uri, loader)?.let { incoming += it.copy(addedAt = now + i) }
            progress(i + 1)
        }
        synchronized(lock) {
            val byId = _frames.value.associateByTo(LinkedHashMap()) { it.id }
            val idByUri = HashMap<String, String>()
            byId.values.forEach { idByUri.putIfAbsent(it.uri, it.id) }
            for (described in incoming) {
                val persisted = described.persisted || runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        Uri.parse(described.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.isSuccess
                val f = described.copy(persisted = persisted)
                val old = idByUri[f.uri]?.let(byId::get)
                if (old == null) {
                    // A sidecar left by an earlier removal still belongs to this photo.
                    val car = readSidecar(f.id)
                    val member = f.copy(sourceMember = UUID.randomUUID().toString())
                    byId[f.id] = if (car != null) member.copy(edited = true, film = car.edit.film, editedAt = car.updated) else member
                    idByUri[f.uri] = f.id
                    added++
                } else {
                    byId[old.id] = old.copy(persisted = f.persisted)
                }
            }
            commitIndex(byId.values.sortedByDescending { it.date })
        }
        return added
    }

    private fun describe(uri: Uri, loader: SourceLoader): Frame? {
        val r = context.contentResolver
        var name = ""
        var size = -1L
        var date = 0L
        var w = 0
        var h = 0
        var rotation = 0
        runCatching {
            r.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use
                fun col(n: String) = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }
                col(OpenableColumns.DISPLAY_NAME)?.let { name = c.getString(it) }
                col(OpenableColumns.SIZE)?.let { size = c.getLong(it) }
                // The photo picker says date_taken_millis; MediaStore datetaken (ms), date_modified (s).
                date = col("date_taken_millis")?.let { c.getLong(it) }
                    ?: col("datetaken")?.let { c.getLong(it) }
                    ?: col("date_modified")?.let { c.getLong(it) * 1000 } ?: 0L
                col("width")?.let { w = c.getInt(it) }
                col("height")?.let { h = c.getInt(it) }
                col("orientation")?.let { rotation = c.getInt(it) }
            }
        }
        if (name.isEmpty()) name = loader.displayName(uri)
        val mime = r.getType(uri).orEmpty()
        val raw = loader.isRaw(name, mime, uri)
        // Without the provider's size, the header (a few KB read) gives it for anything BitmapFactory knows.
        if ((w <= 0 || h <= 0) && !raw) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                w = o.outWidth; h = o.outHeight
                rotation = r.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            }
        }
        if (rotation % 180 != 0) { val t = w; w = h; h = t }
        val exif = ExifCopy.read(context, uri)
        return Frame(
            id = Frame.idOf(uri.toString()), uri = uri.toString(), name = name, size = size, date = date,
            mime = mime, raw = raw, width = w.coerceAtLeast(0), height = h.coerceAtLeast(0),
            exifDate = exif.dateTimeOriginal.orEmpty(), persisted = uri.scheme == "file",
        )
    }

    fun sidecar(id: String): Sidecar? = readSidecar(id)

    private fun readSidecar(id: String): Sidecar? =
        runCatching { Sidecar.fromJson(JSONObject(File(dir, "$id.json").readText())) }.getOrNull()

    /** Writes [id]'s sidecar and marks the frame edited. */
    fun saveEdit(id: String, edit: Edit, wbK: Double, wbTint: Double): Unit = synchronized(lock) {
        val frame = byId(id) ?: return
        val now = System.currentTimeMillis()
        val updated = frame.copy(edited = true, film = if (edit.noFilm) "" else edit.film, editedAt = now)
        writeAtomicText(File(dir, "$id.json"), Sidecar(edit, wbK, wbTint, now).toJson(updated).toString())
        commitIndex(_frames.value.map { if (it.id == id) updated else it })
    }

    /** Picks, rejects or stars [ids]; null leaves that field alone. */
    fun rate(ids: Set<String>, pick: Int? = null, stars: Int? = null): Unit = synchronized(lock) {
        commitIndex(_frames.value.map { f ->
            if (f.id !in ids) f else f.copy(pick = pick ?: f.pick, stars = (stars ?: f.stars).coerceIn(0, 5))
        })
    }

    /**
     * Takes [ids] off the table. Their sidecars stay (a re-import finds them);
     * a grant goes back unless another frame, the editor or an unfinished export uses it.
     * [inUse] is read under the grant lock, together with queue owner publication.
     */
    fun remove(ids: Set<String>, inUse: () -> Set<String>): Unit = synchronized(lock) {
        load()
        val gone = _frames.value.filter { it.id in ids }
        val uriCounts = _frames.value.groupingBy { it.uri }.eachCount()
        for (frame in gone) {
            val sourceId = Frame.idOf(frame.uri)
            val legacy = File(dir, "${frame.id}.json")
            if (sourceId != frame.id && uriCounts[frame.uri] == 1 && legacy.isFile) {
                // An indexed row proves which source owns the old edit.
                writeAtomicText(File(dir, "$sourceId.json"), legacy.readText())
            }
        }
        commitIndex(_frames.value.filter { it.id !in ids })
        releaseUnusedGrants(gone, inUse)
    }

    private fun releaseUnusedGrants(gone: List<Frame>, inUse: () -> Set<String>) {
        val editorUri = context.getSharedPreferences("settings", 0).getString("editor_grant", null)
        val still = _frames.value.map { it.uri }.toSet() + inUse() + listOfNotNull(editorUri)
        for (frame in gone) if (frame.uri !in still) releaseReadGrant(frame.uri)
    }

    private fun releaseReadGrant(uri: String) {
        val source = Uri.parse(uri)
        if (source.scheme == "content") runCatching {
            context.contentResolver.releasePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun heldGrants(): Set<String> = runCatching {
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
    }.getOrDefault(emptySet())

    private fun commitIndex(frames: List<Frame>) {
        val array = JSONArray()
        frames.forEach { array.put(it.toJson()) }
        writeAtomicText(indexFile, array.toString())
        _frames.value = frames
    }

    companion object {
        @Volatile private var instance: FrameStore? = null
        fun of(context: Context): FrameStore =
            instance ?: synchronized(this) { instance ?: FrameStore(context.applicationContext).also { instance = it } }
    }
}

/** What 复制调整 / 粘贴 can carry, in the order of the darkroom chain. */
enum class LookGroup(private val message: String) {
    FILM_PAPER("胶片与相纸"),
    EXPOSURE("曝光"),
    WHITE_BALANCE("白平衡（RAW）"),
    EFFECTS("胶片效果"),
    LAYER2("调整层"),
    BORDER("边框");

    val label get() = tr(message)
}

/** A copied edit and the RAW white balance it was decoded with. */
data class CopiedLook(val edit: Edit, val wbK: Double, val wbTint: Double, val fromName: String)

/**
 * [target] with [groups] of [from] pasted onto it. Settings are pasted, not
 * results: auto exposure meters each photo again, and a date back takes the
 * target's own EXIF date. A film edge needs the target's shape for its gate
 * crop; when the size is unknown (most RAWs before their first open) the edge
 * is left off and [Pasted.edgeSkipped] says so.
 */
data class Pasted(val edit: Edit, val wbK: Double, val wbTint: Double, val edgeSkipped: Boolean)

fun pasteLook(target: Sidecar, frame: Frame, from: CopiedLook, groups: Set<LookGroup>, catalog: Catalog): Pasted {
    val s = from.edit
    var e = target.edit
    var wbK = target.wbK
    var wbTint = target.wbTint
    var skipped = false
    if (LookGroup.FILM_PAPER in groups) e = e.copy(
        film = s.film, paper = s.paper, scanFilm = s.scanFilm, filmFormatMm = s.filmFormatMm,
        noFilm = s.noFilm, digitalIntermediate = s.digitalIntermediate,
        printExposure = s.printExposure, yShift = s.yShift, mShift = s.mShift,
    )
    if (LookGroup.EXPOSURE in groups) e = e.copy(autoExposure = s.autoExposure, aeMethod = s.aeMethod, exposureEv = s.exposureEv, sceneCurve = s.sceneCurve).let {
        if (frame.raw) it.copy(rawDevelopment = s.rawDevelopment, rawAutoExposure = s.rawAutoExposure,
            rawExposureEv = s.rawExposureEv) else it
    }
    if (LookGroup.WHITE_BALANCE in groups && frame.raw) { wbK = from.wbK; wbTint = from.wbTint }
    if (LookGroup.EFFECTS in groups) e = e.copy(
        grainActive = s.grainActive, grainAmount = s.grainAmount,
        halationActive = s.halationActive, halationAmount = s.halationAmount,
        glareActive = s.glareActive, glareAmount = s.glareAmount,
        scatterAmount = s.scatterAmount, couplersAmount = s.couplersAmount,
        grainLayered = s.grainLayered, grainArea = s.grainArea,
        unsharpSigma = s.unsharpSigma, unsharpAmount = s.unsharpAmount,
    )
    if (LookGroup.LAYER2 in groups) e = e.copy(look = s.look)
    if (LookGroup.BORDER in groups) {
        val preset = Borders.byId(s.borderId)
        val needsShape = s.filmEdge || (preset?.crop ?: 0.0) > 0
        e = when {
            frame.dimsKnown && preset != null -> Edit.withBorder(e, preset, frame.width, frame.height)
            frame.dimsKnown -> Edit.withFilmEdge(e.copy(borderId = s.borderId), frame.width, frame.height, s.filmEdge, s.edgeFormat)
            // Unknown shape: only what needs no crop goes over.
            needsShape -> { skipped = true; e.copy(filmEdge = false, borderId = "none") }
            else -> e.copy(filmEdge = false, borderId = s.borderId)
        }
        // The date in the copied order, from this frame's own EXIF. The frame number
        // and the shooting-data line belong to the frame, so they stay its own.
        val date = Edit.dateBackText(frame.exifDate, s.dateOrder)
        e = e.copy(
            frame = s.frame,
            edgeMode = s.edgeMode, edgeHoles = s.edgeHoles, edgeGate = s.edgeGate, edgeCarrier = s.edgeCarrier,
            edgeFog = s.edgeFog, edgeLeaks = s.edgeLeaks, edgeRealName = s.edgeRealName,
            dateImprint = s.dateImprint && date.isNotEmpty() && Edit.dateBackFits(e.edgeFormat),
            dateText = date, dateStyle = s.dateStyle, dateCorner = s.dateCorner,
            datePlacement = s.datePlacement, dateSize = s.dateSize, dateEv = s.dateEv,
            dateInsetX = s.dateInsetX, dateInsetY = s.dateInsetY,
            dateOrder = s.dateOrder, dateWithData = s.dateWithData,
        )
    }
    return Pasted(catalog.normalize(e), wbK, wbTint, skipped)
}
