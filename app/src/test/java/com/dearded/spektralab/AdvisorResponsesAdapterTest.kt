package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.*
import com.dearded.spektralab.advisor.network.*
import com.dearded.spektralab.advisor.session.RequestContextBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AdvisorResponsesAdapterTest {
    private val adapter = ResponsesAdapter(AdvisorWireFixtures.schema)
    private val model = AdvisorWireFixtures.catalog.models.getValue("openai/gpt-6-luna")
    private val binding = ModelBinding("binding", 1, "endpoint", "synthetic-model", model.id,
        reasoningEffort = "low", maxOutputTokens = 2048)

    @Test(timeout = 15000) fun actualRequestCarriesOnlyExplicitStatelessParameterContextAndStrictSchema() = runBlocking {
        AdvisorAdapterFixture().use { f ->
            f.server.enqueue(MockResponse().setBody(AdvisorWireFixtures.responses().toString()))
            val reply = f.gateway(adapter).generate(f.endpoint(), binding, AdvisorWireFixtures.request)
            assertEquals(.25, (reply.result as AdviceResult.Proposal).changes.single().target, 0.0)
            val request = checkNotNull(f.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method); assertEquals("/v1/responses", request.path)
            assertEquals("Bearer synthetic-secret", request.getHeader("Authorization"))
            val json = JSONObject(request.body.readUtf8())
            assertEquals(setOf("model", "instructions", "input", "max_output_tokens", "store", "stream", "reasoning", "text"),
                json.keys().asSequence().toSet())
            assertEquals("synthetic-model", json.getString("model")); assertFalse(json.getBoolean("store"))
            assertFalse(json.getBoolean("stream")); assertEquals(2048, json.getInt("max_output_tokens"))
            assertEquals("low", json.getJSONObject("reasoning").getString("effort"))
            assertEquals(RequestContextBuilder.instruction, json.getString("instructions"))
            val input = json.getJSONArray("input"); assertEquals(1, input.length())
            assertEquals("user", input.getJSONObject(0).getString("role"))
            assertEquals(RequestContextBuilder.parameters(AdvisorWireFixtures.request), input.getJSONObject(0).getString("content"))
            val format = json.getJSONObject("text").getJSONObject("format")
            assertEquals("json_schema", format.getString("type")); assertTrue(format.getBoolean("strict"))
            assertEquals("spektralab_advice_v2", format.getString("name"))
            val expected = JSONObject(AdvisorWireFixtures.schema)
            listOf("\$schema", "\$id", "title", "description").forEach { expected.remove(it) }
            assertTrue(expected.similar(format.getJSONObject("schema")))
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test(timeout = 15000) fun allCatalogOpenAiEffortsReachTheServerAndIllegalCombinationsSendNothing() = runBlocking {
        AdvisorAdapterFixture().use { f ->
            val endpoint = f.endpoint(); val gateway = f.gateway(adapter)
            var count = 0
            for (model in AdvisorWireFixtures.catalog.models.values.filter { it.provider == "openai" }) {
                for (effort in model.profile.efforts) {
                    f.server.enqueue(MockResponse().setBody(AdvisorWireFixtures.responses(AdvisorWireFixtures.noChange).toString()))
                    assertEquals(AdviceResult.NoChange, gateway.generate(endpoint, binding.copy(
                        catalogTemplateId = model.id, reasoningEffort = effort), AdvisorWireFixtures.request).result)
                    val sent = JSONObject(checkNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8())
                    assertEquals(effort, sent.getJSONObject("reasoning").getString("effort")); count++
                }
            }
            for (invalid in listOf(binding.copy(reasoningEffort = "unsupported"), binding.copy(reasoningMode = "disabled"),
                binding.copy(visionEnabled = true))) {
                try { gateway.generate(endpoint, invalid, AdvisorWireFixtures.request); fail("invalid capability sent") }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(count, f.server.requestCount)
        }
    }

    @Test(timeout = 15000) fun unknownModelUsesPromptOnlyAndNeverGuessesReasoningFromName() = runBlocking {
        AdvisorAdapterFixture().use { f ->
            val endpoint = f.endpoint(schema = AdvisorSchemaMode.PROMPT_JSON)
            f.server.enqueue(MockResponse().setBody(AdvisorWireFixtures.responses(AdvisorWireFixtures.noChange).toString()))
            f.gateway(adapter).generate(endpoint, binding.copy(catalogTemplateId = null, reasoningEffort = null), AdvisorWireFixtures.request)
            val json = JSONObject(checkNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8())
            assertFalse(json.has("reasoning")); assertFalse(json.has("text"))
            assertTrue(json.getString("instructions").contains("JSON"))
        }
    }

    @Test fun incompleteRefusalToolsAndAmbiguousMessagesCannotSmuggleValidBusinessJson() {
        fun rejected(root: JSONObject, expected: AdvisorCompletionError) {
            try { adapter.decode(root.toString()); fail("unsafe envelope accepted") }
            catch (failure: AdvisorProtocolFailure) { assertEquals(expected, failure.category) }
        }
        for (status in listOf("incomplete", "failed", "cancelled", "queued", "in_progress"))
            rejected(AdvisorWireFixtures.responses(status = status), AdvisorCompletionError.INCOMPLETE)
        rejected(AdvisorWireFixtures.responses().put("error", JSONObject().put("message", "synthetic-private-error")), AdvisorCompletionError.PROVIDER_ERROR)
        rejected(AdvisorWireFixtures.responses().put("incomplete_details", JSONObject().put("reason", "max_output_tokens")), AdvisorCompletionError.INCOMPLETE)
        rejected(AdvisorWireFixtures.responses().apply {
            getJSONArray("output").getJSONObject(0).put("status", "incomplete")
        }, AdvisorCompletionError.INCOMPLETE)
        rejected(AdvisorWireFixtures.responses().apply {
            getJSONArray("output").getJSONObject(0).getJSONArray("content").put(JSONObject().put("type", "refusal").put("refusal", "synthetic-refusal"))
        }, AdvisorCompletionError.REFUSED)
        for (type in listOf("function_call", "web_search_call", "mcp_call", "unknown"))
            rejected(AdvisorWireFixtures.responses().apply { getJSONArray("output").put(JSONObject().put("type", type)) }, AdvisorCompletionError.TOOL_CALL)
        rejected(AdvisorWireFixtures.responses().put("output", JSONArray().put(JSONObject().put("type", "reasoning").put("summary", AdvisorWireFixtures.proposal))),
            AdvisorCompletionError.NO_FINAL_TEXT)
        rejected(AdvisorWireFixtures.responses().apply { getJSONArray("output").put(AdvisorWireFixtures.message()) }, AdvisorCompletionError.INVALID_ENVELOPE)
        rejected(AdvisorWireFixtures.responses(" "), AdvisorCompletionError.NO_FINAL_TEXT)
    }

    @Test fun phasedCommentaryNeverEntersTheBusinessResult() {
        val commentary = AdvisorWireFixtures.message("Checking the available adjustments.").put("phase", "commentary")
        val final = AdvisorWireFixtures.message(AdvisorWireFixtures.noChange).put("phase", "final_answer")
        fun envelope(vararg output: JSONObject) = AdvisorWireFixtures.responses()
            .put("output", JSONArray(output.toList())).toString()
        fun rejected(category: AdvisorCompletionError, vararg output: JSONObject) {
            val failure = assertThrows(AdvisorProtocolFailure::class.java) { adapter.decode(envelope(*output)) }
            assertEquals(category, failure.category)
        }

        assertEquals(AdviceResult.NoChange, adapter.decode(envelope(commentary, final)).result)
        assertEquals(AdviceResult.NoChange, adapter.decode(envelope(AdvisorWireFixtures.message(AdvisorWireFixtures.noChange))).result)
        rejected(AdvisorCompletionError.NO_FINAL_TEXT, commentary)
        rejected(AdvisorCompletionError.INVALID_ENVELOPE, commentary, final, final)
        rejected(AdvisorCompletionError.INVALID_ENVELOPE, commentary, final, AdvisorWireFixtures.message())
        rejected(AdvisorCompletionError.INVALID_ENVELOPE, final, AdvisorWireFixtures.message().put("phase", "unknown"))
        rejected(AdvisorCompletionError.REFUSED, final, AdvisorWireFixtures.message().put("phase", "commentary")
            .put("content", JSONArray().put(JSONObject().put("type", "refusal"))))
        rejected(AdvisorCompletionError.INCOMPLETE, final, AdvisorWireFixtures.message().put("phase", "commentary")
            .put("status", "in_progress"))
    }

    @Test fun duplicateKeysMalformedEnvelopesAndOversizedBusinessTextFailClosed() {
        val encoded = AdvisorWireFixtures.responses().toString()
        for (text in listOf(encoded.replace("\"status\":\"completed\"", "\"status\":\"incomplete\",\"status\":\"completed\""),
            encoded + "{}", "[]", "{\"status\":\"completed\",\"output\":null}", "{" + "\"nested\":{".repeat(34) + "}".repeat(35))) {
            try { adapter.decode(text); fail("malformed envelope") } catch (_: AdvisorProtocolFailure) { }
        }
        try { adapter.decode(AdvisorWireFixtures.responses("x".repeat(StrictResultParser.MAX_BYTES + 1)).toString()); fail("business limit") }
        catch (failure: AdviceFailure) { assertEquals(AdviceError.OUTPUT_TOO_LARGE, failure.code) }
        try { adapter.decode(AdvisorWireFixtures.responses("prefix " + AdvisorWireFixtures.proposal).toString()); fail("substring extraction") }
        catch (failure: AdviceFailure) { assertEquals(AdviceError.INVALID_JSON, failure.code) }
    }

    @Test fun thinkingTextIsIgnoredAndUsageDoesNotDoubleCountReasoning() {
        val root = AdvisorWireFixtures.responses().apply {
            getJSONArray("output").put(JSONObject().put("type", "reasoning").put("summary", AdvisorWireFixtures.noChange))
            put("usage", JSONObject("""{"input_tokens":100,"input_tokens_details":{"cached_tokens":60},"output_tokens":80,"output_tokens_details":{"reasoning_tokens":50},"total_tokens":180,"unknown":"private"}"""))
        }
        val reply = adapter.decode(root.toString())
        assertTrue(reply.result is AdviceResult.Proposal)
        assertEquals(100L, reply.usage.inputTokens); assertEquals(60L, reply.usage.cachedInputTokens)
        assertEquals(80L, reply.usage.outputTokens); assertEquals(50L, reply.usage.reasoningTokens)
        assertEquals(180L, reply.usage.totalTokens); assertEquals(true, reply.usage.outputIncludesReasoning)
        assertEquals(5, reply.usage.rawCounters.size); assertFalse(reply.usage.toString().contains("private"))
        val missing = adapter.decode(AdvisorWireFixtures.responses().toString()).usage
        assertNull(missing.totalTokens); assertNull(missing.outputTokens)
        val badCounters = adapter.decode(root.put("usage", JSONObject("""{"input_tokens":"100","output_tokens":-1,"total_tokens":1.5}""")).toString()).usage
        assertNull(badCounters.inputTokens); assertNull(badCounters.outputTokens); assertNull(badCounters.totalTokens)
    }

    @Test(timeout = 15000) fun protocolFailureIsNotAutomaticallyRetriedOrChangedToAnotherModel() = runBlocking {
        AdvisorAdapterFixture().use { f ->
            f.server.enqueue(MockResponse().setBody(AdvisorWireFixtures.responses(status = "incomplete").toString()))
            try { f.gateway(adapter).generate(f.endpoint(), binding, AdvisorWireFixtures.request); fail("incomplete") }
            catch (failure: AdvisorProtocolFailure) { assertEquals(AdvisorCompletionError.INCOMPLETE, failure.category) }
            assertEquals(1, f.server.requestCount)
        }
    }
}
