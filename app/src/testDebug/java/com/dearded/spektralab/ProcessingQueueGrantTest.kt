package com.dearded.spektralab

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual queue cleanup, with the resolver's app-wide persisted grants. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProcessingQueueGrantTest {
    private lateinit var app: Application
    private val uri = Uri.parse("content://queue-grant-test/source")
    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("processing", 0).edit().clear().commit()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        File(app.filesDir, "Sidecars").deleteRecursively()
        resetFrames()
        app.contentResolver.takePersistableUriPermission(uri, read)
        assertTrue(hasReadGrant())
    }

    @After fun tearDown() {
        resetFrames()
    }

    private fun resetFrames() {
        FrameStore::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
    }

    private fun job(id: Long, state: JobState) = ProcessingJob(
        id = id, uri = uri.toString(), name = "source.dng", raw = true,
        editJson = "{}", wbK = 0.0, wbTint = 0.0,
        format = ExportFormat.JPEG, size = ExportSize.FULL,
        workingEdge = 1024, maxPixels = 1_000_000, outPixels = 1_000_000,
        state = state, persisted = true,
    )

    private fun queue(vararg jobs: ProcessingJob, host: EngineHost = EngineHost(app)): ProcessingQueue {
        val json = JSONArray()
        jobs.forEach { json.put(it.toJson()) }
        app.getSharedPreferences("processing", 0).edit()
            .putString("jobs", json.toString())
            .putStringSet("grants", setOf(uri.toString())).commit()
        return ProcessingQueue(app, host)
    }

    private fun cleanup(queue: ProcessingQueue) {
        ProcessingQueue::class.java.getDeclaredMethod("releaseSources").apply {
            isAccessible = true
            invoke(queue)
        }
    }

    private fun hasReadGrant() = app.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission
    }

    private fun tracked() = app.getSharedPreferences("processing", 0)
        .getStringSet("grants", emptySet()).orEmpty().toSet()

    private fun tableFrame(other: Frame? = null): Frame {
        val frame = Frame("test-frame", uri.toString(), "source.dng", 100L, 0L, "image/x-adobe-dng", true)
        File(app.filesDir, "Sidecars").apply { mkdirs() }
        val index = JSONArray().put(frame.toJson())
        if (other != null) index.put(other.toJson())
        File(app.filesDir, "Sidecars/index.json").writeText(index.toString())
        return frame
    }

    @Test fun tableKeepsGrantAndCanReleaseItAfterQueueFinishes() {
        val frame = tableFrame()

        cleanup(queue(job(1, JobState.DONE)))

        assertTrue(hasReadGrant())
        assertTrue("Queue must hand back ownership, not leave a stale reference", tracked().isEmpty())
        FrameStore.of(app).remove(setOf(frame.id)) { emptySet() }
        assertFalse(hasReadGrant())
    }

    @Test fun tableRemovalAfterQueueFinishesKeepsEditorGrant() {
        val frame = tableFrame()
        app.getSharedPreferences("settings", 0).edit()
            .putString("editor_grant", uri.toString()).commit()

        cleanup(queue(job(1, JobState.DONE)))
        assertTrue(hasReadGrant())
        assertTrue(tracked().isEmpty())

        FrameStore.of(app).remove(setOf(frame.id)) { emptySet() }
        assertFalse(FrameStore.of(app).holds(uri.toString()))
        assertTrue("Removing the table frame must not revoke the editor's grant", hasReadGrant())
    }

    @Test fun tableRemovalAfterEditorMovesReleasesFinalGrant() {
        val other = Frame("other-frame", "content://queue-grant-test/other", "other.dng", 200L, 0L, "image/x-adobe-dng", true)
        val frame = tableFrame(other)
        app.getSharedPreferences("settings", 0).edit()
            .putString("editor_grant", uri.toString()).commit()

        cleanup(queue(job(1, JobState.DONE)))
        assertTrue(hasReadGrant())
        assertTrue(tracked().isEmpty())

        // The editor leaves this grant to the table when it opens another URI.
        app.getSharedPreferences("settings", 0).edit()
            .putString("editor_grant", "content://queue-grant-test/other").commit()
        FrameStore.of(app).remove(setOf(frame.id)) { emptySet() }
        assertFalse(hasReadGrant())
        assertEquals(listOf(other.id), FrameStore.of(app).frames.value.map { it.id })
        assertEquals(1, JSONArray(File(app.filesDir, "Sidecars/index.json").readText()).length())
    }

    @Test fun tableImportKeepsGrantWhenQueueCleanupRunsBeforeCommit() {
        val queue = queue(job(1, JobState.DONE))
        val store = FrameStore.of(app)
        var cleaned = false

        val added = store.import(listOf(uri)) {
            // This callback runs after metadata collection, before the table commit.
            cleanup(queue)
            cleaned = true
        }

        assertTrue(cleaned)
        assertEquals(1, added)
        assertTrue(store.holds(uri.toString()))
        assertTrue(store.frames.value.single().persisted)
        assertTrue("The committed table frame must own a real persisted grant", hasReadGrant())
        assertTrue(tracked().isEmpty())
    }

    @Test fun tableRemovalReadsPendingJobPublishedAfterProviderWasCreated() {
        val frame = tableFrame()
        val host = EngineHost(app)
        // Exercise actual enqueue publication without starting native processing.
        host.scope.cancel()
        val queue = queue(job(1, JobState.DONE), host = host)
        val needed = {
            queue.jobs.value.filter { it.state != JobState.DONE }.map { it.uri }.toSet()
        }
        assertTrue(needed().isEmpty())

        queue.enqueue(job(2, JobState.QUEUED))
        assertFalse(queue.jobs.value.single { it.id == 2L }.secured)
        FrameStore.of(app).remove(setOf(frame.id), needed)

        assertTrue(hasReadGrant())
        assertFalse(FrameStore.of(app).holds(uri.toString()))
    }

    @Test fun tableRemovalReleasesGrantWhenPreviouslyQueuedJobHasFinished() {
        val frame = tableFrame()
        val queue = queue(job(1, JobState.QUEUED))
        val needed = {
            queue.jobs.value.filter { it.state != JobState.DONE }.map { it.uri }.toSet()
        }
        assertEquals(setOf(uri.toString()), needed())
        // Use the same state transition helper as the queue's successful export.
        ProcessingQueue::class.java.getDeclaredMethod("set", java.lang.Long.TYPE, Function1::class.java).apply {
            isAccessible = true
            invoke(queue, 1L, { value: ProcessingJob -> value.copy(state = JobState.DONE) })
        }

        FrameStore.of(app).remove(setOf(frame.id), needed)
        cleanup(queue)

        assertFalse(hasReadGrant())
        assertTrue(tracked().isEmpty())
    }

    @Test fun editorKeepsGrantWithoutStaleQueueOwnership() {
        app.getSharedPreferences("settings", 0).edit()
            .putString("editor_grant", uri.toString()).commit()

        cleanup(queue(job(1, JobState.DONE)))

        assertTrue(hasReadGrant())
        assertTrue(tracked().isEmpty())
    }

    @Test fun finishedJobAloneDoesNotKeepGrant() {
        cleanup(queue(job(1, JobState.DONE)))

        assertFalse(hasReadGrant())
        assertTrue(tracked().isEmpty())
    }

    @Test fun removedJobsDoNotKeepGrant() {
        cleanup(queue())

        assertFalse(hasReadGrant())
        assertTrue(tracked().isEmpty())
    }

    @Test fun anotherPendingJobKeepsGrantUntilItFinishes() {
        cleanup(queue(job(1, JobState.DONE), job(2, JobState.QUEUED)))

        assertTrue(hasReadGrant())
        assertEquals(setOf(uri.toString()), tracked())

        cleanup(queue(job(1, JobState.DONE), job(2, JobState.DONE)))
        assertFalse(hasReadGrant())
        assertTrue(tracked().isEmpty())
    }

    @Test fun failedAndCancelledJobsKeepTheirSourceForRetry() {
        for (state in listOf(JobState.FAILED, JobState.CANCELLED)) {
            cleanup(queue(job(1, state)))
            assertTrue("Lost retry source for $state", hasReadGrant())
            assertEquals(setOf(uri.toString()), tracked())
        }
    }

    @Test fun unrelatedEditorGrantDoesNotRetainFinishedSource() {
        app.getSharedPreferences("settings", 0).edit()
            .putString("editor_grant", "content://queue-grant-test/other").commit()

        cleanup(queue(job(1, JobState.DONE)))

        assertFalse(hasReadGrant())
        assertTrue(tracked().isEmpty())
    }
}
