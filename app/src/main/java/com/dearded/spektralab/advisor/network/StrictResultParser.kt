package com.dearded.spektralab.advisor.network

import com.dearded.spektralab.advisor.domain.*
import com.squareup.moshi.JsonReader
import okio.Buffer
import java.io.IOException

/** A strict streaming reader sees duplicate keys before any object becomes a map. */
object StrictResultParser {
    const val MAX_BYTES = 16 * 1024
    const val MAX_DEPTH = 16

    fun parse(text: String, expectedVersion: Int? = null): AdviceResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw AdviceFailure(AdviceError.OUTPUT_TOO_LARGE)
        val value = try {
            JsonReader.of(Buffer().write(bytes)).use { reader ->
                val parsed = read(reader, 0)
                if (reader.peek() != JsonReader.Token.END_DOCUMENT) throw AdviceFailure(AdviceError.INVALID_JSON)
                parsed
            }
        } catch (e: AdviceFailure) { throw e
        } catch (_: IOException) { throw AdviceFailure(AdviceError.INVALID_JSON)
        } catch (_: com.squareup.moshi.JsonDataException) { throw AdviceFailure(AdviceError.INVALID_JSON) }
        val version = (value as? Map<*, *>)?.get("schema_version")
        if (expectedVersion != null && version != expectedVersion.toDouble()) badShape()
        if (version == 3.0) return direction(value)
        val root = obj(value, setOf("schema_version", "status", "changes", "question"))
        if (root["schema_version"] != 2.0) badShape()
        val status = string(root["status"])
        val changes = list(root["changes"]).map { item ->
            val change = obj(item, setOf("parameter", "target"))
            val id = string(change["parameter"])
            if (AdviceParameter.of(id) == null) throw AdviceFailure(AdviceError.UNKNOWN_PARAMETER)
            ParameterChange(id, change["target"] as? Double ?: badShape())
        }
        val question = obj(root["question"], setOf("kind", "prompt", "options"))
        val kind = string(question["kind"])
        val prompt = string(question["prompt"])
        val options = list(question["options"]).map { item ->
            val option = obj(item, setOf("id", "label"))
            AdviceResult.Option(string(option["id"]), string(option["label"]))
        }
        if (kind !in setOf("none", "choice", "text")) badShape()
        if (status !in setOf("proposal", "needs_input", "no_change", "unsupported")) badShape()
        if (status == "needs_input") {
            if (changes.isNotEmpty() || kind == "none" || !validText(prompt, 160)) badStatus()
            if (kind == "text" && options.isNotEmpty()) badStatus()
            if (kind == "choice" && (options.size !in 2..3 || options.map { it.id }.toSet().size != options.size ||
                options.any { !it.id.matches(Regex("[A-Za-z0-9_]{1,24}")) || !validText(it.label, 40) })) badStatus()
            return AdviceResult.Question(kind, prompt, options)
        }
        if (kind != "none" || prompt.isNotEmpty() || options.isNotEmpty()) badStatus()
        if (status == "proposal") {
            if (changes.size !in 1..8) badStatus()
            if (changes.map { it.parameter }.toSet().size != changes.size)
                throw AdviceFailure(AdviceError.DUPLICATE_PARAMETER)
            for (change in changes) {
                val parameter = checkNotNull(AdviceParameter.of(change.parameter))
                if (change.target < parameter.minimum || change.target > parameter.maximum)
                    throw AdviceFailure(AdviceError.OUT_OF_RANGE)
            }
            return AdviceResult.Proposal(changes)
        }
        if (changes.isNotEmpty()) badStatus()
        return if (status == "no_change") AdviceResult.NoChange else AdviceResult.Unsupported
    }

    private fun direction(value: Any?): AdviceResult {
        val root = obj(value, setOf("schema_version", "status", "directional_light"))
        val light = obj(root["directional_light"], setOf("bright_side", "bright_ev", "dark_ev"))
        val side = string(light["bright_side"])
        val bright = light["bright_ev"] as? Double ?: badShape()
        val dark = light["dark_ev"] as? Double ?: badShape()
        return when (string(root["status"])) {
            "proposal" -> {
                val axis = BrightSide.entries.firstOrNull { it.wire == side } ?: badStatus()
                AdviceResult.Proposal(emptyList(), DirectionalLight(axis, bright, dark))
            }
            "no_change", "unsupported" -> {
                if (side != "none" || bright != 0.0 || dark != 0.0) badStatus()
                if (root["status"] == "no_change") AdviceResult.NoChange else AdviceResult.Unsupported
            }
            else -> badShape()
        }
    }

    private fun read(reader: JsonReader, depth: Int): Any? {
        if (depth > MAX_DEPTH) throw AdviceFailure(AdviceError.INVALID_JSON)
        return when (reader.peek()) {
            JsonReader.Token.BEGIN_OBJECT -> {
                val values = linkedMapOf<String, Any?>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (values.containsKey(name)) throw AdviceFailure(AdviceError.INVALID_JSON)
                    values[name] = read(reader, depth + 1)
                }
                reader.endObject()
                values
            }
            JsonReader.Token.BEGIN_ARRAY -> {
                val values = mutableListOf<Any?>()
                reader.beginArray()
                while (reader.hasNext()) values += read(reader, depth + 1)
                reader.endArray()
                values
            }
            JsonReader.Token.STRING -> reader.nextString()
            JsonReader.Token.NUMBER -> reader.nextDouble().also {
                if (!it.isFinite()) throw AdviceFailure(AdviceError.INVALID_JSON)
            }
            JsonReader.Token.BOOLEAN -> reader.nextBoolean()
            JsonReader.Token.NULL -> reader.nextNull<Any>()
            else -> throw AdviceFailure(AdviceError.INVALID_JSON)
        }
    }

    private fun obj(value: Any?, keys: Set<String>): Map<*, *> {
        val result = value as? Map<*, *> ?: badShape()
        if (result.keys != keys) badShape()
        return result
    }
    private fun string(value: Any?) = value as? String ?: badShape()
    private fun list(value: Any?) = value as? List<*> ?: badShape()
    private fun validText(value: String, max: Int) = value.isNotBlank() && value.codePointCount(0, value.length) in 1..max
    private fun badShape(): Nothing = throw AdviceFailure(AdviceError.SCHEMA_MISMATCH)
    private fun badStatus(): Nothing = throw AdviceFailure(AdviceError.INVALID_STATUS_PAYLOAD)
}
