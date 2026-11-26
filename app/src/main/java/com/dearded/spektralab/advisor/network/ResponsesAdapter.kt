package com.dearded.spektralab.advisor.network

import com.dearded.spektralab.advisor.domain.AdviceRequest
import com.dearded.spektralab.advisor.session.RequestContextBuilder
import com.dearded.spektralab.advisor.session.TaskClarification

class ResponsesAdapter(schema: String, directionalSchema: String? = null) : AdviceAdapter {
    override val transport = AdvisorTransport.RESPONSES
    override val version = "responses.2"
    private val schema = AdviceWireSchema(schema, directionalSchema)

    override fun encode(endpoint: EndpointConfig, binding: ModelBinding, request: AdviceRequest,
                        clarification: TaskClarification?): String {
        require(endpoint.transport == transport && endpoint.dialect == AdvisorDialect.OPENAI)
        require(!binding.visionEnabled && binding.reasoningMode == null)
        val body = linkedMapOf<String, Any?>("model" to binding.remoteModelId,
            "instructions" to RequestContextBuilder.instructionFor(request),
            "input" to listOf(mapOf("role" to "user", "content" to RequestContextBuilder.parameters(request, clarification))),
            "max_output_tokens" to binding.maxOutputTokens, "store" to false, "stream" to false)
        binding.reasoningEffort?.let { body["reasoning"] = mapOf("effort" to it) }
        when (endpoint.schemaMode) {
            AdvisorSchemaMode.JSON_SCHEMA -> body["text"] = mapOf("format" to mapOf("type" to "json_schema",
                "name" to "spektralab_advice_v${request.schemaVersion}", "strict" to true, "schema" to schema.value(request)))
            AdvisorSchemaMode.JSON_OBJECT -> body["text"] = mapOf("format" to mapOf("type" to "json_object"))
            AdvisorSchemaMode.PROMPT_JSON -> Unit
        }
        return ProviderJson.encode(body)
    }

    override fun decode(response: String, schemaVersion: Int): AdvisorReply {
        val root = ProviderJson.parse(response)
        if (root["error"] != null) protocolFailure(AdvisorCompletionError.PROVIDER_ERROR)
        if (root["status"] != "completed" || root["incomplete_details"] != null)
            protocolFailure(AdvisorCompletionError.INCOMPLETE)
        var finalText: String? = null
        for (value in root["output"].wireArray()) {
            val item = value.wireObject()
            when (item["type"].wireText()) {
                "reasoning" -> if (item["status"] != null && item["status"] != "completed")
                    protocolFailure(AdvisorCompletionError.INCOMPLETE)
                "message" -> {
                    if (item["role"] != "assistant") protocolFailure()
                    if (item["status"] != "completed") protocolFailure(AdvisorCompletionError.INCOMPLETE)
                    when (item["phase"]) {
                        "commentary" -> messageText(item, collect = false)
                        null, "final_answer" -> {
                            if (finalText != null) protocolFailure()
                            finalText = messageText(item, collect = true)
                        }
                        else -> protocolFailure()
                    }
                }
                else -> protocolFailure(AdvisorCompletionError.TOOL_CALL)
            }
        }
        return finalReply(finalText ?: protocolFailure(AdvisorCompletionError.NO_FINAL_TEXT), usage(root["usage"]), schemaVersion)
    }

    private fun messageText(message: Map<String, Any?>, collect: Boolean): String {
        val text = if (collect) StringBuilder() else null
        for (value in message["content"].wireArray()) {
            val part = value.wireObject()
            when (part["type"].wireText()) {
                "refusal" -> protocolFailure(AdvisorCompletionError.REFUSED)
                "output_text" -> {
                    val valueText = part["text"].wireText()
                    text?.append(valueText)
                }
                else -> protocolFailure()
            }
        }
        return text?.toString().orEmpty()
    }

    private fun usage(value: Any?): AdvisorTokenUsage {
        if (value == null) return AdvisorTokenUsage()
        val raw = value.wireObject()
        val counters = linkedMapOf<String, Long>()
        for (key in listOf("input_tokens", "output_tokens", "total_tokens"))
            raw[key].tokenCount()?.let { counters[key] = it }
        for ((parent, key) in listOf("input_tokens_details" to "cached_tokens", "output_tokens_details" to "reasoning_tokens"))
            (raw[parent] as? Map<*, *>)?.get(key).tokenCount()?.let { counters["$parent.$key"] = it }
        return AdvisorTokenUsage(counters["input_tokens"], counters["input_tokens_details.cached_tokens"],
            counters["output_tokens"], counters["output_tokens_details.reasoning_tokens"], counters["total_tokens"], true,
            counters.toMap())
    }
}
