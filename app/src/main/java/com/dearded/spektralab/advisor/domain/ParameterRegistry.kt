package com.dearded.spektralab.advisor.domain

import com.dearded.spektralab.Catalog
import com.dearded.spektralab.Edit
import com.dearded.spektralab.Look
import com.dearded.spektralab.LocalEdits
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** Mirror of assets/advisor/parameter-registry.v1.json, checked against it in tests. */
enum class AdviceParameter(val id: String, val minimum: Double, val maximum: Double,
                           val unit: String, val step: Double) {
    TEMPERATURE("layer2.temperature", -100.0, 100.0, "layer2_blue_amber_offset", .1),
    TINT("layer2.tint", -100.0, 100.0, "layer2_green_magenta_offset", .1),
    EXPOSURE("layer2.exposure", -3.0, 3.0, "EV", .01),
    CONTRAST("layer2.contrast", -50.0, 50.0, "engine_parameter", .1),
    SATURATION("layer2.saturation", -100.0, 100.0, "engine_parameter", .1),
    VIBRANCE("layer2.vibrance", -100.0, 100.0, "engine_parameter", .1),
    HIGHLIGHTS("layer2.highlights", -100.0, 100.0, "engine_parameter", .1),
    SHADOWS("layer2.shadows", -100.0, 100.0, "engine_parameter", .1);

    fun read(look: Look): Double = when (this) {
        TEMPERATURE -> look.temperature; TINT -> look.tint; EXPOSURE -> look.exposure
        CONTRAST -> look.contrast; SATURATION -> look.saturation; VIBRANCE -> look.vibrance
        HIGHLIGHTS -> look.highlights; SHADOWS -> look.shadows
    }

    fun write(look: Look, value: Double): Look = when (this) {
        TEMPERATURE -> look.copy(temperature = value); TINT -> look.copy(tint = value)
        EXPOSURE -> look.copy(exposure = value); CONTRAST -> look.copy(contrast = value)
        SATURATION -> look.copy(saturation = value); VIBRANCE -> look.copy(vibrance = value)
        HIGHLIGHTS -> look.copy(highlights = value); SHADOWS -> look.copy(shadows = value)
    }

    companion object {
        const val VERSION = "layer2.scalars.1"
        const val EPSILON = 1e-6
        fun of(id: String) = entries.firstOrNull { it.id == id }
        fun values(look: Look): Map<String, Double> = entries.associate { it.id to it.read(look) }
    }
}

/** Layer 2 consumes resolved scalars as floats, not the doubles in a saved recipe. */
internal fun effectiveValueChanged(before: Double, after: Double): Boolean =
    abs(after - before) > AdviceParameter.EPSILON && after.toFloat() != before.toFloat()

data class ParameterRange(val minimum: Double, val maximum: Double) {
    init { require(minimum.isFinite() && maximum.isFinite() && minimum <= maximum) }
    operator fun contains(value: Double) = value.isFinite() && value >= minimum && value <= maximum
}

/** Missing fields are locked; all producers and validators use this same allowed set. */
class AdviceConstraints(ranges: Map<AdviceParameter, ParameterRange>, val revision: Long) {
    val ranges = ranges.toMap()
    init {
        require(this.ranges.all { (p, range) -> range.minimum >= p.minimum && range.maximum <= p.maximum })
    }
    companion object {
        fun all(revision: Long = 0) = AdviceConstraints(AdviceParameter.entries.associateWith {
            ParameterRange(it.minimum, it.maximum)
        }, revision)
    }
}

data class ParameterChange(val parameter: String, val target: Double)
enum class AdviceError {
    INVALID_JSON, SCHEMA_MISMATCH, INVALID_STATUS_PAYLOAD, UNKNOWN_PARAMETER,
    DUPLICATE_PARAMETER, OUT_OF_RANGE, LOCK_CONFLICT, NO_EFFECT, STALE_RESULT,
    RENDER_FAILED, SAVE_FAILED, PROVIDER_INCOMPLETE, OUTPUT_TOO_LARGE,
    CONFIGURATION_REQUIRED, NETWORK_AUTH, NETWORK_ADDRESS, NETWORK_RATE_LIMIT,
    NETWORK_FAILED, NETWORK_TIMEOUT, PROVIDER_REFUSED, PROVIDER_INVALID,
}
class AdviceFailure(val code: AdviceError) : IllegalArgumentException(code.name)

