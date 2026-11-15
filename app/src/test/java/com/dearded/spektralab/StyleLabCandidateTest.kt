package com.dearded.spektralab

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Public generated candidates through the real parser, LUT and apply contract. */
class StyleLabCandidateTest {
    @Test fun generatedBilingualPackagesRespectNativeFilterContract() {
        val supplied = System.getenv("SPK_STYLE_CANDIDATES_DIR")
        assumeTrue("Cloud style-lab artifacts are supplied explicitly", supplied != null)
        val root = File(checkNotNull(supplied))
        assertTrue("Configured candidate directory must exist", root.isDirectory)
        val packs = root.walkTopDown().filter { it.extension == "spklook" }.sortedBy { it.path }.toList()
        assertEquals(20, packs.size)
        val input = Edit.NONE.copy(cropX = .1, cropW = .8, quarterTurns = 1,
            rawDevelopment = RawDevelopmentMode.STANDARD_V1, rawExposureEv = .75,
            look = Look(exposure = .25, masks = listOf(EditMask(tone = MaskTone(exposure = .5)))))
        val catalog = Catalog(emptyList(), emptyList())
        val languageCubes = mutableMapOf<String, ByteArray>()
        for (pack in packs) {
            val report = ImportReport()
            val parsed = SpkPackage.read(pack.readBytes(), report, pack.nameWithoutExtension)
            assertTrue("${pack.name}: ${report.unconverted}", report.unconverted.isEmpty())
            assertEquals(StyleScope.FILTER, parsed.look.scope)
            assertTrue(parsed.look.engine.empty)
            assertTrue(parsed.look.layer2.neutral)
            assertEquals(SpkLut.SPACE_PROPHOTO, parsed.look.lut!!.space)
            assertEquals(1.0, parsed.look.lut!!.strength, 0.0)
            assertTrue(parsed.look.name.isNotBlank())
            val bytes = parsed.lutCube!!
            val parsedCube = CubeFile.parse(bytes.toString(Charsets.UTF_8))
            val lut = parsedCube.toLut(report)
            assertEquals(65, lut.size)
            assertTrue(lut.data.all { it.isFinite() && it in 0f..1f })
            assertSame(lut, LutBake.forLayer2(lut, parsed.look.lut!!.space))
            val previous = languageCubes.putIfAbsent(pack.nameWithoutExtension, bytes)
            if (previous != null) assertArrayEquals(previous, bytes)
            if (pack.parentFile.name == "en") {
                val fixture = ByteBuffer.wrap(File(root, pack.nameWithoutExtension + ".bin").readBytes())
                    .order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(65, fixture.int)
                val count = fixture.int
                assertEquals(16384, count)
                for (value in lut.data) assertEquals(value, fixture.float, 0f)
                repeat(count) {
                    val pixel = FloatArray(3) { fixture.float }
                    val expected = FloatArray(3) { fixture.float }
                    lut.sample(pixel)
                    for (channel in 0..2) {
                        assertEquals(expected[channel], pixel[channel], 1e-6f)
                        assertTrue(abs((pixel[channel] * 65535).roundToInt() -
                            (expected[channel] * 65535).roundToInt()) <= 1)
                    }
                }
                assertFalse(fixture.hasRemaining())
            }
            val style = parsed.look.toStyle("candidate", input, "candidate_lut")
            val applied = style.applyTo(input, catalog)!!
            assertEquals(input.copy(look = Look(lut = "candidate_lut", lutStrength = 1.0,
                masks = input.look.masks)), applied)
            assertTrue(applied.sameRenderInputAs(input))
            val withFilm = input.copy(noFilm = false, film = "user_film", paper = "user_paper", printExposure = .7)
            assertEquals(withFilm.copy(look = applied.look), style.applyTo(withFilm, catalog))
            assertTrue(style.look.finishAt(0.0).neutral)
        }
    }
}
