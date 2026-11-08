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
    private var previousRefreshRate = 0f
    private val idle = Runnable { release() }

    /** Main thread, every touch event of [activity]'s window. */
    fun observe(activity: Activity, ev: MotionEvent) {
        if (Build.VERSION.SDK_INT < 35) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                release()
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y; moving = false
            }
            MotionEvent.ACTION_MOVE -> {
                // A continuing drag must yield if power or thermal limits change.
                if (!allowed(activity)) {
                    release()
                    return
                }
                if (ev.x == lastX && ev.y == lastY) return
                lastX = ev.x; lastY = ev.y
                if (!moving) {
                    val slop = ViewConfiguration.get(activity).scaledTouchSlop
                    if (abs(ev.x - downX) <= slop && abs(ev.y - downY) <= slop) return
                    moving = true
                }
                val decor = activity.window.decorView
                decor.removeCallbacks(idle)
                if (boosted?.get() == null) boost(activity.window)
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
        val host = (activity.application as? SpektraLabApp)?.host
        return permitted(pm.isPowerSaveMode, pm.currentThermalStatus, host?.thermal?.headroom() ?: Float.NaN)
    }

    internal fun permitted(powerSave: Boolean, thermalStatus: Int, headroom: Float): Boolean =
        !powerSave && thermalStatus < PowerManager.THERMAL_STATUS_SEVERE &&
            // The asynchronous sampler reports NaN until a usable sample arrives.
            (!headroom.isFinite() || headroom <= HOT_HEADROOM)

    /** A stopped activity may never receive the gesture's final UP or CANCEL. */
    fun onStop(activity: Activity) {
        moving = false
        if (boosted?.get() === activity.window) release()
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
        previousRefreshRate = window.attributes.preferredRefreshRate
        window.attributes = window.attributes.also { it.preferredRefreshRate = top }
        boosted = WeakReference(window)
    }

    /** Restore the window's previous preference; safe when nothing is held. */
    private fun release() {
        val window = boosted?.get()
        boosted = null
        if (window == null) return
        window.decorView.removeCallbacks(idle)
        if (window.attributes.preferredRefreshRate != previousRefreshRate) {
            window.attributes = window.attributes.also { it.preferredRefreshRate = previousRefreshRate }
        }
    }
}
