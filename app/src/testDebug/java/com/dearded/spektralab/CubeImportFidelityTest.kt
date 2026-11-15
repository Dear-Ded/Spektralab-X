package com.dearded.spektralab

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real import/share front doors, co-created with GPT (OpenAI). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CubeImportFidelityTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = ("# Original shaper, precision and CRLF\r\nTITLE \"fidelity\"\r\n" +
        "LUT_1D_SIZE 3\r\n0.00123456789 0.00234567891 0.00345678912\r\n" +
        "0.2501234567 0.3502345678 0.4503456789\r\n1 1 1\r\n").toByteArray()

    @Test fun standaloneAndPackagedImportsKeepSourceBytesAndDoNotDriftOnSharing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imports = StyleImports(context)
        for (space in listOf(SpkLut.SPACE_SRGB, SpkLut.SPACE_PROPHOTO)) {
            val input = temp.newFile(if (space == SpkLut.SPACE_SRGB) "input.cube" else "input.spklook")
            input.writeBytes(if (space == SpkLut.SPACE_SRGB) source else ByteArrayOutputStream().also {
                SpkPackage(SpkLook("source", lut = SpkLut(space = space)), source).write(it)
            }.toByteArray())
            var pending = imports.read(Uri.fromFile(input), Catalog.EMPTY)
            val first = imports.luts.load(pending.lutId)!!.data.copyOf()
            repeat(3) {
                assertArrayEquals(source, imports.luts.original(pending.lutId)!!.first)
                assertArrayEquals(first, imports.luts.load(pending.lutId)!!.data, 0f)
                val edit = Edit.NONE.copy(look = Look(lut = pending.lutId, lutStrength = .37))
                val output = imports.exportLook(edit, "round", null, StyleScope.FILTER)
                val pkg = SpkPackage.read(output.readBytes(), ImportReport(), "shared")
                assertArrayEquals(source, pkg.lutCube)
                assertEquals(space, pkg.look.lut!!.space)
                assertEquals(.37, pkg.look.lut!!.strength, 0.0)
                val previous = pending
                pending = imports.read(Uri.fromFile(output), Catalog.EMPTY)
                imports.discard(previous)
            }
            imports.discard(pending)
        }
    }
}
