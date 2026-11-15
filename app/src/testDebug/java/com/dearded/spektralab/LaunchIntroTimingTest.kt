package com.dearded.spektralab

import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Visible launch timing, including a slow system splash and a user skip. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-normal-long-notround-port-night-mdpi-keyshidden-nonav")
class LaunchIntroTimingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var visible by mutableStateOf(false)
    private var ready by mutableStateOf(true)
    private var finished = 0

    @Before fun prepare() {
        compose.mainClock.autoAdvance = false
        LaunchIcon.rect = null
        LaunchIcon.wipe = 1f
        File("build/outputs/roborazzi").mkdirs()
    }

    private fun show() = compose.setContent {
        CompositionLocalProvider(LocalCpuGlow provides true) {
            SpektraTheme {
                Box(Modifier.fillMaxSize().background(Palette.Surround)) {
                    Text("Spektralab-X", color = Palette.OnSurface)
                    LaunchIntro(ready = ready, visible = visible) { finished++ }
                }
            }
        }
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    @Test fun slowSystemSplashDoesNotConsumeTheVisibleIntro() {
        show()
        advance(4000)
        assertEquals(0, finished)
        compose.runOnIdle { visible = true }
        advance(1000)
        assertEquals(0, finished)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/93_intro_settled.png")
        advance(650)
        assertEquals("The editor dissolve must still be in progress", 0, finished)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/94_intro_dissolve.png")
        advance(600)
        assertEquals(1, finished)
        advance(3000)
        assertEquals("Completion is delivered once", 1, finished)
    }

    @Test fun unreadyEngineHasABoundedWait() {
        ready = false
        visible = true
        show()
        advance(2500)
        assertEquals(0, finished)
        advance(1000)
        assertEquals(1, finished)
    }

    @Test fun aTapSkipsTheHoldAfterTheLaunchTouchGuard() {
        visible = true
        ready = false
        show()
        advance(500)
        // Pointer event time follows the frame clock; no physical phone input is involved.
        compose.onRoot().performTouchInput { click(center) }
        advance(400)
        assertEquals(1, finished)
    }

    @Test fun preferenceAndExternalOpenStillSkipTheIntro() {
        val context = compose.activity
        context.getSharedPreferences("settings", 0).edit().putBoolean(LAUNCH_INTRO, false).commit()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        assertFalse(launchIntroWanted(context, launcher))
        context.getSharedPreferences("settings", 0).edit().putBoolean(LAUNCH_INTRO, true).commit()
        assertFalse(launchIntroWanted(context, Intent(Intent.ACTION_VIEW)))
    }
}
