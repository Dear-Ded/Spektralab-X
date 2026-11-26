package com.dearded.spektralab.advisor.network

import com.dearded.spektralab.advisor.domain.AdviceRequest
import com.dearded.spektralab.advisor.session.RequestContextBuilder
import com.dearded.spektralab.advisor.session.TaskClarification
import java.util.Locale

class GenerateContentAdapter(schema: String, directionalSchema: String? = null) : AdviceAdapter {
    override val transport = AdvisorTransport.GENERATE_CONTENT
    override val version = "generateContent.responseFormat.1"
    private val schema = AdviceWireSchema(schema, directionalSchema)

    override fun encode(endpoint: EndpointConfig, binding: ModelBinding, request: AdviceRequest,
                        clarification: TaskClarification?): String {
        require(endpoint.transport == transport && endpoint.dialect == AdvisorDialect.GOOGLE)
        require(!binding.visionEnabled && binding.reasoningMode == null)
        val generation = linkedMapOf<String, Any?>("maxOutputTokens" to binding.maxOutputTokens)
        binding.reasoningEffort?.let {
            // REST reference enum spelling, not an SDK property or a legacy thinking budget.
            generation["thinkingConfig"] = mapOf("thinkingLevel" to it.uppercase(Locale.ROOT), "includeThoughts" to false)
        }
        if (endpoint.schemaMode != AdvisorSchemaMode.PROMPT_JSON) {
            val format = linkedMapOf<String, Any?>("mimeType" to "APPLICATION_JSON")
            if (endpoint.schemaMode == AdvisorSchemaMode.JSON_SCHEMA) format["schema"] = schema.value(request)
            generation["responseFormat"] = mapOf("text" to format)
        }
        return ProviderJson.encode(linkedMapOf("systemInstruction" to mapOf("parts" to listOf(mapOf("text" to RequestContextBuilder.instructionFor(request)))),
            "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to RequestContextBuilder.parameters(request, clarification))))),
            "generationConfig" to generation))
    }

    override fun decode(response: String, schemaVersion: Int): AdvisorReply {
        val root = ProviderJson.parse(response)
        if (root["error"] != null) protocolFailure(AdvisorCompletionError.PROVIDER_ERROR)
        root["promptFeedback"]?.wireObject()?.get("blockReason")?.let { protocolFailure(AdvisorCompletionError.REFUSED) }
        val candidates = root["candidates"].wireArray()
        if (candidates.size != 1) protocolFailure()
        val candidate = candidates.single().wireObject()
        when (candidate["finishReason"].wireText()) {
            "STOP" -> Unit
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" -> protocolFailure(AdvisorCompletionError.REFUSED)
            else -> protocolFailure(AdvisorCompletionError.INCOMPLETE)
        }
        val content = candidate["content"].wireObject()
        if (content["role"] != "model") protocolFailure()
        val text = StringBuilder()
        for (value in content["parts"].wireArray()) {
            val part = value.wireObject()
            if (part.keys.any { it in setOf("functionCall", "functionResponse", "executableCode", "codeExecutionResult") })
                protocolFailure(AdvisorCompletionError.TOOL_CALL)
            if (part.keys.any { it !in setOf("text", "thought", "thoughtSignature") }) protocolFailure()
            if (part["thought"] != null && part["thought"] !is Boolean) protocolFailure()
            if (part["thought"] != true) text.append(part["text"].wireText())
        }
        return finalReply(text.toString(), usage(root["usageMetadata"]), schemaVersion)
    }

    private fun usage(value: Any?): AdvisorTokenUsage {
        if (value == null) return AdvisorTokenUsage()
        val raw = value.wireObject()
        val counters = listOf("promptTokenCount", "cachedContentTokenCount", "candidatesTokenCount", "thoughtsTokenCount",
            "totalTokenCount", "toolUsePromptTokenCount").mapNotNull { key -> raw[key].tokenCount()?.let { key to it } }.toMap()
        return AdvisorTokenUsage(counters["promptTokenCount"], counters["cachedContentTokenCount"], counters["candidatesTokenCount"],
            counters["thoughtsTokenCount"], counters["totalTokenCount"], false, counters)
    }
}
