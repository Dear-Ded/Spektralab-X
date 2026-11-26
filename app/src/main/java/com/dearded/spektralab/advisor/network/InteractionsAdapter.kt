package com.dearded.spektralab.advisor.network

import com.dearded.spektralab.advisor.domain.AdviceRequest
import com.dearded.spektralab.advisor.session.RequestContextBuilder
import com.dearded.spektralab.advisor.session.TaskClarification

class InteractionsAdapter(schema: String, directionalSchema: String? = null) : AdviceAdapter {
    override val transport = AdvisorTransport.INTERACTIONS
    override val version = "interactions.steps.1"
    private val schema = AdviceWireSchema(schema, directionalSchema)

    override fun encode(endpoint: EndpointConfig, binding: ModelBinding, request: AdviceRequest,
                        clarification: TaskClarification?): String {
        require(endpoint.transport == transport && endpoint.dialect == AdvisorDialect.GOOGLE)
        require(!binding.visionEnabled && binding.reasoningMode == null)
        require(endpoint.requiredHeaders.keys.none { it.equals("api-revision", true) })
        val generation = linkedMapOf<String, Any?>("max_output_tokens" to binding.maxOutputTokens)
        binding.reasoningEffort?.let { generation["thinking_level"] = it }
        val body = linkedMapOf<String, Any?>("model" to binding.remoteModelId,
            "system_instruction" to RequestContextBuilder.instructionFor(request),
            "input" to RequestContextBuilder.parameters(request, clarification), "generation_config" to generation,
            "store" to false, "stream" to false)
        if (endpoint.schemaMode != AdvisorSchemaMode.PROMPT_JSON) {
            val format = linkedMapOf<String, Any?>("type" to "text", "mime_type" to "application/json")
            if (endpoint.schemaMode == AdvisorSchemaMode.JSON_SCHEMA) format["schema"] = schema.value(request)
            body["response_format"] = format
        }
        return ProviderJson.encode(body)
    }

    override fun decode(response: String, schemaVersion: Int): AdvisorReply {
        val root = ProviderJson.parse(response)
        if (root["error"] != null) protocolFailure(AdvisorCompletionError.PROVIDER_ERROR)
        if (root["status"] != "completed") protocolFailure(AdvisorCompletionError.INCOMPLETE)
        val output = mutableListOf<Map<String, Any?>>()
        for (value in root["steps"].wireArray()) {
            val step = value.wireObject()
            when (step["type"].wireText()) {
                "model_output" -> output.add(step)
                "thought" -> Unit
                else -> protocolFailure(AdvisorCompletionError.TOOL_CALL)
            }
        }
        if (output.isEmpty()) protocolFailure(AdvisorCompletionError.NO_FINAL_TEXT)
        if (output.size != 1) protocolFailure()
        val text = StringBuilder()
        for (value in output.single()["content"].wireArray()) {
            val part = value.wireObject()
            if (part["type"] != "text") protocolFailure()
            text.append(part["text"].wireText())
        }
        return finalReply(text.toString(), usage(root["usage"]), schemaVersion)
    }

    private fun usage(value: Any?): AdvisorTokenUsage {
        if (value == null) return AdvisorTokenUsage()
        val raw = value.wireObject()
        val counters = listOf("total_input_tokens", "total_cached_tokens", "total_output_tokens",
            "total_thought_tokens", "total_tokens", "total_tool_use_tokens")
            .mapNotNull { key -> raw[key].tokenCount()?.let { key to it } }.toMap()
        return AdvisorTokenUsage(counters["total_input_tokens"], counters["total_cached_tokens"],
            counters["total_output_tokens"], counters["total_thought_tokens"], counters["total_tokens"], false, counters)
    }
}
