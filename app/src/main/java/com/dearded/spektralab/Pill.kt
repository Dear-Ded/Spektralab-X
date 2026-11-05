package com.dearded.spektralab

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The editor's building blocks (UI2.md 1.2, 7.2). Every panel is made of the
 * same few pieces so that "selected", "changed" and "you can tap this" look
 * the same everywhere:
 *
 *  - PanelHeader: title · summary on one line, the reset at its right end, one
 *    plain sentence under it. No dividers; hierarchy is brightness and space.
 *  - SelectPill / PillRow: one choice out of many, scrolled sideways.
 *  - Segmented: two to four choices that belong together, in one track.
 *  - ThumbCard / TextCard: a big picture (or a text card) to pick a look.
 *  - SettingRow: a whole-row switch with an optional second line.
 *  - MoreRow: the one way into a step's 更多参数.
 *
 * Selected is always the inverted near-white fill with dark ink plus a shape
 * change, never a hue. Hit targets are 48 dp; items in a row sit 8 dp apart.
 */

/** Vertical rhythm inside a panel: between controls, and before a new group. */
internal val RowGap = 12.dp
internal val GroupGap = 20.dp

/** The changed-from-default mark: a 6 dp dot, dark on a selected fill, light elsewhere. */
@Composable
internal fun EditedDot(onFill: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).background(if (onFill) Palette.OnAccent else Palette.Accent, CircleShape))
}

/**
 * The one selectable chip (UI2.md 1.2). "Selected" is told three ways at
 * once, never by colour alone: the fill inverts (near-white with dark text),
 * the corners round from 12 dp to a full pill, and the label goes Medium.
 * Fill and text move on effects springs (no overshoot), the corner and the
 * press scale on spatial ones. The pill is drawn 40 dp tall inside a 48 dp
 * touch target.
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
    val corner by animateDpAsState(if (selected) 20.dp else 12.dp, Motion.fastSpatial(), label = "pill-corner")
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, Motion.fastSpatial(), label = "pill-press")
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .height(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled || selected) 1f else 0.5f }
            .clip(shape)
            .background(fill)
            .border(1.dp, if (selected) fill else Palette.OutlineSoft, shape)
            .selectable(
                selected = selected, enabled = enabled, role = Role.Tab,
                interactionSource = press, indication = ripple(), onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides ink,
            LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(
                color = ink, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
        ) { label() }
        if (edited) EditedDot(onFill = selected,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = 5.dp))
    }
}

/** A sideways-scrolling row of pills, 8 dp apart (UI2.md 1.3). */
@Composable
internal fun PillRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The top of every panel and step (UI2.md 7.2 point 2): the title in white,
 * then what is set now in grey on the same line (dropped from the end when
 * the line is short), the reset at the right end only once something changed,
 * and one sentence on what this is for.
 */
@Composable
internal fun PanelHeader(
    title: String,
    summary: String = "",
    hint: String = "",
    resetLabel: String = "重置",
    changed: Boolean = false,
    enabled: Boolean = true,
    onReset: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.OnSurface, maxLines = 1)
            if (summary.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurfaceSoft,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            if (onReset != null && changed) {
                QuietButton(resetLabel, enabled = enabled, onClick = onReset)
            }
        }
        if (hint.isNotBlank()) Hint(hint)
    }
}

/** One line of plain explanation (UI2.md 2.6): 12 sp, white 64 %. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft, modifier = modifier)
}

/** A group's name inside a panel: smaller and greyer than the panel title, with room above it. */
@Composable
internal fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.OnSurfaceSoft,
        modifier = modifier.padding(top = GroupGap - RowGap, bottom = 2.dp))
}

/** A text action without a container: 48 dp tall, white 92 %, a Layer1 pill only while pressed. */
@Composable
internal fun QuietButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Palette.OnSurface else Palette.OnSurfaceSoft.copy(alpha = 0.45f))
    }
}

/**
 * Two to four choices that belong together (画幅, 日期顺序, 齿孔位置), in one
 * Layer1 track so the set reads as "pick one of these" (UI2.md 7.2 point 8).
 * The chosen segment is the inverted pill; each segment is a 48 dp target.
 */
