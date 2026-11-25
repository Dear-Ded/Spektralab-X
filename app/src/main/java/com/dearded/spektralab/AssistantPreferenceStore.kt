package com.dearded.spektralab

import android.content.Context
import android.util.AtomicFile
import com.dearded.spektralab.advisor.domain.AdviceCandidate
import com.dearded.spektralab.advisor.domain.AdviceOrigin
import com.dearded.spektralab.advisor.domain.AdviceParameter
import com.dearded.spektralab.advisor.domain.EditSnapshot
import com.dearded.spektralab.advisor.domain.effectiveValueChanged
import com.dearded.spektralab.advisor.editor.AppliedReceipt
import com.dearded.spektralab.advisor.editor.SaveReceipt
import com.dearded.spektralab.advisor.editor.SaveTarget
import java.io.DataInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal sealed interface PendingAssistantAdoption

/** One instance per app runtime. The editor owns the photo and its save outcome. */
internal class AssistantPreferenceStore(context: Context, scope: CoroutineScope) : AssistantPreferenceControls {
    private val gate = Any()
    private var fence = Any()
    private var captureAllowed = false
    private var controlsPending = 0
    private var privacyPending = 0
    private var tokens = Tokens()
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val state = MutableStateFlow(AssistantPreferences(busy = true))
    override val preferences = state.asStateFlow()

