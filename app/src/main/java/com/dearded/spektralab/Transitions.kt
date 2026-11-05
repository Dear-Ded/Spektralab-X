package com.dearded.spektralab

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * Screen-to-screen motion (UI2.md 3.7, 3.8): shared frames between the light
 * table's grid and the loupe, and screen switches a back gesture can scrub.
 *
 *  - rememberSeekableNav(page) + SeekBack(state, to) { commit }: a screen
 *    switch driven by a SeekableTransitionState. App events animate to the new
 *    page; a back gesture seeks the transition towards `to` under the finger,
 *    a release commits, a cancel springs back.
 *  - Modifier.sharedFrame(key, shape): the same photo in two screens. Needs
 *    LocalSharedScope (a SharedTransitionLayout) and LocalNavScope (the
 *    AnimatedContent page it sits in); without them it does nothing.
 *
 * Only the light table runs inside a SharedTransitionLayout: its lookahead
 * pass would otherwise re-measure the editor's panel on every drag frame.
 */

/** The SharedTransitionLayout the light table's pages live in. Null outside it. */
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The AnimatedContent page a composable belongs to, for its shared frames. Null outside one. */
val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Shared frames move on slowSpatial (UI2.md 3.2: full-screen transitions), settling at a pixel. */
private val FrameBounds = BoundsTransform { _, _ ->
    spring(dampingRatio = 0.8f, stiffness = 200f, visibilityThreshold = Rect.VisibilityThreshold)
}

/**
 * Marks this photo as the same picture as any other [key]ed one on the next
 * page: during a page switch it flies between the two positions (slowSpatial)
 * while the two renderings cross-fade (a grid crop into a fitted loupe image),
 * clipped to [shape]. Put it on the image itself, before any drag offset.
 * Off when animations are off, and outside the light table's pages.
 */
fun Modifier.sharedFrame(key: Any, shape: Shape = RectangleShape, enabled: Boolean = true): Modifier = composed {
    val shared = LocalSharedScope.current
    val page = LocalNavScope.current
    val off = rememberReduceMotion()
    if (!enabled || shared == null || page == null || off) return@composed Modifier
    with(shared) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = page,
            enter = fadeIn(Motion.defaultEffects()),
            exit = fadeOut(Motion.defaultEffects()),
            boundsTransform = FrameBounds,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}

/**
 * A screen state that animates to every new [page] (equal pages do nothing).
 * Feed it to `rememberTransition(state).AnimatedContent { ... }`.
 */
@Composable
fun <S> rememberSeekableNav(page: S): SeekableTransitionState<S> {
    val state = remember { SeekableTransitionState(page) }
    LaunchedEffect(page) { state.animateTo(page) }
    return state
}

/**
 * The back gesture for a [rememberSeekableNav] screen (UI2.md 3.8). While
 * [to] is non-null a back gesture seeks the switch towards it, eased through
 * STANDARD_DECELERATE as the guide asks. On release [onCommit] runs, which
 * must make the screen's page equal [to], and the switch carries on from
 * where the finger left it. A cancel animates back.
 *
 * Disabled while [to] is null, so the system keeps its own back (and its
 * back-to-home animation) when there is nowhere in the app to go back to.
 */
@Composable
fun <S> SeekBack(state: SeekableTransitionState<S>, to: S?, onStart: () -> Unit = {}, onCommit: () -> Unit) {
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled = to != null) { events ->
        val target = to
        // Before the first seek, so a transitionSpec that reads it picks the "back" motion.
        onStart()
        try {
            events.collect { e ->
                if (target != null) state.seekTo(BackEase.transform(e.progress.coerceIn(0f, 1f)), target)
            }
            onCommit()
        } catch (e: CancellationException) {
            scope.launch { state.animateTo(state.currentState) }
            throw e
        }
    }
}
