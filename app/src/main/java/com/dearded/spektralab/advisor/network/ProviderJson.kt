package com.dearded.spektralab.advisor.network

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer
import java.io.IOException
import java.math.BigDecimal

/** Provider envelopes allow metadata, but never duplicate keys, coercion or trailing JSON. */
internal object ProviderJson {
    fun parse(text: String): Map<String, Any?> {
        if (text.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024) protocolFailure()
        try {
            return JsonReader.of(Buffer().writeUtf8(text)).use { reader ->
                val root = read(reader, 0).wireObject()
                if (reader.peek() != JsonReader.Token.END_DOCUMENT) protocolFailure()
                root
            }
        } catch (e: AdvisorProtocolFailure) { throw e
        } catch (_: IOException) { protocolFailure()
        } catch (_: com.squareup.moshi.JsonDataException) { protocolFailure()
        } catch (_: NumberFormatException) { protocolFailure() }
    }

    private fun read(reader: JsonReader, depth: Int): Any? {
        if (depth > 32) protocolFailure()
        return when (reader.peek()) {
            JsonReader.Token.BEGIN_OBJECT -> {
                val values = linkedMapOf<String, Any?>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (values.containsKey(name)) protocolFailure()
                    values[name] = read(reader, depth + 1)
                }
                reader.endObject(); values
            }
            JsonReader.Token.BEGIN_ARRAY -> {
                val values = mutableListOf<Any?>()
                reader.beginArray()
                while (reader.hasNext()) values.add(read(reader, depth + 1))
                reader.endArray(); values
            }
            JsonReader.Token.STRING -> reader.nextString()
            JsonReader.Token.NUMBER -> reader.nextString().toBigDecimal()
            JsonReader.Token.BOOLEAN -> reader.nextBoolean()
            JsonReader.Token.NULL -> reader.nextNull<Any>()
            else -> protocolFailure()
        }
    }

    fun encode(value: Map<String, Any?>): String {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { write(it, value) }
        return buffer.readUtf8()
    }

    private fun write(writer: JsonWriter, value: Any?) {
        when (value) {
            null -> writer.nullValue()
            is String -> writer.value(value)
            is Boolean -> writer.value(value)
            is Number -> writer.value(value)
            is Map<*, *> -> {
                writer.beginObject()
                value.forEach { (key, item) -> writer.name(key as String); write(writer, item) }
                writer.endObject()
            }
            is List<*> -> { writer.beginArray(); value.forEach { write(writer, it) }; writer.endArray() }
            else -> error("Unsupported advisor wire value")
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.wireObject(): Map<String, Any?> = this as? Map<String, Any?> ?: protocolFailure()
internal fun Any?.wireArray(): List<*> = this as? List<*> ?: protocolFailure()
internal fun Any?.wireText(): String = this as? String ?: protocolFailure()
internal fun Any?.tokenCount(): Long? = try {
    (this as? BigDecimal)?.longValueExact()?.takeIf { it >= 0 }
} catch (_: ArithmeticException) { null }

internal fun protocolFailure(category: AdvisorCompletionError = AdvisorCompletionError.INVALID_ENVELOPE): Nothing =
    throw AdvisorProtocolFailure(category)
