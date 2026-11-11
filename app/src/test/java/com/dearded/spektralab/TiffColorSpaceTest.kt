package com.dearded.spektralab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TiffColorSpaceTest {
    private val edit = """{"output_color_space":"ProPhoto RGB","input_linear":true,"exposure":-0.75,"custom":{"preserve":"yes"}}"""

    private fun legacyJob() = JSONObject()
        .put("id", 42L).put("uri", "content://test/photo")
        .put("name", "test.dng").put("raw", true).put("edit", edit)
        .put("format", "TIFF").put("size", "FULL")

    @Test fun missingOrUnknownTargetKeepsSrgbAndExistingEdit() {
        for (target in listOf(null, "", "FUTURE_TARGET")) {
            val json = legacyJob()
            if (target != null) json.put("tiffSpace", target)
            val restored = ProcessingJob.fromJson(json)
            assertNotNull(restored)
            assertEquals(TiffColorSpace.SRGB, restored!!.tiffColorSpace)
            assertEquals(edit, restored.editJson)
            assertEquals("SRGB", restored.toJson().getString("tiffSpace"))
        }
    }

    @Test fun stableStoredNamesRoundTripWithCorrectNativeTarget() {
        val targets = mapOf(
            "SRGB" to "sRGB",
            "DISPLAY_P3" to "Display P3",
            "PROPHOTO" to "ProPhoto RGB",
            "ADOBE_RGB" to "Adobe RGB (1998)",
        )
        for ((name, nativeTarget) in targets) {
            val restored = ProcessingJob.fromJson(legacyJob().put("tiffSpace", name))!!
            assertEquals(name, restored.tiffColorSpace.name)
            assertEquals(nativeTarget, restored.tiffColorSpace.targetName)
            val persisted = restored.toJson()
            assertEquals(name, persisted.getString("tiffSpace"))
            assertEquals(edit, persisted.getString("edit"))
            assertEquals(restored, ProcessingJob.fromJson(persisted))
        }
    }

    @Test fun newlyCreatedJobStillDefaultsToSrgb() {
        val job = ProcessingJob(
            id = 1L, uri = "content://test/photo", name = "test.dng", raw = true,
            editJson = edit, wbK = 0.0, wbTint = 0.0,
            format = ExportFormat.TIFF, size = ExportSize.FULL,
            workingEdge = 4096, maxPixels = 42_000_000L, outPixels = 12_000_000L,
        )
        assertEquals(TiffColorSpace.SRGB, job.tiffColorSpace)
        assertEquals("SRGB", job.toJson().getString("tiffSpace"))
        assertEquals(edit, job.editJson)
    }
}
