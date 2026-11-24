package com.dearded.spektralab

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FrameSourceReauthorizationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val resolver get() = app.contentResolver
    private val source = Uri.parse("content://photo-documents/document/42")
    private val other = Uri.parse("content://photo-documents/document/43")
    private val index = app.filesDir.resolve("Sidecars/index.json")
    private val frame = Frame("legacy", source.toString(), "same.png", 2, 4, "image/png",
        false, stars = 3, edited = true)
    private val sidecar = Sidecar(Edit.NONE.copy(look = Look(exposure = 0.5)), 5300.0, 3.0, updated = 5)
    private val editFile get() = index.resolveSibling("${frame.id}.json")

    @Before fun seed() {
        index.parentFile!!.mkdirs()
        index.writeText(JSONArray().put(frame.toJson()).toString())
        editFile.writeText(sidecar.toJson(frame).toString())
        shadowOf(resolver).registerInputStreamSupplier(source) { byteArrayOf(1, 2).inputStream() }
        shadowOf(resolver).registerInputStreamSupplier(other) { byteArrayOf(1, 2).inputStream() }
    }

    @Test fun sameDocumentPermissionKeepsTheIndexAndEdit() {
        val store = FrameStore(app)
        val before = index.readBytes()
        assertEquals(SourceReauthorization.DifferentDocument, store.reauthorizeSource(frame, other))
        assertEquals(SourceReauthorization.Restored(frame), store.reauthorizeSource(frame, source))
        val reopened = FrameStore(app).also { it.load() }
        assertEquals(frame, reopened.byId(frame.id))
        assertEquals(sidecar, reopened.sidecar(frame.id))
        assertArrayEquals(before, index.readBytes())
    }

    @Test fun missingDocumentLeavesTheFrameAndSavedEditInPlace() {
        val store = FrameStore(app).also { it.load() }
        shadowOf(resolver).registerInputStreamSupplier(source) { throw FileNotFoundException() }
        assertEquals(SourceReauthorization.Unavailable, store.reauthorizeSource(frame, source))
        assertEquals(frame.copy(persisted = false), store.byId(frame.id))
        assertEquals(sidecar, store.sidecar(frame.id))
        assertTrue(resolver.persistedUriPermissions.isEmpty())
    }

    @Test fun relinkRequiresExactBytesAndPreservesRatingAndEditAfterRestart() {
        val store = FrameStore(app)
        assertEquals(SourceRelink.UnverifiedOriginal, store.relinkSource(frame, other) { emptySet() })
        assertTrue(store.rememberSourceIdentity(frame))
        shadowOf(resolver).registerInputStreamSupplier(source) { throw FileNotFoundException() }
        shadowOf(resolver).registerInputStreamSupplier(other) { byteArrayOf(1, 3).inputStream() }
        assertEquals(SourceRelink.DifferentDocument, store.relinkSource(frame, other) { emptySet() })
        shadowOf(resolver).registerInputStreamSupplier(other) { byteArrayOf(1, 2).inputStream() }
        resolver.takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val moved = (store.relinkSource(frame, other) { setOf(frame.uri) } as SourceRelink.Linked).frame
        val reopened = FrameStore(app).also { it.load() }
        assertEquals(listOf(moved), reopened.frames.value)
        assertEquals(3, moved.stars)
        assertEquals(sidecar, reopened.sidecar(moved.id))
        assertArrayEquals(editFile.readBytes(), index.resolveSibling("${moved.id}.json").readBytes())
        assertEquals(setOf(source, other), resolver.persistedUriPermissions.map { it.uri }.toSet())
        assertFalse(sidecar.toJson(moved).toString().contains(checkNotNull(moved.sourceSha256)))
        reopened.remove(setOf(moved.id)) { emptySet() }
        assertEquals(1, reopened.import(listOf(other)))
        assertEquals(sidecar, reopened.sidecar(Frame.idOf(other.toString())))
    }

    @Test fun failedIndexCommitDoesNotMoveTheEditOrKeepTheNewGrant() {
        val store = FrameStore(app)
        assertTrue(store.rememberSourceIdentity(frame))
        assertTrue(index.resolveSibling("index.json.part").mkdir())
        val before = index.readBytes()
        val files = index.parentFile!!.list()!!.toSet()
        assertEquals(SourceRelink.Unavailable, store.relinkSource(frame, other) { error("Not committed") })
        assertArrayEquals(before, index.readBytes())
        assertEquals(files, index.parentFile!!.list()!!.toSet())
        assertTrue(resolver.persistedUriPermissions.isEmpty())
    }

    @Test fun incompleteIndexIsNeverRewrittenByRecovery() {
        val valid = FrameStore(app)
        assertTrue(valid.rememberSourceIdentity(frame))
        index.writeText(JSONArray(index.readText()).put("unreadable entry").toString())
        val before = index.readBytes()
        val store = FrameStore(app)
        assertFalse(store.rememberSourceIdentity(frame))
        assertEquals(SourceRelink.Unavailable, store.relinkSource(frame, other) { emptySet() })
        assertArrayEquals(before, index.readBytes())
    }

    @Test fun oldPickerResultCannotRestoreARemovedAndReimportedPhoto() {
        val store = FrameStore(app)
        store.remove(setOf(frame.id)) { emptySet() }
        assertEquals(1, store.import(listOf(source)))
        val current = store.frames.value.single()
        assertEquals(sidecar, store.sidecar(current.id))
        store.remove(setOf(current.id)) { emptySet() }
        assertEquals(1, store.import(listOf(source)))
        assertEquals(SourceReauthorization.Removed, store.reauthorizeSource(current, source))
        assertNotEquals(current.sourceMember, store.frames.value.single().sourceMember)
    }

    @Test fun originalsWithIdenticalMetadataKeepSeparateEditsAndRatings() {
        fun localPhoto(folder: String, byte: Int): Uri {
            val file = app.filesDir.resolve("$folder/matching.jpg")
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(byte.toByte()))
            return Uri.fromFile(file)
        }
        val first = localPhoto("first", 1)
        val second = localPhoto("second", 2)
        index.writeText("[]")
        val store = FrameStore(app)
        assertEquals(1, store.import(listOf(first)))
        val id = store.frames.value.single().id
        store.rate(setOf(id), stars = 4)
        store.saveEdit(id, sidecar.edit, sidecar.wbK, sidecar.wbTint)
        assertEquals(1, store.import(listOf(second)))
        assertEquals(0, store.import(listOf(first)))
        val (a, b) = store.frames.value
        assertEquals(Triple(a.name, a.size, a.date), Triple(b.name, b.size, b.date))
        assertEquals(4, a.stars)
        assertEquals(0, b.stars)
        assertEquals(sidecar.edit, store.sidecar(a.id)?.edit)
        assertNull(store.sidecar(b.id))
    }
}
