package com.dearded.spektralab.advisor.domain

/** Deliberate projection. No Edit, URI, history, profile or ticket field can serialize implicitly. */
data class AdviceRequest(
    val schemaVersion: Int,
    val registryVersion: String,
    val goal: String,
    val stored: Map<String, Double>,
    val effective: Map<String, Double>,
    val allowed: Map<String, ParameterRange>,
    val dependencies: ResolutionDependencies,
    val statistics: AdviceStatistics? = null,
    val brightSide: BrightSide? = null,
) {
    companion object {
        fun from(snapshot: EditSnapshot, constraints: AdviceConstraints, goal: String, statistics: AdviceStatistics? = null,
            brightSide: BrightSide? = null): AdviceRequest {
            require(goal.isNotBlank() && goal.codePointCount(0, goal.length) <= 2000)
            require(snapshot.ticket.constraintsRevision == constraints.revision)
            require(statistics == null || statistics.sourceRevision == snapshot.ticket.formalRevision)
            val look = snapshot.edit.look
            return AdviceRequest(if (brightSide == null) 2 else 3, AdviceParameter.VERSION, goal, snapshot.stored, snapshot.effective,
                constraints.ranges.mapKeys { it.key.id }, ResolutionDependencies(
                    look.palette, look.tone, look.colour, look.toneHome, look.colourHome,
                    snapshot.edit.noFilm, look.lut.isNotEmpty(), !look.curves.isIdentity,
                    look.masks.any { it.active }), statistics, brightSide)
        }
    }
}

data class ResolutionDependencies(val palette: Double, val tone: Double, val colour: Double,
                                  val toneHome: Double, val colourHome: Double, val noFilm: Boolean,
                                  val hasLut: Boolean, val hasCurves: Boolean, val hasLocalAdjustments: Boolean)
