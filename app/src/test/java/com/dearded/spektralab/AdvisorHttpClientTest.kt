package com.dearded.spektralab

import com.dearded.spektralab.advisor.network.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/** Loopback fake server, test-key encryption and test-only URL routing; no TLS/provider claim. */
class AdvisorHttpClientTest {
    private val loopback = "127.0.0.1"
    private val server = MockWebServer().also { it.start(InetAddress.getByName(loopback), 0) }
    private val dir = java.nio.file.Files.createTempDirectory("advisor-http-key").toFile()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val credentials = CredentialStore(dir, CredentialKeySource { key })
    private val base = EndpointConfig("endpoint", 1, "Synthetic", "https://unit.invalid/v1".toHttpUrl(),
        AdvisorTransport.RESPONSES, AdvisorDialect.OPENAI, AdvisorAuth.BEARER)
    private val endpoint = base.copy(credential = credentials.store(base, "synthetic-secret".toCharArray()))
    private val binding = ModelBinding("binding", 1, endpoint.id, "synthetic-model")
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val target = server.url(chain.request().url.encodedPath).newBuilder().host(loopback).build()
        chain.proceed(chain.request().newBuilder().url(target).build())
    }.build()
    private val http = AdvisorHttpClient(client)

    @After fun close() {
        client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        server.shutdown(); credentials.remove(checkNotNull(endpoint.credential)); dir.delete()
    }

    @Test(timeout = 15000) fun sendsExactlyOneExplicitPostWithoutDefaultExtraHeaders() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        assertEquals("{\"ok\":true}", http.post(endpoint, binding, "{\"input\":\"synthetic\"}", credentials))
        val request = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method); assertEquals("/v1/responses", request.path)
        assertEquals("Bearer synthetic-secret", request.getHeader("Authorization"))
        assertEquals("{\"input\":\"synthetic\"}", request.body.readUtf8())
        assertNull(request.getHeader("x-api-key")); assertEquals(1, server.requestCount)
    }

    @Test(timeout = 15000) fun redirectRateLimitAndRetryAfterZeroNeverTriggerAnotherPost() = runBlocking {
        for (code in listOf(301, 307, 308, 408, 421, 429, 500, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", server.url("/different"))
                .setHeader("Retry-After", "0").setBody("synthetic-error-body"))
            val before = server.requestCount
            try { http.post(endpoint, binding, "{}", credentials); fail("HTTP $code accepted") }
            catch (failure: AdvisorNetworkFailure) {
                assertEquals(code, failure.status)
                assertFalse(failure.message!!.contains("synthetic-error-body"))
                assertFalse(failure.message!!.contains("synthetic-secret"))
            }
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test(timeout = 15000) fun decompressedResponseLimitAndMalformedUtf8AreRejected() = runBlocking {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.writeUtf8("x".repeat(4096)) }
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(compressed))
        try { AdvisorHttpClient(client, 1024).post(endpoint, binding, "{}", credentials); fail("gzip limit") }
        catch (failure: AdvisorNetworkFailure) { assertEquals(AdvisorNetworkError.TOO_LARGE, failure.category) }
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(0xc3.toByte(), 0x28))))
        try { http.post(endpoint, binding, "{}", credentials); fail("invalid UTF-8") }
        catch (failure: AdvisorNetworkFailure) { assertEquals(AdvisorNetworkError.INVALID_RESPONSE, failure.category) }
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = 15000) fun cancelAfterHeadersInterruptsBodyReadWithoutRetry() = runBlocking {
        server.enqueue(MockResponse().setBody("{}".repeat(1024)).throttleBody(1, 1, TimeUnit.SECONDS))
        val request = launch { http.post(endpoint, binding, "{}", credentials) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
        withTimeout(3000) { request.cancelAndJoin() }
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 15000) fun disconnectedPostDoesNotReconnectAutomatically() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try { http.post(endpoint, binding, "{}", credentials); fail("disconnection accepted") }
        catch (failure: AdvisorNetworkFailure) { assertEquals(AdvisorNetworkError.TRANSPORT, failure.category) }
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 25000) fun nonStreamingReplyCanWaitTwelveSecondsForHeadersWithoutRetry() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(12, TimeUnit.SECONDS).setBody("{\"ok\":true}"))
        assertEquals("{\"ok\":true}", http.post(endpoint, binding, "{}", credentials))
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 15000) fun totalDeadlineBoundsHeadersAndBodyWithoutRetry() = runBlocking {
        val bounded = AdvisorHttpClient(client, callTimeoutMillis = 500)
        for ((stage, response) in listOf(
            "response headers" to MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("{}"),
            "response body" to MockResponse().setBodyDelay(2, TimeUnit.SECONDS).setBody("{}"),
        )) {
            server.enqueue(response)
            try { bounded.post(endpoint, binding, "{}", credentials); fail("deadline ignored") }
            catch (failure: AdvisorNetworkFailure) {
                assertEquals(stage, AdvisorNetworkError.TIMEOUT, failure.category)
                assertNull(failure.status)
            }
        }
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = 15000) fun cancelBeforeHeadersRemainsCancellationWithoutRetry() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("{}"))
        val request = async { http.post(endpoint, binding, "{}", credentials) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
        withTimeout(3000) { request.cancelAndJoin() }
        assertTrue(request.isCancelled)
        assertEquals(1, server.requestCount)
    }
}
