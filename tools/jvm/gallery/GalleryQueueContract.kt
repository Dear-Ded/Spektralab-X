package com.dearded.spektralab

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Real MediaSaver and extracted queue methods; no Android provider/emulator. */
class GalleryQueueContract {
    private class Provider : ContentResolver(), java.io.Closeable {
        data class Row(val file: File, val name: String, var pending: Boolean = true)
        val dir = Files.createTempDirectory("gallery-provider-").toFile()
        val rows = java.util.concurrent.ConcurrentHashMap<String, Row>()
        var inserts = 0
        var failQueries = 0
        var nullCursor = false
        var failDelete = false
        var zeroDelete = false
        var failPublishAfterCommit = false
        var published: CountDownLatch? = null
        var releasePublish: CountDownLatch? = null
        var copying: CountDownLatch? = null
        var releaseCopy: CountDownLatch? = null
        override fun insert(uri: Uri, values: ContentValues): Uri {
            val target = Uri.parse("$uri/${++inserts}")
            rows[target.toString()] = Row(File(dir, "$inserts.bin"), values.values[MediaStore.MediaColumns.DISPLAY_NAME].toString())
            return target
        }
        override fun update(uri: Uri, values: ContentValues, selection: String?, args: Array<String>?): Int {
            val row = rows[uri.toString()] ?: return 0
            row.pending = false
            published?.countDown()
            releasePublish?.let { check(it.await(10, TimeUnit.SECONDS)) }
            if (failPublishAfterCommit) error("IPC failed after commit")
            return 1
        }
        override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int {
            check(selection == "${MediaStore.MediaColumns.IS_PENDING} = 1")
            if (failDelete) error("provider delete failed")
            if (zeroDelete) return 0
            val row = rows[uri.toString()] ?: return 0
            if (!row.pending) return 0
            rows.remove(uri.toString())
            row.file.delete()
            return 1
        }
        override fun query(uri: Uri, projection: Array<String>, args: Bundle?, signal: Any?): Cursor? {
            if (failQueries > 0) { failQueries--; error("provider query failed") }
            if (nullCursor) return null
            if (projection.contentEquals(arrayOf(MediaStore.MediaColumns._ID))) {
                val ids = rows.entries.filter { it.key.startsWith("$uri/") && it.value.pending }
                    .map { it.key.substringAfterLast('/').toLong() }
                return object : Cursor {
                    var index = -1
                    override fun moveToFirst(): Boolean { index = 0; return ids.isNotEmpty() }
                    override fun moveToNext(): Boolean { index++; return index < ids.size }
                    override fun getLong(column: Int) = ids[index]
                    override fun getInt(column: Int) = error("ID only")
                    override fun getString(column: Int) = error("ID only")
                }
            }
            val row = rows[uri.toString()]
            return object : Cursor {
                override fun moveToFirst() = row != null
                override fun moveToNext() = false
                override fun getInt(column: Int) = if (row!!.pending) 1 else 0
                override fun getLong(column: Int) = uri.toString().substringAfterLast('/').toLong()
                override fun getString(column: Int) = row!!.name
            }
        }
        override fun openOutputStream(uri: Uri, mode: String): OutputStream {
            val out = rows.getValue(uri.toString()).file.outputStream()
            return object : FilterOutputStream(out) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    copying?.countDown()
                    releaseCopy?.let { check(it.await(10, TimeUnit.SECONDS)) }
                    out.write(bytes, offset, length)
                }
            }
        }
        override fun openFileDescriptor(uri: Uri, mode: String) =
            rows[uri.toString()]?.let { ParcelFileDescriptor(RandomAccessFile(it.file, "r")) }
        override fun close() { dir.deleteRecursively() }
    }

    private fun job(id: Long = 1, pendingUri: String = "", attempts: Int = 0, state: JobState = JobState.QUEUED) = ProcessingJob(
        id, "content://source/1", "source.dng", true, "{}", 0.0, 0.0, ExportFormat.JPEG, ExportSize.FULL,
        workingEdge = 1024, maxPixels = 1_000_000, outPixels = 1_000_000,
        pendingUri = pendingUri, attempts = attempts, state = state,
    )

    private fun fixture(block: suspend (Provider, ProcessingQueue, CoroutineDispatcher) -> Unit) = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Provider().use { provider ->
                val scope = CoroutineScope(SupervisorJob() + main)
                try {
                    block(provider, ProcessingQueue(Context(provider), TestHost(scope), listOf(job())), main)
                } finally { scope.cancel() }
            }
        }
    }

    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue("provider barrier timed out", latch.await(10, TimeUnit.SECONDS))
    }

    private fun configureSave(provider: Provider, queue: ProcessingQueue) {
        val file = File(provider.dir, "input.bin").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        queue.processor = { job ->
            withContext(Dispatchers.IO) {
                MediaSaver.saveFile(app, file, "Spektralab-X_test.jpg", "image/jpeg") { recordTest(job.id, it) }
            }
            "saved"
        }
    }

    @Test fun actualMediaSaverNullCursorIsUnknownAndEmptyCursorIsGone() = fixture { provider, queue, _ ->
        val uri = Uri.parse("content://media/external_primary/images/media/99")
        provider.nullCursor = true
        assertEquals(GalleryState.UNKNOWN, MediaSaver.state(queue.app, uri).state)
        provider.nullCursor = false
        assertEquals(GalleryState.GONE, MediaSaver.state(queue.app, uri).state)
    }

    @Test fun cancelWhileCopyingStillRecognizesPublishedResult() = fixture { provider, queue, main ->
        configureSave(provider, queue)
        provider.copying = CountDownLatch(1)
        provider.releaseCopy = CountDownLatch(1)
        val run = queue.host.scope.launch { queue.runTest() }
        await(provider.copying!!)
        withContext(main) { queue.cancel(1) }
        provider.releaseCopy!!.countDown()
        withTimeout(10_000) { run.join() }
        assertEquals(JobState.DONE, queue.jobs.single().state)
        assertEquals("", queue.jobs.single().pendingUri)
        assertFalse(provider.rows.values.single().pending)
        assertEquals(1, provider.inserts)
    }

    @Test fun cancelAfterPublishBeforeIoReturnStillFinishesDone() = fixture { provider, queue, main ->
        configureSave(provider, queue)
        provider.published = CountDownLatch(1)
        provider.releasePublish = CountDownLatch(1)
        val run = queue.host.scope.launch { queue.runTest() }
        await(provider.published!!)
        withContext(main) { queue.cancel(1) }
        provider.releasePublish!!.countDown()
        withTimeout(10_000) { run.join() }
        assertEquals(JobState.DONE, queue.jobs.single().state)
        assertEquals(1, provider.rows.size)
    }

    @Test fun cancelBeforePublicationDeletesPendingAndFinishesCancelled() = fixture { provider, queue, main ->
        val created = CompletableDeferred<Unit>()
        queue.processor = { job ->
            val uri = MediaSaver.create(app, "pending.jpg", "image/jpeg")
            recordTest(job.id, uri)
            created.complete(Unit)
            awaitCancellation()
        }
        val run = queue.host.scope.launch { queue.runTest() }
        withTimeout(10_000) { created.await() }
        withContext(main) { queue.cancel(1) }
        withTimeout(10_000) { run.join() }
        assertEquals(JobState.CANCELLED, queue.jobs.single().state)
        assertEquals("", queue.jobs.single().pendingUri)
        assertTrue(provider.rows.isEmpty())
    }

    @Test fun publishAcknowledgementFailureDoesNotRelabelFinishedPhotoAsFailed() = fixture { provider, queue, _ ->
        configureSave(provider, queue)
        provider.failPublishAfterCommit = true
        withTimeout(10_000) { withContext(queue.host.scope.coroutineContext) { queue.runTest() } }
        assertEquals(JobState.DONE, queue.jobs.single().state)
        assertEquals(1, provider.rows.size)
    }

    @Test fun queryFailureThenManualRetryReconcilesSameTicketWithoutRendering() = fixture { provider, original, main ->
        val uri = MediaSaver.create(original.app, "saved.jpg", "image/jpeg")
        MediaSaver.publish(original.app, uri)
        val queue = ProcessingQueue(original.app, original.host, listOf(job(pendingUri = uri.toString())))
        provider.failQueries = 1
        withContext(main) { queue.runTest() }
        assertEquals(JobState.FAILED, queue.jobs.single().state)
        assertEquals(uri.toString(), queue.jobs.single().pendingUri)
        assertEquals(0, queue.renders)
        withContext(main) { queue.retry(1); queue.runTest() }
        assertEquals(1L, queue.jobs.single().id)
        assertEquals(JobState.DONE, queue.jobs.single().state)
        assertEquals(0, queue.renders)
        assertEquals(1, provider.inserts)
    }

    @Test fun alreadyPublishedTicketReconcilesBeforeAttemptLimit() = fixture { _, original, main ->
        val uri = MediaSaver.create(original.app, "saved.jpg", "image/jpeg")
        MediaSaver.publish(original.app, uri)
        val queue = ProcessingQueue(original.app, original.host, listOf(job(pendingUri = uri.toString(), attempts = 2)))
        withContext(main) { queue.runTest() }
        assertEquals(JobState.DONE, queue.jobs.single().state)
        assertEquals(0, queue.renders)
    }

    @Test fun failedOrZeroDeleteKeepsTicketAndStopsThisJob() = fixture { provider, original, main ->
        for (throws in listOf(true, false)) {
            val uri = MediaSaver.create(original.app, "pending.jpg", "image/jpeg")
            val queue = ProcessingQueue(original.app, original.host, listOf(job(pendingUri = uri.toString())))
            provider.failDelete = throws
            provider.zeroDelete = !throws
            withContext(main) { queue.runTest() }
            assertEquals(JobState.FAILED, queue.jobs.single().state)
            assertEquals(uri.toString(), queue.jobs.single().pendingUri)
            assertEquals(0, queue.renders)
        }
    }

    @Test fun knownMissingTicketCanRenderAgain() = fixture { _, original, main ->
        val queue = ProcessingQueue(original.app, original.host, listOf(job(pendingUri = "content://media/images/99")))
        queue.processor = { "new save" }
        withContext(main) { queue.runTest() }
        assertEquals(JobState.DONE, queue.jobs.single().state)
        assertEquals(1, queue.renders)
    }

    @Test fun retainedTicketsSurviveHistoryClearAndThirtyRecordPersistenceLimit() = fixture { _, original, _ ->
        val retained = job(pendingUri = "content://media/images/99", state = JobState.FAILED)
        val finished = (2L..45L).map { job(it, state = JobState.DONE).copy(finishedAt = it) }
        val queue = ProcessingQueue(original.app, original.host, finished + retained)
        queue.persistTest()
        val saved = JSONArray(queue.prefs.json)
        assertEquals(31, saved.length())
        assertTrue((0 until saved.length()).any { saved.getJSONObject(it).getString("pending") == retained.pendingUri })
        queue.clearFinished()
        assertEquals(listOf(retained), queue.jobs)
    }

    @Test fun uncertainCleanupBlocksAutomaticMemoryRetry() = fixture { provider, queue, main ->
        queue.useAdmitted = true
        provider.failDelete = true
        queue.processor = { job ->
            recordTest(job.id, MediaSaver.create(app, "partial.jpg", "image/jpeg"))
            throw OutOfMemoryError("allocation failed after insert")
        }
        withContext(main) { queue.runTest() }
        assertEquals(1, queue.renders)
        assertEquals(1, provider.inserts)
        assertEquals(JobState.FAILED, queue.jobs.single().state)
        assertTrue(queue.jobs.single().pendingUri.isNotEmpty())
    }

    @Test fun confirmedCleanupStillAllowsOneAutomaticMemoryRetry() = fixture { provider, queue, main ->
        queue.useAdmitted = true
        queue.processor = { job ->
            if (renders == 1) {
                recordTest(job.id, MediaSaver.create(app, "partial.jpg", "image/jpeg"))
                throw OutOfMemoryError("allocation failed after insert")
            }
            "second attempt saved"
        }
        withContext(main) { queue.runTest() }
        assertEquals(2, queue.renders)
        assertEquals(JobState.DONE, queue.jobs.single().state)
        assertTrue(provider.rows.isEmpty())
    }

    @Test fun publicationWinsOverMemoryRetry() = fixture { provider, queue, main ->
        queue.useAdmitted = true
        queue.processor = { job ->
            val uri = MediaSaver.create(app, "saved.jpg", "image/jpeg")
            recordTest(job.id, uri)
            MediaSaver.publish(app, uri)
            throw OutOfMemoryError("allocation failed after publication")
        }
        withContext(main) { queue.runTest() }
        assertEquals(1, queue.renders)
        assertEquals(1, provider.inserts)
        assertEquals(JobState.DONE, queue.jobs.single().state)
    }

    @Test fun blockedRecoveryDoesNotMakeOtherQueuedJobsUnrunnable() = fixture { provider, original, main ->
        val uri = MediaSaver.create(original.app, "saved.jpg", "image/jpeg")
        val queue = ProcessingQueue(original.app, original.host, listOf(job(pendingUri = uri.toString()), job(2)))
        provider.failQueries = 1
        withContext(main) { queue.runTest(queue.jobs.first()) }
        assertEquals(JobState.FAILED, queue.jobs.first().state)
        queue.processor = { "other job saved" }
        withContext(main) { queue.runTest(queue.jobs.last()) }
        assertEquals(JobState.DONE, queue.jobs.last().state)
        assertEquals(1, queue.renders)
    }

    @Test fun failedJournalCommitStopsPublicationAndKeepsRecoverableIdentity() = fixture { provider, queue, main ->
        configureSave(provider, queue)
        queue.prefs.commitSucceeds = false
        provider.failDelete = true
        withContext(main) { queue.runTest() }
        assertEquals(JobState.FAILED, queue.jobs.single().state)
        assertTrue(queue.jobs.single().pendingUri.isNotEmpty())
        assertTrue(provider.rows.values.single().pending)
    }

    @Test fun cancelThenProcessDeathRecoversPendingAsCancelled() = fixture { provider, original, main ->
        val uri = MediaSaver.create(original.app, "partial.jpg", "image/jpeg")
        val queue = ProcessingQueue(original.app, original.host,
            listOf(job(pendingUri = uri.toString(), state = JobState.RUNNING)))
        withContext(main) { queue.cancel(1) }
        // Snapshot immediately after the persisted cancel, before any runner completion.
        val loaded = queue.loadTest()
        assertEquals(JobState.CANCELLED, loaded.single().state)
        assertTrue(loaded.single().cancelledByUser)
        val restored = ProcessingQueue(original.app, original.host, loaded)
        withContext(Dispatchers.IO) { restored.recoverTest() }
        assertEquals(JobState.CANCELLED, restored.jobs.single().state)
        assertEquals("", restored.jobs.single().pendingUri)
        assertTrue(provider.rows.isEmpty())
        assertEquals(0, restored.renders)
        withContext(main) { restored.retry(1) }
        assertFalse(restored.jobs.last().cancelledByUser)
        assertEquals(JobState.QUEUED, restored.jobs.last().state)
    }

    @Test fun cancelThenProcessDeathRecoversPublishedAsDone() = fixture { provider, original, main ->
        val uri = MediaSaver.create(original.app, "saved.jpg", "image/jpeg")
        MediaSaver.publish(original.app, uri)
        val queue = ProcessingQueue(original.app, original.host,
            listOf(job(pendingUri = uri.toString(), state = JobState.RUNNING)))
        withContext(main) { queue.cancel(1) }
        val restored = ProcessingQueue(original.app, original.host, queue.loadTest())
        withContext(Dispatchers.IO) { restored.recoverTest() }
        assertEquals(JobState.DONE, restored.jobs.single().state)
        assertEquals(1, provider.rows.size)
        assertEquals(0, restored.renders)
    }

    @Test fun cancelThenProcessDeathRetainsUnknownAndRetryClearsIntent() = fixture { provider, original, main ->
        val uri = MediaSaver.create(original.app, "partial.jpg", "image/jpeg")
        val queue = ProcessingQueue(original.app, original.host,
            listOf(job(pendingUri = uri.toString(), state = JobState.RUNNING)))
        withContext(main) { queue.cancel(1) }
        val restored = ProcessingQueue(original.app, original.host, queue.loadTest())
        provider.failQueries = 1
        withContext(Dispatchers.IO) { restored.recoverTest() }
        assertEquals(JobState.FAILED, restored.jobs.single().state)
        assertEquals(uri.toString(), restored.jobs.single().pendingUri)
        assertTrue(restored.jobs.single().cancelledByUser)
        assertEquals("startup sweep must protect unresolved rows", 1, provider.rows.size)
        withContext(main) { restored.retry(1) }
        assertFalse(restored.jobs.single().cancelledByUser)
        restored.processor = { "explicit retry saved" }
        withContext(main) { restored.runTest() }
        assertEquals(JobState.DONE, restored.jobs.single().state)
        assertEquals(1, restored.renders)
    }

    @Test fun legacyRunningJsonDefaultsToUncancelledAndResumesQueued() = fixture { _, queue, _ ->
        val legacy = job(state = JobState.RUNNING).toJson().apply { remove("cancelledByUser") }
        queue.prefs.json = JSONArray().put(legacy).toString()
        val loaded = queue.loadTest().single()
        assertFalse(loaded.cancelledByUser)
        assertEquals(JobState.QUEUED, loaded.state)
    }

    @Test fun startupSweepKeepsProtectedPendingAndPublishedRows() = fixture { provider, queue, _ ->
        val protected = MediaSaver.create(queue.app, "Spektralab-X_protected.jpg", "image/jpeg")
        val orphan = MediaSaver.create(queue.app, "Spektralab-X_orphan.jpg", "image/jpeg")
        val published = MediaSaver.create(queue.app, "Spektralab-X_saved.jpg", "image/jpeg")
        MediaSaver.publish(queue.app, published)
        assertEquals(1, MediaSaver.sweepPending(queue.app, setOf(protected.toString())))
        assertTrue(provider.rows.containsKey(protected.toString()))
        assertTrue(provider.rows.containsKey(published.toString()))
        assertFalse(provider.rows.containsKey(orphan.toString()))
    }

    @Test fun queuedCancelThenImmediateRetryKeepsExplicitRetryQueued() = fixture { provider, original, main ->
        val uri = MediaSaver.create(original.app, "partial.jpg", "image/jpeg")
        val queue = ProcessingQueue(original.app, original.host, listOf(job(pendingUri = uri.toString())))
        withContext(main) {
            queue.cancel(1)
            queue.retry(1)
        }
        withTimeout(10_000) { queue.host.scope.coroutineContext[Job]!!.children.toList().joinAll() }
        assertEquals(JobState.QUEUED, queue.jobs.single().state)
        assertFalse(queue.jobs.single().cancelledByUser)
        assertEquals("", queue.jobs.single().pendingUri)
        assertTrue(provider.rows.isEmpty())
        queue.processor = { "explicit retry saved" }
        withContext(main) { queue.runTest() }
        assertEquals(JobState.DONE, queue.jobs.single().state)
        assertEquals(1, queue.renders)
    }

    @Test fun delayedQueuedCancelCleanupCannotTouchANewerTicket() = fixture { provider, original, main ->
        val oldUri = MediaSaver.create(original.app, "old-partial.jpg", "image/jpeg")
        val queue = ProcessingQueue(original.app, original.host, listOf(job(pendingUri = oldUri.toString())))
        var newUri: Uri? = null
        withContext(main) {
            queue.cancel(1)
            queue.retry(1)
            // Model the retry's next insertion before the old cleanup is dispatched,
            // using the same production settle/markPending operations as the worker.
            assertFalse(queue.settleTest(1))
            newUri = MediaSaver.create(queue.app, "new-partial.jpg", "image/jpeg")
            queue.recordTest(1, newUri!!)
        }
        withTimeout(10_000) { queue.host.scope.coroutineContext[Job]!!.children.toList().joinAll() }
        assertEquals(newUri.toString(), queue.jobs.single().pendingUri)
        assertTrue(provider.rows.containsKey(newUri.toString()))
        assertFalse(provider.rows.containsKey(oldUri.toString()))
        assertEquals(JobState.QUEUED, queue.jobs.single().state)
        assertFalse(queue.jobs.single().cancelledByUser)
    }
}
