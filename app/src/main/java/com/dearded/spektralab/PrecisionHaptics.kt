package com.dearded.spektralab

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View

/** Foreground touch feedback only. Unsupported primitives must never silence a whole composition. */
internal object PrecisionHaptics {
    @Volatile private var device: Device? = null

    fun play(view: View, event: HapticEvent): Boolean {
        if (!view.isHapticFeedbackEnabled || !view.isShown || !view.hasWindowFocus()) return true
        val context = view.context.applicationContext
        val enabled = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
        }.getOrDefault(false)
        if (!enabled) return true
        val target = device ?: synchronized(this) {
            device ?: Device(context).also { device = it }
        }
        return target.play(event)
    }

    private class Device(context: Context) {
        private val vibrator = runCatching {
            if (Build.VERSION.SDK_INT >= 31)
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else context.getSystemService(Vibrator::class.java)
        }.getOrNull()
        private val primitives = if (Build.VERSION.SDK_INT >= 30) runCatching {
            val ids = if (Build.VERSION.SDK_INT >= 31) intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_CLICK,
            ) else intArrayOf(VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK)
            val supported = vibrator?.arePrimitivesSupported(*ids) ?: BooleanArray(ids.size)
            ids.filterIndexed { i, _ -> supported.getOrElse(i) { false } }.toSet()
        }.getOrDefault(emptySet()) else emptySet()
        private val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        private val effects = HashMap<HapticEvent, VibrationEffect>()

        fun play(event: HapticEvent): Boolean {
            val motor = vibrator ?: return false
            return runCatching {
                if (!motor.hasVibrator()) return false
                val effect = effects.getOrPut(event) { effect(event) }
                // New feedback replaces the previous pulse; there is no coroutine or delayed queue.
                motor.vibrate(effect, attributes)
                true
            }.getOrDefault(false)
        }

        private fun effect(event: HapticEvent): VibrationEffect {
            if (Build.VERSION.SDK_INT >= 30) {
                val primitive = when (event) {
                    HapticEvent.Detent -> if (Build.VERSION.SDK_INT >= 31 &&
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK in primitives)
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK else VibrationEffect.Composition.PRIMITIVE_TICK
                    HapticEvent.Tick, HapticEvent.Segment, HapticEvent.GestureStart, HapticEvent.GestureEnd,
                    HapticEvent.ToggleOn, HapticEvent.ToggleOff -> VibrationEffect.Composition.PRIMITIVE_TICK
                    else -> VibrationEffect.Composition.PRIMITIVE_CLICK
                }
                if (primitive in primitives) {
                    val scale = when (event) {
                        HapticEvent.Detent -> 0.22f
                        HapticEvent.Tick -> 0.65f
                        HapticEvent.Confirm -> 0.48f
                        HapticEvent.Edge, HapticEvent.Reject -> 0.75f
                        else -> 0.42f
                    }
                    val composition = VibrationEffect.startComposition().addPrimitive(primitive, scale)
                    if (event == HapticEvent.Confirm) composition.addPrimitive(primitive, 0.32f, 45)
                    return composition.compose()
                }
            }
            // Predefined effects have Android's device-specific fallback, including API 29.
            return VibrationEffect.createPredefined(when (event) {
                HapticEvent.Tick -> VibrationEffect.EFFECT_CLICK
                HapticEvent.Detent, HapticEvent.Segment,
                HapticEvent.ToggleOn, HapticEvent.ToggleOff, HapticEvent.GestureStart,
                HapticEvent.GestureEnd -> VibrationEffect.EFFECT_TICK
                HapticEvent.Confirm -> VibrationEffect.EFFECT_DOUBLE_CLICK
                HapticEvent.Edge, HapticEvent.Reject -> VibrationEffect.EFFECT_HEAVY_CLICK
                else -> VibrationEffect.EFFECT_CLICK
            })
        }
    }
}