/** Local identity only. None of these identifiers belongs in the request body. */
data class RequestTicket(
    val photoSessionId: Long,
    val formalRevision: Long,
    val draftRevision: Long,
    val engineGeneration: Long,
    val requestId: Long,
    val constraintsRevision: Long,
    val parameterVersion: String = AdviceParameter.VERSION,
    val renderVersion: Long = 1,
    val styleVersion: Long = 1,
    val endpointId: String = "offline",
    val endpointRevision: Long = 0,
    val credentialRevision: Long = 0,
    val bindingId: String = "offline",
    val bindingRevision: Long = 0,
    val catalogVersion: String = "offline",
    val adapterVersion: String = "offline.1",
    val configurationRevision: Long = 0,
    val inputRevision: Long = 0,
)

data class EditSnapshot(val edit: Edit, val ticket: RequestTicket) {
    val stored get() = AdviceParameter.values(edit.look)
    val effective get() = AdviceParameter.values(edit.look.resolved())
}

/** Only Projection can produce the candidate a session may display. */
class AdviceCandidate internal constructor(val edit: Edit, val changes: List<ParameterChange>,
    val ticket: RequestTicket, val origin: AdviceOrigin? = AdviceOrigin.Local,
    val manuallyAdjusted: Boolean = false, val correctionOf: AdviceCandidate? = null,
    internal val directionalLight: DirectionalLight? = null, internal val directionalPercent: Int = 100) {
    val id: String = java.util.UUID.randomUUID().toString()
    init {
        require(manuallyAdjusted || origin != AdviceOrigin.Manual)
        require(!manuallyAdjusted || correctionOf != null || origin == AdviceOrigin.Manual)
        correctionOf?.let {
            require(manuallyAdjusted && !it.manuallyAdjusted && origin == it.origin)
            require(ticket.photoSessionId == it.ticket.photoSessionId && ticket.formalRevision == it.ticket.formalRevision &&
                ticket.engineGeneration == it.ticket.engineGeneration)
        }
    }
}

object AdviceProjection {
    internal fun directionalCandidate(snapshot: EditSnapshot, light: DirectionalLight, measured: BrightSide,
        origin: AdviceOrigin?): AdviceCandidate {
        if (light.brightSide != measured) throw AdviceFailure(AdviceError.LOCK_CONFLICT)
        return AdviceCandidate(withDirectionalLight(snapshot.edit, light, 100), emptyList(), snapshot.ticket, origin,
            directionalLight = light)
    }

    internal fun canPlaceDirectionalLight(edit: Edit, constraints: AdviceConstraints): Boolean =
        AdviceParameter.EXPOSURE in constraints.ranges && edit.look.masks.size <= LocalEdits.MAX_MASKS - 2 && !edit.filmEdge

    internal fun rescaleDirectional(entry: EditSnapshot, previous: AdviceCandidate, percent: Int,
        ticket: RequestTicket): AdviceCandidate {
        val light = checkNotNull(previous.directionalLight)
        return AdviceCandidate(withDirectionalLight(entry.edit, light, percent), emptyList(), ticket, previous.origin,
            directionalLight = light, directionalPercent = percent)
    }

    /** The editor commits the exact verified edit as one ordinary history step. */
    internal fun commitEdit(entry: EditSnapshot, draft: AdviceCandidate, catalog: Catalog): Edit {
        val light = draft.directionalLight
        val verified = if (light != null) {
            if (draft.directionalPercent == 0) throw AdviceFailure(AdviceError.NO_EFFECT)
            withDirectionalLight(entry.edit, light, draft.directionalPercent)
        } else {
            val changes = AdviceParameter.entries.filter { it.read(draft.edit.look) != it.read(entry.edit.look) }
                .map { ParameterChange(it.id, it.read(draft.edit.look)) }
            candidate(entry, changes, AdviceConstraints.all(entry.ticket.constraintsRevision), catalog, entry.ticket).edit
        }
        if (verified != draft.edit) throw AdviceFailure(AdviceError.LOCK_CONFLICT)
        return verified
    }

    private fun withDirectionalLight(entry: Edit, light: DirectionalLight, percent: Int) =
        entry.copy(look = entry.look.copy(masks = entry.look.masks + light.masks(percent)))

