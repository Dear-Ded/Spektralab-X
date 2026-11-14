package com.dearded.spektralab

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** External routing and explicit preset import, co-created with GPT (OpenAI). Synthetic files only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExternalFileRoutingTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val loader get() = SourceLoader(app)
    private lateinit var provider: Provider
    private val uri = Uri.parse("content://external-routing/document/412")

    class Provider : ContentProvider() {
        var name: String? = "camera.dng"
        var mime: String? = "application/octet-stream"
        var unavailable = false
        @Volatile var queryThread: Thread? = null
        var blockedUri: Uri? = null
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun onCreate() = true
        override fun getType(uri: Uri): String? {
            if (unavailable) throw SecurityException("Synthetic unavailable metadata")
            return mime
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            args: Array<out String>?, order: String?): Cursor {
            queryThread = Thread.currentThread()
            if (uri == blockedUri) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            if (unavailable) throw SecurityException("Synthetic unavailable metadata")
            return MatrixCursor(projection ?: arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
                addRow(columnNames.map { if (it == OpenableColumns.DISPLAY_NAME) name else null }.toTypedArray())
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    @Before fun prepare() {
        clearQueue()
        provider = Provider().apply { attachInfo(app, null) }
        ShadowContentResolver.registerProviderInternal("external-routing", provider)
    }
    @After fun clearQueue() { while (ImportRequests.take() != null) Unit }

    @Test fun genericCameraRawUsesTheProviderFileName() {
        for (extension in listOf("DNG", "NEF", "CR3", "ARW", "ORF", "RAF", "RW2")) {
            provider.name = "camera.$extension"
            assertTrue(extension, loader.isExternalPhoto(uri, "application/octet-stream"))
        }
    }

    @Test fun rawMimeWorksWithoutAnExtension() {
        provider.name = "412"
        provider.mime = "image/x-adobe-dng"
        assertTrue(loader.isExternalPhoto(uri, null))
        assertTrue(loader.isExternalPhoto(uri, "application/octet-stream"))
    }

    @Test fun genericOrMissingDeclaredTypeUsesTheProviderImageType() {
        provider.name = "412"
        provider.mime = "image/jpeg"
        for (declared in listOf(null, "", "application/octet-stream", "APPLICATION/OCTET-STREAM"))
            assertTrue("declared=$declared", loader.isExternalPhoto(uri, declared))
    }

    @Test fun numericHaldUriUsesTheProviderFileName() {
        provider.name = "Neutral-HALD.PNG"
        provider.mime = "image/png"
        assertEquals("412", uri.lastPathSegment)
        for (declared in listOf(null, "image/png", "application/octet-stream"))
            assertFalse("declared=$declared", loader.isExternalPhoto(uri, declared))
    }

    @Test fun ordinaryImagesStillOpenAsPhotos() {
        for (mime in listOf("image/png", "image/jpeg", "image/heic", "image/webp", "IMAGE/PNG")) {
            provider.name = "ordinary-photo.png"
            assertTrue(mime, loader.isExternalPhoto(uri, mime))
        }
    }

    @Test fun stylesAndRecipesStillEnterTheImporter() {
        for (extension in listOf("cube", "spklook", "xmp", "json", "zip", "txt")) {
            provider.name = "style.$extension"
            assertFalse(extension, loader.isExternalPhoto(uri, "application/octet-stream"))
        }
    }

    @Test fun unavailableMetadataFallsBackToUriAndDeclaredType() {
        provider.unavailable = true
        assertTrue(loader.isExternalPhoto(Uri.parse("content://external-routing/camera.dng"), null))
        assertTrue(loader.isExternalPhoto(uri, "image/jpeg"))
        assertFalse(loader.isExternalPhoto(uri, null))
        assertFalse(loader.isExternalPhoto(Uri.parse("content://external-routing/neutral-hald.png"), "image/png"))
    }

    @Test fun nullProviderNameFallsBackToUri() {
        provider.name = null
        assertTrue(loader.isExternalPhoto(Uri.parse("content://external-routing/camera.dng"), null))
        assertFalse(loader.isExternalPhoto(Uri.parse("content://external-routing/neutral-hald.png"), "image/png"))
    }

    @Test fun mainActivityQueuesHaldFromViewAndShare() {
        provider.name = "neutral-hald.png"
        provider.mime = "image/png"
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val route = MainActivity::class.java.getDeclaredMethod("handleIntent", Intent::class.java)
            .apply { isAccessible = true }
        for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) {
            val intent = Intent(action).setDataAndType(uri, "image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
            route.invoke(activity, intent)
            await { ImportRequests.uris.value.isNotEmpty() }
            assertEquals(listOf(uri), ImportRequests.uris.value)
            assertNotSame(Thread.currentThread(), provider.queryThread)
            clearQueue()
        }
    }

    @Test fun newerExternalOpenDiscardsTheSlowProviderResult() {
        provider.name = "neutral-hald.png"
        provider.blockedUri = uri
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val route = MainActivity::class.java.getDeclaredMethod("handleIntent", Intent::class.java)
            .apply { isAccessible = true }
        val job = MainActivity::class.java.getDeclaredField("incoming").apply { isAccessible = true }
        route.invoke(activity, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png"))
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS))
        val first = job.get(activity) as kotlinx.coroutines.Job
        val newest = Uri.parse("content://external-routing/document/413")
        try {
            route.invoke(activity, Intent(Intent.ACTION_VIEW).setDataAndType(newest, "image/png"))
            await { ImportRequests.uris.value == listOf(newest) }
        } finally { provider.release.countDown() }
        await { first.isCompleted }
        assertEquals(listOf(newest), ImportRequests.uris.value)
    }

    private fun await(done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!done() && System.nanoTime() < until) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Asynchronous provider operation did not finish", done())
    }

    @Test fun explicitDngPresetImportStillReadsEmbeddedXmp() {
        val file = File(app.cacheDir, "synthetic-preset.dng")
        val xmp = """<x:xmpmeta><rdf:Description crs:ProcessVersion="11.0" crs:Exposure2012="0.75" crs:Contrast2012="20"/></x:xmpmeta>"""
        // A synthetic TIFF header plus an XMP packet exercises the preset reader, not RAW decoding.
        file.writeBytes(byteArrayOf(0x49, 0x49, 0x2a, 0, 8, 0, 0, 0) + xmp.toByteArray())
        try {
            val library = StyleImports(app)
            val pending = library.read(Uri.fromFile(file), Catalog(emptyList(), emptyList()))
            assertEquals(ImportKind.STYLE, pending.kind)
            assertEquals(0.75, pending.look.layer2.exposure, 0.0)
            assertEquals(10.0, pending.look.layer2.contrast, 0.0)
            library.discard(pending)
        } finally { file.delete() }
    }
}
