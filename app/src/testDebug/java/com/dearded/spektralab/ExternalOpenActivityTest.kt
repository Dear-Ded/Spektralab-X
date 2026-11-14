package com.dearded.spektralab

import android.content.Intent
import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Repeated gallery opens reuse the foreground editor and retain the newest launch intent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalOpenActivityTest {
    @Test fun manifestReusesTheTopEditor() {
        val app = RuntimeEnvironment.getApplication()
        val component = android.content.ComponentName(app, MainActivity::class.java)
        @Suppress("DEPRECATION")
        val info = app.packageManager.getActivityInfo(component, 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }

    @Test fun newIntentReplacesTheStoredIntent() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN))
        val next = Intent(Intent.ACTION_MAIN).putExtra("test_marker", "latest")
        controller.newIntent(next)
        assertSame(next, controller.get().intent)
    }
}
