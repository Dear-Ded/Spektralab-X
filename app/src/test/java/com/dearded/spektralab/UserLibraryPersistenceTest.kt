package com.dearded.spektralab

import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real failed replacement and retry paths, co-created with GPT (OpenAI). */
class UserLibraryPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedLibraryReplacementNeverDeletesAnExistingDirectory() {
        val profileDir = temporary.newFolder("profiles")
        val styleDir = temporary.newFolder("styles")
        val lutDir = temporary.newFolder("luts")
        val profile = File(profileDir, "user_test.json").apply { mkdir() }
        val style = File(styleDir, "us_test.json").apply { mkdir() }
        val lut = File(lutDir, "lut_test.lut").apply { mkdir() }
        assertThrows(IOException::class.java) { UserProfiles(profileDir).store("user_test", JSONObject()) }
        assertThrows(IOException::class.java) { UserStyles(styleDir).save(UserStyle("us_test", SpkLook("test"))) }
        assertThrows(IOException::class.java) { LutStore(lutDir).save("lut_test", Lut3.identity(2), SpkLut.SPACE_PROPHOTO) }
        assertTrue(profile.isDirectory)
        assertTrue(style.isDirectory)
        assertTrue(lut.isDirectory)
    }

    @Test fun failedProfileReplacementKeepsTheOriginalAndRetryCommits() {
        val profiles = UserProfiles(temporary.root)
        val target = profiles.store("user_test", JSONObject().put("value", "original"))
        val blocked = File(target.path + ".part").apply { mkdir() }
        assertThrows(IOException::class.java) { profiles.store("user_test", JSONObject().put("value", "next")) }
        assertEquals("original", JSONObject(target.readText()).getString("value"))
        assertTrue(blocked.delete())
        profiles.store("user_test", JSONObject().put("value", "next"))
        assertEquals("next", JSONObject(target.readText()).getString("value"))
    }

    @Test fun failedEngineProfileCopyKeepsTheInstalledProfile() {
        val source = temporary.newFolder("source")
        val engine = temporary.newFolder("engine")
        val profiles = UserProfiles(source)
        profiles.store("user_test", JSONObject().put("value", "long replacement"))
        val target = File(engine, "profiles/user_test.json").apply {
            parentFile!!.mkdirs()
            writeText("old")
        }
        val blocked = File(target.path + ".part").apply { mkdir() }
        assertThrows(IOException::class.java) { profiles.syncInto(engine) }
        assertEquals("old", target.readText())
        assertTrue(blocked.delete())
        assertEquals(1, profiles.syncInto(engine))
        assertEquals("long replacement", JSONObject(target.readText()).getString("value"))
    }

    @Test fun failedStyleReplacementKeepsPublishedAndPersistedStyle() {
        val styles = UserStyles(temporary.root)
        val original = UserStyle("us_test", SpkLook("original"))
        val next = original.copy(look = SpkLook("next"))
        styles.save(original)
        val target = File(temporary.root, "us_test.json")
        val bytes = target.readBytes()
        val blocked = File(target.path + ".part").apply { mkdir() }
        assertThrows(IOException::class.java) { styles.save(next) }
        assertEquals(listOf(original), styles.all.value)
        assertArrayEquals(bytes, target.readBytes())
        assertTrue(blocked.delete())
        styles.save(next)
        assertEquals(listOf(next), UserStyles(temporary.root).all.value)
    }

    @Test fun failedLutReplacementKeepsTheOldTableAndCanBeRetried() {
        val store = LutStore(temporary.root)
        val original = Lut3.identity(2)
        val next = Lut3(2, original.data.map { it * 0.5f }.toFloatArray())
        store.save("lut_test", original, SpkLut.SPACE_PROPHOTO)
        val target = File(temporary.root, "lut_test.lut")
        val bytes = target.readBytes()
        val exported = store.original("lut_test")!!
        val blocked = File(target.path + ".part").apply { mkdir() }
        assertThrows(IOException::class.java) { store.save("lut_test", next, SpkLut.SPACE_PROPHOTO) }
        assertArrayEquals(bytes, target.readBytes())
        assertArrayEquals(original.data, store.load("lut_test")!!.data, 0f)
        assertArrayEquals(original.data, LutStore(temporary.root).load("lut_test")!!.data, 0f)
        assertArrayEquals(exported.first, LutStore(temporary.root).original("lut_test")!!.first)
        assertTrue(blocked.delete())
        store.save("lut_test", next, SpkLut.SPACE_PROPHOTO)
        assertArrayEquals(next.data, LutStore(temporary.root).load("lut_test")!!.data, 0f)
    }

    @Test fun lutCommitDoesNotDependOnASecondCubeWrite() {
        val dir = temporary.newFolder("paired-lut")
        File(dir, "lut_test.cube.part").mkdir()
        File(dir, "lut_test.cube").writeText("stale legacy original")
        val raw = Lut3.identity(2)
        val baked = LutStore(dir).save("lut_test", raw, SpkLut.SPACE_PROPHOTO)
        val restored = LutStore(dir)
        assertArrayEquals(baked.data, restored.load("lut_test")!!.data, 0f)
        assertArrayEquals(raw.toCube("lut_test"), restored.original("lut_test")!!.first)
        assertEquals(SpkLut.SPACE_PROPHOTO, restored.original("lut_test")!!.second)
    }

    @Test fun truncatedPairedLutIsRejectedForRenderingAndSharing() {
        val dir = temporary.newFolder("truncated-lut")
        LutStore(dir).save("lut_test", Lut3.identity(2), SpkLut.SPACE_PROPHOTO)
        val file = File(dir, "lut_test.lut")
        file.writeBytes(file.readBytes().dropLast(1).toByteArray())
        assertNull(LutStore(dir).load("lut_test"))
        assertNull(LutStore(dir).original("lut_test"))
    }

    @Test fun failedDeletionDoesNotReportAStyleAsRemoved() {
        val styles = UserStyles(temporary.root)
        val original = UserStyle("us_test", SpkLook("original"), "lut_test")
        styles.save(original)
        val target = File(temporary.root, "us_test.json")
        assertTrue(target.delete())
        assertTrue(target.mkdir())
        File(target, "unremoved").writeText("keep")
        assertThrows(IOException::class.java) { styles.delete(original.id) }
        assertEquals(listOf(original), styles.all.value)
        assertTrue(styles.usesLut("lut_test"))
    }

    @Test fun failedProfileCleanupReportsFailureAndPreservesItsContents() {
        val target = File(temporary.root, "user_test.json").apply { mkdir() }
        val content = File(target, "unremoved").apply { writeText("keep") }
        assertThrows(IOException::class.java) { UserProfiles(temporary.root).delete("user_test", null) }
        assertEquals("keep", content.readText())
    }

    @Test fun failedEngineProfileCleanupPreservesTheUserOriginal() {
        val dir = temporary.newFolder("user-profiles")
        val engine = temporary.newFolder("engine-profiles")
        val profiles = UserProfiles(dir)
        val original = profiles.store("user_test", JSONObject().put("value", "keep"))
        val blocked = File(engine, "profiles/user_test.json").apply { mkdirs() }
        File(blocked, "unremoved").writeText("keep")
        assertThrows(IOException::class.java) { profiles.delete("user_test", engine) }
        assertEquals("keep", JSONObject(original.readText()).getString("value"))
    }
}
