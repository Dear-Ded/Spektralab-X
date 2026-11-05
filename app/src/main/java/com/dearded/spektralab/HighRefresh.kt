package com.dearded.spektralab

import android.os.Build
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.preferredFrameRate

/**
 * While [active] (a finger on a slider, curve, palette pad or the panel's
 * handle), this subtree votes for the display's high refresh rate (120 Hz on
 * the Xiaomi 10-18 panels); on release the vote goes and the adaptive rate
 * takes over again. Android 15+ only: per-view frame-rate votes start at API
 * 35, and earlier releases keep their own policy. The layer gives the vote
 * something to sit on (Compose's frame rate is set per layer).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.highRefresh(active: Boolean): Modifier =
    if (active && Build.VERSION.SDK_INT >= 35) this.preferredFrameRate(FrameRateCategory.High).graphicsLayer() else this
