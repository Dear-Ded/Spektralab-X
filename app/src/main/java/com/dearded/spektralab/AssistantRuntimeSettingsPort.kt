package com.dearded.spektralab

import com.dearded.spektralab.advisor.network.AdvisorConfiguration
import com.dearded.spektralab.advisor.network.AdvisorDialect
import com.dearded.spektralab.advisor.network.AdvisorSchemaMode
import com.dearded.spektralab.advisor.network.AdvisorTransport
import com.dearded.spektralab.advisor.network.ChatOutputLimit
import com.dearded.spektralab.advisor.network.EndpointConfig
import com.dearded.spektralab.advisor.network.ModelBinding
import com.dearded.spektralab.advisor.network.ModelCatalog
import com.dearded.spektralab.advisor.session.AdvisorProbeResult
import com.dearded.spektralab.advisor.session.AdvisorRuntime
import com.dearded.spektralab.advisor.session.AdvisorRuntimeState
import com.dearded.spektralab.advisor.session.AdvisorSettingsError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

internal interface AssistantPreferenceControls {
    val preferences: StateFlow<AssistantPreferences>
    fun learn(on: Boolean)
    fun useRules(on: Boolean)
    fun clearPreferences()
}

/** Main-owned settings adapter. The runtime owns persisted routes, secrets and probes. */
internal class AssistantRuntimeSettingsPort(
    scope: CoroutineScope,
    private val runtime: AdvisorRuntime,
    private val preferenceControls: AssistantPreferenceControls,
) : AssistantSettingsPort {
    private data class RejectedSave(val state: AdvisorRuntimeState, val error: AssistantError)
    private val rejectedSave = MutableStateFlow<RejectedSave?>(null)

    override val settings: StateFlow<AssistantSettings> = combine(runtime.state, rejectedSave, ::project)
        .stateIn(scope, SharingStarted.Eagerly, project(runtime.state.value, null))
    override val preferences: StateFlow<AssistantPreferences> get() = preferenceControls.preferences
    override fun learn(on: Boolean) = preferenceControls.learn(on)
    override fun useRules(on: Boolean) = preferenceControls.useRules(on)
    override fun clearPreferences() = preferenceControls.clearPreferences()

    override fun save(service: String, address: String, model: String, key: CharArray?) {
        try {
            val state = runtime.state.value
            if (state.loading || state.saving || state.error == AdvisorSettingsError.LOAD) return
            val catalog = state.catalog ?: return
            val provider = SettingsProvider.offered(catalog).singleOrNull { it.label == service }
                ?: return reject(AssistantError.Configuration)
            val selected = state.selected?.first
            val text = address.trim()
            val entered = if (text.isEmpty()) null else {
                text.toHttpUrlOrNull()
                    ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null }
                    ?: return reject(AssistantError.Address)
            }
            // The sheet leaves the previous address untouched when the provider pill changes.
            val requested = entered.takeUnless { selected != null && selected.dialect != provider.dialect && it == selected.baseUrl }
            val saved = provider.savedEndpoint(state.configuration, requested)
            val target = requested ?: saved?.baseUrl ?: provider.defaultAddress(catalog)
                ?: return reject(AssistantError.Address)
            val remoteId = model.trim()
            val bindings = state.configuration.bindings.filter { it.endpointId == saved?.id }
            val previous = state.selected?.second?.takeIf { it.endpointId == saved?.id } ?: bindings.lastOrNull()
            val existing = previous?.takeIf { it.remoteModelId == remoteId }
                ?: bindings.lastOrNull { it.remoteModelId == remoteId }
            val template = provider.models(catalog).singleOrNull { it.remoteId == remoteId }
            if (!provider.relay && existing == null && template == null) return reject(AssistantError.Configuration)

            val (endpoint, binding) = try {
                val endpoint = when {
                    saved == null -> provider.newEndpoint(catalog, target)
                    saved.baseUrl == target -> saved
                    else -> saved.atAddress(target.toString())
                }
                val binding = existing ?: ModelBinding(
                    UUID.randomUUID().toString(), 0, endpoint.id, remoteId,
                    catalogTemplateId = template?.id,
                    reasoningMode = template?.initialMode,
                    reasoningEffort = template?.initialEffort,
                    maxOutputTokens = previous?.maxOutputTokens ?: 4096,
                )
                endpoint to binding
            } catch (_: IllegalArgumentException) {
                return reject(AssistantError.Configuration)
            }
            rejectedSave.value = null
            runtime.save(state.configuration.revision, endpoint, binding, key)
        } finally {
            key?.fill('\u0000')
        }
    }

    override fun testConnection() {
        if (rejectedSave.value?.state !== runtime.state.value) runtime.testConnection()
    }

    override fun cancelTest() = runtime.cancelTest()

    private fun reject(error: AssistantError) {
        runtime.cancelTest()
        rejectedSave.value = RejectedSave(runtime.state.value, error)
    }

    private fun project(state: AdvisorRuntimeState, rejected: RejectedSave?): AssistantSettings {
        val (endpoint, binding) = state.selected ?: (null to null)
        val providers = state.catalog?.let { SettingsProvider.offered(it) }.orEmpty()
        val localError = rejected?.takeIf { it.state === state }?.error
        val probe = state.probeResult.takeIf { localError == null }
        return AssistantSettings(
            loading = state.loading,
            saving = state.saving,
            testing = state.testing,
            services = state.catalog?.let { catalog ->
                providers.map { provider ->
                    val modelIds = if (provider.relay) emptyList() else {
                        val saved = provider.savedEndpoint(state.configuration)
                        (provider.models(catalog).map { it.remoteId } + state.configuration.bindings
                            .filter { it.endpointId == saved?.id }.map { it.remoteModelId }).distinct()
                    }
                    AssistantService(provider.label, modelIds)
                }
            }.orEmpty(),
            service = providers.firstOrNull { it.dialect == endpoint?.dialect }?.label.orEmpty(),
            address = endpoint?.baseUrl?.toString().orEmpty(),
            model = binding?.remoteModelId.orEmpty(),
            keySaved = endpoint?.credential != null,
            tested = probe?.let { it == AdvisorProbeResult.PASSED },
            error = localError ?: state.error?.toAssistantError() ?: probe?.toAssistantError(),
        )
    }
}

