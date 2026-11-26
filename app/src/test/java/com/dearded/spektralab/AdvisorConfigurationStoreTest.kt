package com.dearded.spektralab

import com.dearded.spektralab.advisor.network.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AdvisorConfigurationStoreTest {
    private val dir = java.nio.file.Files.createTempDirectory("advisor-settings").toFile()
    private val file = File(dir, "settings.json")
    private val store = AdvisorConfigurationStore(file)
    private val endpoint = EndpointConfig("endpoint", 100, "Synthetic", "https://unit.invalid/v1".toHttpUrl(),
        AdvisorTransport.CHAT, AdvisorDialect.GENERIC_CHAT, AdvisorAuth.BEARER, chatOutputLimit = ChatOutputLimit.MAX_TOKENS)
    private val binding = ModelBinding("binding", 100, endpoint.id, "synthetic-model")
    private val config = AdvisorConfiguration(endpoints = listOf(endpoint), bindings = listOf(binding), selectedBindingId = binding.id)

    @After fun clean() { file.delete(); File(dir, "settings.json.tmp").delete(); dir.delete() }

    @Test fun explicitConfigurationRoundTripPreservesSelectionAndOnlyCredentialReferences() {
        assertNull(store.load().selected())
        val ref = CredentialRef(UUID.randomUUID().toString(), endpoint.id, endpoint.origin, 1)
        val result = store.save(0, config.copy(endpoints = listOf(endpoint.copy(credential = ref))))
        assertEquals(1, result.revision); assertEquals(1, result.endpoints.single().revision)
        assertEquals(result, AdvisorConfigurationStore(file).load())
        assertEquals(ref, result.selected()!!.first.credential)
        assertEquals(ChatOutputLimit.MAX_TOKENS, result.selected()!!.first.chatOutputLimit)
        val text = file.readText()
        for (word in listOf("Authorization", "secret", "goal", "photo_uri", "history", "profile")) assertFalse(text.contains(word))
    }

    @Test fun olderSettingsCannotOverwriteNewerSelectionEvenAcrossStoreInstances() {
        val first = store.save(0, config)
        val second = AdvisorConfigurationStore(file).save(first.revision, first.copy(bindings = listOf(first.bindings.single().copy(remoteModelId = "changed"))))
        assertEquals(2, second.revision); assertEquals(2, second.bindings.single().revision)
        assertEquals(1, second.endpoints.single().revision)
        try { store.save(first.revision, first); fail("stale settings accepted") } catch (_: AdvisorConfigurationConflict) { }
        assertEquals(second, store.load())
        val back = store.save(second.revision, first)
        assertEquals(3, back.bindings.single().revision); assertEquals("synthetic-model", back.bindings.single().remoteModelId)
    }

    @Test fun changedHostCannotReuseTheSameCredentialIdentity() {
        val ref = CredentialRef(UUID.randomUUID().toString(), endpoint.id, endpoint.origin, 1)
        val saved = store.save(0, config.copy(endpoints = listOf(endpoint.copy(credential = ref))))
        val address = "https://different.invalid/v1".toHttpUrl()
        val changed = saved.endpoints.single().copy(baseUrl = address, credential = ref.copy(origin = "https://different.invalid:443"))
        val next = store.save(saved.revision, saved.copy(endpoints = listOf(changed)))
        assertNull(next.endpoints.single().credential); assertEquals(2, next.endpoints.single().revision)
    }

    @Test fun damagedUnknownAndFailedWritesPreserveTheExistingFile() {
        for (bad in listOf("{", "{\"schema_version\":2}", "{\"schema_version\":1,\"schema_version\":1}")) {
            file.writeText(bad)
            try { store.load(); fail("invalid file silently reset") } catch (_: AdvisorConfigurationUnavailable) { }
            try { store.save(0, config); fail("damaged file overwritten") } catch (_: AdvisorConfigurationUnavailable) { }
            assertEquals(bad, file.readText())
        }
        file.delete()
        val saved = store.save(0, config)
        val bytes = file.readBytes()
        val block = File(dir, "settings.json.tmp").also { assertTrue(it.mkdir()) }
        try { store.save(saved.revision, saved); fail("write falsely reported success") }
        catch (_: AdvisorConfigurationUnavailable) { }
        assertArrayEquals(bytes, file.readBytes()); block.delete()
        assertEquals(saved, store.load())
    }
}
