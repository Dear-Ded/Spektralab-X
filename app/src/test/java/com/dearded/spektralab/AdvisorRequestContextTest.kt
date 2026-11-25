package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.*
import com.dearded.spektralab.advisor.session.*
import com.dearded.spektralab.advisor.network.StrictResultParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdvisorRequestContextTest {
    private val ticket = RequestTicket(123, 456, 7, 8, 9, 2, endpointId = "private-endpoint")
    private val edit = Catalog.EMPTY.normalize(Edit.NONE.copy(look = Look(palette = 60.0, tone = 15.0,
        toneHome = -2.0, lut = "content://private/local-lut")))
    private val constraints = AdviceConstraints(mapOf(AdviceParameter.EXPOSURE to ParameterRange(-.5, .5)), 2)

    @Test fun serializedProjectionContainsOnlyAllowedContextAndEscapesGoal() {
        val goal = "less contrast\n\"},\"uri\":\"untrusted text\""
        val dto = AdviceRequest.from(EditSnapshot(edit, ticket), constraints, goal)
        val text = RequestContextBuilder.parameters(dto)
        val json = JSONObject(text)
        assertEquals(goal, json.getString("goal"))
        assertEquals(setOf("schema_version", "registry_version", "input_mode", "goal", "stored", "effective",
            "allowed_parameters", "locked_parameters", "read_only"), json.keys().asSequence().toSet())
        assertEquals(7, json.getJSONArray("locked_parameters").length())
        assertEquals(edit.look.resolved().contrast, json.getJSONObject("effective").getDouble("layer2.contrast"), 0.0)
        assertTrue(json.getJSONObject("read_only").getBoolean("has_lut"))
        for (privateValue in listOf("private-profile", "private-endpoint", "content://private/local-lut")) assertFalse(text.contains(privateValue))
        assertFalse(json.has("image")); assertFalse(json.has("statistics")); assertFalse(json.has("history"))
    }

    @Test fun wrongConstraintRevisionAndInvalidDtoCannotBeSent() {
        try { AdviceRequest.from(EditSnapshot(edit, ticket.copy(constraintsRevision = 1)), constraints, "test"); fail("stale locks") }
        catch (_: IllegalArgumentException) { }
        val dto = AdviceRequest.from(EditSnapshot(edit, ticket), constraints, "test")
        for (invalid in listOf(dto.copy(stored = dto.stored + ("private" to 2.0)),
            dto.copy(stored = dto.stored + ("layer2.tint" to Double.NaN)))) {
            try { RequestContextBuilder.parameters(invalid); fail("invalid projection") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun statisticsModeIsExplicitBoundedAndUnavailableDoesNotReuseAnyMeasurements() {
        val snapshot = EditSnapshot(edit, ticket)
        val unavailable = AdviceStatistics(ticket.formalRevision)
        val json = JSONObject(RequestContextBuilder.parameters(AdviceRequest.from(snapshot, constraints, "Synthetic", unavailable)))
        assertEquals("statistics", json.getString("input_mode"))
        val stats = json.getJSONObject("current_draft_statistics")
        assertFalse(stats.getBoolean("available")); assertEquals(0, stats.getInt("sample_width"))
        assertEquals(setOf("domain", "measurement_version", "available", "sample_width", "sample_height"),
            stats.keys().asSequence().toSet())
        assertEquals("display_srgb_encoded", stats.getString("domain")); assertFalse(stats.has("red_bins16"))
        val bins = List(16) { if (it == 8) 1.0 else 0.0 }
        val values = AdviceStatisticsValues(listOf(.5, .5, .5), 0.0, 0.0, bins, bins, bins,
            List(64) { if (it == 42) 1.0 else 0.0 })
        val available = AdviceStatistics(ticket.formalRevision, 18, 32, 32, values)
        val text = RequestContextBuilder.parameters(AdviceRequest.from(snapshot, constraints, "Synthetic", available))
        val measured = JSONObject(text).getJSONObject("current_draft_statistics")
        assertTrue(measured.getBoolean("available")); assertEquals(64, measured.getJSONArray("rgb_bins4x4x4_r_major").length())
        val otherRevision = snapshot.copy(ticket = ticket.copy(formalRevision = 987))
        val equivalent = AdviceRequest.from(otherRevision, constraints, "Synthetic",
            available.copy(sourceRevision = 987, sampleRevision = 64))
        assertEquals(text, RequestContextBuilder.parameters(equivalent))
        assertFalse(text.contains("private-profile")); assertFalse(text.contains("content://"))
        try { AdviceRequest.from(snapshot, constraints, "Synthetic", available.copy(sourceRevision = 0)); fail("stale sample") }
        catch (_: IllegalArgumentException) { }
        val parameterOnly = JSONObject(RequestContextBuilder.parameters(AdviceRequest.from(snapshot, constraints, "Synthetic")))
        assertEquals("parameters", parameterOnly.getString("input_mode")); assertFalse(parameterOnly.has("current_draft_statistics"))
    }

    @Test fun questionsRequireExplicitAnswersAndStopAfterTwoWithoutWritingParameters() {
        val policy = LocalQuestionPolicy()
        val question = AdviceResult.Question("text", "Which adjustment?", emptyList())
        val dto = AdviceRequest.from(EditSnapshot(edit, ticket), constraints, "test")
        repeat(2) {
            assertTrue(policy.offer(question))
            val answer = policy.answer("A little warmer")
            val json = JSONObject(RequestContextBuilder.parameters(dto, answer))
            assertEquals("A little warmer", json.getJSONObject("clarification").getString("answer"))
            assertNull(policy.question)
            assertEquals(dto.stored.getValue("layer2.temperature"), json.getJSONObject("stored").getDouble("layer2.temperature"), 0.0)
        }
        assertFalse(policy.offer(question))
        policy.reset(); assertTrue(policy.offer(question))
    }

    @Test fun sceneEvidenceRequiresExplicitOptInAndDoesNotChangeParameterAuthority() {
        val snapshot = EditSnapshot(edit, ticket)
        val bins = List(16) { if (it == 8) 1.0 else 0.0 }
        val values = AdviceStatisticsValues(listOf(.5, .5, .5), 0.0, 0.0, bins, bins, bins,
            List(64) { if (it == 42) 1.0 else 0.0 })
        val base = AdviceStatistics(ticket.formalRevision, 18, 8, 8, values, BrightSide.RIGHT)
        val reference = SceneReference(.25, .25, .5, .5, 1.0 / 16, .4, 0.0, .1, .3)
        val measured = base.copy(scene = SceneEvidence(4, 4, List(16) { if (it % 4 == 3) .75 else .4 }, reference))
        for (side in listOf(null, BrightSide.RIGHT)) {
            val withoutScene = AdviceRequest.from(snapshot, constraints, "Synthetic", base, brightSide = side)
            val default = AdviceRequest.from(snapshot, constraints, "Synthetic", measured, brightSide = side)
            assertEquals(RequestContextBuilder.parameters(withoutScene), RequestContextBuilder.parameters(default))
            assertEquals(RequestContextBuilder.instructionFor(withoutScene), RequestContextBuilder.instructionFor(default))

            val allowed = AdviceRequest.from(snapshot, constraints, "Synthetic", measured,
                brightSide = side, includeSceneEvidence = true)
            if (side != null) {
                assertEquals(RequestContextBuilder.parameters(default), RequestContextBuilder.parameters(allowed))
                assertEquals(RequestContextBuilder.instructionFor(default), RequestContextBuilder.instructionFor(allowed))
                continue
            }
            val input = JSONObject(RequestContextBuilder.parameters(allowed))
            val scene = input.remove("scene_evidence") as JSONObject
            assertTrue(input.similar(JSONObject(RequestContextBuilder.parameters(default))))
            assertEquals(setOf("measurement_version", "domain", "coordinates", "columns", "rows",
                "tile_luma_means", "reference"), scene.keys().asSequence().toSet())
            assertEquals(SceneEvidence.VERSION, scene.getString("measurement_version"))
            assertEquals(AdviceStatistics.DOMAIN, scene.getString("domain"))
            assertEquals("rendered_content_top_left_normalized", scene.getString("coordinates"))
            assertEquals(4, scene.getInt("rows")); assertEquals(4, scene.getInt("columns"))
            assertEquals(16, scene.getJSONArray("tile_luma_means").length())
            assertEquals(.75, scene.getJSONArray("tile_luma_means").getDouble(3), 0.0)
            val roi = scene.getJSONObject("reference")
            assertEquals(setOf("bounds", "area_fraction", "luma_mean", "near_black_fraction", "near_white_fraction",
                "ring_luma_mean"), roi.keys().asSequence().toSet())
            assertEquals(.5, roi.getJSONArray("bounds").getDouble(2), 0.0)
            assertEquals(.3, roi.getDouble("ring_luma_mean"), 0.0)
            assertTrue(RequestContextBuilder.instructionFor(allowed).contains("not a subject or edit mask"))
            val noEvidence = allowed.copy(statistics = null)
            assertFalse(JSONObject(RequestContextBuilder.parameters(noEvidence)).has("scene_evidence"))
        }
    }

    @Test fun generatedParameterBoundaryPayloadsUseTheRealParserAndProjection() {
        val baseline = Catalog.EMPTY.normalize(Edit.NONE)
        val fresh = EditSnapshot(baseline, ticket.copy(constraintsRevision = 0))
        for (p in AdviceParameter.entries) for (value in listOf(p.minimum, p.maximum, p.minimum - .001, p.maximum + .001)) {
            val text = """{"schema_version":2,"status":"proposal","changes":[{"parameter":"${p.id}","target":$value}],"question":{"kind":"none","prompt":"","options":[]}}"""
            if (value in p.minimum..p.maximum) {
                val proposal = StrictResultParser.parse(text) as AdviceResult.Proposal
                val candidate = AdviceProjection.candidate(fresh, proposal.changes, AdviceConstraints.all(), Catalog.EMPTY, fresh.ticket)
                assertEquals(value, p.read(candidate.edit.look), 0.0)
            } else {
                try {
                    val proposal = StrictResultParser.parse(text) as AdviceResult.Proposal
                    AdviceProjection.candidate(fresh, proposal.changes, AdviceConstraints.all(), Catalog.EMPTY, fresh.ticket)
                    fail("outside boundary")
                }
                catch (failure: AdviceFailure) { assertEquals(AdviceError.OUT_OF_RANGE, failure.code) }
            }
        }
    }

    @Test fun clarificationLimitUsesCodePointsAndMatchesTheExecutionPlan() {
        val question = AdviceResult.Question("text", "Which adjustment?", emptyList())
        val supplementary = String(Character.toChars(0x1f600))
        assertEquals(2000, TaskClarification(question, supplementary.repeat(1000)).answer.length)
        try { TaskClarification(question, supplementary.repeat(1001)); fail("oversize clarification") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun wireContextReportsActualValuesAndExplicitRangesWithoutLocalRuleTargets() {
        val locked = AdviceConstraints(mapOf(AdviceParameter.CONTRAST to ParameterRange(4.0, 4.0)), 2)
        val dto = AdviceRequest.from(EditSnapshot(edit, ticket), locked, "Synthetic")
        val text = RequestContextBuilder.parameters(dto)
        val json = JSONObject(text)
        assertEquals(edit.look.contrast, json.getJSONObject("stored").getDouble("layer2.contrast"), 0.0)
        val contrastRange = json.getJSONObject("allowed_parameters").getJSONObject("layer2.contrast")
        assertEquals(4.0, contrastRange.getDouble("minimum"), 0.0)
        assertEquals(4.0, contrastRange.getDouble("maximum"), 0.0)
        assertFalse(json.has("planned_fixed_targets"))
        for (key in listOf("private-profile", "private-endpoint", "ruleId", "baseCandidateId", "applicationGeneration", "history"))
            assertFalse(text.contains(key))
    }
}
