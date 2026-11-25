package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.AdviceFailure
import com.dearded.spektralab.advisor.domain.AdviceStatistics
import com.dearded.spektralab.advisor.domain.AdviceStatisticsValues
import com.dearded.spektralab.advisor.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AdvisorNetworkGatewayTest {
    private data class Case(val adapter: AdviceAdapter, val provider: String, val modelId: String,
                            val mode: String? = null, val effort: String = "low")
    private val cases = listOf(
        Case(ResponsesAdapter(AdvisorWireFixtures.schema), "openai", "openai/gpt-6-luna"),
        Case(ChatAdapter(AdvisorWireFixtures.schema), "deepseek", "deepseek/deepseek-flash", "enabled"),
        Case(MessagesAdapter(AdvisorWireFixtures.schema), "anthropic", "anthropic/claude-opus-5-5", "adaptive"),
        Case(InteractionsAdapter(AdvisorWireFixtures.schema), "google", "google/gemini-4-argon"),
        Case(GenerateContentAdapter(AdvisorWireFixtures.schema), "google", "google/gemini-4-argon"))

    @Test(timeout = 20000) fun allFiveTransportsRejectIncompleteAndInvalidBusinessRepliesAfterExactlyOnePost() = runBlocking {
        AdvisorAdapterFixture().use { f ->
            val gateway = f.gateway(*cases.map { it.adapter }.toTypedArray())
            var sent = 0
            for (case in cases) {
                val dialect = when (case.provider) { "openai" -> AdvisorDialect.OPENAI; "deepseek" -> AdvisorDialect.DEEPSEEK
                    "anthropic" -> AdvisorDialect.ANTHROPIC; else -> AdvisorDialect.GOOGLE }
                val auth = when (case.provider) { "anthropic" -> AdvisorAuth.X_API_KEY; "google" -> AdvisorAuth.X_GOOG_API_KEY; else -> AdvisorAuth.BEARER }
                val endpoint = f.endpoint(case.adapter.transport, dialect, auth,
                    if (case.provider == "deepseek") AdvisorSchemaMode.JSON_OBJECT else AdvisorSchemaMode.JSON_SCHEMA,
                    if (case.provider == "anthropic") mapOf("anthropic-version" to MessagesAdapter.API_VERSION) else emptyMap())
                val binding = ModelBinding("binding", 1, endpoint.id, "synthetic", case.modelId, case.mode, case.effort)
                for (incomplete in listOf(true, false)) {
                    f.server.enqueue(MockResponse().setBody(envelope(case.adapter.transport,
                        if (incomplete) AdvisorWireFixtures.proposal else "{\"schema_version\":2}", incomplete).toString()))
                    try { gateway.generate(endpoint, binding, AdvisorWireFixtures.request); fail("invalid result escaped transport") }
                    catch (_: AdvisorProtocolFailure) { assertTrue(incomplete) }
                    catch (_: AdviceFailure) { assertFalse(incomplete) }
                    val request = checkNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)); sent++
                    assertEquals("POST", request.method); assertEquals(sent, f.server.requestCount)
                    val body = request.body.readUtf8()
                    for (field in listOf("profile_id", "photoSessionId", "previous_response_id", "previous_interaction_id", "image_url", "file_uri"))
                        assertFalse(body.contains(field))
                }
            }
            assertEquals(10, sent)
        }
    }

    private fun envelope(transport: AdvisorTransport, text: String, incomplete: Boolean): JSONObject = when (transport) {
        AdvisorTransport.RESPONSES -> AdvisorWireFixtures.responses(text, if (incomplete) "incomplete" else "completed")
        AdvisorTransport.CHAT -> JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("finish_reason", if (incomplete) "length" else "stop").put("message", JSONObject().put("role", "assistant").put("content", text))))
        AdvisorTransport.MESSAGES -> JSONObject().put("type", "message").put("role", "assistant")
            .put("stop_reason", if (incomplete) "max_tokens" else "end_turn")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        AdvisorTransport.INTERACTIONS -> JSONObject().put("status", if (incomplete) "incomplete" else "completed")
            .put("steps", JSONArray().put(JSONObject().put("type", "model_output")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))))
        AdvisorTransport.GENERATE_CONTENT -> JSONObject().put("candidates", JSONArray().put(JSONObject()
            .put("finishReason", if (incomplete) "MAX_TOKENS" else "STOP").put("content", JSONObject().put("role", "model")
                .put("parts", JSONArray().put(JSONObject().put("text", text))))))
    }

    @Test(timeout = 20000) fun everyTransportPreservesTheExplicitStatisticsBoundaryInCapturedHttp() = runBlocking<Unit> {
        AdvisorAdapterFixture().use { f ->
            val gateway = f.gateway(*cases.map { it.adapter }.toTypedArray())
            val bins = List(16) { if (it == 8) 1.0 else 0.0 }
            val measured = AdviceStatistics(1, 9, 16, 16, AdviceStatisticsValues(listOf(.5, .5, .5), 0.0, 0.0,
                bins, bins, bins, List(64) { if (it == 42) 1.0 else 0.0 }))
            for (case in cases) {
                val dialect = when (case.provider) { "openai" -> AdvisorDialect.OPENAI; "deepseek" -> AdvisorDialect.DEEPSEEK
                    "anthropic" -> AdvisorDialect.ANTHROPIC; else -> AdvisorDialect.GOOGLE }
                val auth = when (case.provider) { "anthropic" -> AdvisorAuth.X_API_KEY; "google" -> AdvisorAuth.X_GOOG_API_KEY; else -> AdvisorAuth.BEARER }
                val endpoint = f.endpoint(case.adapter.transport, dialect, auth,
                    if (case.provider == "deepseek") AdvisorSchemaMode.JSON_OBJECT else AdvisorSchemaMode.JSON_SCHEMA,
                    if (case.provider == "anthropic") mapOf("anthropic-version" to MessagesAdapter.API_VERSION) else emptyMap())
                val binding = ModelBinding("binding", 1, endpoint.id, "synthetic", case.modelId, case.mode, case.effort)
                for (statistics in listOf(null, AdviceStatistics(1), measured)) {
                    f.server.enqueue(MockResponse().setBody(envelope(case.adapter.transport, AdvisorWireFixtures.noChange, false).toString()))
                    gateway.generate(endpoint, binding, AdvisorWireFixtures.request.copy(statistics = statistics))
                    val sent = checkNotNull(f.server.takeRequest(2, TimeUnit.SECONDS))
                    val body = JSONObject(sent.body.readUtf8())
                    val content = when (case.adapter.transport) {
                        AdvisorTransport.RESPONSES -> body.getJSONArray("input").getJSONObject(0).getString("content")
                        AdvisorTransport.CHAT -> body.getJSONArray("messages").getJSONObject(1).getString("content")
                        AdvisorTransport.MESSAGES -> body.getJSONArray("messages").getJSONObject(0).getString("content")
                        AdvisorTransport.INTERACTIONS -> body.getString("input")
                        AdvisorTransport.GENERATE_CONTENT -> body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
                    }
                    val input = JSONObject(content)
                    assertEquals(if (statistics == null) "parameters" else "statistics", input.getString("input_mode"))
                    if (statistics == null) assertFalse(input.has("current_draft_statistics")) else {
                        val stats = input.getJSONObject("current_draft_statistics")
                        assertEquals(statistics.available, stats.getBoolean("available"))
                        assertEquals(statistics.available, stats.has("rgb_bins4x4x4_r_major"))
                    }
                    for (field in listOf("image", "image_url", "file_uri", "exif", "gps", "profile_id", "history")) assertFalse(input.has(field))
                }
            }
            assertEquals(15, f.server.requestCount)
        }
    }
}
