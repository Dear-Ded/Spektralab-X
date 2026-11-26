package com.dearded.spektralab.advisor.network

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class ReasoningProfile(val modes: Set<String>, val efforts: Set<String>,
                            val modeEfforts: Map<String, Set<String>>, val effortModes: Set<String>?) {
    fun accepts(mode: String?, effort: String?): Boolean {
        if (modes.isEmpty()) return mode == null && effort in efforts
        if (mode !in modes) return false
        if (effortModes != null && mode !in effortModes) return effort == null
        return effort in (modeEfforts[mode] ?: efforts)
    }
}

data class CatalogModel(val id: String, val remoteId: String, val provider: String,
                        val displayName: String, val profile: ReasoningProfile,
                        val imageDeclared: Boolean, val sources: Set<String>,
                        val initialMode: String?, val initialEffort: String?)

data class AdvisorProviderTemplate(val id: String, val baseUrl: HttpUrl, val transports: List<AdvisorTransport>,
                                   val schemaMode: AdvisorSchemaMode)
data class AdvisorTransportTemplate(val auth: AdvisorAuth, val headers: Map<String, String>)

/** The supplied directory is a documentation snapshot, never proof of account/API availability. */
class ModelCatalog private constructor(val version: String, val reviewedAt: String,
                                       val models: Map<String, CatalogModel>,
                                       val providers: Map<String, AdvisorProviderTemplate>,
                                       val transports: Map<AdvisorTransport, AdvisorTransportTemplate>) {
    companion object {
        fun parse(json: String): ModelCatalog {
            val root = JSONObject(json)
            require(root.getInt("schema_version") == 1)
            require(!root.getBoolean("live_api_tests_performed"))
            val profiles = root.getJSONObject("reasoning_profiles")
            val entries = root.getJSONArray("models")
            val result = linkedMapOf<String, CatalogModel>()
            for (i in 0 until entries.length()) {
                val model = entries.getJSONObject(i)
                val profile = profiles.getJSONObject(model.getString("reasoning_profile"))
                val limits = profile.optJSONObject("mode_effort_constraints")
                val reasoning = ReasoningProfile(profile.optJSONArray("modes").strings(),
                    profile.getJSONArray("efforts").strings(),
                    limits?.keys()?.asSequence()?.associateWith { limits.getJSONArray(it).strings() }.orEmpty(),
                    profile.optJSONArray("effort_applicable_modes")?.strings())
                val parsed = CatalogModel(model.getString("catalog_id"), model.getString("model_id"),
                    model.getString("provider"), model.getString("display_name"), reasoning,
                    "image" in model.getJSONArray("input_modalities").strings(), model.getJSONArray("source_ids").strings(),
                    model.getJSONObject("initial_settings").optString("mode").takeIf { it.isNotEmpty() },
                    model.getJSONObject("initial_settings").optString("effort").takeIf { it.isNotEmpty() })
                require(parsed.profile.accepts(parsed.initialMode, parsed.initialEffort))
                require(result.put(parsed.id, parsed) == null)
            }
            val providers = root.getJSONObject("provider_templates").let { source -> source.keys().asSequence().associateWith { id ->
                val entry = source.getJSONObject(id)
                AdvisorProviderTemplate(id, entry.getString("base_url").toHttpUrl(),
                    listOfNotNull(entry.getString("primary_transport"), entry.optString("secondary_transport").takeIf { it.isNotEmpty() })
                        .map(AdvisorTransport::of), AdvisorSchemaMode.valueOf(entry.getString("output_mode").uppercase(java.util.Locale.ROOT)))
            } }
            val transports = root.getJSONObject("transports").let { source -> AdvisorTransport.entries.associateWith { transport ->
                val entry = source.getJSONObject(transport.wire)
                val auth = when (entry.getString("auth")) {
                    "bearer" -> AdvisorAuth.BEARER; "x-api-key" -> AdvisorAuth.X_API_KEY; "x-goog-api-key" -> AdvisorAuth.X_GOOG_API_KEY
                    else -> error("Unsupported catalog authentication")
                }
                val headers = entry.optJSONObject("headers")
                AdvisorTransportTemplate(auth, headers?.keys()?.asSequence()?.associateWith { headers.getString(it) }.orEmpty())
            } }
            return ModelCatalog(root.getString("catalog_version"), root.getString("as_of"), result.toMap(), providers, transports)
        }
        private fun JSONArray?.strings(): Set<String> = this?.let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.orEmpty()
    }
}

data class ResolvedCapabilities(val status: CapabilityStatus, val vision: Boolean,
                                val mode: String?, val effort: String?)

object CapabilityResolver {
    fun resolve(endpoint: EndpointConfig, binding: ModelBinding, catalog: ModelCatalog,
                adapterVersion: String, verification: CapabilityVerification? = null): ResolvedCapabilities {
        require(binding.endpointId == endpoint.id)
        val model = binding.catalogTemplateId?.let { catalog.models[it] ?: error("Unknown catalog template") }
        val verified = verification?.matches(endpoint, binding, catalog.version, adapterVersion) == true
        if (model == null) {
            require(binding.reasoningMode == null && binding.reasoningEffort == null)
            require(endpoint.schemaMode == AdvisorSchemaMode.PROMPT_JSON)
        } else {
            if (endpoint.dialect == AdvisorDialect.GENERIC_CHAT)
                require(binding.reasoningMode == null && binding.reasoningEffort == null)
            else require(model.profile.accepts(binding.reasoningMode, binding.reasoningEffort))
            // Explicit relay dialects may use a chosen template, without inheriting verified status.
            if (endpoint.dialect !in setOf(AdvisorDialect.GENERIC_CHAT, AdvisorDialect.OPENROUTER)) {
                require(model.provider == when (endpoint.dialect) {
                    AdvisorDialect.OPENAI -> "openai"; AdvisorDialect.DEEPSEEK -> "deepseek"
                    AdvisorDialect.ANTHROPIC -> "anthropic"; AdvisorDialect.GOOGLE -> "google"
                    else -> error("Unsupported dialect")
                })
                val official = catalog.providers.getValue(model.provider).baseUrl
                if (endpoint.baseUrl.host == official.host && endpoint.baseUrl.port == official.port)
                    require(binding.remoteModelId == model.remoteId)
            }
        }
        val vision = verified && verification?.vision == true && model?.imageDeclared == true
        require(!binding.visionEnabled || vision)
        return ResolvedCapabilities(if (verified) CapabilityStatus.VERIFIED else CapabilityStatus.UNVERIFIED,
            binding.visionEnabled && vision, binding.reasoningMode, binding.reasoningEffort)
    }
}
