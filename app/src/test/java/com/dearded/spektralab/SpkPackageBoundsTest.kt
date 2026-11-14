package com.dearded.spektralab

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/** Skipped entries are decompressed by nextEntry too; every inflated byte needs a bound. */
class SpkPackageBoundsTest {
    private val inflatedLimit = SpkPackage.MAX_LOOK + SpkPackage.MAX_LUT + SpkPackage.MAX_THUMB

    private fun archive(vararg extras: Pair<String, Long>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("look.json"))
            zip.write("""{"format":"spklook","version":2,"name":"bounded"}""".toByteArray())
            zip.closeEntry()
            val zeros = ByteArray(64 * 1024)
            for ((name, size) in extras) {
                zip.putNextEntry(ZipEntry(name))
                var remaining = size
                while (remaining > 0) {
                    val count = minOf(remaining, zeros.size.toLong()).toInt()
                    zip.write(zeros, 0, count)
                    remaining -= count
                }
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test fun smallUnknownEntryStillImportsTheLook() {
        assertEquals("bounded", SpkPackage.read(archive("notes.bin" to 32_768L), ImportReport(), "fallback").look.name)
    }

    @Test(expected = ImportException::class)
    fun unknownEntryCannotInflatePastThePackageBudget() {
        SpkPackage.read(archive("ignored.bin" to inflatedLimit + 1), ImportReport(), "fallback")
    }

    @Test(expected = ImportException::class)
    fun directoryPayloadCannotInflatePastThePackageBudget() {
        SpkPackage.read(archive("extras/" to inflatedLimit + 1), ImportReport(), "fallback")
    }

    @Test(expected = ImportException::class)
    fun multipleSkippedEntriesShareOneBudget() {
        SpkPackage.read(archive("first.bin" to 9L * 1024 * 1024, "second.bin" to 9L * 1024 * 1024),
            ImportReport(), "fallback")
    }

    @Test(expected = ImportException::class)
    fun duplicateEntryCannotBypassThePackageBudget() {
        val bytes = archive("look.jsox" to inflatedLimit + 1)
        // ZipOutputStream forbids duplicates; rename this same-length local/central name.
        val old = "look.jsox".toByteArray()
        val replacement = "look.json".toByteArray()
        for (i in 0..bytes.size - old.size) {
            if (old.indices.all { bytes[i + it] == old[it] }) replacement.copyInto(bytes, i)
        }
        SpkPackage.read(bytes, ImportReport(), "fallback")
    }
}
