package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdvisorParameterTest {
    private val catalog = Catalog.EMPTY
    private val ticket = RequestTicket(1, 2, 3, 4, 5, 0)
    private val base = catalog.normalize(Edit.NONE.copy(cropX = .1, cropW = .8,
        rawExposureEv = .75, look = Look(exposure = .3, palette = 50.0, tone = 25.0,
            colour = -30.0, toneHome = 5.0, colourHome = -10.0,
            lut = "private-local-id", masks = listOf(EditMask(tone = MaskTone(exposure = .2))))))

    private fun project(changes: List<ParameterChange>, edit: Edit = base,
                        constraints: AdviceConstraints = AdviceConstraints.all(),
                        current: RequestTicket = ticket) =
        AdviceProjection.candidate(EditSnapshot(edit, ticket), changes, constraints, catalog, current)

    private fun fails(code: AdviceError, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: AdviceFailure) { assertEquals(code, e.code) }
    }

    @Test fun registryMirrorMatchesTheVersionedAssetExactly() {
        val root = JSONObject(File("src/main/assets/advisor/parameter-registry.v1.json").readText())
        assertEquals(AdviceParameter.VERSION, root.getString("registry_version"))
        assertEquals(AdviceParameter.EPSILON, root.getDouble("comparison_epsilon"), 0.0)
        val rows = root.getJSONArray("parameters")
        assertEquals(AdviceParameter.entries.size, rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val parameter = AdviceParameter.entries[index]
            assertEquals(parameter.id, row.getString("id"))
            assertEquals(parameter.minimum, row.getDouble("minimum"), 0.0)
            assertEquals(parameter.maximum, row.getDouble("maximum"), 0.0)
            assertEquals(parameter.unit, row.getString("unit"))
            assertEquals(parameter.step, row.getDouble("display_step_hint"), 0.0)
        }
    }

    @Test fun sparseAbsoluteValuesPreserveAllOtherPhotoAndLookFields() {
        val candidate = project(listOf(ParameterChange("layer2.temperature", 8.1234567)))
        assertEquals(base.copy(look = base.look.copy(temperature = 8.1234567)), candidate.edit)
        assertEquals(4.06172835, candidate.edit.look.resolved().temperature, 1e-10)
        assertEquals(base.look.resolved().shadows, candidate.edit.look.resolved().shadows, 0.0)
        val zero = project(listOf(ParameterChange("layer2.exposure", 0.0)))
        assertEquals(0.0, zero.edit.look.exposure, 0.0)
    }

    @Test fun allBoundsAreAcceptedWithoutClampingAndOutsideValuesRejected() {
        val input = catalog.normalize(Edit.NONE)
        for (parameter in AdviceParameter.entries) {
            for (value in listOf(parameter.minimum, parameter.maximum)) {
                val candidate = project(listOf(ParameterChange(parameter.id, value)), input)
                assertEquals(value, parameter.read(candidate.edit.look), 0.0)
            }
            for (value in listOf(parameter.minimum - .001, parameter.maximum + .001, Double.NaN, Double.POSITIVE_INFINITY))
                fails(AdviceError.OUT_OF_RANGE) { project(listOf(ParameterChange(parameter.id, value)), input) }
        }
    }

    @Test fun duplicateUnknownAndLockedChangesRejectTheWholeProposal() {
        fails(AdviceError.DUPLICATE_PARAMETER) { project(listOf(ParameterChange("layer2.tint", 3.0), ParameterChange("layer2.tint", 4.0))) }
        fails(AdviceError.UNKNOWN_PARAMETER) { project(listOf(ParameterChange("engine.exposureEv", 1.0))) }
        fails(AdviceError.LOCK_CONFLICT) { project(listOf(ParameterChange("layer2.tint", 3.0)), constraints = AdviceConstraints(emptyMap(), 0)) }
        fails(AdviceError.LOCK_CONFLICT) { project(listOf(ParameterChange("layer2.tint", 3.0)), constraints =
            AdviceConstraints(mapOf(AdviceParameter.TINT to ParameterRange(-1.0, 1.0)), 0)) }
    }

    @Test fun paletteZeroAndClampedPadChangesCannotPretendToHaveEffect() {
        fails(AdviceError.NO_EFFECT) { project(listOf(ParameterChange("layer2.temperature", 8.0)), base.copy(look = Look(palette = 0.0))) }
        fails(AdviceError.NO_EFFECT) { project(listOf(ParameterChange("layer2.saturation", 95.0)),
            base.copy(look = Look(saturation = 90.0, colour = 100.0))) }
    }

    @Test fun aDifferentStoredValueWithIdenticalRenderInputsIsNotADraft() {
        val input = catalog.normalize(Edit.NONE.copy(look = Look(contrast = 40.0)))
        val target = 40.0000015
        assertTrue(target - input.look.contrast > AdviceParameter.EPSILON)
        assertArrayEquals(input.look.values(), input.look.copy(contrast = target).values(), 0f)
        fails(AdviceError.NO_EFFECT) { project(listOf(ParameterChange(AdviceParameter.CONTRAST.id, target)), input) }

        val next = project(listOf(ParameterChange(AdviceParameter.CONTRAST.id, 40.1)), input)
        assertEquals(40.1, next.edit.look.contrast, 0.0)
        assertFalse(input.look.values().contentEquals(next.edit.look.values()))
    }

    @Test fun normalizationCannotChangeAProtectedField() {
        fails(AdviceError.LOCK_CONFLICT) { project(listOf(ParameterChange("layer2.tint", 8.0)), base.copy(filmEdge = true)) }
    }

    @Test fun newRevisionRejectsEvenAnIdenticalValueSnapshot() {
        fails(AdviceError.STALE_RESULT) { project(listOf(ParameterChange("layer2.tint", 8.0)), current = ticket.copy(formalRevision = 3)) }
        fails(AdviceError.STALE_RESULT) { project(listOf(ParameterChange("layer2.tint", 8.0)), current = ticket.copy(endpointRevision = 1)) }
    }

    @Test fun requestProjectionDoesNotContainLocalResourceOrPhotoIdentity() {
        val request = AdviceRequest.from(EditSnapshot(base, ticket), AdviceConstraints.all(), "less saturated")
        assertEquals(base.look.resolved().saturation, request.effective["layer2.saturation"]!!, 0.0)
        assertTrue(request.dependencies.hasLut)
        assertFalse(request.toString().contains("private-local-id"))
        assertFalse(request.javaClass.declaredFields.any { it.type == Edit::class.java || it.type == RequestTicket::class.java })
    }
}
