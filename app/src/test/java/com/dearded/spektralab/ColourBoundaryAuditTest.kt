package com.dearded.spektralab

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Import/cache/readout boundary regressions. Co-created with GPT (OpenAI). */
class ColourBoundaryAuditTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun utf8BomDoesNotChangeACubeOrItsOriginalBytes() {
        val bytes = ("\uFEFFLUT_1D_SIZE 2\n0 0 0\n1 1 1\n").toByteArray(Charsets.UTF_8)
        val parsed = CubeFile.parse(String(bytes, Charsets.UTF_8))
        assertArrayEquals(floatArrayOf(.125f, .5f, .875f), floatArrayOf(.125f, .5f, .875f).also(parsed::sample), 0f)
        val store = LutStore(temporary.newFolder("bom"))
        store.saveCube("lut_bom", parsed, bytes, SpkLut.SPACE_PROPHOTO, ImportReport())
        assertArrayEquals(bytes, store.original("lut_bom")!!.first)
        assertNotNull(store.source("lut_bom"))
    }

    @Test fun finiteWideDomainsDoNotOverflowBeforeInterpolation() {
        for (header in listOf("LUT_1D_INPUT_RANGE -3e38 3e38", "DOMAIN_MIN -3e38 -3e38 -3e38\nDOMAIN_MAX 3e38 3e38 3e38")) {
            val cube = CubeFile.parse("LUT_1D_SIZE 2\n$header\n0 0 0\n1 1 1\n")
            assertArrayEquals(header, floatArrayOf(.5f, .5f, .5f), floatArrayOf(0f, 0f, 0f).also(cube::sample), 0f)
        }
        val cube = CubeFile.parse(buildString {
            append("LUT_3D_SIZE 2\nDOMAIN_MIN -3e38 -3e38 -3e38\nDOMAIN_MAX 3e38 3e38 3e38\n")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) append("$r $g $b\n")
        })
        assertArrayEquals(floatArrayOf(.5f, .5f, .5f), floatArrayOf(0f, 0f, 0f).also(cube::sample), 0f)
    }

    @Test fun nonfiniteCachePixelsAreDamagedInsteadOfChangingTheLook() {
        val root = temporary.newFolder("damaged")
        for ((index, invalid) in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).withIndex()) {
            val values = Lut3.identity(2).data.copyOf().apply { this[11] = invalid }
            val bytes = ByteBuffer.allocate(12 + values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x4C4B5053).putInt(2).putInt(1)
            values.forEach { bytes.putFloat(it) }
            val id = "lut_bad$index"
            File(root, "$id.lut").writeBytes(bytes.array())
            assertNull("nonfinite cache $index", LutStore(root).load(id))
        }
    }

    @Test fun modificationSummaryKeepsHundredthEvPrecision() {
        for (value in listOf(.01, -.01, 1.23)) {
            val change = changesOf(Edit(exposureEv = value), Catalog.EMPTY).single { it.name == tr("曝光") }
            assertTrue(change.detail, change.detail.contains(String.format(java.util.Locale.ROOT, "%+.2f EV", value)))
        }
    }

    @Test fun importedMeterIntentIsVisibleAndExposureResetRestoresIt() {
        val defaults = Edit()
        val catalog = Catalog(listOf(Stock(defaults.film, "Portra", false, false, defaults.paper)),
            listOf(Stock(defaults.paper, "Supra", false, false, null)))
        for (method in listOf("center", "protect_highlights", "protect_shadows", "center_weighted", "average", "median")) {
            for (automatic in listOf(true, false)) {
                val imported = SpkLook("meter", engine = SpkEngine(autoExposure = automatic, aeMethod = method))
                    .toStyle("meter", defaults, "").applyTo(defaults, catalog)!!
                val original = imported.copy(look = Look(exposure = .25), quarterTurns = 1)
                assertTrue(method, original.exposureChanged)
                val exposure = changesOf(original, Catalog.EMPTY).single { it.name == tr("曝光") }
                assertTrue(method, exposure.detail.isNotBlank())
                val reset = exposure.reset(original)
                assertEquals(method, defaults.aeMethod, reset.aeMethod)
                assertEquals(defaults.autoExposure, reset.autoExposure)
                assertEquals(original.look, reset.look)
                assertEquals(original.quarterTurns, reset.quarterTurns)
                assertFalse(method, reset.exposureChanged)
                assertFalse(changesOf(reset, Catalog.EMPTY).any { it.name == tr("曝光") })
            }
        }
    }
}
