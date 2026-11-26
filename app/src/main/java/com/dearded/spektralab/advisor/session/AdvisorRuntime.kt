package com.dearded.spektralab.advisor.session

import android.content.Context
import com.dearded.spektralab.advisor.domain.AdviceFailure
import com.dearded.spektralab.advisor.domain.AdviceResult
import com.dearded.spektralab.advisor.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AdvisorSettingsError { LOAD, SAVE, CONFLICT, CONFIGURATION, CREDENTIAL }
enum class AdvisorProbeResult { PASSED, AUTH, ADDRESS, RATE_LIMIT, TIMEOUT, NETWORK, REFUSED, INVALID, CONFIGURATION }
data class AdvisorRuntimeState(val loading: Boolean = true, val saving: Boolean = false, val testing: Boolean = false,
    val catalog: ModelCatalog? = null, val configuration: AdvisorConfiguration = AdvisorConfiguration(),
    val error: AdvisorSettingsError? = null, val probeResult: AdvisorProbeResult? = null, val probeUsage: AdvisorTokenUsage? = null,
    val configurationReady: Boolean = false) {
    val selected get() = configuration.selected()
    val requestSelection get() = selected.takeIf { configurationReady }
    val probePassed get() = probeResult == AdvisorProbeResult.PASSED
}

/** Synchronous disk boundary, called only on IO. A failed settings write removes its new orphan secret. */
internal class AdvisorSettingsService(val catalog: ModelCatalog, val gateway: AdvisorNetworkGateway,
                                     private val store: AdvisorConfigurationStore, private val credentials: CredentialStore) {
    fun load() = store.load()

    fun save(expected: Long, endpoint: EndpointConfig, binding: ModelBinding, replacement: CharArray?): AdvisorConfiguration {
        var created: CredentialRef? = null
        try {
            val previous = store.load()
            if (previous.revision != expected) throw AdvisorConfigurationConflict()
            val old = previous.endpoints.find { it.id == endpoint.id }
            // An editor may preserve a reference only from the saved configuration and same origin.
            val retained = old?.credential?.takeIf { old.origin == endpoint.origin && it == endpoint.credential }
            var target = endpoint.copy(credential = retained)
            gateway.validate(target, binding)
            if (replacement != null) {
                created = credentials.store(target, replacement)
                target = target.copy(credential = created)
            }
            credentials.use(target) { }
            val next = store.save(expected, previous.copy(endpoints = previous.endpoints.filterNot { it.id == target.id } + target,
                bindings = previous.bindings.filterNot { it.id == binding.id } + binding, selectedBindingId = binding.id))
            old?.credential?.takeIf { it.id != target.credential?.id }?.let { credentials.remove(it) }
            return next
        } catch (failure: Exception) {
            created?.let { credentials.remove(it) }
            throw failure
        } finally { replacement?.fill('\u0000') }
    }

    fun select(expected: Long, id: String?): AdvisorConfiguration {
        val previous = store.load()
        return store.save(expected, previous.copy(selectedBindingId = id))
    }

    fun remove(expected: Long, bindingId: String): AdvisorConfiguration {
        val previous = store.load()
        val binding = previous.bindings.single { it.id == bindingId }
        val bindings = previous.bindings.filterNot { it.id == bindingId }
        val unused = bindings.none { it.endpointId == binding.endpointId }
        val removed = previous.endpoints.find { it.id == binding.endpointId }.takeIf { unused }
        val next = store.save(expected, previous.copy(bindings = bindings,
            endpoints = previous.endpoints.filterNot { unused && it.id == binding.endpointId },
            selectedBindingId = previous.selectedBindingId.takeIf { it != bindingId }))
        removed?.credential?.let { credentials.remove(it) }
        return next
    }

    suspend fun probe(configuration: AdvisorConfiguration): AdvisorReply {
        val (endpoint, binding) = requireNotNull(configuration.selected())
        return gateway.generate(endpoint, binding, AdvisorConnectionProbe.request)
    }

    companion object {
        fun create(context: Context): AdvisorSettingsService {
            val catalog = context.assets.open("advisor/model-catalog.v1.json").bufferedReader().use { ModelCatalog.parse(it.readText()) }
            val schema = context.assets.open("advisor/advice-result.v2.schema.json").bufferedReader().use { it.readText() }
            val directional = context.assets.open("advisor/directional-light.v3.schema.json").bufferedReader().use { it.readText() }
            val credentials = CredentialStore(context)
            val gateway = AdvisorNetworkGateway(AdvisorHttpClient(), credentials, catalog,
                listOf(ResponsesAdapter(schema, directional), ChatAdapter(schema, directional), MessagesAdapter(schema, directional),
                    InteractionsAdapter(schema, directional), GenerateContentAdapter(schema, directional)))
            return AdvisorSettingsService(catalog, gateway, AdvisorConfigurationStore(context), credentials)
        }
    }
}

