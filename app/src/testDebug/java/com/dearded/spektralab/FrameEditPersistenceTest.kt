package com.dearded.spektralab

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
}
