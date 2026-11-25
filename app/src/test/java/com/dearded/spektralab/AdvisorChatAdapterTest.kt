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

class AdvisorChatAdapterTest {
    private val adapter = ChatAdapter(AdvisorWireFixtures.schema)
    private fun response(text: Any = AdvisorWireFixtures.proposal, reason: String = "stop") = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", reason).put("message",
            JSONObject().put("role", "assistant").put("content", text))))
    private fun binding(model: CatalogModel, mode: String?, effort: String?) =
        ModelBinding("binding", 1, "endpoint", "synthetic-model", model.id, mode, effort, 2048)

    @Test(timeout = 20000) fun openAiAndDeepSeekMatrixUsesDistinctBudgetsAndDisabledOmitsEffort() = runBlocking {
        AdvisorAdapterFixture().use { f ->
            val gateway = f.gateway(adapter)
            var sent = 0
            for (model in AdvisorWireFixtures.catalog.models.values.filter { it.provider in setOf("openai", "deepseek") }) {
                val deepseek = model.provider == "deepseek"
                val endpoint = f.endpoint(AdvisorTransport.CHAT, if (deepseek) AdvisorDialect.DEEPSEEK else AdvisorDialect.OPENAI,
                    schema = if (deepseek) AdvisorSchemaMode.JSON_OBJECT else AdvisorSchemaMode.JSON_SCHEMA)
                val modes = if (model.profile.modes.isEmpty()) listOf(null) else model.profile.modes.toList()
                for (mode in modes) for (effort in listOf(null) + model.profile.efforts) {
                    val binding = binding(model, mode, effort)
                    if (!model.profile.accepts(mode, effort)) {
                        try { gateway.generate(endpoint, binding, AdvisorWireFixtures.request); fail("illegal reasoning") }
                        catch (_: IllegalArgumentException) { }
                        continue
                    }
                    f.server.enqueue(MockResponse().setBody(response().toString()))
                    assertTrue(gateway.generate(endpoint, binding, AdvisorWireFixtures.request).result is AdviceResult.Proposal)
                    val request = checkNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)); sent++
                    assertEquals("/v1/chat/completions", request.path)
                    val body = JSONObject(request.body.readUtf8())
                    assertEquals(2048, body.getInt(if (deepseek) "max_tokens" else "max_completion_tokens"))
                    assertFalse(body.has(if (deepseek) "max_completion_tokens" else "max_tokens"))
                    assertEquals(2, body.getJSONArray("messages").length())
                    assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("Example JSON"))
                    assertEquals(if (deepseek) "json_object" else "json_schema", body.getJSONObject("response_format").getString("type"))
                    if (deepseek) assertEquals(mode, body.getJSONObject("thinking").getString("type"))
                    else { assertFalse(body.has("thinking")); assertFalse(body.getBoolean("store")) }
                    if (effort == null) assertFalse(body.has("reasoning_effort")) else assertEquals(effort, body.getString("reasoning_effort"))
                    for (key in listOf("tools", "temperature", "top_p", "reasoning_content", "previous_response_id")) assertFalse(body.has(key))
                }
            }
            assertEquals(sent, f.server.requestCount)
        }
    }

    @Test(timeout = 15000) fun genericRequiresExplicitBudgetAndOpenRouterHasItsOwnReasoningAndNoFallback() = runBlocking {
        AdvisorAdapterFixture().use { f ->
            val gateway = f.gateway(adapter)
            val custom = ModelBinding("binding", 1, "endpoint", "synthetic-slug")
            val generic = f.endpoint(AdvisorTransport.CHAT, AdvisorDialect.GENERIC_CHAT, schema = AdvisorSchemaMode.PROMPT_JSON)
            try { gateway.generate(generic, custom, AdvisorWireFixtures.request); fail("guessed budget") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, f.server.requestCount)
            for (field in ChatOutputLimit.entries) {
                f.server.enqueue(MockResponse().setBody(response().toString()))
                gateway.generate(generic.copy(chatOutputLimit = field), custom, AdvisorWireFixtures.request)
                val body = JSONObject(checkNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8())
                assertEquals(4096, body.getInt(field.wire)); assertFalse(body.has("reasoning_effort")); assertFalse(body.has("response_format"))
            }
            val relay = f.endpoint(AdvisorTransport.CHAT, AdvisorDialect.OPENROUTER)
            val model = AdvisorWireFixtures.catalog.models.getValue("openai/gpt-6-luna")
            f.server.enqueue(MockResponse().setBody(response().toString()))
            gateway.generate(relay, binding(model, null, "low"), AdvisorWireFixtures.request)
            val body = JSONObject(checkNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8())
            assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
            assertTrue(body.getJSONObject("reasoning").getBoolean("exclude")); assertFalse(body.has("reasoning_effort"))
            assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
            assertTrue(body.getJSONObject("provider").getBoolean("require_parameters"))
            assertEquals(2048, body.getInt("max_completion_tokens")); assertFalse(body.has("max_tokens"))
            for ((id, mode) in listOf("anthropic/claude-opus-5-5" to "adaptive", "anthropic/claude-sonnet-5-5" to "between_tools")) {
                val claude = AdvisorWireFixtures.catalog.models.getValue(id)
                try { gateway.generate(relay, binding(claude, mode, "low"), AdvisorWireFixtures.request); fail("unverified native mode mapping") }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(3, f.server.requestCount)
        }
    }

    @Test fun finishFailuresRefusalToolsAndReasoningOnlyAreNeverBusinessResults() {
        fun rejected(root: JSONObject) {
            try { adapter.decode(root.toString()); fail("unsafe chat accepted") } catch (_: AdvisorProtocolFailure) { }
        }
        for (reason in listOf("length", "content_filter", "tool_calls", "function_call", "insufficient_system_resource", "aborted", "unknown"))
            rejected(response(reason = reason))
        fun message(root: JSONObject) = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        rejected(response().apply { message(this).put("refusal", "synthetic") })
        rejected(response().apply { message(this).put("tool_calls", JSONArray().put(JSONObject())) })
        rejected(response().apply { message(this).put("function_call", JSONObject()) })
        rejected(response(JSONObject.NULL).apply { message(this).put("reasoning_content", AdvisorWireFixtures.proposal) })
        rejected(response("   ")); rejected(response().apply { getJSONArray("choices").put(getJSONArray("choices").getJSONObject(0)) })
        rejected(response().put("error", JSONObject().put("message", "private")))
        rejected(response().apply { getJSONArray("choices").getJSONObject(0).put("native_finish_reason", "MAX_TOKENS") })
        rejected(response().apply { getJSONArray("choices").getJSONObject(0).put("error", JSONObject()) })
        val allowed = response().apply { message(this).put("reasoning_content", AdvisorWireFixtures.noChange) }
        assertTrue(adapter.decode(allowed.toString()).result is AdviceResult.Proposal)
    }

    @Test fun usageKeepsCacheSubsetsAndInclusiveReasoningWithoutAddingThemAgain() {
        val result = adapter.decode(response().put("usage", JSONObject("""{"prompt_tokens":100,"completion_tokens":80,"total_tokens":180,"prompt_cache_hit_tokens":70,"prompt_cache_miss_tokens":30,"completion_tokens_details":{"reasoning_tokens":50,"safety_tokens":2}}""")).toString())
        assertEquals(100L, result.usage.inputTokens); assertEquals(70L, result.usage.cachedInputTokens)
        assertEquals(80L, result.usage.outputTokens); assertEquals(50L, result.usage.reasoningTokens)
        assertEquals(180L, result.usage.totalTokens); assertEquals(true, result.usage.outputIncludesReasoning)
        assertEquals(7, result.usage.rawCounters.size)
        assertNull(adapter.decode(response().toString()).usage.totalTokens)
        val cached = adapter.decode(response().put("usage", JSONObject("""{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":50,"cache_write_tokens":25}}""")).toString()).usage
        assertEquals(100L, cached.inputTokens); assertEquals(50L, cached.cachedInputTokens)
        assertEquals(25L, cached.rawCounters["prompt_tokens_details.cache_write_tokens"]); assertEquals(120L, cached.totalTokens)
    }
}
