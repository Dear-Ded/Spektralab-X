package com.dearded.spektralab.advisor.domain

import java.util.UUID

/** Local provenance only. A binding revision identifies configuration, not an upstream model release. */
sealed interface AdviceOrigin {
    data object Manual : AdviceOrigin
    data object Local : AdviceOrigin
    data class Model(val bindingId: String, val bindingRevision: Long, val catalogVersion: String,
        val adapterVersion: String, val promptVersion: String, val configurationRevision: Long = 0,
        val endpointRevision: Long = 0) : AdviceOrigin {
        init {
            require(UUID.fromString(bindingId).toString() == bindingId && bindingRevision >= 0)
            require(configurationRevision >= 0 && endpointRevision >= 0)
            require(listOf(catalogVersion, adapterVersion, promptVersion).all { it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) })
        }
        val localVersion get() = "binding.$bindingRevision.config.$configurationRevision.endpoint.$endpointRevision"
    }
}
