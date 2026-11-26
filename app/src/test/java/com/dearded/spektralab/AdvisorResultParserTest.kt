package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.*
import com.dearded.spektralab.advisor.network.StrictResultParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdvisorResultParserTest {
    private val none = """{"schema_version":2,"status":"no_change","changes":[],"question":{"kind":"none","prompt":"","options":[]}}"""
    private fun code(text: String): AdviceError {
        try { StrictResultParser.parse(text); fail("must reject") }
        catch (e: AdviceFailure) { return e.code }
        error("unreachable")
    }

    @Test fun allSuppliedCasesUseRealParserAndLookProjection() {
        val text = checkNotNull(javaClass.classLoader!!.getResourceAsStream("advisor/advice-result.v2.cases.json"))
            .bufferedReader().use { it.readText() }
        val cases = JSONObject(text).getJSONArray("cases")
        assertEquals(23, cases.length())
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val context = case.optJSONObject("context_override") ?: JSONObject()
            val actual = try {
                if (!context.optBoolean("provider_completed", true)) throw AdviceFailure(AdviceError.PROVIDER_INCOMPLETE)
                val raw = if (case.has("raw_json")) case.getString("raw_json") else case.getJSONObject("payload").toString()
                when (val parsed = StrictResultParser.parse(raw)) {
                    is AdviceResult.Proposal -> {
                        val constraints = AdviceConstraints(AdviceConstraints.all().ranges.filterKeys {
                            context.optJSONArray("locks")?.let { a -> (0 until a.length()).none { i -> a.getString(i) == it.id } } ?: true
                        }, 0)
                        val edit = Catalog.EMPTY.normalize(Edit.NONE.copy(look = Look(palette =
                            if (context.optString("resolution") == "palette_zero_and_pad_at_home") 0.0 else 100.0)))
                        val ticket = RequestTicket(1, 1, 0, 1, 1, 0)
                        AdviceProjection.candidate(EditSnapshot(edit, ticket), parsed.changes, constraints, Catalog.EMPTY,
                            if (context.optBoolean("ticket_current", true)) ticket else ticket.copy(formalRevision = 2))
                        "ACCEPT_PROPOSAL"
                    }
                    is AdviceResult.Question -> "ACCEPT_QUESTION"
                    AdviceResult.NoChange -> "ACCEPT_NO_CHANGE"
                    AdviceResult.Unsupported -> "ACCEPT_UNSUPPORTED"
                }
            } catch (e: AdviceFailure) { e.code.name }
            assertEquals(case.getString("id"), case.getString("expected"), actual)
        }
    }

    @Test fun strictReaderRejectsDuplicatesEscapesCommentsFencesAndOverflow() {
        for (text in listOf(none + none, "```json\n$none\n```", none.replace("\"kind\":\"none\"", "\"kind\":\"none\",\"k\\u0069nd\":\"none\""),
            none.replace("\"schema_version\":2", "\"schema_version\":1e9999"), "/*comment*/$none"))
            assertEquals(AdviceError.INVALID_JSON, code(text))
        assertEquals(AdviceError.SCHEMA_MISMATCH, code("[$none]"))
        for (value in listOf("true", "null", "\"2\""))
            assertEquals(AdviceError.SCHEMA_MISMATCH, code(none.replace("\"schema_version\":2", "\"schema_version\":$value")))
    }

    @Test fun lengthsCountCodepointsAndLimitsRejectBeforeCandidateConstruction() {
        fun question(prompt: String) = JSONObject(none).put("status", "needs_input")
            .put("question", JSONObject().put("kind", "text").put("prompt", prompt).put("options", org.json.JSONArray())).toString()
        val nonBmp = String(Character.toChars(0x1f600))
        assertTrue(StrictResultParser.parse(question(nonBmp.repeat(160))) is AdviceResult.Question)
        assertEquals(AdviceError.INVALID_STATUS_PAYLOAD, code(question(nonBmp.repeat(161))))
        assertEquals(AdviceError.INVALID_STATUS_PAYLOAD, code(question("  ")))
        assertEquals(AdviceError.OUTPUT_TOO_LARGE, code(" ".repeat(StrictResultParser.MAX_BYTES + 1)))
        assertEquals(AdviceError.INVALID_JSON, code("[".repeat(18) + "0" + "]".repeat(18)))
    }

    @Test fun everyNonProposalStateRejectsChangesAndNoQuestionMustBeEmpty() {
        val change = org.json.JSONArray().put(JSONObject().put("parameter", "layer2.tint").put("target", 1))
        for (status in listOf("needs_input", "no_change", "unsupported"))
            assertEquals(AdviceError.INVALID_STATUS_PAYLOAD, code(JSONObject(none).put("status", status).put("changes", change).toString()))
        assertEquals(AdviceError.INVALID_STATUS_PAYLOAD, code(none.replace("\"prompt\":\"\"", "\"prompt\":\"x\"")))
    }
}
