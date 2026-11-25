package com.dearded.spektralab.advisor.network

import com.dearded.spektralab.advisor.domain.AdviceResult

/** Explicit offline fixtures, not a model and not an image assessment. */
object LocalAdviceGateway {
    fun brighter(): AdviceResult.Proposal = fixture("layer2.exposure", "0.35")
    fun softer(): AdviceResult.Proposal = fixture("layer2.contrast", "-8.0")

    private fun fixture(parameter: String, target: String): AdviceResult.Proposal =
        StrictResultParser.parse("""{"schema_version":2,"status":"proposal","changes":[{"parameter":"$parameter","target":$target}],"question":{"kind":"none","prompt":"","options":[]}}""") as AdviceResult.Proposal
}
