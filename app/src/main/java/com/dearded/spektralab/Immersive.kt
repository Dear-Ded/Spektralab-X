package com.dearded.spektralab

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/*
 * Immersive viewing (docs/UI.md, "沉浸式"): a tap on the photo fades every
 * control and the system bars, leaving the print on its neutral surround.
 * The layout never changes -- bars and panels only fade -- so the photo does
 * not move a pixel when the chrome goes or comes back.
 */

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Hides the status and navigation bars while [hidden]; a swipe from an edge shows them for a moment. */
@Composable
fun SystemBarsHidden(hidden: Boolean) {
    val activity = LocalContext.current.activity() ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(hidden, activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        fun apply() {
            if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars())
            else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        apply()
        // The system brings the bars back after a picker, a permission dialog
        // or a trip to the background; hide them again on return.
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) apply() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Fades this chrome with [visible] (effects spring, read in the draw phase so
 * nothing recomposes), and while hidden takes it out of touch and of the
 * accessibility tree: a transparent button must not be pressable.
 */
fun Modifier.chrome(visible: Boolean): Modifier = composed {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, Motion.defaultEffects(), label = "chrome")
    var m = graphicsLayer { this.alpha = alpha }
    if (!visible) {
        m = m.clearAndSetSemantics { }.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }
    m
}

/** Tap-to-hide stays off under TalkBack, where a tap means "explore", not "hide". */
@Composable
fun immersiveAllowed(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        am?.isTouchExplorationEnabled != true
    }
}
