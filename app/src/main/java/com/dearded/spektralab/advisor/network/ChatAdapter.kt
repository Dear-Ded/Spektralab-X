package com.dearded.spektralab.advisor.network

import com.dearded.spektralab.advisor.domain.AdviceRequest
import com.dearded.spektralab.advisor.session.RequestContextBuilder
import com.dearded.spektralab.advisor.session.TaskClarification

class ChatAdapter(schema: String, directionalSchema: String? = null) : AdviceAdapter {
    override val transport = AdvisorTransport.CHAT
    override val version = "chat.1"
    private val schema = AdviceWireSchema(schema, directionalSchema)

    override fun encode(endpoint: EndpointConfig, binding: ModelBinding, request: AdviceRequest,
                        clarification: TaskClarification?): String {
        require(endpoint.transport == transport && !binding.visionEnabled)
        val body = linkedMapOf<String, Any?>("model" to binding.remoteModelId, "stream" to false,
            "messages" to listOf(mapOf("role" to "system", "content" to RequestContextBuilder.instructionFor(request) +
                if (request.schemaVersion == 2) "\nExample JSON when nothing should change: $EMPTY_RESULT" else ""),
                mapOf("role" to "user", "content" to RequestContextBuilder.parameters(request, clarification))))
        when (endpoint.dialect) {
            AdvisorDialect.OPENAI -> {
                require(binding.reasoningMode == null)
                body["max_completion_tokens"] = binding.maxOutputTokens
                body["store"] = false
                binding.reasoningEffort?.let { body["reasoning_effort"] = it }
            }
            AdvisorDialect.DEEPSEEK -> {
                require(endpoint.schemaMode != AdvisorSchemaMode.JSON_SCHEMA)
                require(binding.reasoningMode in setOf(null, "enabled", "disabled"))
                require(binding.reasoningMode != "disabled" || binding.reasoningEffort == null)
                body["max_tokens"] = binding.maxOutputTokens
                binding.reasoningMode?.let { body["thinking"] = mapOf("type" to it) }
                binding.reasoningEffort?.let { body["reasoning_effort"] = it }
            }
            AdvisorDialect.OPENROUTER -> {
                // Claude native adaptive/between-tools semantics have no lossless verified Chat mapping.
                require(binding.reasoningMode in setOf(null, "enabled", "disabled"))
                require(binding.reasoningMode != "disabled" || binding.reasoningEffort == null)
                body["max_completion_tokens"] = binding.maxOutputTokens
                body["provider"] = mapOf("allow_fallbacks" to false, "require_parameters" to true)
                if (binding.reasoningMode != null || binding.reasoningEffort != null) {
                    val reasoning = linkedMapOf<String, Any?>("exclude" to true)
                    binding.reasoningMode?.let { reasoning["enabled"] = it != "disabled" }
                    binding.reasoningEffort?.let { reasoning["effort"] = it }
                    body["reasoning"] = reasoning
                }
            }
            AdvisorDialect.GENERIC_CHAT -> {
                require(binding.reasoningMode == null && binding.reasoningEffort == null)
                body[requireNotNull(endpoint.chatOutputLimit) { "Choose the endpoint output-budget field" }.wire] = binding.maxOutputTokens
            }
            else -> error("Unsupported Chat dialect")
        }
        when (endpoint.schemaMode) {
            AdvisorSchemaMode.JSON_SCHEMA -> body["response_format"] = mapOf("type" to "json_schema",
                "json_schema" to mapOf("name" to "spektralab_advice_v${request.schemaVersion}", "strict" to true,
                    "schema" to schema.value(request)))
            AdvisorSchemaMode.JSON_OBJECT -> body["response_format"] = mapOf("type" to "json_object")
            AdvisorSchemaMode.PROMPT_JSON -> Unit
        }
        return ProviderJson.encode(body)
    }

    override fun decode(response: String, schemaVersion: Int): AdvisorReply {
        val root = ProviderJson.parse(response)
        if (root["error"] != null) protocolFailure(AdvisorCompletionError.PROVIDER_ERROR)
        val choices = root["choices"].wireArray()
        if (choices.size != 1) protocolFailure()
        val choice = choices.single().wireObject()
        if (choice["error"] != null) protocolFailure(AdvisorCompletionError.PROVIDER_ERROR)
        when (choice["finish_reason"].wireText()) {
            "stop" -> Unit
            "tool_calls", "function_call" -> protocolFailure(AdvisorCompletionError.TOOL_CALL)
            "content_filter" -> protocolFailure(AdvisorCompletionError.REFUSED)
            else -> protocolFailure(AdvisorCompletionError.INCOMPLETE)
        }
        choice["native_finish_reason"]?.let {
            if (it !in setOf("stop", "end_turn", "STOP")) protocolFailure(AdvisorCompletionError.INCOMPLETE)
        }
        val message = choice["message"].wireObject()
        if (message["role"] != "assistant") protocolFailure()
        if (message["refusal"] != null) protocolFailure(AdvisorCompletionError.REFUSED)
        if (message["function_call"] != null || message["tool_calls"]?.wireArray()?.isNotEmpty() == true)
            protocolFailure(AdvisorCompletionError.TOOL_CALL)
        val text = message["content"] as? String ?: protocolFailure(AdvisorCompletionError.NO_FINAL_TEXT)
        return finalReply(text, usage(root["usage"]), schemaVersion)
    }

    private fun usage(value: Any?): AdvisorTokenUsage {
        if (value == null) return AdvisorTokenUsage()
        val raw = value.wireObject()
        val counters = linkedMapOf<String, Long>()
        for (key in listOf("prompt_tokens", "completion_tokens", "total_tokens", "prompt_cache_hit_tokens", "prompt_cache_miss_tokens"))
            raw[key].tokenCount()?.let { counters[key] = it }
        for ((parent, keys) in mapOf("prompt_tokens_details" to listOf("cached_tokens", "cache_write_tokens"),
            "completion_tokens_details" to listOf("reasoning_tokens", "safety_tokens")))
            for (key in keys) (raw[parent] as? Map<*, *>)?.get(key).tokenCount()?.let { counters["$parent.$key"] = it }
        return AdvisorTokenUsage(counters["prompt_tokens"],
            counters["prompt_tokens_details.cached_tokens"] ?: counters["prompt_cache_hit_tokens"],
            counters["completion_tokens"], counters["completion_tokens_details.reasoning_tokens"],
            counters["total_tokens"], if ("completion_tokens_details.reasoning_tokens" in counters) true else null,
            counters.toMap())
    }

    companion object {
        private const val EMPTY_RESULT = """{"schema_version":2,"status":"no_change","changes":[],"question":{"kind":"none","prompt":"","options":[]}}"""
    }
}
