package com.dearded.spektralab.advisor.network

import com.dearded.spektralab.advisor.domain.AdviceRequest
import com.dearded.spektralab.advisor.session.TaskClarification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One explicit operation, one transport. No catalog status is upgraded by a successful response. */
class AdvisorNetworkGateway(private val http: AdvisorHttpClient, private val credentials: CredentialStore,
                            private val catalog: ModelCatalog, adapters: List<AdviceAdapter>) {
    private val adapters = adapters.associateBy { it.transport }
    init { require(this.adapters.size == adapters.size) }

    fun adapterVersion(transport: AdvisorTransport): String = requireNotNull(adapters[transport]).version

    fun validate(endpoint: EndpointConfig, binding: ModelBinding) {
        val adapter = requireNotNull(adapters[endpoint.transport])
        CapabilityResolver.resolve(endpoint, binding, catalog, adapter.version)
        adapter.encode(endpoint, binding, AdvisorConnectionProbe.request)
    }

    suspend fun generate(endpoint: EndpointConfig, binding: ModelBinding, request: AdviceRequest,
                         clarification: TaskClarification? = null): AdvisorReply {
        val frozen = endpoint.copy(requiredHeaders = endpoint.requiredHeaders.toMap())
        val adapter = requireNotNull(adapters[frozen.transport]) { "Transport is not configured" }
        CapabilityResolver.resolve(frozen, binding, catalog, adapter.version)
        require(!binding.visionEnabled)
        val body = adapter.encode(frozen, binding, request, clarification)
        val response = http.post(frozen, binding, body, credentials)
        return withContext(Dispatchers.Default) { adapter.decode(response, request.schemaVersion) }
    }
}
