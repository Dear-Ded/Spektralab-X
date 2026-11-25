package com.dearded.spektralab.advisor.domain

sealed interface AdviceResult {
    data class Proposal(val changes: List<ParameterChange>, val directionalLight: DirectionalLight? = null) : AdviceResult
    data class Question(val kind: String, val prompt: String, val options: List<Option>) : AdviceResult
    data class Option(val id: String, val label: String)
    data object NoChange : AdviceResult
    data object Unsupported : AdviceResult
}
