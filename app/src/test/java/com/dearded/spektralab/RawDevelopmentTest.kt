package com.dearded.spektralab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Co-created with GPT (OpenAI). RAW intent belongs to a photo, not its film preset. */
class RawDevelopmentTest {
    private val edited = Edit.NONE.copy(rawDevelopment = RawDevelopmentMode.STANDARD_V1,
        rawAutoExposure = false, rawExposureEv = -2.25)
    private val frame = Frame("test", "content://photo/1", "test.dng", 1024, 0, "image/x-adobe-dng", true)
    private val catalog = Catalog(
        listOf(Stock("kodak_portra_400", "Portra", false, false, "kodak_supra_endura")),
        listOf(Stock("kodak_supra_endura", "Supra", false, false, null)),
    )

    @Test fun newPhotosAndOldRecordsRemainNeutral() {
        assertEquals(RawDevelopmentMode.NEUTRAL, Edit.NONE.rawDevelopment)
        val old = JSONObject().put("film_stock", "kodak_portra_400").put("x_no_film", true)
        val restored = Edit.fromParams(old, edited)
        assertEquals(RawDevelopmentMode.NEUTRAL, restored.rawDevelopment)
        assertTrue(restored.rawAutoExposure)
        assertEquals(0.0, restored.rawExposureEv, 0.0)
    }

    @Test fun sidecarAndQueuePreserveTheCompleteIntent() {
        val sidecar = Sidecar.fromJson(JSONObject(Sidecar(edited).toJson(frame).toString()))!!.edit
        assertIntent(edited, sidecar)
        val job = ProcessingJob(1, frame.uri, frame.name, true, edited.toJson().toString(), 0.0, 0.0,
            ExportFormat.JPEG, ExportSize.FULL, workingEdge = 0, maxPixels = 24_000_000, outPixels = 24_000_000)
        val restored = ProcessingJob.fromJson(JSONObject(job.toJson().toString()))!!
        assertIntent(edited, Edit.fromParams(JSONObject(restored.editJson), Edit()))
    }

    @Test fun resetProducesExplicitDeltasAndOnlyResetsRawDevelopment() {
        val reset = changesOf(edited.copy(look = Look(exposure = .25)), catalog)
            .single { it.name == "RAW 显影" }.reset(edited.copy(look = Look(exposure = .25)))
        val delta = reset.delta(edited)
        assertEquals("neutral", delta.getString("x_raw_development"))
        assertTrue(delta.getBoolean("x_raw_auto_exposure"))
        assertEquals(0.0, delta.getDouble("x_raw_exposure_ev"), 0.0)
        assertEquals(.25, reset.look.exposure, 0.0)
    }

    @Test fun exposurePasteCopiesRawIntentOnlyOntoRawPhotos() {
        val copied = CopiedLook(edited, 0.0, 0.0, "source")
        assertIntent(edited, pasteLook(Sidecar(Edit.NONE), frame, copied, setOf(LookGroup.EXPOSURE), catalog).edit)
        val jpeg = frame.copy(raw = false, name = "test.jpg")
        assertEquals(RawDevelopmentMode.NEUTRAL,
            pasteLook(Sidecar(Edit.NONE), jpeg, copied, setOf(LookGroup.EXPOSURE), catalog).edit.rawDevelopment)
        assertEquals(RawDevelopmentMode.NEUTRAL,
            pasteLook(Sidecar(Edit.NONE), frame, copied, setOf(LookGroup.LAYER2), catalog).edit.rawDevelopment)
    }

    @Test fun filmPresetsAndStyleRemovalPreservePerPhotoRawIntent() {
        assertIntent(edited, Preset.of("film", Edit()).applyTo(edited))
        assertIntent(edited, edited.withoutStyle())
        assertIntent(edited, catalog.normalize(edited.copy(noFilm = false)))
    }

    @Test fun unknownModeAndUnrepresentableExposureAreRejected() {
        for (mode in listOf("standard_v2", "", "camera")) {
            assertThrows(IllegalArgumentException::class.java) {
                Edit.fromParams(JSONObject().put("x_raw_development", mode), Edit())
            }
        }
        for (ev in listOf(-8.01, 8.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { edited.copy(rawExposureEv = ev).toJson() }
        }
    }

    @Test fun developmentCanRepackButFilmAndGeometryRequireANewFrame() {
        assertTrue(edited.sameRenderInputAs(Edit.NONE))
        assertTrue(edited.copy(look = Look(exposure = .25)).sameRenderInputAs(edited))
        assertFalse(edited.copy(noFilm = false).sameRenderInputAs(edited))
        assertFalse(edited.copy(cropW = .5).sameRenderInputAs(edited))
        assertFalse(edited.copy(quarterTurns = 1).sameRenderInputAs(edited))
        assertFalse(edited.copy(exposureEv = 1.0).sameRenderInputAs(edited))
    }

    @Test fun meteringIsRequiredOnlyForActiveRawDevelopment() {
        val source = SourceInfo("test.dng", 1024, 768, 4096, 3072, "raw", "test", 0.0, 0.0, 0.0, 0.0)
        assertTrue(edited.requiresRawDevelopmentSource(source))
        assertTrue(edited.copy(rawAutoExposure = false).requiresRawDevelopmentSource(source))
        assertFalse(edited.requiresRawDevelopmentSource(source.copy(rawStatsReady = true)))
        assertFalse(edited.copy(noFilm = false).requiresRawDevelopmentSource(source))
        assertFalse(Edit.NONE.requiresRawDevelopmentSource(source))
        assertFalse(edited.requiresRawDevelopmentSource(source.copy(kind = "photo")))
        assertFalse(edited.requiresRawDevelopmentSource(null))
    }

    private fun assertIntent(expected: Edit, actual: Edit) {
        assertEquals(expected.rawDevelopment, actual.rawDevelopment)
        assertEquals(expected.rawAutoExposure, actual.rawAutoExposure)
        assertEquals(expected.rawExposureEv, actual.rawExposureEv, 0.0)
    }
}
