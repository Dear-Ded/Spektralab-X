package com.dearded.spektralab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/** Real recipe/library/export contracts, co-created with GPT (OpenAI). No photos or native engine. */
class StyleRecipeTest {
    @get:Rule val temp = TemporaryFolder()
    private val catalog = Catalog(
        listOf(Stock("kodak_portra_400", "Portra", false, false, "kodak_supra_endura"),
            Stock("fujifilm_velvia_100", "Velvia", true, false, null)),
        listOf(Stock("kodak_supra_endura", "Supra", false, false, null)))
    private val mask = EditMask(tone = MaskTone(exposure = .5))
    private val finish = Look(temperature = 12.0, contrast = -8.0, saturation = -15.0,
        dust = Dust(strength = 32.0, scratches = 20.0),
        curves = CurveSet(rgb = Curve(listOf(CurvePoint(0.0, 0.0), CurvePoint(.5, .6), CurvePoint(1.0, 1.0)))))
    private val target = Edit.NONE.copy(cropX = .1, cropW = .8, quarterTurns = 1,
        rawDevelopment = RawDevelopmentMode.STANDARD_V1, rawExposureEv = 1.0,
        look = Look(exposure = .25, masks = listOf(mask)))
    private val physical = Edit(exposureEv = .75, autoExposure = false, aeMethod = "center_weighted",
        printExposure = 1.2, yShift = .25, mShift = -.5, filmFormatMm = 24.0,
        grainAmount = 1.5, halationAmount = 2.5, glareAmount = 20.0,
        scatterAmount = .5, couplersAmount = 1.25, grainLayered = false, grainArea = .6,
        unsharpSigma = 1.5, unsharpAmount = 1.2, preflash = .15, negativeGamma = 1.2,
        highlightBoostEv = 2.0, lensBlurUm = 12.0, noAntihalation = true,
        cameraFilter = "w25", labLevels = true, look = finish)

    private fun roundTrip(look: SpkLook): SpkLook {
        val bytes = ByteArrayOutputStream().also { SpkPackage(look).write(it) }.toByteArray()
        val report = ImportReport()
        val restored = SpkPackage.read(bytes, report, "test").look
        assertTrue(report.unconverted.toString(), report.unconverted.isEmpty())
        return restored
    }

    private fun apply(saved: SpkLook, edit: Edit, lut: String = "") =
        roundTrip(saved).toStyle("us_test", edit, lut).applyTo(edit, catalog)!!

    @Test fun filterAloneLeavesAllPhysicalAndPerPhotoSettingsUntouched() {
        val actual = apply(physical.savedStyle("filter", StyleScope.FILTER), target)
        assertEquals(target.copy(look = finish.copy(masks = target.look.masks)), actual)
        assertTrue(actual.noFilm)
        assertTrue(actual.sameRenderInputAs(target))
    }

    @Test fun filterOverFilmDoesNotChooseNewStocksOrChangePrintSettings() {
        val input = physical.copy(look = Look.NEUTRAL)
        val actual = apply(target.copy(look = finish).savedStyle("filter", StyleScope.FILTER), input)
        assertEquals(input.copy(look = finish), actual)
        assertFalse(actual.noFilm)
    }

    @Test fun filterApplicationNeedsNoDormantFilmInTheCatalog() {
        val style = Style("isolated", "filter", "test", "removed", "removed", look = finish, scope = StyleScope.FILTER)
        assertEquals(target.copy(look = style.look.copy(masks = target.look.masks)), style.applyTo(target, Catalog(emptyList(), emptyList())))
        assertNull(style.copy(scope = StyleScope.COMBINATION).applyTo(target, catalog))
    }

    @Test fun filmRecipePreservesTheEntireCurrentFilterAndPerPhotoControls() {
        val saved = physical.savedStyle("film", StyleScope.FILM)
        assertTrue(saved.layer2.neutral)
        assertNull(saved.lut)
        val actual = apply(saved, target)
        val expected = catalog.normalize(physical.copy(look = target.look, cropX = target.cropX, cropW = target.cropW,
            quarterTurns = target.quarterTurns, rawDevelopment = target.rawDevelopment, rawExposureEv = target.rawExposureEv))
        assertEquals(expected, actual)
    }

    @Test fun fullRecipeRetainsAllExposedPhysicalOptionsIncludingLargeGlare() {
        val saved = physical.savedStyle("combination")
        assertEquals(20.0, roundTrip(saved).engine.glare!!, 0.0)
        val actual = apply(saved, Edit.NONE)
        assertEquals(catalog.normalize(physical), actual)
    }

    @Test fun disabledFilmEffectsRetainTheirRememberedStrengths() {
        val input = physical.copy(grainActive = false, halationActive = false, glareActive = false)
        assertEquals(catalog.normalize(input), apply(input.savedStyle("off"), Edit.NONE))
    }

    @Test fun noFilmCombinationExplicitlyTurnsOffAPreviouslyActiveFilm() {
        val saved = target.copy(look = finish).savedStyle("digital combination")
        assertEquals(true, saved.engine.noFilm)
        val actual = apply(saved, physical)
        assertTrue(actual.noFilm)
        assertEquals(finish, actual.look)
        assertEquals(physical.rawDevelopment, actual.rawDevelopment)
    }

