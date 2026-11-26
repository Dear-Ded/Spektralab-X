package com.dearded.spektralab

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/*
 * One way to give feedback: a [Cue], named after what the hand would feel on a
 * camera, decides the vibration and, for the few moments that deserve one, the
 * sound. Every control asks for a cue; none picks a system constant itself.
 *
 *  - Small, frequent things (a dial's detent, a tab, a toggle) use the system's
 *    own touch effects through View.performHapticFeedback: tuned for each phone's
 *    motor, they follow the system touch-feedback switch, and they need no permission.
 *  - Mechanical moments (the shutter, a stroke of the advance lever, the back
 *    closing) are composed from vibration primitives on API 30+ where the motor
 *    supports them, so they have the rhythm of the real thing; elsewhere they fall
 *    back to one system effect. They also follow the system touch-feedback switch.
 *  - The small, frequent ones are also composed on API 31+ when the motor has the primitives:
 *    a light PRIMITIVE_TICK (0.2-0.5) is finer than the system's tick on a good X-axis motor.
 *  - 振动反馈 in settings ([VIBRATION], default on) turns every vibration off; sounds keep
 *    their own switch.
 *  - Sounds (res/raw/snd_*.ogg, recordings cut by tools/sound/cut_sounds.py) play only for
 *    those moments, never for a dial or a tab. They follow media volume and
 *    only play while 相机声音 is on in settings.
 *
 * Each cue is logged (logcat tag "Feedback" and [recent]), so a test or an agent
 * can see what fired without holding the phone.
 */
enum class Cue {
    /** A dial passes a step. */
    Detent,
    /** A dial passes a labelled step or a stop it rests on (40 / 70 % strength). */
    DetentMajor,
    /** A dial comes back to its default. */
    Zero,
    /** A dial reaches an end. */
    Limit,
    /** One stroke of the film-advance lever. */
    Ratchet,
    /** The frame counter steps on. */
    Counter,
    /** Mirror up, two curtains, mirror down. */
    Shutter,
    /** The developing tank turned over. */
    Agitate,
    /** A finished frame is put away (export done). */
    Saved,
    /** The rewind crank (a batch export done). */
    Rewind,
    /** The camera back closes over a new roll (a film or paper chosen). */
    Load,
    /** A look chosen (a style card). */
    Pick,
    /** A tab or a segment changed. */
    Tab,
    /** A finger takes hold of something it will drag (a panel, a curve point, compare). */
    Hold,
    /** And lets go of it. */
    Release,
    /** The panel snaps to a height, a step in a strip. */
    Segment,
    LongPress,
    ToggleOn,
    ToggleOff,
    /** Something failed. */
    Error,
}

/** Settings key of 相机声音 in "settings", default on. */
internal const val CAMERA_SOUND = "camera_sound"
/** Settings key of 振动反馈 in "settings", default on. */
internal const val VIBRATION = "vibration"

object Feedback {
    private const val TAG = "Feedback"
    private const val LOG_SIZE = 40

    private val log = ArrayDeque<String>()
    /** The last cues, newest last (for tests and diagnostics). */
    fun recent(): List<String> = synchronized(log) { log.toList() }

    @Volatile private var pool: SoundPool? = null
    private val ids = HashMap<Cue, Int>()
    private val loaded = HashSet<Int>()
    private var lastDetent = 0L

    private val SOUNDS = mapOf(
        Cue.Shutter to R.raw.snd_shutter, Cue.Ratchet to R.raw.snd_ratchet, Cue.Counter to R.raw.snd_counter,
        Cue.Agitate to R.raw.snd_agitate, Cue.Saved to R.raw.snd_saved, Cue.Rewind to R.raw.snd_rewind,
        Cue.Load to R.raw.snd_load,
    )
    private val VOLUME = mapOf(Cue.Shutter to 0.9f, Cue.Counter to 0.5f, Cue.Agitate to 0.55f)