/** Main-thread owner. Loading/saving/selecting never dispatches a model request. */
class AdvisorRuntime internal constructor(private val scope: CoroutineScope,
    private val serviceFactory: () -> AdvisorSettingsService, private val connect: (AdviceConnection?) -> Unit) {
    constructor(context: Context, scope: CoroutineScope, controller: AdviceSessionController) :
        this(scope, { AdvisorSettingsService.create(context.applicationContext) }, controller::configure)

    private val _state = MutableStateFlow(AdvisorRuntimeState())
    val state: StateFlow<AdvisorRuntimeState> = _state
    private var service: AdvisorSettingsService? = null
    private var job: Job? = null

    init { reload() }

    fun reload() {
        if (job?.isActive == true) return
        connect(null)
        _state.value = _state.value.copy(loading = true, error = null, testing = false, probeResult = null,
            probeUsage = null, configurationReady = false)
        job = scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val active = service ?: serviceFactory()
                    active to active.load()
                }
                service = loaded.first
                publish(loaded.second)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { _state.value = _state.value.copy(loading = false, error = AdvisorSettingsError.LOAD) }
        }
    }

    fun save(expected: Long, endpoint: EndpointConfig, binding: ModelBinding, secret: CharArray?) {
        if (!editable()) { secret?.fill('\u0000'); return }
        val owned = secret?.copyOf()
        secret?.fill('\u0000')
        mutate { it.save(expected, endpoint, binding, owned) }
        job?.invokeOnCompletion { owned?.fill('\u0000') }
    }
    fun select(bindingId: String?) { if (editable()) mutate { it.select(state.value.configuration.revision, bindingId) } }
    fun remove(bindingId: String) { if (editable()) mutate { it.remove(state.value.configuration.revision, bindingId) } }

    private fun editable() = service != null && !state.value.loading && !state.value.saving && state.value.error != AdvisorSettingsError.LOAD

    private fun mutate(action: (AdvisorSettingsService) -> AdvisorConfiguration) {
        val active = checkNotNull(service)
        val previousJob = job
        previousJob?.cancel()
        connect(null)
        _state.value = state.value.copy(saving = true, testing = false, error = null, probeResult = null,
            probeUsage = null, configurationReady = false)
        job = scope.launch {
            previousJob?.join()
            try {
                val config = withContext(NonCancellable + Dispatchers.IO) { action(active) }
                publish(config)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _state.value = state.value.copy(saving = false, error = when (e) {
                    is CredentialUnavailable -> AdvisorSettingsError.CREDENTIAL
                    is AdvisorConfigurationConflict -> AdvisorSettingsError.CONFLICT
                    is IllegalArgumentException -> AdvisorSettingsError.CONFIGURATION
                    else -> AdvisorSettingsError.SAVE
                })
                // Failure is explicit. Require successful save/reload before reconnecting.
            }
        }
    }

    private fun publish(config: AdvisorConfiguration) {
        val active = checkNotNull(service)
        connect(AdviceConnection.from(config, active.catalog.version, active.gateway))
        _state.value = AdvisorRuntimeState(loading = false, catalog = active.catalog, configuration = config,
            configurationReady = true)
    }

    fun testConnection() {
        if (!editable() || state.value.testing || state.value.requestSelection == null) return
        val active = checkNotNull(service)
        val config = state.value.configuration
        connect(null)
        _state.value = state.value.copy(testing = true, error = null, probeResult = null, probeUsage = null)
        job = scope.launch {
            val (result, usage) = try {
                val reply = active.probe(config)
                (if (reply.result == AdviceResult.NoChange) AdvisorProbeResult.PASSED else AdvisorProbeResult.INVALID) to reply.usage
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { probeFailure(e) to null }
            ensureActive()
            _state.value = state.value.copy(testing = false, probeResult = result, probeUsage = usage)
            connect(AdviceConnection.from(config, active.catalog.version, active.gateway))
        }
    }

    private fun probeFailure(error: Exception): AdvisorProbeResult = when (error) {
        is CredentialUnavailable -> AdvisorProbeResult.AUTH
        is AdvisorNetworkFailure -> when (error.category) {
            AdvisorNetworkError.AUTH -> AdvisorProbeResult.AUTH
            AdvisorNetworkError.NOT_FOUND, AdvisorNetworkError.REDIRECT -> AdvisorProbeResult.ADDRESS
            AdvisorNetworkError.RATE_LIMIT -> AdvisorProbeResult.RATE_LIMIT
            AdvisorNetworkError.TIMEOUT -> AdvisorProbeResult.TIMEOUT
            AdvisorNetworkError.TOO_LARGE, AdvisorNetworkError.INVALID_RESPONSE -> AdvisorProbeResult.INVALID
            AdvisorNetworkError.SERVER, AdvisorNetworkError.HTTP, AdvisorNetworkError.TRANSPORT -> AdvisorProbeResult.NETWORK
        }
        is AdvisorProtocolFailure -> if (error.category == AdvisorCompletionError.REFUSED) AdvisorProbeResult.REFUSED
            else AdvisorProbeResult.INVALID
        is AdviceFailure -> AdvisorProbeResult.INVALID
        is IllegalArgumentException -> AdvisorProbeResult.CONFIGURATION
        else -> AdvisorProbeResult.NETWORK
    }

    fun cancelTest() {
        if (!state.value.testing) return
        job?.cancel()
        _state.value = state.value.copy(testing = false, probeResult = null, probeUsage = null)
        service?.let { connect(AdviceConnection.from(state.value.configuration, it.catalog.version, it.gateway)) }
    }
}