    init {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            drain { AtomicFile(File(app.noBackupFilesDir, FILE_NAME)) }
        }.invokeOnCompletion { commands.cancel() }
    }

    override fun learn(on: Boolean) = change(Control.Learn(on))
    override fun useRules(on: Boolean) = change(Control.Rules(on))
    override fun clearPreferences() = change(Control.Clear)

    internal fun capture(
        entry: EditSnapshot, candidate: AdviceCandidate, finalEdit: Edit, commit: AppliedReceipt, target: SaveTarget,
    ): PendingAssistantAdoption? {
        val (captureFence, captureTokens) = synchronized(gate) {
            if (!captureAllowed || privacyPending != 0) return null
            fence to tokens
        }
        if (candidate.origin !is AdviceOrigin.Model || candidate.manuallyAdjusted || candidate.directionalLight != null ||
            candidate.changes.isEmpty() || candidate.edit != finalEdit || commit.commitId != candidate.id ||
            commit.photoSessionId != entry.ticket.photoSessionId || commit.photoSessionId != candidate.ticket.photoSessionId ||
            candidate.ticket.formalRevision != entry.ticket.formalRevision || commit.revision != entry.ticket.formalRevision + 1 ||
            entry.ticket.parameterVersion != AdviceParameter.VERSION || candidate.ticket.parameterVersion != AdviceParameter.VERSION
        ) return null

        val parameters = AdviceParameter.entries
        if (candidate.changes.size > parameters.size || candidate.changes.any { AdviceParameter.of(it.parameter) == null }) return null
        val restored = parameters.fold(finalEdit.look) { look, p -> p.write(look, p.read(entry.edit.look)) }
        if (finalEdit.copy(look = restored) != entry.edit) return null

        val before = Scalars.from(entry.edit)
        val adopted = Scalars.from(finalEdit)
        if (!before.finite || !adopted.finite || parameters.indices.none {
            effectiveValueChanged(before.effective[it], adopted.effective[it])
        }) return null
        return FrozenAdoption(captureFence, captureTokens, Evidence(commit, target, before, adopted, adopted))
    }

    internal fun record(pending: PendingAssistantAdoption, receipt: SaveReceipt) {
        val frozen = pending as? FrozenAdoption ?: return
        if (!receipt.success || receipt.commit != frozen.evidence.commit || receipt.target != frozen.evidence.target) return
        synchronized(gate) {
            if (!current(frozen) || frozen.submitted) return
            if (commands.trySend(Command.Append(frozen)).isSuccess) frozen.submitted = true
        }
    }

    private fun current(frozen: FrozenAdoption) =
        captureAllowed && privacyPending == 0 && frozen.fence === fence && frozen.tokens == tokens

    private fun change(control: Control) {
        synchronized(gate) {
            if (controlsPending == 0 && !state.value.busy && state.value.error == null &&
                (control is Control.Learn && control.on == state.value.learn ||
                    control is Control.Rules && control.on == state.value.useRules)
            ) return
            if (commands.trySend(control).isFailure) return
            controlsPending++
            if (control !is Control.Rules) {
                // Invalidate captures now, even if the writer has not reached this command yet.
                privacyPending++
                fence = Any()
            }
            state.value = state.value.copy(busy = true)
        }
    }

    private suspend fun drain(open: () -> AtomicFile) {
        val file by lazy(open)
        var persisted = storage { read(file) }
        var privacyWriteFailed = false
        var error: AssistantError? = if (persisted == null) AssistantError.Preferences else null

        fun publish() {
            val saved = persisted
            tokens = saved?.tokens ?: Tokens()
            captureAllowed = saved?.learn == true && !privacyWriteFailed
            state.value = AssistantPreferences(saved?.learn == true, saved?.useRules == true, controlsPending != 0, error)
        }

        synchronized(gate) { publish() }
        for (command in commands) {
            val before = persisted
            val privacyChange = command is Control.Learn || command == Control.Clear
            val next = when (command) {
                is Control.Learn -> before?.copy(learn = command.on,
                    tokens = before.tokens.copy(generation = before.tokens.generation + 1))
                is Control.Rules -> before?.copy(useRules = command.on)
                Control.Clear -> (before ?: StoreData()).let {
                    it.copy(tokens = it.tokens.copy(epoch = it.tokens.epoch + 1), evidence = emptyList())
                }
                is Command.Append -> {
                    val frozen = command.pending
                    val allowed = synchronized(gate) { current(frozen) }
                    if (!allowed || before == null || before.evidence.any {
                        it.commit.commitId == frozen.evidence.commit.commitId
                    }) continue
                    before.copy(evidence = (before.evidence + frozen.evidence).takeLast(MAX_RECORDS))
                }
            }
            if (next != null) {
                if (next == persisted || storage { write(file, next); true } == true) {
                    persisted = next
                    if (privacyChange) privacyWriteFailed = false
                    error = AssistantError.Save.takeIf { privacyWriteFailed }
                } else {
                    // A failed pause or clear stops new collection, and only an explicit privacy command can resume it.
                    if (privacyChange) privacyWriteFailed = true
                    error = AssistantError.Save
                }
            }
            synchronized(gate) {
                if (command is Control) {
                    controlsPending--
                    if (privacyChange) privacyPending--
                }
                publish()
            }
        }
    }

    private sealed interface Command {
        data class Append(val pending: FrozenAdoption) : Command
    }

    private sealed interface Control : Command {
        data class Learn(val on: Boolean) : Control
        data class Rules(val on: Boolean) : Control
        data object Clear : Control
    }

    private data class Tokens(val generation: Long = 0, val epoch: Long = 0)

    private class FrozenAdoption(val fence: Any, val tokens: Tokens, val evidence: Evidence) : PendingAssistantAdoption {
        var submitted = false // Guarded by the issuing store's gate.
    }

    private data class Scalars(val stored: List<Double>, val effective: List<Double>) {
        val finite get() = stored.all(Double::isFinite) && effective.all(Double::isFinite)

        fun encode() = JSONObject().put("stored", encodeValues(stored)).put("effective", encodeValues(effective))

        companion object {
            fun from(edit: Edit): Scalars {
                val resolved = edit.look.resolved()
                return Scalars(AdviceParameter.entries.map { it.read(edit.look) }, AdviceParameter.entries.map { it.read(resolved) })
            }

            fun decode(json: JSONObject): Scalars {
                require(json.length() == 2)
                return Scalars(decodeValues(json.getJSONObject("stored")),
                    decodeValues(json.getJSONObject("effective"))).also { require(it.finite) }
            }

            private fun encodeValues(values: List<Double>) = JSONObject().apply {
                AdviceParameter.entries.forEachIndexed { index, parameter -> put(parameter.id, values[index]) }
            }

            private fun decodeValues(json: JSONObject): List<Double> {
                require(json.length() == AdviceParameter.entries.size)
                return AdviceParameter.entries.map { (json.get(it.id) as? Number ?: errorValue()).toDouble() }
            }
        }
    }

    private data class Evidence(
        val commit: AppliedReceipt, val target: SaveTarget, val entry: Scalars, val offered: Scalars, val final: Scalars,
    ) {
        fun encode() = JSONObject().put("origin", "model").put("registryVersion", AdviceParameter.VERSION)
            .put("commitId", commit.commitId).put("photoSessionId", commit.photoSessionId).put("revision", commit.revision)
            .put("target", target.name).put("entry", entry.encode()).put("offered", offered.encode()).put("final", final.encode())

        companion object {
            fun decode(json: JSONObject): Evidence {
                require(json.length() == 9 && json.get("origin") == "model" && json.get("registryVersion") == AdviceParameter.VERSION)
                val id = json.getString("commitId")
                require(UUID.fromString(id).toString() == id)
                val commit = AppliedReceipt(id, integer(json, "photoSessionId"), integer(json, "revision"))
                return Evidence(commit, SaveTarget.valueOf(json.getString("target")), Scalars.decode(json.getJSONObject("entry")),
                    Scalars.decode(json.getJSONObject("offered")), Scalars.decode(json.getJSONObject("final")))
            }
        }
    }

    private data class StoreData(
        val learn: Boolean = false, val useRules: Boolean = false, val tokens: Tokens = Tokens(),
        val evidence: List<Evidence> = emptyList(),
    )

    private companion object {
        const val FILE_NAME = "assistant-preferences.json"
        const val VERSION = 1
        const val MAX_RECORDS = 64
        const val MAX_BYTES = 256 * 1024

        fun errorValue(): Nothing = throw IllegalArgumentException("Invalid preference value")

        fun integer(json: JSONObject, key: String): Long = when (val value = json.get(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> errorValue()
        }

        inline fun <T> storage(block: () -> T): T? = try {
            block()
        } catch (_: IOException) { null }
        catch (_: SecurityException) { null }
        catch (_: JSONException) { null }
        catch (_: IllegalArgumentException) { null }

        fun bytes(file: AtomicFile) = file.openRead().use { stream ->
            val size = stream.channel.size()
            require(size in 1..MAX_BYTES.toLong())
            val input = DataInputStream(stream)
            ByteArray(size.toInt()).also { input.readFully(it); require(input.read() == -1) }
        }

        fun read(file: AtomicFile): StoreData {
            val raw = try { bytes(file) } catch (e: FileNotFoundException) {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
                return StoreData()
            }
            val input = JSONTokener(raw.toString(Charsets.UTF_8))
            val json = JSONObject(input)
            require(input.nextClean() == '\u0000' && json.length() == 6 && json.get("version") == VERSION)
            val tokens = Tokens(integer(json, "generation"), integer(json, "clearEpoch"))
            require(tokens.generation >= 0 && tokens.epoch >= 0)
            val records = json.getJSONArray("adoptions")
            require(records.length() <= MAX_RECORDS)
            return StoreData(json.get("learn") as? Boolean ?: errorValue(), json.get("useRules") as? Boolean ?: errorValue(), tokens,
                List(records.length()) { Evidence.decode(records.getJSONObject(it)) })
        }

        fun write(file: AtomicFile, data: StoreData) {
            val payload = JSONObject().put("version", VERSION).put("learn", data.learn).put("useRules", data.useRules)
                .put("generation", data.tokens.generation).put("clearEpoch", data.tokens.epoch)
                .put("adoptions", JSONArray(data.evidence.map(Evidence::encode))).toString().toByteArray(Charsets.UTF_8)
            require(payload.size <= MAX_BYTES)
            val stream = file.startWrite()
            try {
                stream.write(payload)
                stream.fd.sync()
            } catch (e: IOException) {
                file.failWrite(stream)
                throw e
            }
            file.finishWrite(stream)
            // AtomicFile logs (rather than throws) rename failures.
            if (!bytes(file).contentEquals(payload)) throw IOException("Preference write did not commit")
        }
    }
}
