package com.dearded.spektralab.advisor.network

import com.dearded.spektralab.advisor.domain.AdviceRequest
import com.dearded.spektralab.advisor.session.RequestContextBuilder
import com.dearded.spektralab.advisor.session.TaskClarification

class MessagesAdapter(schema: String, directionalSchema: String? = null) : AdviceAdapter {
    override val transport = AdvisorTransport.MESSAGES
    override val version = "messages.1"
    private val schema = AdviceWireSchema(schema, directionalSchema)

    override fun encode(endpoint: EndpointConfig, binding: ModelBinding, request: AdviceRequest,
                        clarification: TaskClarification?): String {
        require(endpoint.transport == transport && endpoint.dialect == AdvisorDialect.ANTHROPIC && !binding.visionEnabled)
        require(endpoint.requiredHeaders.entries.singleOrNull { it.key.equals("anthropic-version", true) }?.value == API_VERSION)
        require(endpoint.schemaMode != AdvisorSchemaMode.JSON_OBJECT)
        val body = linkedMapOf<String, Any?>("model" to binding.remoteModelId, "max_tokens" to binding.maxOutputTokens,
            "stream" to false, "system" to RequestContextBuilder.instructionFor(request),
            "messages" to listOf(mapOf("role" to "user", "content" to RequestContextBuilder.parameters(request, clarification))))
        binding.reasoningMode?.let { body["thinking"] = mapOf("type" to it) }
        val output = linkedMapOf<String, Any?>()
        binding.reasoningEffort?.let { output["effort"] = it }
        if (endpoint.schemaMode == AdvisorSchemaMode.JSON_SCHEMA)
            output["format"] = mapOf("type" to "json_schema", "schema" to schema.value(request))
        if (output.isNotEmpty()) body["output_config"] = output
        return ProviderJson.encode(body)
    }

    override fun decode(response: String, schemaVersion: Int): AdvisorReply {
        val root = ProviderJson.parse(response)
        if (root["type"] == "error" || root["error"] != null) protocolFailure(AdvisorCompletionError.PROVIDER_ERROR)
        if (root["type"] != "message" || root["role"] != "assistant") protocolFailure()
        when (root["stop_reason"].wireText()) {
            "end_turn" -> Unit
            "refusal" -> protocolFailure(AdvisorCompletionError.REFUSED)
            "tool_use" -> protocolFailure(AdvisorCompletionError.TOOL_CALL)
            else -> protocolFailure(AdvisorCompletionError.INCOMPLETE)
        }
        val text = StringBuilder()
        for (value in root["content"].wireArray()) {
            val part = value.wireObject()
            when (part["type"].wireText()) {
                "text" -> text.append(part["text"].wireText())
                "thinking", "redacted_thinking" -> Unit
                "refusal" -> protocolFailure(AdvisorCompletionError.REFUSED)
                else -> protocolFailure(AdvisorCompletionError.TOOL_CALL)
            }
        }
        return finalReply(text.toString(), usage(root["usage"]), schemaVersion)
    }

    private fun usage(value: Any?): AdvisorTokenUsage {
        if (value == null) return AdvisorTokenUsage()
        val raw = value.wireObject()
        val counters = linkedMapOf<String, Long>()
        for (key in listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens"))
            raw[key].tokenCount()?.let { counters[key] = it }
        (raw["output_tokens_details"] as? Map<*, *>)?.get("thinking_tokens").tokenCount()?.let {
            counters["output_tokens_details.thinking_tokens"] = it
        }
        val input = tokenSum(counters["input_tokens"], counters["cache_creation_input_tokens"], counters["cache_read_input_tokens"])
        return AdvisorTokenUsage(input, counters["cache_read_input_tokens"], counters["output_tokens"],
            counters["output_tokens_details.thinking_tokens"], tokenSum(input, counters["output_tokens"]), true, counters.toMap())
    }

    companion object { const val API_VERSION = "2026-10-21" }
}