    internal fun strengthChanges(entry: Edit, recommendation: Edit, current: Edit, percent: Int,
        allowed: Map<AdviceParameter, ParameterRange>, manualOverrides: Set<AdviceParameter>): List<ParameterChange> =
        AdviceParameter.entries.mapNotNull { parameter ->
            val range = allowed[parameter] ?: return@mapNotNull null
            if (parameter in manualOverrides) return@mapNotNull null
            val from = parameter.read(entry.look)
            val to = parameter.read(recommendation.look)
            // An imported entry outside the advisor's range cannot form a valid interpolation interval.
            if (from == to || from !in range || to !in range) return@mapNotNull null
            val target = when (percent) {
                0 -> from
                100 -> to
                else -> {
                    val start = BigDecimal.valueOf(from)
                    val delta = BigDecimal.valueOf(to).subtract(start)
                    val value = start.add(delta.multiply(BigDecimal.valueOf(percent.toLong())).movePointLeft(2))
                    val step = BigDecimal.valueOf(parameter.step)
                    value.divide(step, 0, RoundingMode.HALF_UP).multiply(step).toDouble()
                        .coerceIn(range.minimum, range.maximum)
                }
            }
            ParameterChange(parameter.id, target).takeIf { target != parameter.read(current.look) }
        }

    internal fun rescale(previous: AdviceCandidate, changes: List<ParameterChange>, constraints: AdviceConstraints,
        catalog: Catalog, ticket: RequestTicket): AdviceCandidate {
        // A plateau (or an exact return to entry) is a valid strength preview, not a new proposal.
        val edit = projectEdit(EditSnapshot(previous.edit, ticket), changes, constraints, catalog, ticket)
        return AdviceCandidate(edit, changes.toList(), ticket, previous.origin, previous.manuallyAdjusted,
            previous.correctionOf)
    }

    internal fun hasEffectiveChange(before: Edit, after: Edit): Boolean {
        val old = before.look.resolved()
        val next = after.look.resolved()
        return old.masks != next.masks || AdviceParameter.entries.any { effectiveValueChanged(it.read(old), it.read(next)) }
    }

    fun candidate(snapshot: EditSnapshot, changes: List<ParameterChange>, constraints: AdviceConstraints,
        catalog: Catalog, current: RequestTicket, origin: AdviceOrigin? = AdviceOrigin.Local,
        manuallyAdjusted: Boolean = false, correctionOf: AdviceCandidate? = null): AdviceCandidate {
        val edit = projectEdit(snapshot, changes, constraints, catalog, current)
        val before = snapshot.edit.look.resolved()
        val after = edit.look.resolved()
        if (changes.none {
            val parameter = checkNotNull(AdviceParameter.of(it.parameter))
            effectiveValueChanged(parameter.read(before), parameter.read(after))
        }) throw AdviceFailure(AdviceError.NO_EFFECT)
        return AdviceCandidate(edit, changes.toList(), current, origin, manuallyAdjusted, correctionOf)
    }

    internal fun projectEdit(snapshot: EditSnapshot, changes: List<ParameterChange>, constraints: AdviceConstraints,
        catalog: Catalog, current: RequestTicket): Edit {
        if (snapshot.ticket != current || current.constraintsRevision != constraints.revision)
            throw AdviceFailure(AdviceError.STALE_RESULT)
        if (changes.isEmpty() || changes.size > AdviceParameter.entries.size)
            throw AdviceFailure(AdviceError.INVALID_STATUS_PAYLOAD)
        var look = snapshot.edit.look
        val touched = mutableSetOf<AdviceParameter>()
        for (change in changes) {
            val parameter = AdviceParameter.of(change.parameter) ?: throw AdviceFailure(AdviceError.UNKNOWN_PARAMETER)
            if (!touched.add(parameter)) throw AdviceFailure(AdviceError.DUPLICATE_PARAMETER)
            if (!change.target.isFinite() || change.target < parameter.minimum || change.target > parameter.maximum)
                throw AdviceFailure(AdviceError.OUT_OF_RANGE)
            val range = constraints.ranges[parameter] ?: throw AdviceFailure(AdviceError.LOCK_CONFLICT)
            if (change.target !in range) throw AdviceFailure(AdviceError.LOCK_CONFLICT)
            look = parameter.write(look, change.target)
        }
        val candidate = catalog.normalize(snapshot.edit.copy(look = look))
        var restored = candidate.look
        for (parameter in touched) restored = parameter.write(restored, parameter.read(snapshot.edit.look))
        if (candidate.copy(look = restored) != snapshot.edit)
            throw AdviceFailure(AdviceError.LOCK_CONFLICT)
        return candidate
    }
}
