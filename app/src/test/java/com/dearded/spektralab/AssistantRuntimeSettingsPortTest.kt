package com.dearded.spektralab

import com.dearded.spektralab.advisor.network.*
import com.dearded.spektralab.advisor.session.AdvisorRuntime
import com.dearded.spektralab.advisor.session.AdvisorSettingsService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class AssistantRuntimeSettingsPortTest {
    @get:Rule val temp = TemporaryFolder()
    private val catalog get() = AdvisorWireFixtures.catalog

    @Test(timeout = 15000) fun catalogLabelsSaveOfficialDefaultsAndReturnToSavedEndpointAndModelIds() = runBlocking<Unit> {
        withPort { port, runtime, _, fixture, _ ->
            val services = port.await { !it.loading }.services
            assertEquals(listOf("OpenAI", "DeepSeek", "Anthropic", "Google", "OpenRouter", "Custom"), services.map { it.name })
            val saved = listOf("OpenAI" to "openai", "DeepSeek" to "deepseek", "Anthropic" to "anthropic", "Google" to "google")
                .associate { (label, id) ->
                    val provider = catalog.providers.getValue(id)
                    val model = catalog.models.values.first { it.provider == id }
                    assertTrue(model.remoteId in services.single { it.name == label }.models)
                    val key = "synthetic-$id".toCharArray()
                    // On provider switches the sheet submits the previous service's unchanged address.
                    port.save(label, port.settings.value.address, model.remoteId, key)
                    assertWiped(key)
                    val shown = port.await { !it.saving && it.service == label }
                    val (endpoint, binding) = checkNotNull(runtime.state.value.selected)
                    assertEquals(provider.baseUrl, endpoint.baseUrl)
                    assertEquals(provider.transports.first(), endpoint.transport)
                    assertEquals(provider.schemaMode, endpoint.schemaMode)
                    assertEquals(catalog.transports.getValue(endpoint.transport).auth, endpoint.auth)
                    assertEquals(catalog.transports.getValue(endpoint.transport).headers, endpoint.requiredHeaders)
                    assertEquals(model.id, binding.catalogTemplateId)
                    assertEquals(model.initialMode, binding.reasoningMode)
                    assertEquals(model.initialEffort, binding.reasoningEffort)
                    assertEquals(model.remoteId, shown.model)
                    assertTrue(shown.keySaved)
                    assertNull(shown.tested)
                    label to (endpoint to binding)
                }
            val (endpoint, original) = saved.getValue("OpenAI")
            val other = services.single { it.name == "OpenAI" }.models.first { it != original.remoteModelId }
            val previousAddress = port.settings.value.address
            port.save("OpenAI", previousAddress, other, null)
            port.await { !it.saving && it.model == other }
            val (sameEndpoint, newBinding) = checkNotNull(runtime.state.value.selected)
            assertEquals(endpoint, sameEndpoint)
            assertNotEquals(original.id, newBinding.id)
            port.save("OpenAI", sameEndpoint.baseUrl.toString(), original.remoteModelId, null)
            port.await { !it.saving && it.model == original.remoteModelId }
            assertEquals(endpoint to original, runtime.state.value.selected)
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test(timeout = 15000) fun legacyCustomFieldsSurviveAndOriginChangesRequireAnExplicitKey() = runBlocking<Unit> {
        withPort(seed = { service ->
            val endpoint = EndpointConfig("legacy-endpoint", 0, "My relay", "https://relay.invalid/v1".toHttpUrl(),
                AdvisorTransport.CHAT, AdvisorDialect.GENERIC_CHAT, AdvisorAuth.BEARER, AdvisorSchemaMode.PROMPT_JSON,
                pathOverride = "custom/chat", chatOutputLimit = ChatOutputLimit.MAX_COMPLETION_TOKENS,
                requiredHeaders = mapOf("api-revision" to "legacy"))
            val binding = ModelBinding("legacy-binding", 0, endpoint.id, "relay/alias", maxOutputTokens = 777)
            service.save(0, endpoint, binding, "synthetic-legacy".toCharArray())
        }) { port, runtime, store, fixture, _ ->
            val initial = port.await { !it.loading }
            assertEquals("Custom", initial.service)
            assertEquals("relay/alias", initial.model)
            assertTrue(initial.services.single { it.name == "Custom" }.models.isEmpty())
            val (originalEndpoint, originalBinding) = checkNotNull(runtime.state.value.selected)
            val nextPath = "https://relay.invalid/other"
            port.save(initial.service, nextPath, initial.model, null)
            port.await { !it.saving && it.address == nextPath }
            val (sameOrigin, sameBinding) = checkNotNull(runtime.state.value.selected)
            assertEquals(originalEndpoint.atAddress(nextPath), sameOrigin)
            assertEquals(originalBinding, sameBinding)
            val otherOrigin = "https://relay.invalid:8443/other"
            port.save(initial.service, otherOrigin, initial.model, null)
            port.await { !it.saving && it.error == AssistantError.Auth }
            assertEquals(sameOrigin to sameBinding, store.load().selected())
            port.testConnection()
            assertFalse(runtime.state.value.testing)
            assertEquals(0, fixture.server.requestCount)

            val replacement = "synthetic-replacement".toCharArray()
            port.save(initial.service, otherOrigin, initial.model, replacement)
            assertWiped(replacement)
            port.await { !it.saving && it.address == otherOrigin }
            val (changed, changedBinding) = checkNotNull(runtime.state.value.selected)
            assertEquals(sameOrigin.atAddress(otherOrigin), changed.copy(credential = null))
            assertEquals(sameBinding, changedBinding)
            assertNotEquals(sameOrigin.credential, changed.credential)
            fixture.credentials.use(changed) { assertEquals("synthetic-replacement", String(it)) }
            assertEquals(1, fixture.credentialFileCount())
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test(timeout = 15000) fun testsOnlySavedConfigAndValidationNeverKeepsKeysOrShowsOldSuccess() = runBlocking<Unit> {
        withPort { port, runtime, store, fixture, _ ->
            port.await { !it.loading }
            port.testConnection()
            assertFalse(runtime.state.value.testing)
            assertEquals(0, fixture.server.requestCount)
            port.save("OpenAI", "https://saved.invalid/custom", "gpt-6-luna", "synthetic-saved".toCharArray())
            val saved = port.await { !it.saving && it.keySaved }
            fixture.server.enqueue(MockResponse().setBody(AdvisorWireFixtures.responses(AdvisorWireFixtures.noChange).toString()))
            port.testConnection()
            port.await { it.tested == true }
            val request = checkNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/custom/responses", request.path)
            assertEquals("gpt-6-luna", JSONObject(request.body.readUtf8()).getString("model"))
            assertEquals("Bearer synthetic-saved", request.getHeader("Authorization"))

            val previous = store.load()
            val wrongService = "invalid-service-key".toCharArray()
            port.save("Not offered", saved.address, saved.model, wrongService)
            assertWiped(wrongService)
            assertNull(port.await { it.error == AssistantError.Configuration }.tested)
            val wrongAddress = "invalid-address-key".toCharArray()
            port.save(saved.service, "https://saved.invalid/?key=not-allowed", saved.model, wrongAddress)
            assertWiped(wrongAddress)
            assertNull(port.await { it.error == AssistantError.Address }.tested)
            port.testConnection()
            assertFalse(runtime.state.value.testing)
            assertEquals(1, fixture.server.requestCount)
            assertEquals(previous, store.load())

            port.save(saved.service, saved.address, saved.model, null)
            port.await { !it.saving && it.error == null && it.tested == null }
            fixture.server.enqueue(MockResponse().setResponseCode(429))
            port.testConnection()
            assertEquals(false, port.await { it.error == AssistantError.RateLimit }.tested)
            assertEquals(2, fixture.server.requestCount)
            assertNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))

            fixture.server.enqueue(MockResponse().setBody(AdvisorWireFixtures.responses(AdvisorWireFixtures.noChange).toString())
                .setBodyDelay(5, TimeUnit.SECONDS))
            port.testConnection()
            port.await { it.testing }
            assertNotNull(withContext(Dispatchers.IO) { fixture.server.takeRequest(2, TimeUnit.SECONDS) })
            port.cancelTest()
            val cancelled = port.await { !it.testing }
            assertNull(cancelled.tested)
            assertNull(cancelled.error)
            assertEquals(3, fixture.server.requestCount)
        }
    }

    @Test(timeout = 15000) fun preferenceStateAndAllActionsAreForwardedToTheSuppliedDelegate() = runBlocking<Unit> {
        withPort { port, _, _, fixture, controls ->
            assertSame(controls.preferences, port.preferences)
            port.learn(true)
            port.useRules(true)
            assertEquals(AssistantPreferences(learn = true, useRules = true), port.preferences.value)
            port.clearPreferences()
            assertEquals(listOf("learn:true", "rules:true", "clear"), controls.calls)
            assertEquals(AssistantPreferences(), port.preferences.value)
            assertEquals(0, fixture.server.requestCount)
        }
    }

    private suspend fun withPort(
        seed: (AdvisorSettingsService) -> Unit = {},
        block: suspend (AssistantRuntimeSettingsPort, AdvisorRuntime, AdvisorConfigurationStore, AdvisorAdapterFixture, TestPreferences) -> Unit,
    ) = coroutineScope {
        AdvisorAdapterFixture().use { fixture ->
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val store = AdvisorConfigurationStore(temp.root.resolve("settings.json"))
            val schema = AdvisorWireFixtures.schema
            val gateway = fixture.gateway(ResponsesAdapter(schema), ChatAdapter(schema), MessagesAdapter(schema),
                InteractionsAdapter(schema), GenerateContentAdapter(schema))
            val service = AdvisorSettingsService(catalog, gateway, store, fixture.credentials)
            try {
                seed(service)
                val runtime = AdvisorRuntime(scope, { service }, {})
                val controls = TestPreferences()
                block(AssistantRuntimeSettingsPort(scope, runtime, controls), runtime, store, fixture, controls)
            } finally {
                checkNotNull(scope.coroutineContext[Job]).cancelAndJoin()
                store.load().endpoints.mapNotNull { it.credential }.forEach { fixture.credentials.remove(it) }
            }
        }
    }

    private suspend fun AssistantRuntimeSettingsPort.await(predicate: (AssistantSettings) -> Boolean) =
        withTimeout(5000) { settings.first { predicate(it) } }

    private fun assertWiped(key: CharArray) = assertTrue(key.all { it == '\u0000' })

    private class TestPreferences : AssistantPreferenceControls {
        override val preferences = MutableStateFlow(AssistantPreferences())
        val calls = mutableListOf<String>()
        override fun learn(on: Boolean) {
            calls.add("learn:$on")
            preferences.value = preferences.value.copy(learn = on)
        }
        override fun useRules(on: Boolean) {
            calls.add("rules:$on")
            preferences.value = preferences.value.copy(useRules = on)
        }
        override fun clearPreferences() {
            calls.add("clear")
            preferences.value = AssistantPreferences()
        }
    }
}
