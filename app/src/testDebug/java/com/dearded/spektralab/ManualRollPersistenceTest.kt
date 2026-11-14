package com.dearded.spektralab

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Failed roll edits must survive retry and reload. Co-created with GPT (OpenAI). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ManualRollPersistenceTest {
    private lateinit var app: Application
    private lateinit var file: File

    private fun reset() {
        ManualRolls::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }

    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        reset()
        file = File(app.filesDir, "Sidecars/rolls.json")
        File(file.path + ".part").delete()
        file.delete()
    }

    @After fun finish() {
        File(file.path + ".part").delete()
        file.delete()
        reset()
    }

    @Test fun failedInitialPackDoesNotPublishANonexistentRoll() {
        val store = ManualRolls.of(app)
        val blocked = File(file.path + ".part").apply { parentFile!!.mkdirs(); mkdir() }
        assertThrows(IOException::class.java) { store.pack(listOf("one", "two"), "roll") }
        assertTrue(store.rolls.value.isEmpty())
        assertFalse(file.exists())
        assertTrue(blocked.delete())
        assertEquals(1, store.pack(listOf("one", "two"), "roll"))
        reset()
        val restored = ManualRolls.of(app).also { it.load() }
        assertEquals(store.rolls.value, restored.rolls.value)
    }

    @Test fun failedRenameReleaseAndDissolveKeepTheLastGoodRoll() {
        val store = ManualRolls.of(app)
        store.pack(listOf("one", "two"), "original")
        val before = store.rolls.value
        val bytes = file.readBytes()
        val id = before.single().id
        val blocked = File(file.path + ".part").apply { mkdir() }
        assertThrows(IOException::class.java) { store.rename(id, "next") }
        assertThrows(IOException::class.java) { store.release(listOf("one")) }
        assertThrows(IOException::class.java) { store.dissolve(id) }
        assertEquals(before, store.rolls.value)
        assertArrayEquals(bytes, file.readBytes())
        reset()
        assertEquals(before, ManualRolls.of(app).also { it.load() }.rolls.value)
        assertTrue(blocked.delete())
        store.rename(id, "next")
        reset()
        assertEquals("next", ManualRolls.of(app).also { it.load() }.rolls.value.single().name)
    }
}