private enum class SettingsProvider(val id: String, val label: String, val dialect: AdvisorDialect, val relay: Boolean = false) {
    OPENAI("openai", "OpenAI", AdvisorDialect.OPENAI),
    DEEPSEEK("deepseek", "DeepSeek", AdvisorDialect.DEEPSEEK),
    ANTHROPIC("anthropic", "Anthropic", AdvisorDialect.ANTHROPIC),
    GOOGLE("google", "Google", AdvisorDialect.GOOGLE),
    OPENROUTER("openrouter", "OpenRouter", AdvisorDialect.OPENROUTER, relay = true),
    CUSTOM("custom", "Custom", AdvisorDialect.GENERIC_CHAT, relay = true);

    fun models(catalog: ModelCatalog) = catalog.models.values.filter { it.provider == id }

    fun defaultAddress(catalog: ModelCatalog): HttpUrl? = catalog.providers[id]?.baseUrl
        ?: if (this == OPENROUTER) "https://openrouter.ai/api/v1".toHttpUrlOrNull() else null

    fun savedEndpoint(config: AdvisorConfiguration, address: HttpUrl? = null): EndpointConfig? =
        config.selected()?.first?.takeIf { it.dialect == dialect }
            ?: config.endpoints.lastOrNull { it.dialect == dialect && it.baseUrl == address }
            ?: config.endpoints.lastOrNull { it.dialect == dialect }

    fun newEndpoint(catalog: ModelCatalog, address: HttpUrl): EndpointConfig {
        val template = catalog.providers[id]
        val transport = template?.transports?.first() ?: AdvisorTransport.CHAT
        val wire = catalog.transports.getValue(transport)
        return EndpointConfig(
            UUID.randomUUID().toString(), 0, label, address, transport, dialect, wire.auth,
            schemaMode = template?.schemaMode ?: AdvisorSchemaMode.PROMPT_JSON,
            requiredHeaders = wire.headers,
            chatOutputLimit = if (this == CUSTOM) ChatOutputLimit.MAX_TOKENS else null,
        )
    }

    companion object {
        fun offered(catalog: ModelCatalog) = entries.filter { it.relay || it.id in catalog.providers }
    }
}

private fun AdvisorSettingsError.toAssistantError() = when (this) {
    AdvisorSettingsError.LOAD -> AssistantError.Configuration
    AdvisorSettingsError.SAVE, AdvisorSettingsError.CONFLICT -> AssistantError.Save
    AdvisorSettingsError.CONFIGURATION -> AssistantError.Configuration
    AdvisorSettingsError.CREDENTIAL -> AssistantError.Auth
}

private fun AdvisorProbeResult.toAssistantError() = when (this) {
    AdvisorProbeResult.PASSED -> null
    AdvisorProbeResult.AUTH -> AssistantError.Auth
    AdvisorProbeResult.ADDRESS -> AssistantError.Address
    AdvisorProbeResult.RATE_LIMIT -> AssistantError.RateLimit
    AdvisorProbeResult.TIMEOUT -> AssistantError.Timeout
    AdvisorProbeResult.NETWORK -> AssistantError.Network
    AdvisorProbeResult.REFUSED -> AssistantError.Refused
    AdvisorProbeResult.INVALID -> AssistantError.Invalid
    AdvisorProbeResult.CONFIGURATION -> AssistantError.Configuration
}
