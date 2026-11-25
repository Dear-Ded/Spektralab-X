package com.dearded.spektralab

import com.dearded.spektralab.advisor.domain.*
import com.dearded.spektralab.advisor.network.*
import com.dearded.spektralab.advisor.session.RequestContextBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class AdvisorDirectionalLightTest {
    private val ticket = RequestTicket(1, 1, 0, 1, 1, 0)
    private val manual = EditMask(kind = MaskKind.RADIAL, tone = MaskTone(saturation = -6.0))
    private val entry = EditSnapshot(Edit.NONE.copy(quarterTurns = 1, flipH = true, cropW = .85,
        look = Look(exposure = .25, contrast = 5.0, masks = listOf(manual))), ticket)

    @Test fun `bright side needs consistent evidence along the whole edge`() {
        fun measure(sample: (Int, Int) -> Double) = measuredBrightSide(40, 20, DoubleArray(800) { sample(it % 40, it / 40) })
        assertEquals(BrightSide.RIGHT, measure { x, _ -> .15 + x * .012 })
        assertEquals(BrightSide.LEFT, measure { x, _ -> .65 - x * .012 })
        assertEquals(BrightSide.TOP, measure { _, y -> .65 - y * .02 })
        assertEquals(BrightSide.BOTTOM, measure { _, y -> .15 + y * .02 })
        assertNull(measure { x, y -> if (x >= 32 && y < 5) .85 else .24 })
        assertNull(measure { x, y -> .15 + x * .01 + y * .021 })
        assertNull(measure { _, _ -> .40 })
    }

    @Test fun `inset rejects narrow rims but keeps broad steps and ramps on either aspect`() {
        for ((w, h) in listOf(128 to 128, 128 to 64, 64 to 128, 4 to 4)) for (side in BrightSide.entries) {
            val length = if (side == BrightSide.LEFT || side == BrightSide.RIGHT) w else h
            fun measure(byDistance: (Int) -> Double) = measuredBrightSide(w, h, DoubleArray(w * h) { i ->
                byDistance(when (side) {
                    BrightSide.LEFT -> i % w
                    BrightSide.RIGHT -> w - 1 - i % w
                    BrightSide.TOP -> i / w
                    BrightSide.BOTTOM -> h - 1 - i / w
                })
            })
            val label = "${w}x$h $side"
            val rim = if (length == 4) 1 else 4
            assertNull("rim $label", measure { if (it < rim) .95 else .20 })
            assertEquals("step $label", side, measure { if (it < length / 2) .75 else .20 })
            assertEquals("ramp $label", side, measure { .75 - .55 * it / (length - 1) })
        }
    }

    @Test fun `an inset with the opposite sign does not confirm an otherwise strong edge`() {
        val luma = DoubleArray(128 * 64) { i ->
            when (i % 128) {
                in 0..3 -> .95
                in 91..115 -> .28
                else -> .20
            }
        }
        assertNull(measuredBrightSide(128, 64, luma))
    }

    @Test fun `existing mask engine receives one broad complementary axis and no other changes`() {
        for (side in BrightSide.entries) {
            val light = DirectionalLight(side, .6, -1.2)
            val candidate = AdviceProjection.directionalCandidate(entry, light, side, AdviceOrigin.Local)
            assertEquals(entry.edit, candidate.edit.copy(look = candidate.edit.look.copy(masks = listOf(manual))))
            val (dark, bright) = candidate.edit.look.masks.drop(1)
            assertEquals(listOf(MaskKind.LINEAR, MaskKind.LINEAR), listOf(dark.kind, bright.kind))
            assertTrue(dark.inverted)
            assertFalse(bright.inverted)
            assertEquals(bright.copy(inverted = true, tone = dark.tone), dark)
            for (aspect in listOf(2.0, .5)) for (cross in listOf(0.0, .2, .8, 1.0)) {
                fun cover(mask: EditMask, along: Double) = when (side) {
                    BrightSide.LEFT -> LocalEdits.coverage(mask, 1 - along, cross, aspect)
                    BrightSide.RIGHT -> LocalEdits.coverage(mask, along, cross, aspect)
                    BrightSide.TOP -> LocalEdits.coverage(mask, cross, 1 - along, aspect)
                    BrightSide.BOTTOM -> LocalEdits.coverage(mask, cross, along, aspect)
                }
                for (along in listOf(0.0, .15, .25, .5, .75, .85, 1.0))
                    assertEquals(1.0, cover(dark, along) + cover(bright, along), 1e-12)
                assertEquals(0.0, cover(bright, .15), 1e-12)
                assertEquals(.5, cover(bright, .5), 1e-12)
                assertEquals(1.0, cover(bright, .85), 1e-12)
            }
            assertEquals(candidate.edit.look, Look.fromJson(candidate.edit.look.toJson()))
        }
        assertFalse(AdviceProjection.canPlaceDirectionalLight(entry.edit, AdviceConstraints(emptyMap(), 0)))
        val full = entry.edit.copy(look = entry.edit.look.copy(masks = List(LocalEdits.MAX_MASKS - 1) { manual }))
        assertFalse(AdviceProjection.canPlaceDirectionalLight(full, AdviceConstraints.all()))
        assertEquals(AdviceError.LOCK_CONFLICT, assertThrows(AdviceFailure::class.java) {
            AdviceProjection.directionalCandidate(entry, DirectionalLight(BrightSide.LEFT, .3, -.8), BrightSide.RIGHT, null)
        }.code)
    }

    @Test fun `directional mask edits survive persistence before native projection`() {
        val base = EditSnapshot(Edit.NONE, ticket)
        val rows = mutableListOf<Pair<String, Look>>("base" to base.edit.look)
        for (side in BrightSide.entries) {
            val light = DirectionalLight(side, .6, -1.2)
            val candidate = AdviceProjection.directionalCandidate(base, light, side, AdviceOrigin.Local)
            for (percent in listOf(50, 100)) {
                val look = base.edit.look.copy(masks = light.masks(percent))
                val restored = Look.fromJson(look.toJson())
                assertArrayEquals(look.values(), restored.values(), 0f)
                if (percent == 100) assertEquals(candidate.edit.look, restored)
                rows += "${side.wire}_$percent" to restored
            }
        }
        System.getenv("SPK_DIRECTIONAL_WIRE")?.let { output ->
            File(output).writeText(rows.joinToString("\n", postfix = "\n") { (name, look) ->
                look.values().let { "$name ${it.size} ${it.joinToString(" ")}" }
            })
        }
    }

    @Test fun `directional result is strict about version signs and nonproposal payload`() {
        assertEquals(AdviceResult.Proposal(emptyList(), DirectionalLight(BrightSide.RIGHT, .6, -1.2)),
            StrictResultParser.parse(PAYLOAD, 3))
        assertEquals(AdviceResult.NoChange, StrictResultParser.parse(
            """{"schema_version":3,"status":"no_change","directional_light":{"bright_side":"none","bright_ev":0,"dark_ev":0}}""", 3))
        for ((text, code) in listOf(
            AdvisorWireFixtures.noChange to AdviceError.SCHEMA_MISMATCH,
            PAYLOAD.replace("\"right\"", "\"diagonal\"") to AdviceError.INVALID_STATUS_PAYLOAD,
            PAYLOAD.replace("-1.2", "0.4") to AdviceError.OUT_OF_RANGE,
            PAYLOAD.replace("0.6", "1.1") to AdviceError.OUT_OF_RANGE,
            PAYLOAD.replace("\"proposal\"", "\"no_change\"") to AdviceError.INVALID_STATUS_PAYLOAD,
            PAYLOAD.replace("\"dark_ev\":-1.2", "\"dark_ev\":-1.2,\"dark_ev\":-0.5") to AdviceError.INVALID_JSON,
            PAYLOAD.replace("\"dark_ev\":-1.2", "\"dark_ev\":-1.2,\"mask\":[]") to AdviceError.SCHEMA_MISMATCH,
        )) assertEquals(code, assertThrows(AdviceFailure::class.java) { StrictResultParser.parse(text, 3) }.code)
    }

    @Test(timeout = 15000) fun `loopback uses the bundled v3 schema and rejects a v2 response`() = runBlocking<Unit> {
        val adapter = ResponsesAdapter(AdvisorWireFixtures.schema,
            File("src/main/assets/advisor/directional-light.v3.schema.json").readText())
        val request = AdviceRequest.from(entry, AdviceConstraints(emptyMap(), 0), "Directional light", brightSide = BrightSide.RIGHT)
        val context = JSONObject(RequestContextBuilder.parameters(request))
        assertEquals("right", context.getString("measured_bright_side"))
        assertEquals(0, context.getJSONObject("allowed_parameters").length())
        assertFalse(context.has("masks"))
        AdvisorAdapterFixture().use { f ->
            val gateway = f.gateway(adapter)
            val endpoint = f.endpoint()
            val model = AdvisorWireFixtures.catalog.models.values.first { it.provider == "openai" }
            val binding = ModelBinding("binding", 1, "endpoint", "synthetic-model", model.id, model.initialMode, model.initialEffort)
            f.server.enqueue(MockResponse().setBody(AdvisorWireFixtures.responses(PAYLOAD).toString()))
            assertEquals(BrightSide.RIGHT, (gateway.generate(endpoint, binding, request).result as AdviceResult.Proposal)
                .directionalLight!!.brightSide)
            val body = JSONObject(checkNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8())
            val format = body.getJSONObject("text").getJSONObject("format")
            assertEquals("spektralab_advice_v3", format.getString("name"))
            val required = format.getJSONObject("schema").getJSONArray("required")
            assertEquals(listOf("schema_version", "status", "directional_light"),
                List(required.length()) { required.getString(it) })
            f.server.enqueue(MockResponse().setBody(AdvisorWireFixtures.responses(AdvisorWireFixtures.noChange).toString()))
            val error = runCatching { gateway.generate(endpoint, binding, request) }.exceptionOrNull() as AdviceFailure
            assertEquals(AdviceError.SCHEMA_MISMATCH, error.code)
        }
    }

    private companion object {
        const val PAYLOAD = """{"schema_version":3,"status":"proposal","directional_light":{"bright_side":"right","bright_ev":0.6,"dark_ev":-1.2}}"""
    }
}