@Composable
internal fun Segmented(
    options: List<String>,
    selected: Int,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    optionEnabled: (Int) -> Boolean = { true },
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().height(48.dp)
            .background(Palette.Panel, RoundedCornerShape(24.dp))
            .border(1.dp, Palette.OutlineSoft, RoundedCornerShape(24.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val can = enabled && optionEnabled(i)
            val fill by animateColorAsState(if (on) Palette.Accent else Palette.Panel.copy(alpha = 0f),
                Motion.defaultEffects(), label = "seg-fill")
            val ink by animateColorAsState(
                when { on -> Palette.OnAccent; can -> Palette.OnSurface; else -> Palette.OnSurfaceSoft.copy(alpha = 0.45f) },
                Motion.defaultEffects(), label = "seg-ink")
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(20.dp)).background(fill)
                    .selectable(selected = on, enabled = can, role = Role.Tab) { if (!on) onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/**
 * A switch that is the whole row (UI2.md 7.2 point 7): the name and an
 * optional second line on the left, the switch on the right, 56 dp, the
 * entire row toggles. On is the near-white track with a dark thumb.
 */
@Composable
internal fun SettingRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    note: String = "",
    /** Replaces [note] while the row is off: why it cannot be switched (UI2.md 4, question 3). */
    disabledReason: String = "",
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Palette.OnSurface else Palette.OnSurfaceSoft.copy(alpha = 0.6f))
            val line = if (!enabled && disabledReason.isNotBlank()) disabledReason else note
            if (line.isNotBlank()) Hint(line)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.OnAccent,
                checkedTrackColor = Palette.Accent,
                checkedBorderColor = Palette.Accent,
                uncheckedThumbColor = Palette.OnSurfaceSoft,
                uncheckedTrackColor = Palette.PanelHigh,
                uncheckedBorderColor = Palette.Outline,
                disabledCheckedThumbColor = Palette.PanelHigh,
                disabledCheckedTrackColor = Palette.OnSurfaceSoft.copy(alpha = 0.4f),
                disabledUncheckedThumbColor = Palette.Outline,
                disabledUncheckedTrackColor = Palette.Panel,
                disabledUncheckedBorderColor = Palette.OutlineSoft,
            ),
        )
    }
}

/**
 * The one way into a step's 更多参数 (UI2.md 7.2 point 9): a full-width
 * 48 dp row, the words fixed, a dot when something inside changed, and a
 * chevron that turns over as it opens.
 */
@Composable
internal fun MoreRow(open: Boolean, edited: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.defaultSpatial(), label = "more-turn")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (open) "收起" else "更多参数", style = MaterialTheme.typography.labelLarge, color = Palette.OnSurface)
        if (edited && !open) { Spacer(Modifier.width(8.dp)); EditedDot() }
        Spacer(Modifier.weight(1f))
        Canvas(Modifier.size(20.dp).graphicsLayer { rotationZ = turn }) {
            val u = size.width / 20f
            val w = 1.6f * u
            drawLine(Palette.OnSurfaceSoft, Offset(5 * u, 8 * u), Offset(10 * u, 13 * u), w, StrokeCap.Round)
            drawLine(Palette.OnSurfaceSoft, Offset(10 * u, 13 * u), Offset(15 * u, 8 * u), w, StrokeCap.Round)
        }
    }
}

/**
 * A picture card for a style or a film (UI2.md 1.2, 7.2 points 3–4): a
 * square thumbnail rounded 12, the name and one line under it. Selected gets
 * a 2 dp near-white ring outside a 2 dp gap, a check on the corner, and the
 * name on an inverted pill; the press shrinks it a little.
 */
