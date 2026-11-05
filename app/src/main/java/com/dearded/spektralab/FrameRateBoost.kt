package com.dearded.spektralab

import android.app.Activity
import android.os.Build
import android.os.PowerManager
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * 拖动时请求高刷: while a finger is down and moving (past the touch slop) the
 * window asks for the display's highest refresh rate at its current
 * resolution (120 Hz on the Xiaomi 10-18 panels); on lift, cancel, or 300 ms
 * without movement the request goes and the adaptive rate takes over again.
 *
 * Fed from MainActivity.dispatchTouchEvent, before super: it only reads the
 * event, never consumes or changes it, so every gesture (sliders, curves, the
 * palette pad, the panel handle, chrome()) behaves as before.
 *
 * A window preference rather than View.setRequestedFrameRate: a view's vote
 * counts only when that view draws, and Compose's AndroidComposeView sets its
 * own requested rate on every frame (Api35Impl.setRequestedFrameRate, javap of
 * ui-android 1.10.1), which would overwrite ours.
 *
 * Android 15+ only (earlier releases keep their own policy); nothing in
 * battery saver, at thermal status SEVERE or above, or when the editor's hot
 * line (headroom 0.85) is crossed.
 */
object FrameRateBoost {
    private const val IDLE_MS = 300L
    private const val HOT_HEADROOM = 0.85f

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moving = false
    private var boosted: WeakReference<Window>? = null
    private val idle = Runnable { release() }

    /** Main thread, every touch event of [activity]'s window. */
    fun observe(activity: Activity, ev: MotionEvent) {
        if (Build.VERSION.SDK_INT < 35) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y; moving = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.x == lastX && ev.y == lastY) return
                lastX = ev.x; lastY = ev.y
                if (!moving) {
                    val slop = ViewConfiguration.get(activity).scaledTouchSlop
                    if (abs(ev.x - downX) <= slop && abs(ev.y - downY) <= slop) return
                    moving = true
                }
                val decor = activity.window.decorView
                decor.removeCallbacks(idle)
                if (boosted?.get() == null && allowed(activity)) boost(activity.window)
                if (boosted != null) decor.postDelayed(idle, IDLE_MS)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                moving = false
                activity.window.decorView.removeCallbacks(idle)
                release()
            }
        }
    }

    private fun allowed(activity: Activity): Boolean {
        val pm = activity.getSystemService(PowerManager::class.java) ?: return false
        if (pm.isPowerSaveMode) return false
        if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return false
        val host = (activity.application as? SpektraLabApp)?.host
        return host == null || host.thermal.headroom() <= HOT_HEADROOM
    }

    private fun boost(window: Window) {
        val display = window.decorView.display ?: return
        val mode = display.mode
        val top = display.supportedModes
            .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: return
        if (top <= mode.refreshRate && window.attributes.preferredRefreshRate == 0f) {
            // Already at the top rate: nothing to ask for, nothing to undo.
            return
        }
        window.attributes = window.attributes.also { it.preferredRefreshRate = top }
        boosted = WeakReference(window)
    }

    /** Back to no preference; safe to call when nothing is held. */
    private fun release() {
        val window = boosted?.get()
        boosted = null
        if (window == null) return
        window.decorView.removeCallbacks(idle)
        if (window.attributes.preferredRefreshRate != 0f) {
            window.attributes = window.attributes.also { it.preferredRefreshRate = 0f }
        }
    }
}
