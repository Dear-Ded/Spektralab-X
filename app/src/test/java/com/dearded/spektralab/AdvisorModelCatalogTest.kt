package com.dearded.spektralab

import com.dearded.spektralab.advisor.network.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Static directory/configuration checks only: no provider availability or live quality claim. */
class AdvisorModelCatalogTest {
    private val catalog = ModelCatalog.parse(File("src/main/assets/advisor/model-catalog.v1.json").readText())
    private fun endpoint(model: CatalogModel): EndpointConfig {
        val (transport, dialect, auth) = when (model.provider) {
            "openai" -> Triple(AdvisorTransport.RESPONSES, AdvisorDialect.OPENAI, AdvisorAuth.BEARER)
            "deepseek" -> Triple(AdvisorTransport.CHAT, AdvisorDialect.DEEPSEEK, AdvisorAuth.BEARER)
            "anthropic" -> Triple(AdvisorTransport.MESSAGES, AdvisorDialect.ANTHROPIC, AdvisorAuth.X_API_KEY)
            else -> Triple(AdvisorTransport.INTERACTIONS, AdvisorDialect.GOOGLE, AdvisorAuth.X_GOOG_API_KEY)
        }
        return EndpointConfig("endpoint", 1, "Synthetic", "https://example.invalid/v1".toHttpUrl(), transport, dialect, auth)
    }
    private fun binding(model: CatalogModel, mode: String? = model.profile.modes.firstOrNull(),
                        effort: String? = model.profile.efforts.first()) =
        ModelBinding("binding", 1, "endpoint", model.remoteId, model.id, mode, effort)

    @Test fun allDirectoryModelsHaveExplicitLegalReasoningCombinationsAndStayUnverified() {
        assertEquals(15, catalog.models.size)
        var tested = 0
        for (model in catalog.models.values) {
            assertTrue(model.sources.isNotEmpty())
            val modes = if (model.profile.modes.isEmpty()) listOf(null) else model.profile.modes.toList()
            for (mode in modes) for (effort in listOf(null) + model.profile.efforts) {
                val chosen = binding(model, mode, effort)
                if (model.profile.accepts(mode, effort)) {
                    val result = CapabilityResolver.resolve(endpoint(model), chosen, catalog, "test.1")
                    assertEquals(CapabilityStatus.UNVERIFIED, result.status)
                    assertFalse(result.vision)
                } else {
                    try { CapabilityResolver.resolve(endpoint(model), chosen, catalog, "test.1"); fail("invalid effort") }
                    catch (_: IllegalArgumentException) { }
                }
                tested++
            }
            assertFalse(model.profile.accepts("unknown", "ultra"))
        }
        assertTrue(tested > 80)
    }

    @Test fun disabledDeepSeekOmitsEffortAndClaudeModeLimitsAreNotUniversalOff() {
        val deepseek = catalog.models.getValue("deepseek/deepseek-flash")
        assertTrue(deepseek.profile.accepts("disabled", null))
        assertFalse(deepseek.profile.accepts("disabled", "low"))
        val sonnet = catalog.models.getValue("anthropic/claude-sonnet-5-5")
        assertTrue(sonnet.profile.accepts("between_tools", "high"))
        assertFalse(sonnet.profile.accepts("between_tools", "max"))
        assertFalse(sonnet.profile.accepts("disabled", "low"))
    }

    @Test fun verifiedVisionDoesNotSurviveConfigurationChangesOrTextOnlyModel() {
        val model = catalog.models.getValue("openai/gpt-6-luna")
        val base = endpoint(model)
        val endpoint = base.copy(credential = CredentialRef(UUID.randomUUID().toString(), base.id, base.origin, 1))
        val binding = binding(model).copy(visionEnabled = true)
        val proof = CapabilityVerification(endpoint.id, endpoint.revision, 1, binding.id, binding.revision,
            binding.remoteModelId, endpoint.transport, catalog.version, "test.1", true)
        assertTrue(CapabilityResolver.resolve(endpoint, binding, catalog, "test.1", proof).vision)
        fun refused(ep: EndpointConfig = endpoint, selected: ModelBinding = binding, version: String = "test.1") {
            try { CapabilityResolver.resolve(ep, selected, catalog, version, proof); fail("stale capability accepted") }
            catch (_: IllegalArgumentException) { }
        }
        refused(endpoint.copy(revision = 2)); refused(selected = binding.copy(revision = 2)); refused(version = "test.2")
        val text = catalog.models.getValue("deepseek/deepseek-v4-pro")
        val textBinding = binding(text).copy(visionEnabled = true)
        try { CapabilityResolver.resolve(endpoint(text), textBinding, catalog, "test.1"); fail("text-only vision") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun customUnknownModelDoesNotInheritReasoningOrSchemaFromItsName() {
        val model = catalog.models.values.first()
        val endpoint = endpoint(model).copy(transport = AdvisorTransport.CHAT, dialect = AdvisorDialect.GENERIC_CHAT)
        val custom = ModelBinding("binding", 1, endpoint.id, "unverified/model")
        val result = CapabilityResolver.resolve(endpoint, custom, catalog, "test.1")
        assertNull(result.effort); assertFalse(result.vision)
        try { CapabilityResolver.resolve(endpoint, custom.copy(reasoningEffort = "high"), catalog, "test.1"); fail("guessed effort") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun officialEndpointsRequireTheSelectedModelButRelaysCanMapTheirOwnIds() {
        val haiku = catalog.models.getValue("anthropic/claude-haiku-5-5")
        val hydrogen = catalog.models.getValue("google/gemini-4-hydrogen")
        for ((model, chosen, other) in listOf(
            Triple(haiku, binding(haiku, "disabled", "low"), "anthropic/claude-sonnet-5-5"),
            Triple(hydrogen, binding(hydrogen, null, "xhigh"), "google/gemini-4-argon"),
        )) {
            val relay = endpoint(model)
            val direct = relay.copy(baseUrl = catalog.providers.getValue(model.provider).baseUrl)
            val mismatched = chosen.copy(remoteModelId = catalog.models.getValue(other).remoteId)
            assertEquals(CapabilityStatus.UNVERIFIED, CapabilityResolver.resolve(direct, chosen, catalog, "test.1").status)
            assertThrows(IllegalArgumentException::class.java) {
                CapabilityResolver.resolve(direct, mismatched, catalog, "test.1")
            }
            assertEquals(CapabilityStatus.UNVERIFIED, CapabilityResolver.resolve(relay, mismatched, catalog, "test.1").status)
            val custom = ModelBinding("binding", 1, direct.id, "private-deployment")
            assertNull(CapabilityResolver.resolve(direct, custom, catalog, "test.1").effort)
        }
    }
}
