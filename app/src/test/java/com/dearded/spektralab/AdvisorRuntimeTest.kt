package com.dearded.spektralab

import com.dearded.spektralab.advisor.network.*
import com.dearded.spektralab.advisor.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class AdvisorRuntimeTest {
    private val catalog get() = AdvisorWireFixtures.catalog
    private fun config(): Pair<EndpointConfig, ModelBinding> {
        val endpoint = EndpointConfig("runtime-test", 1, "OpenAI", "https://api.openai.com/v1".toHttpUrl(),
            AdvisorTransport.RESPONSES, AdvisorDialect.OPENAI, AdvisorAuth.BEARER, AdvisorSchemaMode.JSON_SCHEMA)
        val model = catalog.models.getValue("openai/gpt-6-luna")
        return endpoint to ModelBinding("8a6d9643-6565-42a9-bffa-bc54c2073001", 1, endpoint.id, model.remoteId,
            catalogTemplateId = model.id, reasoningMode = model.initialMode, reasoningEffort = model.initialEffort)
    }
    private suspend fun AdvisorRuntime.idle() = withTimeout(5000) { state.first { !it.loading && !it.saving && !it.testing } }

    @Test fun connectionProvenanceUsesFrozenLocalVersionsAndNeverEndpointOrRemoteModelText() {
        AdvisorAdapterFixture().use { f ->
            val (endpoint, binding) = config()
            val config = AdvisorConfiguration(3, listOf(endpoint), listOf(binding), binding.id)
            val gateway = f.gateway(ResponsesAdapter(AdvisorWireFixtures.schema))
            val first = checkNotNull(AdviceConnection.from(config, catalog.version, gateway)?.origin)
            assertEquals(binding.id, first.bindingId)
            assertEquals(3L, first.configurationRevision)
            assertEquals(endpoint.revision, first.endpointRevision)
            val next = config.copy(revision = 4, endpoints = listOf(endpoint.copy(revision = endpoint.revision + 1)))
            val changed = checkNotNull(AdviceConnection.from(next, catalog.version, gateway)?.origin)
            assertNotEquals(first.localVersion, changed.localVersion)
            assertEquals(3L, first.configurationRevision)
            val untracked = config.copy(bindings = listOf(binding.copy(id = "custom/name")), selectedBindingId = "custom/name")
            assertNull(AdviceConnection.from(untracked, catalog.version, gateway)?.origin)
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test(timeout = 15000) fun saveReloadSelectAndRemoveNeverSendHttpAndPlaintextKeyIsNotStoredInSettings() = runBlocking<Unit> {
        AdvisorAdapterFixture().use { f ->
            val dir = java.nio.file.Files.createTempDirectory("advisor-runtime").toFile()
            val file = File(dir, "settings.json")
            val service = AdvisorSettingsService(catalog, f.gateway(ResponsesAdapter(AdvisorWireFixtures.schema)), AdvisorConfigurationStore(file), f.credentials)
            val connected = mutableListOf<AdviceConnection?>()
            val runtime = AdvisorRuntime(this, { service }, connected::add)
            runtime.idle(); assertNull(runtime.state.value.selected)
            val (endpoint, binding) = config()
            val key = "synthetic-runtime-key".toCharArray()
            runtime.save(0, endpoint, binding, key)
            assertTrue(key.all { it == '\u0000' })
            runtime.idle(); assertNull(runtime.state.value.error)
            assertNotNull(connected.last()); assertFalse(file.readText().contains("synthetic-runtime-key"))
            val loaded = runtime.state.value.configuration
            runtime.reload(); runtime.idle(); assertEquals(loaded, runtime.state.value.configuration)
            runtime.select(null); runtime.idle(); assertNull(connected.last())
            runtime.select(binding.id); runtime.idle(); assertNotNull(connected.last())
            runtime.remove(binding.id); runtime.idle(); assertNull(connected.last())
            assertTrue(runtime.state.value.configuration.endpoints.isEmpty()); assertEquals(0, f.server.requestCount)
            file.delete(); dir.delete()
        }
    }

    @Test(timeout = 15000) fun explicitProbeUsesSyntheticLockedParametersExactlyOnceAndDoesNotVerifyVision() = runBlocking<Unit> {
        AdvisorAdapterFixture().use { f ->
            val dir = java.nio.file.Files.createTempDirectory("advisor-probe").toFile(); val file = File(dir, "settings.json")
            val service = AdvisorSettingsService(catalog, f.gateway(ResponsesAdapter(AdvisorWireFixtures.schema)), AdvisorConfigurationStore(file), f.credentials)
            val (endpoint, binding) = config()
            service.save(0, endpoint, binding, "synthetic-key".toCharArray())
            val runtime = AdvisorRuntime(this, { service }, {})
            runtime.idle(); assertEquals(0, f.server.requestCount)
            f.server.enqueue(MockResponse().setBody(AdvisorWireFixtures.responses(AdvisorWireFixtures.noChange).toString()))
            runtime.testConnection(); runtime.testConnection(); runtime.idle()
            assertTrue(runtime.state.value.probePassed); assertFalse(runtime.state.value.selected!!.second.visionEnabled)
            val request = checkNotNull(f.server.takeRequest(2, TimeUnit.SECONDS))
            val input = JSONObject(JSONObject(request.body.readUtf8()).getJSONArray("input").getJSONObject(0).getString("content"))
            assertEquals(0, input.getJSONObject("allowed_parameters").length()); assertEquals(8, input.getJSONArray("locked_parameters").length())
            assertTrue(input.getString("goal").startsWith("Connection check with synthetic"))
            assertEquals(1, f.server.requestCount)
            runtime.remove(binding.id); runtime.idle(); file.delete(); dir.delete()
        }
    }

    @Test(timeout = 15000) fun failedWritesDoNotLeaveOrphanKeysAndHostChangesRequireNewInput() = runBlocking<Unit> {
        AdvisorAdapterFixture().use { f ->
            val dir = java.nio.file.Files.createTempDirectory("advisor-failure").toFile(); val file = File(dir, "settings.json")
            val service = AdvisorSettingsService(catalog, f.gateway(ResponsesAdapter(AdvisorWireFixtures.schema)), AdvisorConfigurationStore(file), f.credentials)
            val (endpoint, binding) = config()
            val saved = service.save(0, endpoint, binding, "synthetic-first".toCharArray())
            val keysBefore = f.credentialFileCount()
            val block = File(dir, "settings.json.tmp").also { assertTrue(it.mkdir()) }
            try { service.save(saved.revision, saved.endpoints.single(), binding, "synthetic-second".toCharArray()); fail("save failure accepted") }
            catch (_: AdvisorConfigurationUnavailable) { }
            assertEquals(keysBefore, f.credentialFileCount())
            assertEquals(saved, service.load()); f.credentials.use(saved.endpoints.single()) { assertEquals("synthetic-first", String(it)) }
            block.delete()
            val changed = saved.endpoints.single().atAddress("https://other.invalid/v1")
            try { service.save(saved.revision, changed, binding, null); fail("host change reused key") }
            catch (_: CredentialUnavailable) { }
            val next = service.save(saved.revision, changed, binding, "synthetic-new-host".toCharArray())
            assertNotEquals(saved.endpoints.single().credential, next.endpoints.single().credential)
            service.remove(next.revision, binding.id); file.delete(); dir.delete()
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test(timeout = 15000) fun explicitProbePreservesTypedFailuresAndRequiresTheSyntheticNoChangeResult() = runBlocking<Unit> {
        AdvisorAdapterFixture().use { f ->
            val dir = java.nio.file.Files.createTempDirectory("advisor-probe-errors").toFile()
            val file = File(dir, "settings.json")
            val service = AdvisorSettingsService(catalog, f.gateway(ResponsesAdapter(AdvisorWireFixtures.schema)), AdvisorConfigurationStore(file), f.credentials)
            val (endpoint, binding) = config()
            val saved = service.save(0, endpoint, binding, "synthetic-key".toCharArray())
            val runtime = AdvisorRuntime(this, { service }, {})
            try {
                assertNull(runtime.idle().probeResult)
                val refusal = AdvisorWireFixtures.responses().apply {
                    getJSONArray("output").getJSONObject(0)
                        .put("content", JSONArray().put(JSONObject().put("type", "refusal")))
                }
                val cases = listOf(
                    MockResponse().setResponseCode(401) to AdvisorProbeResult.AUTH,
                    MockResponse().setResponseCode(404) to AdvisorProbeResult.ADDRESS,
                    MockResponse().setResponseCode(302) to AdvisorProbeResult.ADDRESS,
                    MockResponse().setResponseCode(429) to AdvisorProbeResult.RATE_LIMIT,
                    MockResponse().setResponseCode(503) to AdvisorProbeResult.NETWORK,
                    MockResponse().setBody(refusal.toString()) to AdvisorProbeResult.REFUSED,
                    MockResponse().setBody(AdvisorWireFixtures.responses("not a result").toString()) to AdvisorProbeResult.INVALID,
                    MockResponse().setBody(AdvisorWireFixtures.responses().toString()) to AdvisorProbeResult.INVALID,
                )
                for ((response, expected) in cases) {
                    f.server.enqueue(response)
                    runtime.testConnection()
                    val state = runtime.idle()
                    assertEquals(expected, state.probeResult)
                    assertNull(state.error)
                    assertEquals(saved.selected(), state.requestSelection)
                }
                assertEquals(cases.size, f.server.requestCount)
            } finally {
                service.remove(saved.revision, binding.id); file.delete(); dir.delete()
            }
        }
    }

    @Test(timeout = 15000) fun failedSettingsWriteBlocksAProbeUntilTheSavedConfigurationIsReloaded() = runBlocking<Unit> {
        AdvisorAdapterFixture().use { f ->
            val dir = java.nio.file.Files.createTempDirectory("advisor-probe-after-save").toFile()
            val file = File(dir, "settings.json")
            val store = AdvisorConfigurationStore(file)
            val service = AdvisorSettingsService(catalog, f.gateway(ResponsesAdapter(AdvisorWireFixtures.schema)), store, f.credentials)
            val (endpoint, binding) = config()
            val saved = service.save(0, endpoint, binding, "synthetic-key".toCharArray())
            val connections = mutableListOf<AdviceConnection?>()
            val runtime = AdvisorRuntime(this, { service }, connections::add)
            runtime.idle()
            assertNotNull(connections.last())
            val blockedWrite = File(dir, "settings.json.tmp").also { assertTrue(it.mkdir()) }
            try {
                runtime.select(null)
                val failed = runtime.idle()
                assertEquals(AdvisorSettingsError.SAVE, failed.error)
                assertEquals(saved, store.load())
                assertNull(connections.last())
                f.server.enqueue(MockResponse().setBody(AdvisorWireFixtures.responses(AdvisorWireFixtures.noChange).toString()))
                runtime.testConnection()
                runtime.idle()
                assertEquals("A failed update must not send a probe to the previous service", 0, f.server.requestCount)
                assertEquals(saved.selected(), failed.selected)
                assertNull(failed.requestSelection)
                assertEquals(AdvisorSettingsError.SAVE, runtime.state.value.error)
                assertNull(connections.last())

                runtime.reload()
                val restored = runtime.idle()
                assertEquals(saved, restored.configuration)
                assertEquals(saved.selected(), restored.requestSelection)
                assertNotNull(connections.last())
                assertEquals(0, f.server.requestCount)
                runtime.testConnection()
                assertTrue(runtime.idle().probePassed)
                assertEquals(1, f.server.requestCount)
            } finally {
                blockedWrite.delete()
                service.remove(saved.revision, binding.id)
                file.delete(); dir.delete()
            }
        }
    }

    @Test(timeout = 15000) fun cancelProbeAndChangeConfigurationCannotPublishLateSuccess() = runBlocking<Unit> {
        AdvisorAdapterFixture().use { f ->
            val dir = java.nio.file.Files.createTempDirectory("advisor-probe-cancel").toFile(); val file = File(dir, "settings.json")
            val service = AdvisorSettingsService(catalog, f.gateway(ResponsesAdapter(AdvisorWireFixtures.schema)), AdvisorConfigurationStore(file), f.credentials)
            val (endpoint, binding) = config()
            service.save(0, endpoint, binding, "synthetic-key".toCharArray())
            val runtime = AdvisorRuntime(this, { service }, {})
            runtime.idle()
            f.server.enqueue(MockResponse().setBody(AdvisorWireFixtures.responses(AdvisorWireFixtures.noChange).toString()).setBodyDelay(5, TimeUnit.SECONDS))
            runtime.testConnection()
            assertNotNull(withContext(Dispatchers.IO) { f.server.takeRequest(3, TimeUnit.SECONDS) })
            runtime.select(null); runtime.idle()
            assertNull(runtime.state.value.selected); assertFalse(runtime.state.value.testing); assertFalse(runtime.state.value.probePassed)
            assertNull(runtime.state.value.probeResult)
            assertEquals(1, f.server.requestCount)
            service.remove(service.load().revision, binding.id); file.delete(); dir.delete()
        }
    }
}
