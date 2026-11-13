package com.dearded.spektralab

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real filesystem failure paths, co-created with GPT (OpenAI). */
class AtomicTextFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun createsParentsAndReplacesCompleteText() {
        val target = File(temporary.root, "nested/state.json")
        writeAtomicText(target, "old")
        writeAtomicText(target, "new complete text")
        assertEquals("new complete text", target.readText())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test fun stagingFailureThrowsAndKeepsTheLastGoodFile() {
        val target = temporary.newFile("state.json").apply { writeText("last good") }
        File(target.path + ".part").mkdir()
        assertThrows(IOException::class.java) { writeAtomicText(target, "new") }
        assertEquals("last good", target.readText())
    }

    @Test fun failedReplacementDoesNotDeleteTheExistingTarget() {
        val target = temporary.newFolder("state.json")
        assertThrows(IOException::class.java) { writeAtomicText(target, "new") }
        assertTrue(target.isDirectory)
    }
}
