package com.dearded.spektralab

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Drawing owns no editor state, engine bitmap or haptic: the selection callback owns the action. */
@Composable
internal fun FilmPackageCard(
    film: Stock, selected: Boolean, image: Bitmap?, enabled: Boolean = true,
    favourite: Boolean = false, scrollOffset: () -> Float = { 0f },
    onLongClick: () -> Unit = {}, onClick: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val press = remember { MutableInteractionSource() }
    val info = remember(film) { filmPackageSpec(film) }
    val artwork = remember(film.id, info, context) { FilmPackageArtwork(info,
        UiFonts.typeface(context, UiFonts.MONO, 700), UiFonts.typeface(context, UiFonts.SANS, 700), film.id.hashCode()) }
    val preview = remember(image) { image?.asImageBitmap() }
    val reduceMotion = rememberReduceMotion()
    val expansion = animateFloatAsState(if (selected) 1f else 0f,
        if (reduceMotion) snap() else spring(dampingRatio = .68f, stiffness = 420f), label = "film-unroll")
    val favouriteLabel = if (favourite) tr("\u53d6\u6d88\u6536\u85cf") else tr("\u6536\u85cf")
    val favState = tr("\u6536\u85cf")
    Column(
        Modifier.width(120.dp).pressScale(press).combinedClickable(
            enabled = enabled, interactionSource = press, indication = TactileIndication,
            role = Role.RadioButton, hapticFeedbackEnabled = false,
            onLongClickLabel = favouriteLabel, onLongClick = onLongClick, onClick = onClick,
        ).semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(film.label, info.process).joinToString(", ")
            this.selected = selected
            if (favourite) stateDescription = favState
        },
    ) {
        Canvas(Modifier.fillMaxWidth().height(100.dp).clearAndSetSemantics { }
            .graphicsLayer {
                val p = if (reduceMotion) { if (selected) 1f else 0f } else expansion.value
                translationY = -4.dp.toPx() * p
                alpha = if (!enabled) .45f else .85f + .15f * p.coerceIn(0f, 1f)
            }) {
            scale(density, density, pivot = Offset.Zero) {
                translate(7f, 7f) { artwork.draw(this, expansion.value, scrollOffset()) }
            }
        }
        Box(Modifier.fillMaxWidth().height(50.dp)
            .border(.75.dp, if (selected) Palette.Darkroom else Palette.BorderSubtle, RoundedCornerShape(3.dp))
            .padding(3.dp).clip(RoundedCornerShape(2.dp)).background(Palette.PanelHigh)
            .drawWithContent {
                val p = expansion.value.coerceIn(0f, 1f)
                clipRect(right = size.width * (.72f + .28f * p)) { this@drawWithContent.drawContent() }
            }.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            if (preview != null) Image(preview, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(tr("\u80f6\u7247\u7f29\u7565\u56fe"), style = MaterialTheme.typography.labelSmall,
                color = Palette.OnSurfaceSoft, modifier = Modifier.padding(4.dp))
            if (selected) Text("+", color = Palette.Darkroom, fontFamily = UiMono,
                modifier = Modifier.align(Alignment.TopEnd).background(Palette.Background).padding(horizontal = 3.dp))
            if (favourite) Text("*", color = Palette.OnSurface, fontFamily = UiMono,
                modifier = Modifier.align(Alignment.BottomStart).background(Palette.Background).padding(horizontal = 3.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(film.label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp,
            fontFeatureSettings = "tnum", lineHeight = 16.sp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (enabled) Palette.OnSurface else Palette.OnSurfaceSoft,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp).clearAndSetSemantics { })
    }
}
