package com.dearded.spektralab

import org.junit.Assert.*
import org.junit.Test

/** A built-in recipe has one physical result, co-created with GPT (OpenAI). */
class BuiltinStyleFidelityTest {
    private val catalog = Catalog(Style.ALL.map { it.film }.distinct().map { id ->
        Stock(id, id, id in setOf("fujifilm_provia_100f", "fujifilm_velvia_100", "kodak_ektachrome_100", "kodak_kodachrome_64"),
            id in setOf("fujifilm_neopan_acros_100_ii", "ilford_hp5_plus_400", "kodak_tmax_100", "kodak_tri_x_400"), null)
    }, Style.ALL.map { it.paper }.distinct().map { Stock(it, it, false, it == "ilford_multigrade_iv_rc", null) })
    private val previous = Edit(autoExposure = false, aeMethod = "protect_highlights", scatterAmount = .1,
        couplersAmount = .3, grainLayered = false, grainArea = .8, unsharpSigma = 2.0, unsharpAmount = 1.6,
        preflash = .2, negativeGamma = 1.3, highlightBoostEv = 2.0, lensBlurUm = 20.0,
        noAntihalation = true, cameraFilter = "w25", labLevels = true,
        sceneCurve = SceneCurve.fromControls(CurveSet(rgb = Curve(listOf(CurvePoint(0.0, 0.0),
            CurvePoint(.5, .7), CurvePoint(1.0, 1.0))))))

    @Test fun everyBuiltinHasTheSameRecipeAfterAnyPreviousFilmEffects() {
        for (style in Style.ALL) {
            val clean = style.applyTo(Edit(), catalog)
            val inherited = style.applyTo(previous, catalog)
            assertEquals(style.id, clean, inherited)
            System.getenv("SPK_BUILTIN_EVIDENCE_DIR")?.let { path ->
                val edit = requireNotNull(clean)
                val directory = java.io.File(path).apply { mkdirs() }
                val fixture = org.json.JSONObject().put("id", style.id).put("params", edit.toJson())
                    .put("look", org.json.JSONArray(edit.look.values().toList()))
                java.io.File(directory, "${style.id}.json").writeText(fixture.toString())
            }
        }
    }

    @Test fun builtinRoundTripCarriesItsCompletePhysicalRecipe() {
        for (style in Style.ALL) {
            val report = ImportReport()
            val saved = SpkLook.fromJson(style.toSpkLook().toJson(), report, "round")
            val imported = saved.toStyle("us_round", previous, "").applyTo(previous, catalog)!!
            val expected = style.applyTo(Edit(), catalog)!!
            // Built-in pad homes are UI positions, not an extra grade. Compare
            // the physical recipe and the exact resolved renderer values.
            assertEquals(style.id, expected.copy(look = expected.look.resolved()), imported.copy(look = imported.look.resolved()))
            assertArrayEquals(expected.look.values(), imported.look.values(), 0f)
            assertTrue(report.unconverted.toString(), report.unconverted.isEmpty())
        }
    }

    @Test fun everyOrderedBuiltinPairKeepsTheTargetRecipeAndPhotoControls() {
        val photo = previous.copy(cropX = .125, cropY = .0625, cropW = .75, cropH = .875,
            quarterTurns = 3, rawDevelopment = RawDevelopmentMode.STANDARD_V1, rawExposureEv = -1.25,
            look = Look(masks = listOf(EditMask(tone = MaskTone(exposure = -.375)))))
        assertEquals(87, Style.ALL.size)
        for (first in Style.ALL) for (second in Style.ALL) {
            val intermediate = first.applyTo(photo, catalog)!!
            val expected = second.applyTo(photo, catalog)!!
            val actual = second.applyTo(intermediate, catalog)!!
            val label = "${first.id} -> ${second.id}"
            assertEquals(label, expected, actual)
            assertArrayEquals(label, expected.look.values(), actual.look.values(), 0f)
            assertEquals(label, actual, second.applyTo(actual, catalog))
        }
    }

    @Test fun builtinSelectionKeepsPhotoGeometryRawDevelopmentAndMasks() {
        val input = previous.copy(cropX = .2, cropW = .7, quarterTurns = 1,
            rawDevelopment = RawDevelopmentMode.STANDARD_V1, rawExposureEv = 1.5,
            look = Look(masks = listOf(EditMask(tone = MaskTone(exposure = .5)))))
        for (style in Style.ALL) {
            val actual = style.applyTo(input, catalog)!!
            assertEquals(input.cropX, actual.cropX, 0.0)
            assertEquals(input.cropW, actual.cropW, 0.0)
            assertEquals(input.quarterTurns, actual.quarterTurns)
            assertEquals(input.rawDevelopment, actual.rawDevelopment)
            assertEquals(input.rawExposureEv, actual.rawExposureEv, 0.0)
            assertEquals(input.look.masks, actual.look.masks)
        }
    }

    @Test fun partialUserRecipesStillPreserveMissingPhysicalFields() {
        val style = Style("us_partial", "partial", "custom", "kodak_portra_400", "kodak_supra_endura")
        val actual = style.applyTo(previous, catalog)!!
        assertEquals(previous.preflash, actual.preflash, 0.0)
        assertEquals(previous.couplersAmount, actual.couplersAmount, 0.0)
        assertEquals(previous.sceneCurve, actual.sceneCurve)
        assertEquals(previous.aeMethod, actual.aeMethod)
    }
}
