package com.dearded.spektralab.advisor.session

import com.dearded.spektralab.Look
import com.dearded.spektralab.advisor.domain.AdviceConstraints
import com.dearded.spektralab.advisor.domain.AdviceParameter
import com.dearded.spektralab.advisor.domain.AdviceResult
import com.dearded.spektralab.advisor.domain.ParameterChange
import com.dearded.spektralab.advisor.domain.effectiveValueChanged

/** Fixed local increments; a trial is neither a photo assessment nor a model response. */
internal enum class LocalAdvicePreset(private val parameter: AdviceParameter, private val delta: Double) {
    BRIGHTEN(AdviceParameter.EXPOSURE, .35),
    SOFTEN(AdviceParameter.CONTRAST, -8.0);

    fun suggest(look: Look, allowed: AdviceConstraints): AdviceResult {
        val range = allowed.ranges[parameter] ?: return AdviceResult.NoChange
        val before = parameter.read(look)
        val desired = before + delta
        val target = if (delta > 0) minOf(desired, range.maximum) else maxOf(desired, range.minimum)
        if (target !in range || (target - before) * delta <= 0.0) return AdviceResult.NoChange
        val current = parameter.read(look.resolved())
        val proposed = parameter.read(parameter.write(look, target).resolved())
        if (!effectiveValueChanged(current, proposed)) return AdviceResult.NoChange
        return AdviceResult.Proposal(listOf(ParameterChange(parameter.id, target)))
    }
}