@Composable
internal fun ThumbCard(
    selected: Boolean,
    image: ImageBitmap?,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    size: Dp = 104.dp,
    /** Shown where there is no picture yet: the name set large, not a code. */
    placeholder: String = title,
    /** A short tag over the picture's corner, e.g. 推荐. */
    badge: String? = null,
    edited: Boolean = false,
    onClick: () -> Unit,
) {
    val chosen = selected
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.fastSpatial(), label = "card-press")
    val ring by animateColorAsState(if (selected) Palette.Accent else Color.Transparent,
        Motion.defaultEffects(), label = "card-ring")
    val outer = RoundedCornerShape(16.dp)
    val inner = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(size + 8.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.5f }
            .clip(outer)
            .clickable(enabled = enabled, interactionSource = press, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = chosen },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size + 8.dp).border(2.dp, ring, outer).padding(4.dp)) {
            Box(Modifier.fillMaxSize().clip(inner).background(Palette.PanelHigh), contentAlignment = Alignment.Center) {
                if (image != null) {
                    Image(image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(placeholder, style = MaterialTheme.typography.titleSmall, color = Palette.OnSurfaceSoft,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
                }
            }
            if (badge != null) Text(
                badge, style = MaterialTheme.typography.labelSmall, color = Palette.OnSurface,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
            if (selected) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                    .background(Palette.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Check(Palette.OnAccent) }
            else if (edited) EditedDot(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Spacer(Modifier.height(6.dp))
        val nameFill by animateColorAsState(if (selected) Palette.Accent else Palette.Accent.copy(alpha = 0f),
            Motion.defaultEffects(), label = "card-name")
        Text(
            title, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Palette.OnAccent else Palette.OnSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.background(nameFill, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
        )
        if (subtitle.isNotBlank()) Text(
            subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.OnSurfaceSoft,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * A card without a picture (相纸, a stock with nothing rendered): the name and
 * a line, Layer1. Selected inverts the fill, rounds the corners further and
 * carries the check, the same three signals as [SelectPill].
 */
@Composable
internal fun TextCard(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    width: Dp = 136.dp,
    onClick: () -> Unit,
) {
    val chosen = selected
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.fastSpatial(), label = "tcard-press")
    val fill by animateColorAsState(if (selected) Palette.Accent else Palette.Panel, Motion.defaultEffects(), label = "tcard-fill")
    val ink by animateColorAsState(if (selected) Palette.OnAccent else Palette.OnSurface, Motion.defaultEffects(), label = "tcard-ink")
    val corner by animateDpAsState(if (selected) 20.dp else 12.dp, Motion.fastSpatial(), label = "tcard-corner")
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier.width(width).heightIn(min = 80.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.5f }
            .clip(shape).background(fill)
            .border(1.dp, if (selected) fill else Palette.OutlineSoft, shape)
            .clickable(enabled = enabled, interactionSource = press, indication = ripple(), role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = chosen }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(Modifier.padding(end = if (selected) 22.dp else 0.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = ink,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.68f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Box(
            Modifier.align(Alignment.TopEnd).size(20.dp).background(Palette.OnAccent, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Check(Palette.Accent) }
    }
}

/** A drawn check mark, so it never depends on an icon font. */
@Composable
private fun Check(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val u = size.width / 12f
        val w = 1.8f * u
        drawLine(color, Offset(2.2f * u, 6.4f * u), Offset(4.9f * u, 9f * u), w, StrokeCap.Round)
        drawLine(color, Offset(4.9f * u, 9f * u), Offset(9.8f * u, 3.4f * u), w, StrokeCap.Round)
    }
}

/**
 * A secondary action that is not a choice (向左转, 重新抽取, 套用预设): a Layer1
 * pill 48 dp tall with white 92 % text, the press shrinking it a little. Lighter
 * than the near-white primary (保存), heavier than a [QuietButton].
 */
@Composable
internal fun TonalButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.fastSpatial(), label = "tonal-press")
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier.heightIn(min = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.5f }
            .clip(shape).background(Palette.PanelHigh)
            .border(1.dp, Palette.OutlineSoft, shape)
            .clickable(enabled = enabled, interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.OnSurface, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Settings → 默认展开全部参数 (UI2.md 0.1, for film players): every 更多参数
 * starts open. The key is shared with the settings page (ui-screens).
 */
internal const val EXPAND_ALL = "expand_all"

@Composable
internal fun rememberExpandAll(): Boolean {
    val c = androidx.compose.ui.platform.LocalContext.current
    return remember(c) { c.getSharedPreferences("settings", 0).getBoolean(EXPAND_ALL, false) }
}

/**
 * The line under a closed 更多参数 (upstream's collapsed SectionHeader): the
 * values that differ from their defaults ("高光 +20 · 黑场 +5"), or, when
 * nothing inside changed, the names of what is in there.
 */
internal fun moreSummary(names: String, vararg values: Pair<String, String?>): String =
    values.filter { it.second != null }.joinToString(" · ") { "${it.first} ${it.second}" }.ifEmpty { names }

/** A whole-number readout with its sign when it differs from [default], else null (for [moreSummary]). */
internal fun Double.signedOrNull(default: Double): String? =
    if (this == default) null else String.format(java.util.Locale.ROOT, "%+.0f", this)
