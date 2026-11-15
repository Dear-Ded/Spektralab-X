package com.dearded.spektralab

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

/**
 * Compact packaging above the caller's actual selection preview. Callbacks own
 * selection, favourites and haptics; no rendering or bitmap ownership lives here.
 */
@Composable
internal fun FilmPackageCard(
    film: Stock,
    selected: Boolean,
    image: Bitmap?,
    enabled: Boolean = true,
    favourite: Boolean = false,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val info = remember(film) { packageInfo(film) }
    val art = remember(film.id, info.brand, info.process) {
        PackageArtwork(film.id.hashCode(), info.brand, info.process != null)
    }
    val preview = remember(image) { image?.asImageBitmap() }
    val reduceMotion = rememberReduceMotion()
    val lift = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(180),
        label = "film-package-lift",
    )
    val favouriteLabel = if (favourite) tr("\u53d6\u6d88\u6536\u85cf") else tr("\u6536\u85cf")
    val description = listOfNotNull(film.label, info.process).joinToString(", ")
    val favouriteState = tr("\u6536\u85cf")
    val mono = MaterialTheme.typography.labelSmall.copy(
        fontFamily = UiMono, fontFeatureSettings = "tnum", lineHeight = 14.sp,
    )

    Column(
        Modifier.width(112.dp)
            .then(if (reduceMotion) Modifier else Modifier.pressScale(press))
            .combinedClickable(
                enabled = enabled,
                interactionSource = press,
                indication = ripple(),
                role = Role.RadioButton,
                hapticFeedbackEnabled = false,
                onLongClickLabel = favouriteLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
                if (favourite) stateDescription = favouriteState
            },
    ) {
        Box(
            Modifier.fillMaxWidth().height(86.dp).clearAndSetSemantics { }
                .graphicsLayer {
                    translationY = -2.dp.toPx() * if (reduceMotion) {
                        if (selected) 1f else 0f
                    } else lift.value
                    alpha = if (enabled) 1f else 0.5f
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                scale(size.width / 112f, size.height / 86f, pivot = Offset.Zero) {
                    val raised = if (reduceMotion) {
                        if (selected) 1f else 0f
                    } else lift.value
                    drawPackage(art, raised)
                }
            }
            Text(
                info.brandName, color = art.bandInk, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(12.dp, 18.dp).width(68.dp).height(16.dp),
            )
            Text(
                info.model, color = art.ink, fontWeight = FontWeight.SemiBold,
                style = mono, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(12.dp, 36.dp).width(68.dp).height(28.dp),
            )
            info.format?.let { format ->
                Text(format, color = art.ink, style = mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset(12.dp, 66.dp).width(68.dp).height(14.dp))
            }
            info.process?.let { process ->
                Text(process, color = Color.White, style = mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset(63.dp, 2.dp).width(49.dp).height(16.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier.fillMaxWidth().height(50.dp)
                .border(if (selected) 2.dp else 1.dp,
                    if (selected) Palette.Darkroom else Palette.Outline, RoundedCornerShape(4.dp))
                .padding(3.dp).clip(RoundedCornerShape(2.dp))
                .background(Palette.PanelHigh).clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) {
                Image(preview, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
            } else {
                Text(tr("\u80f6\u7247\u7f29\u7565\u56fe"), style = MaterialTheme.typography.labelSmall,
                    color = Palette.OnSurfaceSoft, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 5.dp))
            }
            if (selected) {
                Canvas(Modifier.align(Alignment.TopEnd).padding(2.dp).size(16.dp)
                    .background(Palette.Darkroom, CircleShape)) {
                    val w = size.width
                    drawLine(Palette.OnAccent, Offset(w * 0.25f, w * 0.52f),
                        Offset(w * 0.43f, w * 0.69f), 1.7.dp.toPx(), StrokeCap.Round)
                    drawLine(Palette.OnAccent, Offset(w * 0.43f, w * 0.69f),
                        Offset(w * 0.77f, w * 0.31f), 1.7.dp.toPx(), StrokeCap.Round)
                }
            }
            if (favourite) {
                Canvas(Modifier.align(Alignment.BottomStart).padding(2.dp).size(16.dp)
                    .background(Palette.OnAccent, CircleShape)) {
                    scale(size.width / 16f, size.height / 16f, pivot = Offset.Zero) {
                        drawPath(art.star, Palette.OnSurface)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            film.label, style = MaterialTheme.typography.labelSmall.copy(
                fontFeatureSettings = "tnum", lineHeight = 16.sp,
            ),
            color = if (enabled) Palette.OnSurface else Palette.OnSurfaceSoft,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp).clearAndSetSemantics { },
        )
    }
}

private data class PackageInfo(
    val brand: String?,
    val brandName: String,
    val model: String,
    val process: String?,
    val format: String?,
)

private val CinemaNegatives = setOf(
    "kodak_vision3_50d", "kodak_vision3_250d", "kodak_vision3_200t", "kodak_vision3_500t",
)
private val ColourNegatives = setOf(
    "kodak_portra_160", "kodak_portra_400", "kodak_portra_800",
    "kodak_portra_800_push1", "kodak_portra_800_push2", "kodak_ektar_100",
    "kodak_gold_200", "kodak_ultramax_400", "cinestill_50d", "cinestill_800t",
    "fujifilm_c200", "fujifilm_xtra_400", "fujifilm_pro_400h",
)
private val ReversalFilms = setOf("kodak_ektachrome_100", "fujifilm_provia_100f", "fujifilm_velvia_100")
private val SilverNegatives = setOf("kodak_tri_x_400", "kodak_tmax_100", "fujifilm_neopan_acros_100_ii", "ilford_hp5_plus_400")

private fun packageInfo(film: Stock): PackageInfo {
    val brand = when {
        film.id.startsWith("kodak_") -> "kodak"
        film.id.startsWith("fujifilm_") -> "fujifilm"
        film.id.startsWith("cinestill_") -> "cinestill"
        film.id.startsWith("ilford_") -> "ilford"
        else -> Brands.filmBrand(film.label)?.id
    }
    val brandName = when (brand) {
        "kodak" -> "Kodak"
        "fujifilm" -> "Fujifilm"
        "cinestill" -> "CineStill"
        "ilford" -> "Ilford"
        else -> Brands.filmBrand(film.label)?.name.orEmpty()
    }
    val process = when {
        film.id in CinemaNegatives -> "ECN-2"
        film.id in SilverNegatives -> "B&W"
        film.id == "kodak_kodachrome_64" -> "K-14"
        film.id in ReversalFilms -> "E-6"
        film.id in ColourNegatives -> "C-41"
        else -> null
    }
    // Artwork depicts a known 135/36 retail edition, never a profile's ISO or
    // the editor's current film format. Cinema stock gets no invented roll length.
    val format = when (film.id) {
        in CinemaNegatives -> "35mm"
        in ColourNegatives, in SilverNegatives, in ReversalFilms, "kodak_kodachrome_64" -> "135-36"
        else -> null
    }
    return PackageInfo(brand, brandName, film.label.removePrefix("$brandName "), process, format)
}

/** Paths, brushes and two very faint point clouds are created once per stock. */
private class PackageArtwork(seed: Int, brand: String?, val hasProcess: Boolean) {
    val face = when (brand) {
        "kodak" -> Color(0xFFF2C52D)
        "fujifilm" -> Color(0xFFDDE2DD)
        "cinestill" -> Color(0xFF491B20)
        "ilford" -> Color(0xFFE9E9E4)
        else -> Palette.PanelHighest
    }
    val band = when (brand) {
        "kodak" -> Color(0xFFAF2621)
        "fujifilm" -> Color(0xFF086341)
        "cinestill" -> Color(0xFF832D34)
        "ilford" -> Color(0xFF141618)
        else -> Palette.PanelHigh
    }
    val ink = if (brand == "cinestill" || brand !in setOf("kodak", "fujifilm", "ilford")) {
        Color(0xFFF2EFE8)
    } else Color(0xFF171A19)
    val bandInk = Color.White
    val front = polygon(7f, 15f, 84f, 15f, 84f, 82f, 7f, 82f)
    val top = polygon(7f, 15f, 15f, 8f, 92f, 8f, 84f, 15f)
    val side = polygon(84f, 15f, 92f, 8f, 92f, 75f, 84f, 82f)
    val flap = polygon(15f, 8f, 53f, 8f, 49f, 12f, 11f, 12f)
    val diagonal = when (brand) {
        "ilford" -> polygon(69f, 82f, 84f, 64f, 84f, 82f)
        "fujifilm" -> polygon(73f, 82f, 84f, 67f, 84f, 82f)
        else -> null
    }
    val badge = polygon(62f, 1f, 112f, 1f, 112f, 18f, 56f, 18f)
    val leader = polygon(99f, 46f, 107f, 46f, 112f, 52f, 108f, 61f, 99f, 61f)
    val star = polygon(8f, 2.5f, 9.6f, 5.8f, 13.3f, 6.3f, 10.6f, 8.9f,
        11.3f, 12.6f, 8f, 10.8f, 4.7f, 12.6f, 5.4f, 8.9f, 2.7f, 6.3f, 6.4f, 5.8f)
    val topLight = Brush.verticalGradient(listOf(lerp(face, Color.White, 0.26f), face), 8f, 16f)
    val sideShade = Brush.horizontalGradient(listOf(lerp(face, Color.Black, 0.28f), lerp(face, Color.Black, 0.48f)), 84f, 92f)
    val metal = Brush.horizontalGradient(listOf(Color(0xFF656C6F), Color(0xFFE0E2DF), Color(0xFF747C80), Color(0xFF303639)), 86f, 104f)
    val shell = Brush.horizontalGradient(listOf(Color(0xFF080A0C), Color(0xFF3A4145), Color(0xFF171A1D), Color(0xFF07090B)), 86f, 104f)
    private val random = Random(seed)
    val darkFrost = List(100) { Offset(7f + random.nextFloat() * 77f, 15f + random.nextFloat() * 67f) }
    val lightFrost = List(100) { Offset(7f + random.nextFloat() * 77f, 15f + random.nextFloat() * 67f) }
}

private fun polygon(vararg xy: Float) = Path().apply {
    moveTo(xy[0], xy[1])
    for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1])
    close()
}

private fun DrawScope.drawPackage(art: PackageArtwork, raised: Float) {
    drawOval(Color.Black.copy(alpha = 0.22f + 0.12f * raised), Offset(5f, 81f), Size(91f, 4f))
    if (raised > 0f) clipRect(left = 84f, right = 84f + 28f * raised) {
        drawPath(art.leader, Color(0xFF77513A))
        for (x in 101..107 step 3) {
            drawRect(Color(0xFF151516), Offset(x.toFloat(), 47f), Size(1.5f, 2f))
            drawRect(Color(0xFF151516), Offset(x.toFloat(), 57f), Size(1.5f, 2f))
        }
        drawRoundRect(art.metal, Offset(91f, 27f), Size(7f, 9f), CornerRadius(1f))
        drawRoundRect(art.shell, Offset(86f, 34f), Size(18f, 40f), CornerRadius(2f))
        drawOval(art.metal, Offset(86f, 31f), Size(18f, 7f))
        drawOval(art.metal, Offset(86f, 70f), Size(18f, 6f))
        drawOval(Color(0xFF1B2023), Offset(90f, 33f), Size(10f, 3f))
        drawRect(art.band, Offset(89f, 42f), Size(12f, 17f))
        drawLine(Color.White.copy(alpha = 0.3f), Offset(90f, 42f), Offset(90f, 58f), 0.6f)
    }
    drawPath(art.top, art.topLight)
    drawPath(art.side, art.sideShade)
    drawPath(art.front, art.face)
    clipPath(art.front) {
        drawRect(art.band, Offset(7f, 15f), Size(77f, 18f))
        art.diagonal?.let { drawPath(it, art.band) }
        drawPoints(art.darkFrost, PointMode.Points, Color.Black.copy(alpha = 0.045f), strokeWidth = 0.6f)
        drawPoints(art.lightFrost, PointMode.Points, Color.White.copy(alpha = 0.055f), strokeWidth = 0.6f)
    }
    drawPath(art.flap, Color.Black.copy(alpha = 0.07f))
    drawLine(Color.White.copy(alpha = 0.36f), Offset(7f, 15.4f), Offset(83.5f, 15.4f), 0.65f)
    drawLine(Color.Black.copy(alpha = 0.18f), Offset(83.5f, 16f), Offset(83.5f, 81f), 0.7f)
    drawLine(Color.Black.copy(alpha = 0.12f), Offset(8f, 79f), Offset(81f, 79f), 0.6f)
    drawLine(Color.White.copy(alpha = 0.17f), Offset(86f, 18f), Offset(90f, 14f), 0.6f)
    if (art.hasProcess) drawPath(art.badge, Color(0xFF202326))
}
