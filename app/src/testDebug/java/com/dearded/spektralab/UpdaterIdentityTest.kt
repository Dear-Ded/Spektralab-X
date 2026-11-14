package com.dearded.spektralab

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import org.robolectric.util.ReflectionHelpers

/** Actual updater checks and failed install cleanup, co-created with GPT (OpenAI). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdaterIdentityTest {
    private lateinit var app: Application
    private lateinit var apk: File

    private fun info(version: Long, signer: String = "aabb", debuggable: Boolean = false) = PackageInfo().apply {
        packageName = app.packageName
        longVersionCode = version
        applicationInfo = ApplicationInfo().apply {
            packageName = app.packageName
            flags = if (debuggable) ApplicationInfo.FLAG_DEBUGGABLE else 0
        }
        signingInfo = ReflectionHelpers.newInstance(SigningInfo::class.java).also {
            Shadow.extract<ShadowSigningInfo>(it).setSignatures(arrayOf(Signature(signer)))
        }
    }

    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        apk = File(app.cacheDir, "updater-identity-test.apk").apply { delete() }
        shadowOf(app.packageManager).installPackage(info(100))
        Updater.state.value = Updater.State.Idle
        Updater.prompt.value = false
    }

    private fun check(candidate: PackageInfo) {
        shadowOf(app.packageManager).setPackageArchiveInfo(apk.path, candidate)
        val method = Updater::class.java.getDeclaredMethod("checkSigner", Context::class.java, File::class.java)
            .apply { isAccessible = true }
        try { method.invoke(Updater, app, apk) }
        catch (e: InvocationTargetException) { throw e.targetException }
    }

    @Test fun acceptsANewerReleaseWithTheSameCertificate() = check(info(101))

    @Test fun rejectsEqualAndOlderVersions() {
        for (version in listOf(99L, 100L)) assertThrows(IOException::class.java) { check(info(version)) }
    }

    @Test fun rejectsDebuggableApksEvenWithTheSameCertificate() {
        assertThrows(IOException::class.java) { check(info(101, debuggable = true)) }
    }

    @Test fun rejectsAnotherCertificateAndAnotherPackage() {
        assertThrows(IOException::class.java) { check(info(101, signer = "ccdd")) }
        assertThrows(IOException::class.java) { check(info(101).apply { packageName = "other.app" }) }
    }

    @Test fun installRechecksIdentityBeforeCreatingASession() = runBlocking {
        shadowOf(app.packageManager).setPackageArchiveInfo(apk.path, info(101, signer = "ccdd"))
        Updater.install(app, apk)
        withTimeout(5000) { while (Updater.state.value !is Updater.State.Failed) delay(10) }
        assertTrue(app.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun unreadableApkAbandonsTheCreatedInstallerSession() = runBlocking {
        shadowOf(app.packageManager).setPackageArchiveInfo(apk.path, info(101))
        Updater.install(app, apk)
        withTimeout(5000) { while (Updater.state.value !is Updater.State.Failed) delay(10) }
        assertTrue(app.packageManager.packageInstaller.mySessions.isEmpty())
    }
}