    /** Loads the sounds; call once at start, on any thread. */
    fun warm(context: Context) {
        if (pool != null) return
        synchronized(this) {
            if (pool != null) return
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build()
            p.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
            val app = context.applicationContext
            for ((cue, res) in SOUNDS) ids[cue] = p.load(app, res, 1)
            pool = p
        }
    }

    /**
     * Plays [cue] from [view]. [fast] marks a detent passed in a quick spin: it is
     * played lighter, and detents closer than 14 ms apart are dropped, so a fling
     * never blurs into a buzz.
     */
    fun play(view: View, cue: Cue, fast: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (cue == Cue.Detent || cue == Cue.DetentMajor) {
            if (now - lastDetent < 14) return
            lastDetent = now
        }
        record(cue)
        if (vibrationOn(view.context)) {
            val composed = Build.VERSION.SDK_INT >= 31 && playComposed(view.context, cue, fast)
            if (!composed) systemConstant(cue, fast)?.let { view.performHapticFeedback(it) }
        }
        sound(view.context, cue)
    }

    /** False when 振动反馈 is off in settings; every vibration in the app asks this. */
    fun vibrationOn(context: Context): Boolean =
        context.getSharedPreferences("settings", 0).getBoolean(VIBRATION, true)

    private fun record(cue: Cue) {
        android.util.Log.d(TAG, cue.name)
        synchronized(log) {
            log.addLast("${SystemClock.uptimeMillis()} ${cue.name}")
            while (log.size > LOG_SIZE) log.removeFirst()
        }
    }

    // --- sound -------------------------------------------------------------------

    private fun sound(context: Context, cue: Cue) {
        val id = ids[cue] ?: return
        val p = pool ?: return
        if (synchronized(loaded) { id !in loaded }) return
        if (!context.getSharedPreferences("settings", 0).getBoolean(CAMERA_SOUND, true)) return
        val v = VOLUME[cue] ?: 0.75f
        p.play(id, v, v, 1, 0, 1f)
    }

    // --- vibration ----------------------------------------------------------------

