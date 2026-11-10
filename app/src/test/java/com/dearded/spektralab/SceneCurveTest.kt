package com.dearded.spektralab

import java.io.ByteArrayOutputStream
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SceneCurveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val edited = SceneCurve.fromControls(CurveSet(
        rgb = Curve(listOf(CurvePoint(0.0, 0.0), CurvePoint(0.41, 0.58), CurvePoint(1.0, 1.0))),
        luma = Curve(listOf(CurvePoint(0.0, 0.0), CurvePoint(0.6, 0.65), CurvePoint(1.0, 1.0))),
    ))
    private val frame = Frame("test", "content://photo/1", "photo.dng", 1024, 0, "image/x-adobe-dng", true)
    private val catalog = Catalog(
        listOf(Stock("kodak_portra_400", "Portra", false, false, "kodak_supra_endura")),
        listOf(Stock("kodak_supra_endura", "Supra", false, false, null)),
    )
    private fun sampled(n: Int) = (0 until 5 * n).joinToString(",") { i ->
        (if (i / n == 0) 0.1 + 0.8 * (i % n) / (n - 1) else (i % n).toDouble() / (n - 1)).toString()
    }

    @Test fun identityBypassesAndResetHasAnExplicitDelta() {
        assertEquals("", SceneCurve.IDENTITY.wire)
        assertFalse(Edit().toJson().has("scene_curve"))
        val old = Edit(sceneCurve = edited)
        assertEquals("", Edit().delta(old).getString("scene_curve"))
        assertEquals(edited.wire, old.toJson().getString("scene_curve"))
        assertTrue(old.look.curves.isIdentity)
    }

    @Test fun authoredSamplingIsLocaleIndependentAndKeepsAllFiveRows() {
        val prior = Locale.getDefault()
        val wire = try {
            Locale.setDefault(Locale.GERMANY)
            SceneCurve.fromControls(edited.controls!!).wire
        } finally { Locale.setDefault(prior) }
        val values = wire.split(',').map(String::toFloat)
        assertEquals(5 * 129, values.size)
        assertTrue(values.all { it.isFinite() && it in 0f..1f })
        assertEquals(edited.controls!!.luma.evaluate(0.5).toFloat(), values[129 + 64])
        assertEquals(0.5f, values[2 * 129 + 64])
    }

    @Test fun resolvedEngineParamsRetainAuthoredPoints() {
        val edit = Edit(sceneCurve = edited)
        val read = Edit.fromParams(JSONObject(edit.toJson().toString()), edit)
        assertSame(edited, read.sceneCurve)
        assertEquals(edited.controls, read.sceneCurve.controls)
    }

    @Test fun sidecarRestoresControlPointsAndNoFilmDoesNotEraseThem() {
        val edit = Edit.NONE.copy(sceneCurve = edited)
        val saved = Sidecar(edit).toJson(frame)
        val read = Sidecar.fromJson(JSONObject(saved.toString()))!!.edit
        assertEquals(edited, read.sceneCurve)
        assertTrue(read.noFilm)
        assertEquals(edited.wire, read.copy(noFilm = false).toJson().getString("scene_curve"))
        assertEquals(edited, read.withoutStyle().sceneCurve)
        assertEquals(edited, catalog.normalize(read).sceneCurve)
    }

    @Test fun importedSampleTablesNeverBecomeDefaultOrCubic() {
        for (n in listOf(2, 128, 129, 4096)) {
            val wire = sampled(n)
            val curve = SceneCurve.fromWire(wire)
            assertTrue(curve.isSampled)
            assertFalse(curve.isIdentity)
            assertEquals(n, curve.samplesPerRow)
            assertEquals(wire, SceneCurve.fromJson(JSONObject(curve.toJson().toString())).wire)
            assertEquals(wire, Edit.fromParams(JSONObject().put("scene_curve", wire), Edit()).sceneCurve.wire)
            val row = curve.display.rgb
            val x = 0.25 / (n - 1)
            assertTrue(row.linear)
            assertEquals(row.points[0].y * 0.75 + row.points[1].y * 0.25, row.evaluate(x), 1e-14)
        }
    }

    @Test fun nativeOnlySidecarPreservesSamplesWithoutAppMetadata() {
        val wire = sampled(4096)
        val saved = JSONObject().put("format", Sidecar.FORMAT)
            .put("params", JSONObject().put("scene_curve", wire))
        val edit = Sidecar.fromJson(saved)!!.edit
        assertEquals(wire, edit.sceneCurve.wire)
        assertEquals(wire, Sidecar.fromJson(Sidecar(edit).toJson(frame))!!.edit.sceneCurve.wire)
    }

    @Test fun editorPointOperationsKeepStrictOrderingAtNarrowGaps() {
        val curve = Curve(listOf(CurvePoint(0.0, 0.0), CurvePoint(0.5, 0.4),
            CurvePoint(0.501, 0.5), CurvePoint(0.502, 0.6), CurvePoint(1.0, 1.0)))
        assertSame(curve, curve.insert(CurvePoint(0.5, 0.9)).first)
        assertSame(curve, curve.insert(CurvePoint(0.0, 0.9)).first)
        for (x in listOf(0.0, 1.0)) {
            val moved = curve.move(2, CurvePoint(x, 0.6))
            assertTrue(moved.points.zipWithNext().all { (a, b) -> a.x < b.x })
            assertFalse(SceneCurve.fromControls(CurveSet(rgb = moved)).isIdentity)
        }
        val adjacent = Curve(listOf(CurvePoint(0.0, 0.0), CurvePoint(0.5, 0.4),
            CurvePoint(Math.nextUp(0.5), 0.5), CurvePoint(Math.nextUp(Math.nextUp(0.5)), 0.6), CurvePoint(1.0, 1.0)))
        for (x in listOf(0.0, 1.0)) {
            val moved = adjacent.move(2, CurvePoint(x, 0.5))
            assertEquals(Math.nextUp(0.5), moved.points[2].x, 0.0)
            SceneCurve.fromControls(CurveSet(rgb = moved))
        }
        val full = Curve(List(256) { CurvePoint(it / 255.0, it / 255.0) })
        assertSame(full, full.insert(CurvePoint(0.5, 0.7)).first)
    }

    @Test fun malformedSamplesFailInsteadOfSilentlyDroppingTheCurve() {
        val valid = sampled(2)
        for (bad in listOf("0,1", "$valid,", valid.replaceFirst("0.1", "NaN"),
            valid.replaceFirst("0.1", "1.1"), valid.replaceFirst("0.1", "-0.1"),
            valid.replaceFirst("0.1", "0.1f"), "$valid\u0000", sampled(4097))) {
            assertThrows(IllegalArgumentException::class.java) { SceneCurve.fromWire(bad) }
        }
        val badPoints = JSONObject().put("points", JSONObject().put("rgb", JSONArray("[[0,0],[0,0.5],[1,1]]")))
        assertThrows(IllegalArgumentException::class.java) { SceneCurve.fromJson(badPoints) }
    }

    @Test fun explicitDiagonalSamplesAreNotAnExactBypass() {
        val curve = SceneCurve.fromWire(List(5) { "0,1" }.joinToString(","))
        assertFalse(curve.isIdentity)
        assertFalse(curve.wire.isEmpty())
    }

    @Test fun queueSnapshotPreservesTheActualExportTable() {
        val edit = Edit(sceneCurve = SceneCurve.fromWire(sampled(4096)))
        val job = ProcessingJob(1, frame.uri, frame.name, true, edit.toJson().toString(), 0.0, 0.0,
            ExportFormat.JPEG, ExportSize.FULL, 0, 24_000_000, 24_000_000)
        val restored = ProcessingJob.fromJson(JSONObject(job.toJson().toString()))!!
        assertEquals(edit.sceneCurve.wire, Edit.fromParams(JSONObject(restored.editJson), Edit()).sceneCurve.wire)
    }

    @Test fun exposurePasteIncludesSceneCurveButLayer2PasteDoesNot() {
        val target = Sidecar(Edit())
        val source = CopiedLook(Edit(sceneCurve = edited), 0.0, 0.0, "source")
        assertEquals(edited, pasteLook(target, frame, source, setOf(LookGroup.EXPOSURE), catalog).edit.sceneCurve)
        assertTrue(pasteLook(target, frame, source, setOf(LookGroup.LAYER2), catalog).edit.sceneCurve.isIdentity)
    }

    @Test fun spklookZipRetainsAuthoredPointsAndDenseImportedSamples() {
        for (curve in listOf(edited, SceneCurve.fromWire(sampled(4096)))) {
            val look = SpkLook("test", engine = SpkEngine(sceneCurve = curve))
            val bytes = ByteArrayOutputStream().also { SpkPackage(look).write(it) }.toByteArray()
            val restored = SpkPackage.read(bytes, ImportReport(), "test").look
            assertEquals(curve, restored.engine.sceneCurve)
            val style = restored.toStyle("test", Edit(), "")
            assertEquals(curve, style.applyTo(Edit(), catalog)!!.sceneCurve)
            assertEquals(curve, style.toSpkLook().engine.sceneCurve)
        }
    }

    @Test fun absentStyleFieldPreservesAndExplicitIdentityResets() {
        val edit = Edit(sceneCurve = edited)
        assertEquals(edited, SpkLook("old").toStyle("old", edit, "").applyTo(edit, catalog)!!.sceneCurve)
        val reset = SpkLook("reset", engine = SpkEngine(sceneCurve = SceneCurve.IDENTITY))
        assertTrue(reset.toStyle("reset", edit, "").applyTo(edit, catalog)!!.sceneCurve.isIdentity)
    }

    @Test fun importedLargeSampleTableSurvivesUserLibrarySaveAndReload() {
        val wire = List(4096 * 5) { "0.123456789012345678901234567890" }.joinToString(",")
        assertTrue(wire.length > 512 * 1024)
        val look = SpkLook("large", engine = SpkEngine(sceneCurve = SceneCurve.fromWire(wire)))
        val bytes = ByteArrayOutputStream().also { SpkPackage(look).write(it) }.toByteArray()
        val imported = SpkPackage.read(bytes, ImportReport(), "large").look
        val dir = temporary.newFolder("styles")
        val library = UserStyles(dir)
        library.save(UserStyle("us_large", imported))
        assertEquals(wire, library.all.value.single().look.engine.sceneCurve!!.wire)
        assertEquals(wire, UserStyles(dir).all.value.single().look.engine.sceneCurve!!.wire)
    }

    @Test fun malformedSpklookCurveIsAReportedImportFailure() {
        val json = SpkLook("broken").toJson().put("engine", JSONObject().put("scene_curve", "0,1,"))
        assertThrows(ImportException::class.java) { SpkLook.fromJson(json, ImportReport(), "broken") }
    }

    @Test fun changeGroupResetClearsOnlyTheSceneCurveAndExposureGroup() {
        val edit = Edit(sceneCurve = edited, look = Look(exposure = 0.25))
        val change = changesOf(edit, catalog).single { it.name == "曝光" }
        val reset = change.reset(edit)
        assertTrue(reset.sceneCurve.isIdentity)
        assertEquals(edit.look, reset.look)
    }
}