    @Test fun paperNoneSurvivesForNegativesAndSlides() {
        val negative = physical.copy(digitalIntermediate = true)
        val slide = physical.copy(film = "fujifilm_velvia_100", scanFilm = true)
        for (edit in listOf(negative, slide)) assertEquals(catalog.normalize(edit), apply(edit.savedStyle("no paper"), Edit.NONE))
    }

    @Test fun mixFilmAWithFilterBInEitherOrder() {
        val filmA = roundTrip(physical.savedStyle("A", StyleScope.FILM))
        val filterB = roundTrip(target.copy(look = finish.copy(saturation = 30.0)).savedStyle("B", StyleScope.FILTER))
        val aThenB = apply(filterB, apply(filmA, target))
        val bThenA = apply(filmA, apply(filterB, target))
        assertEquals(aThenB, bThenA)
        assertEquals(30.0, aThenB.look.saturation, 0.0)
        assertEquals(physical.glareAmount, aThenB.glareAmount, 0.0)
        assertEquals(aThenB, apply(aThenB.savedStyle("mix"), target))
    }

    @Test fun userLibraryReloadPreservesScopesAndLutReference() {
        val dir = temp.newFolder("styles")
        val library = UserStyles(dir)
        val saved = physical.copy(look = finish.copy(lut = "lut_test", lutStrength = .4))
            .savedStyle("custom", StyleScope.FILTER, SpkLut.SPACE_PROPHOTO)
        library.save(UserStyle("us_filter", saved, "lut_test", 1))
        val restored = UserStyles(dir).all.value.single()
        assertEquals(StyleScope.FILTER, restored.look.scope)
        assertEquals(SpkLut.SPACE_PROPHOTO, restored.look.lut!!.space)
        val actual = restored.look.toStyle(restored.id, target, restored.lutId).atStrength(.5).applyTo(target, catalog)!!
        assertEquals("lut_test", actual.look.lut)
        assertEquals(.2, actual.look.lutStrength, 0.0)
        assertEquals(16.0, actual.look.dust.strength, 0.0)
        assertEquals(target, actual.copy(look = target.look))
    }

    @Test fun v1Layer2OnlyStillPreservesThePhotosFilmAndMissingEngineOptions() {
        val json = JSONObject("""{"format":"spklook","version":1,"name":"old","layer2":{"contrast":8}}""")
        val saved = SpkLook.fromJson(json, ImportReport(), "old")
        val input = catalog.normalize(physical.copy(digitalIntermediate = true))
        assertEquals(input.copy(look = Look(contrast = 8.0)), saved.toStyle("old", input, "").applyTo(input, catalog))
    }

    @Test fun savedComponentsKeepTheirScopeWhenConverted() {
        for (scope in listOf(StyleScope.FILTER, StyleScope.FILM)) {
            val style = physical.savedStyle("component", scope).toStyle("us_test", target, "")
            assertEquals(scope, style.scope)
        }
    }

    @Test fun filterStrengthZeroDisablesDustAndGlobalAdjustments() {
        val s = physical.savedStyle("filter", StyleScope.FILTER).toStyle("us_test", target, "")
        assertTrue(s.atStrength(0.0).applyTo(target, catalog)!!.look.copy(masks = emptyList()).neutral)
        assertEquals(target.look.masks, s.atStrength(0.0).applyTo(target, catalog)!!.look.masks)
    }

    @Test fun queuedImageExportKeepsNoFilmAndFilterIncludingLut() {
        val edit = apply(physical.savedStyle("filter", StyleScope.FILTER), target).copy(look = finish.copy(lut = "lut_test", lutStrength = .7))
        val job = ProcessingJob(1, "content://test/image", "test.dng", true, edit.toJson().toString(), 0.0, 0.0,
            ExportFormat.JPEG, ExportSize.FULL, workingEdge = 3072, maxPixels = 8_000_000, outPixels = 8_000_000,
            lookJson = edit.look.toJson().toString())
        val restored = ProcessingJob.fromJson(JSONObject(job.toJson().toString()))!!
        val packedLook = Look.fromJson(JSONObject(restored.lookJson))
        assertEquals(edit, Edit.fromParams(JSONObject(restored.editJson), Edit()).copy(look = packedLook))
    }

    @Test fun snapshotsExcludeMasksGeometryAndRawDevelopment() {
        val saved = target.savedStyle("filter", StyleScope.FILTER)
        assertTrue(saved.engine.empty)
        assertTrue(saved.layer2.masks.isEmpty())
        val json = saved.toJson().toString()
        assertFalse(json.contains("crop"))
        assertFalse(json.contains("raw_development"))
    }

    @Test fun invalidScopeCannotAccidentallyApplyAFullCombination() {
        val json = SpkLook("unknown").toJson().put("scope", "future")
        assertThrows(ImportException::class.java) { SpkLook.fromJson(json, ImportReport(), "test") }
    }

    @Test fun optionsAreTypedAndClampedBeforeApplying() {
        val report = ImportReport()
        val json = SpkLook("invalid").toJson().put("engine", JSONObject()
            .put("no_film", "false").put("glare", 100).put("grain_area", -1)
            .put("lens_blur_um", 200).put("camera_filter", "unsupported"))
        val e = SpkLook.fromJson(json, report, "test").engine
        assertNull(e.noFilm)
        assertNull(e.cameraFilter)
        assertEquals(30.0, e.glare!!, 0.0)
        assertEquals(.05, e.grainArea!!, 0.0)
        assertEquals(30.0, e.lensBlurUm!!, 0.0)
        assertEquals(5, report.unconverted.size)
    }
}
