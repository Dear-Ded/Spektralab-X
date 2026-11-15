package com.dearded.spektralab

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/*
 * The launch intro (tools/work/ui-motion_splash.md 6). The system splash shows
 * the launcher's film strip with its spectrum wiping on; this picks up from
 * that exact frame in Compose while the engine warms up behind it:
 *
 *   0-500 ms    the Gemini glow rises behind the strip (Aura, slowEffects)
 *   0-450 ms    the spectrum finishes its wipe if the splash cut it short
 *   60-600 ms   the sprockets pull one frame to the left (defaultSpatial)
 *   200-900 ms  "Spektralab-X" lands on split-flap tiles under the strip, in place
 *
 * No light-leak sweep and no rising wordmark: decoration that says nothing
 * (tools/work/ui_ai_flavor_audit.md 10).
 *
 * Its clock starts after the system splash hands over. The settled name stays
 * visible before a 420 ms dissolve into the editor; a tap after 300 ms skips
 * the hold. It only runs for a launcher start, with system animations on and
 * the 启动动画 setting on. No sound; one tick when the title settles. No photo is on screen yet,
 * so the colour here breaks no rule about judging a print.
 */

/** The 启动动画 setting, default on. */
internal const val LAUNCH_INTRO = "launch_intro"

/** The name the intro flaps in, and when it starts (ms after the first frame). */
private const val NAME = "Spektralab-X"
private const val NAME_DELAY_MS = 200L
internal const val INTRO_HOLD_MS = 1500L
internal const val INTRO_TIMEOUT_MS = 2600L
internal const val INTRO_FADE_MS = 420

/** Where the system splash drew its icon, and how far its wipe had got, handed over by MainActivity. */
internal object LaunchIcon {
    /** Window coordinates in px, or null when the splash did not report it (then the strip is centred). */
    var rect by mutableStateOf<android.graphics.Rect?>(null)
    /** 0..1 of the spectrum wipe the splash had shown (1 below API 31, where the icon is still). */
    var wipe by mutableStateOf(1f)
}

/** Whether this start gets the intro: a launcher start, animations on, the setting on. */
internal fun launchIntroWanted(context: Context, intent: Intent?): Boolean =
    intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
        motionAllowed() && context.getSharedPreferences("settings", 0).getBoolean(LAUNCH_INTRO, true)

// The launcher foreground's geometry (ic_launcher_foreground.xml, a 108-unit viewport).
private const val PITCH = 10.56f
private val SPECTRUM = AuraColors.spectrum

/**
 * The intro over the first screen. [ready] is the engine's readiness;
 * [onDone] runs once it has faded out, and the caller then drops it.
 */
@Composable
fun LaunchIntro(ready: Boolean, visible: Boolean = true, onDone: () -> Unit) {
    var canSkip by remember { mutableStateOf(false) }
    val isReady by rememberUpdatedState(ready)
    val done by rememberUpdatedState(onDone)
    var leaving by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf(false) }

    val glow = remember { Animatable(0f) }
    val wipe = remember { Animatable(LaunchIcon.wipe.coerceIn(0f, 1f)) }
    val pull = remember { Animatable(0f) }
    val shown = remember { Animatable(1f) }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        // Composition can start behind the system splash. Count only visible frames.
        withFrameNanos { }
        launch { delay(300); canSkip = true }
        wipe.snapTo(LaunchIcon.wipe.coerceIn(0f, 1f))
        launch { glow.animateTo(1f, Motion.slowEffects()) }
        launch { wipe.animateTo(1f, Motion.defaultEffects()) }
        launch { delay(60); pull.animateTo(1f, Motion.defaultSpatial()) }
        val hold = launch { delay(INTRO_HOLD_MS) }
        withTimeoutOrNull(INTRO_TIMEOUT_MS) { snapshotFlow { isReady }.first { it } }
        hold.join()
        leaving = true
    }
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        shown.animateTo(0f, tween(if (quick) 180 else INTRO_FADE_MS, easing = FastOutSlowInEasing))
        done()
    }

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = shown.value }
            .clearAndSetSemantics { }
            .pointerInput(visible) {
                detectTapGestures {
                    if (visible && canSkip && !leaving) {
                        quick = true; leaving = true
                    }
                }
            },
    ) {
        Aura(Modifier.matchParentSize(), strength = { glow.value }, active = visible && !leaving)
        Canvas(Modifier.matchParentSize()) {
            val r = LaunchIcon.rect
            // The splash icon box: what the system reported, else 240 dp centred (API 31's icon size).
            val box = if (r != null && r.width() > 0) Rect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
            else {
                val side = 240.dp.toPx()
                Rect(Offset((size.width - side) / 2f, (size.height - side) / 2f), Size(side, side))
            }
            val u = box.width / 108f
            fun x(v: Float) = box.left + v * u
            fun y(v: Float) = box.top + v * u

            // The strip's body.
            val body = Rect(x(22f), y(36f), x(86f), y(72f))
            drawRoundRect(Color(0xFF24242A), body.topLeft, body.size, CornerRadius(3f * u))
            // The spectrum frame, its wipe completed from wherever the splash left it.
            val frameW = 64f * u * if (visible) wipe.value else LaunchIcon.wipe
            if (frameW > 0f) drawRect(
                Brush.horizontalGradient(*SPECTRUM.toTypedArray(), startX = x(22f), endX = x(86f)),
                topLeft = Offset(x(22f), y(43f)), size = Size(frameW, 22f * u),
            )
            // Sprockets, pulled one pitch to the left and clipped to the body.
            clipRect(body.left, body.top, body.right, body.bottom) {
                for (i in 0..7) {
                    val hx = 25f + i * PITCH - PITCH * pull.value
                    for (hy in floatArrayOf(37.7f, 66.7f)) {
                        drawRect(Color(0xFF121216), Offset(x(hx), y(hy)), Size(5.2f * u, 3.6f * u))
                    }
                }
            }
        }
        // The name under the strip on split-flap tiles (SplitFlap.kt), in place, no rise. It
        // starts at 200 ms and has settled by about 900; a tap shows it settled at once. One
        // light tick when the last letter lands, nothing per flip.
        if (visible) {
            val r = LaunchIcon.rect
            val haptic = rememberHaptic()
            SplitFlapText(
                NAME,
                style = MaterialTheme.typography.headlineSmall,
                startDelayMs = NAME_DELAY_MS,
                skip = quick,
                onLanded = { haptic(HapticEvent.Tick) },
                modifier = Modifier
                    .align(if (r == null) Alignment.Center else Alignment.TopCenter)
                    .layout { m, c ->
                        val pl = m.measure(c.copy(minWidth = 0, minHeight = 0))
                        layout(pl.width, pl.height) {
                            // 24 dp below the strip's lower edge, in the splash's window coordinates.
                            val y = if (r != null) (r.top + r.height() * 72f / 108f + 24.dp.toPx()).roundToInt()
                                    else (240.dp.toPx() * 18f / 108f + 24.dp.toPx()).roundToInt()
                            pl.place(0, y)
                        }
                    }
            )
        }
    }
}
