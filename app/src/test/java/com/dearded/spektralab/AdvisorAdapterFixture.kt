package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.*
import com.dearded.spektralab.advisor.network.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import javax.crypto.KeyGenerator

internal object AdvisorWireFixtures {
    val schema = File("src/main/assets/advisor/advice-result.v2.schema.json").readText()
    val catalog = ModelCatalog.parse(File("src/main/assets/advisor/model-catalog.v1.json").readText())
    val request = AdviceRequest.from(EditSnapshot(Catalog.EMPTY.normalize(Edit.NONE),
        RequestTicket(1, 1, 0, 1, 1, 0)), AdviceConstraints.all(), "Synthetic brighter goal")
    const val noChange = """{"schema_version":2,"status":"no_change","changes":[],"question":{"kind":"none","prompt":"","options":[]}}"""
    const val proposal = """{"schema_version":2,"status":"proposal","changes":[{"parameter":"layer2.exposure","target":0.25}],"question":{"kind":"none","prompt":"","options":[]}}"""
    fun responses(text: String = proposal, status: String = "completed"): JSONObject = JSONObject()
        .put("status", status).put("output", JSONArray().put(message(text)))
    fun message(text: String = proposal): JSONObject = JSONObject().put("type", "message")
        .put("role", "assistant").put("status", "completed")
        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))
}

/** Synthetic loopback only; the URL interceptor must never be used in production. */
internal class AdvisorAdapterFixture : Closeable {
    val server = MockWebServer().also { it.start() }
    private val dir = java.nio.file.Files.createTempDirectory("advisor-adapter-key").toFile()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val credentials = CredentialStore(dir, CredentialKeySource { key })
    private val refs = mutableListOf<CredentialRef>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
    }.build()
    val http = AdvisorHttpClient(client)
    fun credentialFileCount() = dir.listFiles().orEmpty().count { it.extension == "bin" }

    fun endpoint(transport: AdvisorTransport = AdvisorTransport.RESPONSES,
                 dialect: AdvisorDialect = AdvisorDialect.OPENAI,
                 auth: AdvisorAuth = AdvisorAuth.BEARER,
                 schema: AdvisorSchemaMode = AdvisorSchemaMode.JSON_SCHEMA,
                 headers: Map<String, String> = emptyMap()): EndpointConfig {
        val base = EndpointConfig("endpoint", 1, "Synthetic", "https://unit.invalid/v1".toHttpUrl(),
            transport, dialect, auth, schema, requiredHeaders = headers)
        val ref = credentials.store(base, "synthetic-secret".toCharArray()).also { refs.add(it) }
        return base.copy(credential = ref)
    }

    fun gateway(vararg adapters: AdviceAdapter) = AdvisorNetworkGateway(http, credentials,
        AdvisorWireFixtures.catalog, adapters.toList())

    override fun close() {
        client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        server.shutdown(); refs.forEach { credentials.remove(it) }; dir.delete()
    }
}
