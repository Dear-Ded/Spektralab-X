package com.dearded.spektralab

// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FrameRateBoostTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity

    @Before fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        activity = controller.get()
        val display = activity.window.decorView.display
        val mode = display.mode
        ShadowDisplayManager.setSupportedModes(
            display.displayId,
            ShadowDisplayManager.ModeBuilder.modeBuilder(mode.modeId)
                .setWidth(mode.physicalWidth).setHeight(mode.physicalHeight).setRefreshRate(60f).build(),
            ShadowDisplayManager.ModeBuilder.modeBuilder(mode.modeId + 1)
                .setWidth(mode.physicalWidth).setHeight(mode.physicalHeight).setRefreshRate(120f).build(),
        )
        activity.window.attributes = activity.window.attributes.also { it.preferredRefreshRate = 24f }
    }

    @After fun tearDown() {
        FrameRateBoost.onStop(activity)
        controller.pause().stop().destroy()
    }

    private fun event(action: Int, x: Float = 200f) {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, action, x, 0f, 0)
        try { FrameRateBoost.observe(activity, ev) } finally { ev.recycle() }
    }

    private fun drag() {
        event(MotionEvent.ACTION_DOWN, 0f)
        event(MotionEvent.ACTION_MOVE)
        val display = activity.window.decorView.display
        val mode = display.mode
        val expected = display.supportedModes
            .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .maxOf { it.refreshRate }
        assertTrue(expected > 24f)
        assertEquals(expected, activity.window.attributes.preferredRefreshRate, 0f)
    }

    private fun assertRestored() = assertEquals(24f, activity.window.attributes.preferredRefreshRate, 0f)

    @Test fun missingForecastDoesNotOverrideKnownPowerAndThermalStatus() {
        assertTrue(FrameRateBoost.permitted(false, PowerManager.THERMAL_STATUS_NONE, Float.NaN))
        assertTrue(FrameRateBoost.permitted(false, PowerManager.THERMAL_STATUS_MODERATE, 0.85f))
        assertFalse(FrameRateBoost.permitted(false, PowerManager.THERMAL_STATUS_NONE, 0.86f))
        assertFalse(FrameRateBoost.permitted(true, PowerManager.THERMAL_STATUS_NONE, Float.NaN))
        assertFalse(FrameRateBoost.permitted(false, PowerManager.THERMAL_STATUS_SEVERE, Float.NaN))
    }

    @Test fun powerSaverDuringDragRestoresPreviousPreference() {
        drag()
        shadowOf(activity.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        event(MotionEvent.ACTION_MOVE, 201f)
        assertRestored()
    }

    @Test fun severeThermalStateReleasesEvenWithoutCoordinateChange() {
        drag()
        shadowOf(activity.getSystemService(PowerManager::class.java)).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        event(MotionEvent.ACTION_MOVE)
        assertRestored()
    }

    @Test fun liftRestoresPreviousPreference() {
        drag()
        event(MotionEvent.ACTION_UP)
        assertRestored()
    }

    @Test fun cancellationRestoresPreviousPreference() {
        drag()
        event(MotionEvent.ACTION_CANCEL)
        assertRestored()
    }

    @Test fun stoppedActivityRestoresPreferenceAndRemovesPendingIdleReset() {
        drag()
        FrameRateBoost.onStop(activity)
        assertRestored()
        activity.window.attributes = activity.window.attributes.also { it.preferredRefreshRate = 30f }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(301))
        assertEquals(30f, activity.window.attributes.preferredRefreshRate, 0f)
    }

    @Test fun idleFingerRestoresPreviousPreference() {
        drag()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(299))
        assertTrue(activity.window.attributes.preferredRefreshRate > 24f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertRestored()
    }
}
