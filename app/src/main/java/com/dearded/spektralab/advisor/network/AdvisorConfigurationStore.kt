package com.dearded.spektralab.advisor.network

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class AdvisorConfiguration(val revision: Long = 0, val endpoints: List<EndpointConfig> = emptyList(),
                                val bindings: List<ModelBinding> = emptyList(), val selectedBindingId: String? = null) {
    init {
        require(revision >= 0 && endpoints.map { it.id }.distinct().size == endpoints.size)
        require(bindings.map { it.id }.distinct().size == bindings.size)
        require(bindings.all { binding -> endpoints.any { it.id == binding.endpointId } })
        require(selectedBindingId == null || bindings.any { it.id == selectedBindingId })
    }
    fun selected(): Pair<EndpointConfig, ModelBinding>? = bindings.find { it.id == selectedBindingId }?.let { binding ->
        endpoints.single { it.id == binding.endpointId } to binding
    }
}

class AdvisorConfigurationUnavailable : IllegalStateException("Advisor settings could not be read or saved")
class AdvisorConfigurationConflict : IllegalStateException("Advisor settings changed; reload before saving")

/** Explicit configuration only. Credentials remain ciphertext in their independent no-backup store. */
class AdvisorConfigurationStore internal constructor(private val file: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "advisor-settings.v1.json"))
    private val directory = requireNotNull(file.absoluteFile.parentFile)

    fun load(): AdvisorConfiguration = synchronized(ioLock) {
        if (!file.exists()) return@synchronized AdvisorConfiguration()
        try {
            require(file.length() in 1..MAX_BYTES)
            decode(ProviderJson.parse(file.readText(Charsets.UTF_8)))
        } catch (_: Exception) { throw AdvisorConfigurationUnavailable() }
    }

    /** Caller revisions cannot move backward or erase a concurrent settings update. */
    fun save(expectedRevision: Long, proposed: AdvisorConfiguration): AdvisorConfiguration = synchronized(ioLock) {
        val previous = load()
        if (previous.revision != expectedRevision) throw AdvisorConfigurationConflict()
        val endpoints = proposed.endpoints.map { entry ->
            val old = previous.endpoints.find { it.id == entry.id }
            var frozen = entry.copy(requiredHeaders = entry.requiredHeaders.toMap())
            if (old != null && old.origin != frozen.origin && old.credential?.id == frozen.credential?.id)
                frozen = frozen.copy(credential = null)
            frozen.copy(revision = if (old == null) 1 else if (frozen.copy(revision = old.revision) == old)
                old.revision else Math.addExact(old.revision, 1))
        }
        val bindings = proposed.bindings.map { binding ->
            val old = previous.bindings.find { it.id == binding.id }
            binding.copy(revision = if (old == null) 1 else if (binding.copy(revision = old.revision) == old)
                old.revision else Math.addExact(old.revision, 1))
        }
        val next = AdvisorConfiguration(Math.addExact(previous.revision, 1), endpoints, bindings, proposed.selectedBindingId)
        val bytes = ProviderJson.encode(encode(next)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val temporary = File(directory, "${file.name}.tmp")
        try {
            check(directory.isDirectory || directory.mkdirs())
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            next
        } catch (_: Exception) { throw AdvisorConfigurationUnavailable()
        } finally { temporary.delete() }
    }

    private fun encode(config: AdvisorConfiguration): Map<String, Any?> = mapOf("schema_version" to 1,
        "revision" to config.revision, "selected_binding_id" to config.selectedBindingId,
        "endpoints" to config.endpoints.map { endpoint -> linkedMapOf(
            "id" to endpoint.id, "revision" to endpoint.revision, "label" to endpoint.label,
            "base_url" to endpoint.baseUrl.toString(), "transport" to endpoint.transport.wire,
            "dialect" to endpoint.dialect.name, "auth" to endpoint.auth.name, "schema_mode" to endpoint.schemaMode.name,
            "path_override" to endpoint.pathOverride, "required_headers" to endpoint.requiredHeaders,
            "chat_output_limit" to endpoint.chatOutputLimit?.name,
            "credential" to endpoint.credential?.let { mapOf("id" to it.id, "endpoint_id" to it.endpointId,
                "origin" to it.origin, "revision" to it.revision) }) },
        "bindings" to config.bindings.map { binding -> mapOf("id" to binding.id, "revision" to binding.revision,
            "endpoint_id" to binding.endpointId, "remote_model_id" to binding.remoteModelId,
            "catalog_template_id" to binding.catalogTemplateId, "reasoning_mode" to binding.reasoningMode,
            "reasoning_effort" to binding.reasoningEffort, "max_output_tokens" to binding.maxOutputTokens,
            "vision_enabled" to binding.visionEnabled) })

    private fun decode(root: Map<String, Any?>): AdvisorConfiguration {
        require(root["schema_version"].tokenCount() == 1L)
        val endpoints = root["endpoints"].wireArray().map { value ->
            val item = value.wireObject()
            val credential = item["credential"]?.wireObject()?.let {
                CredentialRef(it["id"].wireText(), it["endpoint_id"].wireText(), it["origin"].wireText(), number(it, "revision"))
            }
            EndpointConfig(item["id"].wireText(), number(item, "revision"), item["label"].wireText(),
                item["base_url"].wireText().toHttpUrl(), AdvisorTransport.of(item["transport"].wireText()),
                AdvisorDialect.valueOf(item["dialect"].wireText()), AdvisorAuth.valueOf(item["auth"].wireText()),
                AdvisorSchemaMode.valueOf(item["schema_mode"].wireText()), optional(item, "path_override"), credential,
                item["required_headers"].wireObject().mapValues { it.value.wireText() },
                optional(item, "chat_output_limit")?.let(ChatOutputLimit::valueOf))
        }
        val bindings = root["bindings"].wireArray().map { value ->
            val item = value.wireObject()
            val budget = number(item, "max_output_tokens")
            require(budget in 1..65536)
            ModelBinding(item["id"].wireText(), number(item, "revision"), item["endpoint_id"].wireText(),
                item["remote_model_id"].wireText(), optional(item, "catalog_template_id"), optional(item, "reasoning_mode"),
                optional(item, "reasoning_effort"), budget.toInt(), item["vision_enabled"] as? Boolean ?: error("Invalid vision flag"))
        }
        return AdvisorConfiguration(number(root, "revision"), endpoints, bindings, optional(root, "selected_binding_id"))
    }

    private fun number(item: Map<String, Any?>, key: String) = requireNotNull(item[key].tokenCount())
    private fun optional(item: Map<String, Any?>, key: String) = item[key]?.wireText()
    companion object { private val ioLock = Any(); private const val MAX_BYTES = 1024 * 1024L }
}