    /** The system effect for [cue] on this API level (the fallback for the composed ones too). */
    private fun systemConstant(cue: Cue, fast: Boolean): Int? {
        val sdk = Build.VERSION.SDK_INT
        val segment = if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        val frequent = if (sdk >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        val confirm = if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        return when (cue) {
            Cue.Detent -> frequent
            Cue.DetentMajor -> if (fast) frequent else segment
            Cue.Zero -> segment
            Cue.Limit -> if (sdk >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK
            Cue.Ratchet, Cue.Counter -> HapticFeedbackConstants.CLOCK_TICK
            Cue.Shutter, Cue.Saved, Cue.Load -> confirm
            Cue.Agitate -> if (sdk >= 30) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.CLOCK_TICK
            Cue.Rewind -> confirm
            Cue.Pick -> HapticFeedbackConstants.CONTEXT_CLICK
            Cue.Tab -> HapticFeedbackConstants.CLOCK_TICK
            Cue.Hold -> if (sdk >= 30) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.CONTEXT_CLICK
            Cue.Release -> if (sdk >= 30) HapticFeedbackConstants.GESTURE_END else null
            Cue.Segment -> segment
            Cue.LongPress -> HapticFeedbackConstants.LONG_PRESS
            Cue.ToggleOn -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
            Cue.ToggleOff -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
            Cue.Error -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
    }

    /** A primitive, its strength 0..1 and the wait before it in ms. */
    private class Step(val primitive: Int, val scale: Float, val delay: Int)

    /** Plays [cue]'s composed rhythm; false when it has none or this phone cannot play it. */
    @RequiresApi(31)
    private fun playComposed(context: Context, cue: Cue, fast: Boolean): Boolean {
        val steps = composition(cue, fast) ?: return false
        return vibrate(context, steps)
    }

    /** The rhythm of a mechanical moment, or null for cues that use the system effect. API 31 for LOW_TICK and THUD. */
    @RequiresApi(31)
    private fun composition(cue: Cue, fast: Boolean): List<Step>? {
        val click = VibrationEffect.Composition.PRIMITIVE_CLICK
        val tick = VibrationEffect.Composition.PRIMITIVE_TICK
        val low = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        val thud = VibrationEffect.Composition.PRIMITIVE_THUD
        val rise = VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
        val fall = VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
        return when (cue) {
            Cue.Shutter -> listOf(Step(thud, 0.45f, 0), Step(click, 1f, 42), Step(click, 0.75f, 26), Step(thud, 0.4f, 36))
            Cue.Ratchet -> listOf(Step(tick, 0.45f, 0), Step(tick, 0.55f, 40), Step(tick, 0.65f, 36), Step(tick, 0.75f, 32),
                Step(tick, 0.8f, 29), Step(click, 0.9f, 60), Step(low, 0.5f, 80))
            Cue.Counter -> listOf(Step(tick, 0.5f, 0))
            Cue.Agitate -> listOf(Step(rise, 0.35f, 0), Step(fall, 0.4f, 120), Step(thud, 0.3f, 180))
            Cue.Saved -> listOf(Step(click, 0.55f, 0), Step(click, 0.85f, 70))
            Cue.Rewind -> buildList {
                var gap = 22f
                for (i in 0 until 12) { add(Step(tick, 0.7f - i * 0.04f, if (i == 0) 0 else gap.toInt())); gap *= 1.06f }
                add(Step(click, 0.5f, 60))
            }
            Cue.Load -> listOf(Step(thud, 0.9f, 0), Step(click, 0.45f, 32))
            Cue.Zero -> listOf(Step(low, 0.4f, 0), Step(click, 0.8f, 18))
            Cue.Limit -> listOf(Step(thud, 0.7f, 0))
            // The fine ones: a detent is a grain under the thumb, a spin lighter still.
            Cue.Detent -> listOf(Step(tick, if (fast) 0.18f else 0.3f, 0))
            Cue.DetentMajor -> listOf(Step(tick, if (fast) 0.3f else 0.55f, 0))
            Cue.Tab, Cue.Segment -> listOf(Step(tick, 0.45f, 0))
            Cue.Pick -> listOf(Step(click, 0.35f, 0))
            Cue.Hold -> listOf(Step(low, 0.35f, 0))
            Cue.Release -> listOf(Step(tick, 0.25f, 0))
            Cue.ToggleOn -> listOf(Step(low, 0.3f, 0), Step(click, 0.5f, 24))
            Cue.ToggleOff -> listOf(Step(click, 0.35f, 0))
            else -> null
        }
    }

    /** Plays [steps]; false when this phone cannot, so the caller falls back to the system effect. */
    // The primitives come from [composition], which only uses Composition.PRIMITIVE_* constants;
    // lint cannot follow them through the list.
    @android.annotation.SuppressLint("WrongConstant")
    @RequiresApi(31)
    private fun vibrate(context: Context, steps: List<Step>): Boolean {
        // The system's touch-feedback switch covers these too, as it does performHapticFeedback.
        if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return true
        val v = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return false
        if (!v.hasVibrator()) return false
        val primitives = steps.map { it.primitive }.distinct().toIntArray()
        if (!v.areAllPrimitivesSupported(*primitives)) return false
        val c = VibrationEffect.startComposition()
        for (s in steps) c.addPrimitive(s.primitive, s.scale.coerceIn(0f, 1f), s.delay)
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(c.compose(), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(c.compose(), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            }
            true
        }.getOrDefault(false)
    }
}

/** [Feedback.play] on the current view, for click lambdas: `val cue = rememberCue(); ... cue(Cue.Pick)`. */
@Composable
fun rememberCue(): (Cue) -> Unit {
    val view = LocalView.current
    return remember(view) { { c: Cue -> Feedback.play(view, c) } }
}
