package com.dearded.spektralab.advisor.network

import com.dearded.spektralab.advisor.domain.AdviceRequest
import com.dearded.spektralab.advisor.domain.AdviceResult
import com.dearded.spektralab.advisor.session.TaskClarification
import java.io.IOException

enum class AdvisorCompletionError { INCOMPLETE, REFUSED, TOOL_CALL, NO_FINAL_TEXT, PROVIDER_ERROR, INVALID_ENVELOPE }
class AdvisorProtocolFailure(val category: AdvisorCompletionError) :
    IOException("Advisor response rejected: ${category.name}")

/** Memory-only numeric counters. Null means unknown; reasoning is never added to an inclusive output. */
data class AdvisorTokenUsage(
    val inputTokens: Long? = null, val cachedInputTokens: Long? = null,
    val outputTokens: Long? = null, val reasoningTokens: Long? = null,
    val totalTokens: Long? = null, val outputIncludesReasoning: Boolean? = null,
    val rawCounters: Map<String, Long> = emptyMap(),
)

data class AdvisorReply(val result: AdviceResult, val usage: AdvisorTokenUsage)

interface AdviceAdapter {
    val transport: AdvisorTransport
    val version: String
    fun encode(endpoint: EndpointConfig, binding: ModelBinding, request: AdviceRequest,
               clarification: TaskClarification? = null): String
    fun decode(response: String, schemaVersion: Int = 2): AdvisorReply
}

/** Derived from the bundled contract; metadata is not sent as unsupported schema keywords. */
internal class AdviceWireSchema(source: String, directional: String?) {
    private val scalarSchema = portable(source)
    private val directionalSchema = directional?.let(::portable)

    fun value(request: AdviceRequest) = if (request.schemaVersion == 3) checkNotNull(directionalSchema) else scalarSchema

    private fun portable(source: String) = ProviderJson.parse(source)
        .filterKeys { it !in setOf("\$schema", "\$id", "title", "description") }.also {
            require(it["type"] == "object" && it["additionalProperties"] == false)
        }
}

internal fun finalReply(text: String, usage: AdvisorTokenUsage, schemaVersion: Int): AdvisorReply {
    if (text.isBlank()) protocolFailure(AdvisorCompletionError.NO_FINAL_TEXT)
    return AdvisorReply(StrictResultParser.parse(text, schemaVersion), usage)
}

internal fun tokenSum(vararg values: Long?): Long? {
    if (values.any { it == null }) return null
    return try { values.fold(0L) { sum, value -> Math.addExact(sum, checkNotNull(value)) } }
    catch (_: ArithmeticException) { null }
}
