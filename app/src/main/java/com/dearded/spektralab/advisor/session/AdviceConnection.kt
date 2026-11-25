package com.dearded.spektralab.advisor.session

import com.dearded.spektralab.advisor.domain.AdviceRequest
import com.dearded.spektralab.advisor.domain.AdviceOrigin
import com.dearded.spektralab.advisor.domain.RequestTicket
import com.dearded.spektralab.advisor.network.*

/** Local identity only. User goals and provider response text are never persisted here. */
data class AdviceConnectionIdentity(
    val configurationRevision: Long, val endpointId: String, val endpointRevision: Long,
    val credentialRevision: Long, val bindingId: String, val bindingRevision: Long,
    val catalogVersion: String, val adapterVersion: String,
) {
    fun attach(ticket: RequestTicket) = ticket.copy(configurationRevision = configurationRevision,
        endpointId = endpointId, endpointRevision = endpointRevision, credentialRevision = credentialRevision,
        bindingId = bindingId, bindingRevision = bindingRevision, catalogVersion = catalogVersion, adapterVersion = adapterVersion)
}

class AdviceConnection internal constructor(val identity: AdviceConnectionIdentity,
    val origin: AdviceOrigin.Model? = null,
    private val sender: suspend (AdviceRequest, TaskClarification?) -> AdvisorReply) {
    suspend fun generate(request: AdviceRequest, clarification: TaskClarification?) = sender(request, clarification)

    companion object {
        fun from(config: AdvisorConfiguration, catalogVersion: String, gateway: AdvisorNetworkGateway): AdviceConnection? {
            val (selected, binding) = config.selected() ?: return null
            val endpoint = selected.copy(requiredHeaders = selected.requiredHeaders.toMap())
            val adapter = gateway.adapterVersion(endpoint.transport)
            val origin = runCatching { AdviceOrigin.Model(binding.id, binding.revision, catalogVersion,
                adapter, RequestContextBuilder.PROMPT_VERSION, config.revision, endpoint.revision) }.getOrNull()
            return AdviceConnection(AdviceConnectionIdentity(config.revision, endpoint.id, endpoint.revision,
                endpoint.credential?.revision ?: 0, binding.id, binding.revision, catalogVersion,
                adapter), origin) { request, clarification ->
                gateway.generate(endpoint, binding, request, clarification)
            }
        }
    }
}
