package com.dearded.spektralab.advisor.network

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSink
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

enum class AdvisorNetworkError { AUTH, NOT_FOUND, RATE_LIMIT, REDIRECT, SERVER, HTTP, TRANSPORT, TIMEOUT, TOO_LARGE, INVALID_RESPONSE }
class AdvisorNetworkFailure(val category: AdvisorNetworkError, val status: Int? = null) :
    IOException("Advisor request failed: ${category.name}")

/** No logging, redirects, protocol fallback or paid retries. One operation creates one POST body. */
class AdvisorHttpClient internal constructor(base: OkHttpClient = OkHttpClient(),
                                             private val maxResponseBytes: Long = 2L * 1024 * 1024,
                                             callTimeoutMillis: Long = 120_000L) {
    init { require(callTimeoutMillis in 1..120_000L) }
    private val client = base.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        // Non-streaming models may not send headers until the full answer is ready.
        .cache(null).connectTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS).build()

    suspend fun post(endpoint: EndpointConfig, binding: ModelBinding, json: String, credentials: CredentialStore): String = coroutineScope {
        require(binding.endpointId == endpoint.id)
        val frozen = endpoint.copy(requiredHeaders = endpoint.requiredHeaders.toMap())
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024 * 1024)
        val builder = Request.Builder().url(frozen.requestUrl(binding.remoteModelId)).header("Accept", "application/json")
            .post(object : RequestBody() {
                override fun contentType() = "application/json; charset=utf-8".toMediaType()
                override fun contentLength() = bytes.size.toLong()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
            })
        frozen.requiredHeaders.forEach { (name, value) -> builder.header(name, value) }
        withContext(Dispatchers.IO) {
            credentials.use(frozen) { secret ->
                val value = String(secret)
                when (frozen.auth) {
                    AdvisorAuth.BEARER -> builder.header("Authorization", "Bearer $value")
                    AdvisorAuth.X_API_KEY -> builder.header("x-api-key", value)
                    AdvisorAuth.X_GOOG_API_KEY -> builder.header("x-goog-api-key", value)
                }
            }
        }
        ensureActive()
        val call = client.newCall(builder.build())
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw AdvisorNetworkFailure(when (response.code) {
                        401, 403 -> AdvisorNetworkError.AUTH
                        404 -> AdvisorNetworkError.NOT_FOUND
                        429 -> AdvisorNetworkError.RATE_LIMIT
                        in 300..399 -> AdvisorNetworkError.REDIRECT
                        in 500..599 -> AdvisorNetworkError.SERVER
                        else -> AdvisorNetworkError.HTTP
                    }, response.code)
                    val body = response.body ?: throw AdvisorNetworkFailure(AdvisorNetworkError.INVALID_RESPONSE)
                    if (body.contentLength() > maxResponseBytes) throw AdvisorNetworkFailure(AdvisorNetworkError.TOO_LARGE)
                    val buffer = Buffer()
                    val source = body.source()
                    while (buffer.size <= maxResponseBytes) {
                        val read = source.read(buffer, minOf(8192L, maxResponseBytes + 1 - buffer.size))
                        if (read == -1L) break
                    }
                    if (buffer.size > maxResponseBytes) throw AdvisorNetworkFailure(AdvisorNetworkError.TOO_LARGE)
                    try {
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer.readByteArray())).toString()
                    } catch (_: java.nio.charset.CharacterCodingException) {
                        throw AdvisorNetworkFailure(AdvisorNetworkError.INVALID_RESPONSE)
                    }
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: AdvisorNetworkFailure) { throw e
        } catch (_: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw AdvisorNetworkFailure(AdvisorNetworkError.TIMEOUT)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw AdvisorNetworkFailure(AdvisorNetworkError.TRANSPORT)
        } finally {
            cancellation.cancel()
            bytes.fill(0)
        }
    }
}
