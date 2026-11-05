package com.dearded.spektralab

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The one selectable chip (UI2.md 1.2). "Selected" is told three ways at
 * once, never by colour alone: the fill inverts (near-white with dark text),
 * the corners round from 12 dp to a full pill, and the label goes Medium.
 * Fill and text move on effects springs (no overshoot), the corner and the
 * press scale on spatial ones. Same parameters as Material3's FilterChip,
 * which it replaces everywhere.
 */
@Composable
fun SelectPill(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    enabled: Boolean = true,
    /** Changed from its default: a solid dot at the top-right corner (ui2_derivatives.md). */
    edited: Boolean = false,
) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val fill by animateColorAsState(
        if (selected) Palette.Accent else Palette.Panel, Motion.defaultEffects(), label = "pill-fill")
    val ink by animateColorAsState(
        when {
            selected -> Palette.OnAccent
            enabled -> Palette.OnSurface
            else -> Palette.OnSurfaceSoft.copy(alpha = 0.45f)
        },
        Motion.defaultEffects(), label = "pill-ink",
    )
    val corner by animateDpAsState(if (selected) 50.dp else 12.dp, Motion.fastSpatial(), label = "pill-corner")
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.fastSpatial(), label = "pill-press")
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled || selected) 1f else 0.6f }
            .clip(shape)
            .background(fill)
            .border(1.dp, if (selected) fill else Palette.OutlineSoft, shape)
            .selectable(
                selected = selected, enabled = enabled, role = Role.Tab,
                interactionSource = press, indication = ripple(), onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides ink,
            LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(
                color = ink, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
        ) { label() }
        if (edited) Box(
            Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp).size(6.dp)
                .background(if (selected) Palette.OnAccent else Palette.Accent, androidx.compose.foundation.shape.CircleShape),
        )
    }
}
