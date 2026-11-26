package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.AdviceResult
import com.dearded.spektralab.advisor.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AdvisorMessagesAdapterTest {
    private val adapter = MessagesAdapter(AdvisorWireFixtures.schema)
    private fun response(reason: String = "end_turn") = JSONObject().put("type", "message").put("role", "assistant")
        .put("stop_reason", reason).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", AdvisorWireFixtures.proposal)))

    @Test(timeout = 20000) fun allClaudeLegalModesUseOneOutputConfigAndIllegalCombinationsMakeNoPost() = runBlocking {
        AdvisorAdapterFixture().use { f ->
            val endpoint = f.endpoint(AdvisorTransport.MESSAGES, AdvisorDialect.ANTHROPIC, AdvisorAuth.X_API_KEY,
                headers = mapOf("anthropic-version" to MessagesAdapter.API_VERSION))
            val gateway = f.gateway(adapter)
            var count = 0
            for (model in AdvisorWireFixtures.catalog.models.values.filter { it.provider == "anthropic" }) {
                for (mode in listOf(null, "unknown", "disabled", "between_tools", "adaptive")) for (effort in listOf(null) + model.profile.efforts) {
                    val binding = ModelBinding("binding", 1, endpoint.id, "synthetic-model", model.id, mode, effort)
                    if (!model.profile.accepts(mode, effort)) {
                        try { gateway.generate(endpoint, binding, AdvisorWireFixtures.request); fail("illegal mode/effort") }
                        catch (_: IllegalArgumentException) { }
                        continue
                    }
                    f.server.enqueue(MockResponse().setBody(response().toString()))
                    assertTrue(gateway.generate(endpoint, binding, AdvisorWireFixtures.request).result is AdviceResult.Proposal)
                    val request = checkNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)); count++
                    assertEquals("/v1/messages", request.path); assertEquals("synthetic-secret", request.getHeader("x-api-key"))
                    assertEquals(MessagesAdapter.API_VERSION, request.getHeader("anthropic-version"))
                    assertNull(request.getHeader("Authorization"))
                    val body = JSONObject(request.body.readUtf8())
                    assertEquals(mode, body.getJSONObject("thinking").getString("type"))
                    val output = body.getJSONObject("output_config")
                    assertEquals(effort, output.getString("effort")); assertEquals("json_schema", output.getJSONObject("format").getString("type"))
                    assertEquals(setOf("effort", "format"), output.keys().asSequence().toSet())
                    assertEquals(4096, body.getInt("max_tokens"))
                    assertFalse(body.getJSONObject("thinking").has("budget_tokens"))
                    for (key in listOf("temperature", "top_p", "top_k", "tools", "response_format", "store")) assertFalse(body.has(key))
                }
            }
            assertEquals(count, f.server.requestCount)
            val custom = ModelBinding("binding", 1, endpoint.id, "synthetic-model")
            try { gateway.generate(endpoint.copy(schemaMode = AdvisorSchemaMode.PROMPT_JSON, requiredHeaders = emptyMap()), custom, AdvisorWireFixtures.request); fail("missing version") }
            catch (_: IllegalArgumentException) { }
            assertEquals(count, f.server.requestCount)
        }
    }

    @Test fun refusalTruncationPauseAndToolBlocksCannotBecomeAProposal() {
        for (reason in listOf("refusal", "max_tokens", "model_context_window_exceeded", "pause_turn", "tool_use", "stop_sequence")) {
            try { adapter.decode(response(reason).toString()); fail("stop=$reason") } catch (_: AdvisorProtocolFailure) { }
        }
        for (type in listOf("tool_use", "server_tool_use", "refusal", "unknown")) {
            val root = response().apply { getJSONArray("content").put(JSONObject().put("type", type)) }
            try { adapter.decode(root.toString()); fail("block=$type") } catch (_: AdvisorProtocolFailure) { }
        }
        val thought = JSONObject().put("type", "thinking").put("thinking", AdvisorWireFixtures.noChange)
        assertTrue(adapter.decode(response().apply { getJSONArray("content").put(thought) }.toString()).result is AdviceResult.Proposal)
        try { adapter.decode(response().put("content", JSONArray().put(thought)).toString()); fail("thinking-only") }
        catch (failure: AdvisorProtocolFailure) { assertEquals(AdvisorCompletionError.NO_FINAL_TEXT, failure.category) }
    }

    @Test fun claudeUsageAddsInputCacheBucketsButNeverAddsThinkingTwice() {
        val result = adapter.decode(response().put("usage", JSONObject("""{"input_tokens":20,"cache_creation_input_tokens":30,"cache_read_input_tokens":50,"output_tokens":80,"output_tokens_details":{"thinking_tokens":60}}""")).toString())
        assertEquals(100L, result.usage.inputTokens); assertEquals(50L, result.usage.cachedInputTokens)
        assertEquals(80L, result.usage.outputTokens); assertEquals(60L, result.usage.reasoningTokens)
        assertEquals(180L, result.usage.totalTokens); assertEquals(true, result.usage.outputIncludesReasoning)
        val incomplete = adapter.decode(response().put("usage", JSONObject("""{"input_tokens":20,"output_tokens":80}""")).toString()).usage
        assertNull(incomplete.inputTokens); assertNull(incomplete.totalTokens); assertEquals(80L, incomplete.outputTokens)
    }
}
