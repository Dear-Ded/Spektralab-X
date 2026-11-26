package com.dearded.spektralab.advisor.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class AdvisorTransport(val wire: String, val resource: String) {
    RESPONSES("openai_responses", "responses"), CHAT("openai_chat", "chat/completions"),
    MESSAGES("anthropic_messages", "messages"), INTERACTIONS("gemini_interactions", "interactions"),
    GENERATE_CONTENT("gemini_generate_content", "");

    companion object { fun of(value: String) = entries.single { it.wire == value } }
}

enum class AdvisorDialect { OPENAI, DEEPSEEK, ANTHROPIC, GOOGLE, GENERIC_CHAT, OPENROUTER }
enum class AdvisorAuth { BEARER, X_API_KEY, X_GOOG_API_KEY }
enum class AdvisorSchemaMode { PROMPT_JSON, JSON_OBJECT, JSON_SCHEMA }
enum class ChatOutputLimit(val wire: String) { MAX_TOKENS("max_tokens"), MAX_COMPLETION_TOKENS("max_completion_tokens") }
enum class CapabilityStatus { UNVERIFIED, VERIFIED }

/** No credential value, image, user goal or profile data belongs in endpoint configuration. */
data class EndpointConfig(
    val id: String, val revision: Long, val label: String, val baseUrl: HttpUrl,
    val transport: AdvisorTransport, val dialect: AdvisorDialect,
    val auth: AdvisorAuth, val schemaMode: AdvisorSchemaMode = AdvisorSchemaMode.PROMPT_JSON,
    val pathOverride: String? = null, val credential: CredentialRef? = null,
    val requiredHeaders: Map<String, String> = emptyMap(),
    val chatOutputLimit: ChatOutputLimit? = null,
) {
    init {
        require(id.isNotBlank() && revision >= 0)
        require(chatOutputLimit == null || transport == AdvisorTransport.CHAT && dialect == AdvisorDialect.GENERIC_CHAT)
        require(baseUrl.isHttps && baseUrl.username.isEmpty() && baseUrl.password.isEmpty())
        // Query-based credentials cannot be safely separated from shareable configuration.
        require(baseUrl.query == null && baseUrl.fragment == null)
        require(requiredHeaders.all { (key, value) ->
            key.lowercase() in setOf("anthropic-version", "api-revision") &&
                value.isNotBlank() && value.all { it.code in 32..126 }
        })
        require(requiredHeaders.keys.map { it.lowercase() }.distinct().size == requiredHeaders.size)
        require(when (dialect) {
            AdvisorDialect.OPENAI -> transport in setOf(AdvisorTransport.RESPONSES, AdvisorTransport.CHAT)
            AdvisorDialect.DEEPSEEK, AdvisorDialect.GENERIC_CHAT, AdvisorDialect.OPENROUTER -> transport == AdvisorTransport.CHAT
            AdvisorDialect.ANTHROPIC -> transport == AdvisorTransport.MESSAGES
            AdvisorDialect.GOOGLE -> transport in setOf(AdvisorTransport.INTERACTIONS, AdvisorTransport.GENERATE_CONTENT)
        })
        pathOverride?.let(::checkedPath)
        credential?.let { require(it.endpointId == id && it.origin == origin) }
    }

    val origin: String get() = "${baseUrl.scheme}://${baseUrl.host}:${baseUrl.port}"

    fun requestUrl(remoteModelId: String): HttpUrl {
        require(remoteModelId.isNotBlank() && remoteModelId.length <= 200 && remoteModelId.none { it.isISOControl() })
        val builder = baseUrl.newBuilder()
        if (baseUrl.pathSegments.lastOrNull() == "") builder.removePathSegment(baseUrl.pathSize - 1)
        if (pathOverride != null) builder.addPathSegments(checkedPath(pathOverride))
        else if (transport == AdvisorTransport.GENERATE_CONTENT)
            builder.addPathSegment("models").addPathSegment("$remoteModelId:generateContent")
        else builder.addPathSegments(transport.resource)
        return builder.build()
    }

    /** A host or port change removes the credential reference before any next request. */
    fun atAddress(address: String): EndpointConfig {
        val parsed = address.toHttpUrl()
        val nextOrigin = "${parsed.scheme}://${parsed.host}:${parsed.port}"
        return copy(revision = revision + 1, baseUrl = parsed,
            credential = credential.takeIf { origin == nextOrigin })
    }

    companion object {
        private fun checkedPath(path: String): String {
            require(path.isNotBlank() && !path.startsWith('/') && !path.endsWith('/'))
            require(path.all { it.isLetterOrDigit() && it.code < 128 || it in "-_/" })
            require(path.split('/').none { it.isEmpty() || it == "." || it == ".." })
            return path
        }
    }
}

data class ModelBinding(
    val id: String, val revision: Long, val endpointId: String, val remoteModelId: String,
    val catalogTemplateId: String? = null, val reasoningMode: String? = null,
    val reasoningEffort: String? = null, val maxOutputTokens: Int = 4096,
    val visionEnabled: Boolean = false,
) {
    init {
        require(id.isNotBlank() && endpointId.isNotBlank() && revision >= 0)
        require(remoteModelId.isNotBlank() && remoteModelId.length <= 200 && remoteModelId.none { it.isISOControl() })
        require(maxOutputTokens in 1..65536)
    }
}

/** Verification is scoped to a frozen configuration, never inferred from a model name. */
data class CapabilityVerification(
    val endpointId: String, val endpointRevision: Long, val credentialRevision: Long,
    val bindingId: String, val bindingRevision: Long, val remoteModelId: String,
    val transport: AdvisorTransport, val catalogVersion: String, val adapterVersion: String,
    val vision: Boolean,
) {
    fun matches(endpoint: EndpointConfig, binding: ModelBinding, catalog: String, adapter: String) =
        endpointId == endpoint.id && endpointRevision == endpoint.revision &&
        credentialRevision == endpoint.credential?.revision && bindingId == binding.id &&
        bindingRevision == binding.revision && remoteModelId == binding.remoteModelId &&
        transport == endpoint.transport && catalogVersion == catalog && adapterVersion == adapter
}
