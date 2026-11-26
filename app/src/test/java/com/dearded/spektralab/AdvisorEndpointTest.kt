package com.dearded.spektralab

import com.dearded.spektralab.advisor.network.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

class AdvisorEndpointTest {
    private fun endpoint(address: String = "https://example.invalid/v1/") = EndpointConfig("endpoint", 1, "Synthetic",
        address.toHttpUrl(), AdvisorTransport.CHAT, AdvisorDialect.GENERIC_CHAT, AdvisorAuth.BEARER)
    private inline fun refused(block: () -> Unit) {
        try { block(); fail("invalid configuration accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun resourceJoiningKeepsBasePrefixAndEncodesModelAsOneSegment() {
        assertEquals("https://example.invalid/v1/chat/completions", endpoint().requestUrl("custom/model").toString())
        assertEquals("https://example.invalid/proxy/v1/chat/completions",
            endpoint("https://example.invalid/proxy/v1").requestUrl("model").toString())
        val google = endpoint().copy(transport = AdvisorTransport.GENERATE_CONTENT, dialect = AdvisorDialect.GOOGLE)
        assertEquals("https://example.invalid/v1/models/custom%2Fmodel:generateContent", google.requestUrl("custom/model").toString())
        assertEquals("https://example.invalid/v1/custom/chat", endpoint().copy(pathOverride = "custom/chat").requestUrl("model").toString())
    }

    @Test fun unsafeUrlsPathsHeadersAndProtocolCombinationsAreRejected() {
        for (url in listOf("http://example.invalid/v1", "https://user:secret@example.invalid/v1",
            "https://example.invalid/v1?key=synthetic", "https://example.invalid/v1#fragment")) refused { endpoint(url) }
        for (path in listOf("//other.invalid/path", "../responses", "v1/../responses", "responses?key=x", "%2e%2e/responses"))
            refused { endpoint().copy(pathOverride = path) }
        refused { endpoint().copy(requiredHeaders = mapOf("Authorization" to "synthetic")) }
        refused { endpoint().copy(requiredHeaders = mapOf("Api-Revision" to "x\r\ny")) }
        refused { endpoint().copy(dialect = AdvisorDialect.ANTHROPIC) }
    }

    @Test fun credentialsAreDetachedWhenHostOrPortChanges() {
        val initial = endpoint()
        val bound = initial.copy(credential = CredentialRef(UUID.randomUUID().toString(), initial.id, initial.origin, 1))
        assertNotNull(bound.atAddress("https://example.invalid/other").credential)
        assertNull(bound.atAddress("https://second.invalid/v1").credential)
        assertNull(bound.atAddress("https://example.invalid:8443/v1").credential)
        assertTrue(bound.atAddress("https://second.invalid").revision > bound.revision)
    }

    @Test fun encryptedStoreUsesFreshNoncesAndAuthenticatesOriginRevisionAndCiphertext() {
        val dir = java.nio.file.Files.createTempDirectory("advisor-key-test").toFile()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = CredentialStore(dir, CredentialKeySource { key })
        val original = endpoint()
        val secret = "synthetic-test-value".toCharArray()
        val first = store.store(original, secret)
        assertTrue(secret.all { it == '\u0000' })
        val bound = original.copy(credential = first)
        var held: CharArray? = null
        store.use(bound) { held = it; assertEquals("synthetic-test-value", String(it)) }
        assertTrue(checkNotNull(held).all { it == '\u0000' })
        val second = store.store(bound, "synthetic-test-value".toCharArray())
        val bytes = File(dir, "${first.id}.bin").readBytes()
        val newer = File(dir, "${second.id}.bin").readBytes()
        assertFalse(bytes.copyOfRange(8, 20).contentEquals(newer.copyOfRange(8, 20)))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("synthetic-test-value"))
        fun unavailable(config: EndpointConfig) {
            try { store.use(config) { fail("tampered credential read") }; fail("credential accepted") }
            catch (_: CredentialUnavailable) { }
        }
        unavailable(bound.copy(credential = first.copy(revision = first.revision + 1)))
        unavailable(bound.copy(baseUrl = "https://other.invalid/v1".toHttpUrl(),
            credential = first.copy(origin = "https://other.invalid:443")))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        File(dir, "${first.id}.bin").writeBytes(bytes)
        unavailable(bound)
        val wrongKey = CredentialStore(dir, CredentialKeySource { KeyGenerator.getInstance("AES").generateKey() })
        try { wrongKey.use(original.copy(credential = second)) { fail("lost key read") }; fail("invalid key accepted") }
        catch (_: CredentialUnavailable) { }
        assertTrue(store.remove(first)); assertTrue(store.remove(second))
        dir.delete()
    }

    @Test fun permanentlyInvalidKeyCanOnlyBeReplacedByAnExplicitCredentialSave() {
        val dir = java.nio.file.Files.createTempDirectory("advisor-invalid-key").toFile()
        val good = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var invalid = false
        var replacements = 0
        val source = object : CredentialKeySource {
            override fun key(): javax.crypto.SecretKey {
                if (invalid) throw android.security.keystore.KeyPermanentlyInvalidatedException()
                return good
            }
            override fun replacePermanentlyInvalidKey(): javax.crypto.SecretKey { replacements++; invalid = false; return good }
        }
        val store = CredentialStore(dir, source)
        val initial = endpoint()
        val first = store.store(initial, "synthetic-first".toCharArray())
        invalid = true
        try { store.use(initial.copy(credential = first)) { fail("invalid read") }; fail("invalid key accepted") }
        catch (_: CredentialUnavailable) { }
        assertEquals(0, replacements)
        val second = store.store(initial.copy(credential = first), "synthetic-reentered".toCharArray())
        assertEquals(1, replacements)
        store.use(initial.copy(credential = second)) { assertEquals("synthetic-reentered", String(it)) }
        store.remove(first); store.remove(second); dir.delete()
    }
}
