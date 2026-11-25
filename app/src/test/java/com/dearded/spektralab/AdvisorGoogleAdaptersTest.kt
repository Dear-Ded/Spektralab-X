package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.AdviceResult
import com.dearded.spektralab.advisor.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit

class AdvisorGoogleAdaptersTest {
    private val interactions = InteractionsAdapter(AdvisorWireFixtures.schema)
    private val generate = GenerateContentAdapter(AdvisorWireFixtures.schema)
    private fun interaction(status: String = "completed") = JSONObject().put("status", status).put("steps",
        JSONArray().put(JSONObject().put("type", "model_output").put("content",
            JSONArray().put(JSONObject().put("type", "text").put("text", AdvisorWireFixtures.proposal)))))
    private fun generated(reason: String = "STOP") = JSONObject().put("candidates", JSONArray().put(JSONObject()
        .put("finishReason", reason).put("content", JSONObject().put("role", "model").put("parts",
            JSONArray().put(JSONObject().put("text", AdvisorWireFixtures.proposal))))))

    @Test(timeout = 20000) fun fullGoogleEffortMatrixUsesTwoDifferentRestShapesAndHeaderAuth() = runBlocking {
        AdvisorAdapterFixture().use { f ->
            val gateway = f.gateway(interactions, generate)
            var count = 0
            for (adapter in listOf(interactions, generate)) {
                val endpoint = f.endpoint(adapter.transport, AdvisorDialect.GOOGLE, AdvisorAuth.X_GOOG_API_KEY)
                for (model in AdvisorWireFixtures.catalog.models.values.filter { it.provider == "google" }) {
                    for (effort in listOf(null, "unknown") + model.profile.efforts) {
                        val binding = ModelBinding("binding", 1, endpoint.id, "synthetic/model id", model.id, reasoningEffort = effort)
                        if (!model.profile.accepts(null, effort)) {
                            try { gateway.generate(endpoint, binding, AdvisorWireFixtures.request); fail("illegal thinking") }
                            catch (_: IllegalArgumentException) { }
                            continue
                        }
                        f.server.enqueue(MockResponse().setBody(if (adapter === interactions) interaction().toString() else generated().toString()))
                        assertTrue(gateway.generate(endpoint, binding, AdvisorWireFixtures.request).result is AdviceResult.Proposal)
                        val request = checkNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)); count++
                        assertEquals("synthetic-secret", request.getHeader("x-goog-api-key"))
                        assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Api-Revision"))
                        val body = JSONObject(request.body.readUtf8())
                        if (adapter === interactions) {
                            assertEquals("/v1/interactions", request.path)
                            assertEquals(effort, body.getJSONObject("generation_config").getString("thinking_level"))
                            assertEquals(4096, body.getJSONObject("generation_config").getInt("max_output_tokens"))
                            assertFalse(body.getBoolean("store")); assertFalse(body.getBoolean("stream"))
                            val format = body.getJSONObject("response_format")
                            assertEquals("text", format.getString("type")); assertEquals("application/json", format.getString("mime_type"))
                            assertTrue(format.has("schema")); assertFalse(body.has("generationConfig"))
                            assertFalse(body.has("previous_interaction_id")); assertFalse(body.has("agent"))
                        } else {
                            assertEquals("/v1/models/synthetic%2Fmodel%20id:generateContent", request.path)
                            val config = body.getJSONObject("generationConfig")
                            assertEquals(effort!!.uppercase(Locale.ROOT), config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
                            assertFalse(config.getJSONObject("thinkingConfig").getBoolean("includeThoughts"))
                            assertEquals(4096, config.getInt("maxOutputTokens"))
                            val text = config.getJSONObject("responseFormat").getJSONObject("text")
                            assertEquals("APPLICATION_JSON", text.getString("mimeType")); assertTrue(text.has("schema"))
                            assertFalse(config.has("responseMimeType")); assertFalse(config.has("responseJsonSchema"))
                            assertFalse(body.has("model")); assertFalse(body.has("generation_config"))
                            assertEquals("user", body.getJSONArray("contents").getJSONObject(0).getString("role"))
                        }
                        assertFalse(body.has("tools")); assertFalse(body.has("cachedContent"))
                    }
                }
            }
            assertEquals(count, f.server.requestCount)
        }
    }

    @Test fun interactionsOnlyAcceptCompletedModelOutputStepsAndNeverContinueRemotely() {
        fun rejected(root: JSONObject) {
            try { interactions.decode(root.toString()); fail("unsafe interaction") } catch (_: AdvisorProtocolFailure) { }
        }
        for (status in listOf("incomplete", "requires_continuation", "requires_action", "failed", "cancelled", "in_progress")) rejected(interaction(status))
        for (type in listOf("function_call", "function_result", "tool_call", "unknown"))
            rejected(interaction().apply { getJSONArray("steps").put(JSONObject().put("type", type)) })
        rejected(interaction().apply { put("outputs", getJSONArray("steps")); remove("steps") })
        rejected(interaction().put("error", JSONObject()))
        val thought = JSONObject().put("type", "thought").put("summary", AdvisorWireFixtures.noChange)
        assertTrue(interactions.decode(interaction().apply { getJSONArray("steps").put(thought) }.toString()).result is AdviceResult.Proposal)
        rejected(interaction().put("steps", JSONArray().put(thought)))
        rejected(interaction().apply { getJSONArray("steps").put(getJSONArray("steps").getJSONObject(0)) })
        rejected(interaction().apply { getJSONArray("steps").getJSONObject(0).getJSONArray("content").put(JSONObject().put("type", "image")) })
    }

    @Test fun generateContentRejectsBlockedTruncatedToolAndThoughtOnlyReplies() {
        fun rejected(root: JSONObject) {
            try { generate.decode(root.toString()); fail("unsafe generateContent") } catch (_: AdvisorProtocolFailure) { }
        }
        for (reason in listOf("MAX_TOKENS", "CONTINUATION", "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "OTHER")) rejected(generated(reason))
        rejected(generated().put("promptFeedback", JSONObject().put("blockReason", "SAFETY")))
        rejected(generated().put("error", JSONObject()))
        fun parts(root: JSONObject) = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        for (field in listOf("functionCall", "executableCode", "functionResponse", "codeExecutionResult"))
            rejected(generated().apply { parts(this).put(JSONObject().put(field, JSONObject())) })
        assertTrue(generate.decode(generated().apply { parts(this).put(JSONObject().put("text", AdvisorWireFixtures.noChange).put("thought", true)) }
            .toString()).result is AdviceResult.Proposal)
        rejected(generated().apply { parts(this).getJSONObject(0).put("thought", true) })
        rejected(generated().apply { getJSONArray("candidates").put(getJSONArray("candidates").getJSONObject(0)) })
    }

    @Test fun googleKeepsFinalAndThoughtTokensSeparateAndPreservesVendorTotal() {
        val first = interactions.decode(interaction().put("usage", JSONObject("""{"total_input_tokens":100,"total_cached_tokens":60,"total_output_tokens":20,"total_thought_tokens":80,"total_tokens":200,"total_tool_use_tokens":0}""")).toString()).usage
        val second = generate.decode(generated().put("usageMetadata", JSONObject("""{"promptTokenCount":100,"cachedContentTokenCount":60,"candidatesTokenCount":20,"thoughtsTokenCount":80,"totalTokenCount":200,"toolUsePromptTokenCount":0}""")).toString()).usage
        for (usage in listOf(first, second)) {
            assertEquals(100L, usage.inputTokens); assertEquals(60L, usage.cachedInputTokens)
            assertEquals(20L, usage.outputTokens); assertEquals(80L, usage.reasoningTokens)
            assertEquals(200L, usage.totalTokens); assertEquals(false, usage.outputIncludesReasoning)
            assertEquals(6, usage.rawCounters.size)
        }
        assertNull(interactions.decode(interaction().toString()).usage.totalTokens)
        assertNull(generate.decode(generated().toString()).usage.totalTokens)
    }
}
