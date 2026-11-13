package com.dearded.spektralab

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual private sidecar/index round trips. Synthetic metadata only, co-created with GPT (OpenAI). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FrameEditPersistenceTest {
    private lateinit var app: Application
    private val frames = listOf(
        Frame("one", "file:///synthetic/one/photo.dng", "photo.dng", 100, 1, "image/x-adobe-dng", true),
        Frame("two", "file:///synthetic/two/photo.dng", "photo.dng", 200, 2, "image/x-adobe-dng", true),
    )

    private fun resetStore() {
        FrameStore::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }

    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        resetStore()
        val dir = File(app.filesDir, "Sidecars").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "index.json").writeText(JSONArray().apply { frames.forEach { put(it.toJson()) } }.toString())
    }

    @After fun finish() = resetStore()

    @Test fun firstAdjustmentsOnSameNamedPhotosSurviveSwitchAndStoreReload() = runBlocking {
        val owner = SupervisorJob()
        try {
            withTimeout(5000) {
                val store = FrameStore.of(app).also { it.load() }
                val saves = FrameEditAutosave(CoroutineScope(owner)) { store.saveEdit(it.id, it.edit, it.wbK, it.wbTint) }
                val first = Edit.NONE.copy(exposureEv = 0.5, cropX = 0.1, cropW = 0.8,
                    look = Look(contrast = 17.0, saturation = -11.0))
                val second = Edit.NONE.copy(exposureEv = -0.75)
                saves.opened("one", Edit.NONE, 0.0, 0.0, reopening = false)
                saves.changed(first)
                saves.opened("two", Edit.NONE, 0.0, 0.0, reopening = false)
                saves.changed(second)
                saves.close()
                resetStore()
                val loaded = FrameStore.of(app).also { it.load() }
                assertEquals(first, loaded.sidecar("one")!!.edit)
                assertEquals(second, loaded.sidecar("two")!!.edit)
                assertTrue(loaded.frames.value.all { it.edited })
                assertEquals(frames.map { it.uri }, loaded.frames.value.map { it.uri })
            }
        } finally { owner.cancelAndJoin() }
    }

    @Test fun rawReopenPersistsWhiteBalanceAndFullDevelopmentRecipe() = runBlocking {
        val owner = SupervisorJob()
        try {
            withTimeout(5000) {
                val store = FrameStore.of(app).also { it.load() }
                val saves = FrameEditAutosave(CoroutineScope(owner)) { store.saveEdit(it.id, it.edit, it.wbK, it.wbTint) }
                val developed = Edit.NONE.copy(rawDevelopment = RawDevelopmentMode.STANDARD_V1,
                    rawExposureEv = 1.25, quarterTurns = 1, look = Look(temperature = 8.0))
                saves.opened("one", Edit.NONE, 0.0, 0.0, reopening = false)
                saves.opened("one", developed, 4700.0, -0.12, reopening = true)
                saves.flush()
                val car = store.sidecar("one")!!
                assertEquals(developed, car.edit)
                assertEquals(4700.0, car.wbK, 0.0)
                assertEquals(-0.12, car.wbTint, 0.0)
                assertFalse(store.byId("two")!!.edited)
                assertNull(store.sidecar("two"))
                saves.close()
            }
        } finally { owner.cancelAndJoin() }
    }

    @Test fun openingAndReopeningAnUntouchedFrameDoesNotCreateASidecar() = runBlocking {
        val owner = SupervisorJob()
        try {
            withTimeout(5000) {
                val store = FrameStore.of(app).also { it.load() }
                val saves = FrameEditAutosave(CoroutineScope(owner)) { store.saveEdit(it.id, it.edit, it.wbK, it.wbTint) }
                saves.opened("one", Edit.NONE, 0.0, 0.0, reopening = false)
                saves.opened("one", Edit.NONE, 0.0, 0.0, reopening = true)
                saves.close()
                assertNull(store.sidecar("one"))
                assertFalse(store.byId("one")!!.edited)
            }
        } finally { owner.cancelAndJoin() }
    }

    @Test fun failedSidecarWritePreservesTheOldRecipeAndCanBeRetried() = runBlocking {
        val owner = SupervisorJob()
        val blocked = File(app.filesDir, "Sidecars/one.json.part")
        try {
            withTimeout(5000) {
                val store = FrameStore.of(app).also { it.load() }
                val old = Edit.NONE.copy(exposureEv = 0.25)
                val next = Edit.NONE.copy(exposureEv = 1.25)
                store.saveEdit("one", old, 5000.0, 0.1)
                val frameBefore = store.byId("one")
                val indexBefore = File(app.filesDir, "Sidecars/index.json").readText()
                blocked.mkdir()
                var failures = 0
                val saves = FrameEditAutosave(CoroutineScope(owner), onFailure = { failures++ }) {
                    store.saveEdit(it.id, it.edit, it.wbK, it.wbTint)
                }
                saves.opened("one", old, 5000.0, 0.1, reopening = false)
                saves.changed(next)
                try { saves.flush(); fail("storage error must propagate") } catch (_: IOException) { }
                assertEquals(1, failures)
                assertEquals(old, store.sidecar("one")!!.edit)
                assertEquals(frameBefore, store.byId("one"))
                assertEquals(indexBefore, File(app.filesDir, "Sidecars/index.json").readText())
                assertTrue(blocked.delete())
                saves.close()
                resetStore()
                val restored = FrameStore.of(app).also { it.load() }
                assertEquals(next, restored.sidecar("one")!!.edit)
                assertTrue(restored.byId("one")!!.edited)
            }
        } finally { blocked.delete(); owner.cancelAndJoin() }
    }

    @Test fun indexFailureDoesNotPublishASuccessfulEditAndRetryFinishesTheIndex() {
        val store = FrameStore.of(app).also { it.load() }
        val index = File(app.filesDir, "Sidecars/index.json")
        val before = index.readText()
        val blocked = File(index.path + ".part").apply { mkdir() }
        try {
            assertThrows(IOException::class.java) { store.saveEdit("one", Edit.NONE, 0.0, 0.0) }
            assertFalse(store.byId("one")!!.edited)
            assertEquals(before, index.readText())
            // Each file commits atomically, not as a multi-file transaction. The sidecar is already safe.
            assertEquals(Edit.NONE, store.sidecar("one")!!.edit)
            assertTrue(blocked.delete())
            store.saveEdit("one", Edit.NONE, 0.0, 0.0)
            resetStore()
            assertTrue(FrameStore.of(app).also { it.load() }.byId("one")!!.edited)
        } finally { blocked.delete() }
    }

    @Test fun failedRatingAndDimensionsKeepPublishedAndPersistedFramesUnchanged() {
        val store = FrameStore.of(app).also { it.load() }
        val before = store.frames.value
        val index = File(app.filesDir, "Sidecars/index.json")
        val bytes = index.readText()
        val blocked = File(index.path + ".part").apply { mkdir() }
        try {
            assertThrows(IOException::class.java) { store.rate(setOf("one"), stars = 5) }
            assertThrows(IOException::class.java) { store.setSize("one", 4000, 3000) }
            assertEquals(before, store.frames.value)
            assertEquals(bytes, index.readText())
        } finally { blocked.delete() }
    }

    @Test fun failedRemovalKeepsTheFrameAndNeverStartsGrantRelease() {
        val store = FrameStore.of(app).also { it.load() }
        val before = store.frames.value
        var grantOwnersRead = false
        val blocked = File(app.filesDir, "Sidecars/index.json.part").apply { mkdir() }
        try {
            assertThrows(IOException::class.java) {
                store.remove(setOf("one")) { grantOwnersRead = true; emptySet() }
            }
            assertEquals(before, store.frames.value)
            assertFalse(grantOwnersRead)
            resetStore()
            assertEquals(before, FrameStore.of(app).also { it.load() }.frames.value)
        } finally { blocked.delete() }
    }

    @Test fun editorStateFlushKeepsLastGoodJsonOnFailureAndRestoresTheLatestSnapshotAfterRetry() = runBlocking {
        val owner = SupervisorJob()
        val file = File(app.filesDir, "editor-state.json")
        val blocked = File(file.path + ".part")
        try {
            withTimeout(5000) {
                val first = EditorStateSave(frames[0].uri, "one", "photo.dng", true,
                    Edit.NONE.copy(exposureEv = 0.5), 5100.0, 0.12)
                val next = first.copy(uri = frames[1].uri, frameId = "two", edit = Edit.NONE.copy(exposureEv = -1.0),
                    wbK = 4300.0, wbTint = -0.2)
                val saves = EditorStateAutosave(CoroutineScope(owner)) { writeAtomicText(file, it.toJson()) }
                saves.changed(first)
                saves.flush()
                val bytes = file.readText()
                blocked.mkdir()
                saves.changed(next)
                try { saves.flush(); fail("storage error must propagate") } catch (_: IOException) { }
                assertEquals(bytes, file.readText())
                assertTrue(blocked.delete())
                saves.close()
                val json = org.json.JSONObject(file.readText())
                assertEquals(next.uri, json.getString("uri"))
                assertEquals("two", json.getString("frameId"))
                val car = Sidecar.fromJson(json)!!
                assertEquals(next.edit, car.edit)
                assertEquals(next.wbK, car.wbK, 0.0)
                assertEquals(next.wbTint, car.wbTint, 0.0)
            }
        } finally { blocked.delete(); file.delete(); owner.cancelAndJoin() }
    }
}
