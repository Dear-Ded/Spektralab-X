package com.dearded.spektralab

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.random.Random

/** Coordinates are physical dp; the card scales this single cached artwork by density. */
internal class FilmPackageArtwork(val spec: FilmPackageSpec, mono: Typeface, sans: Typeface, seed: Int) {
    private val gold = Color(0xFFF4B41A)
    private val red = Color(0xFFC8232C)
    private val paper = Color(0xFFF0F0F2)
    private val black = Color(0xFF141416)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
    private val monoFace = mono
    private val sansFace = sans
    private val random = Random(seed)
    private val fibres = List(780) { Offset(random.nextFloat() * 72f, random.nextFloat() * 88f) }
    private val darkFibres = fibres.map { it + Offset(.6f, .3f) }
    private val facePath = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(
        androidx.compose.ui.geometry.Rect(0f, 0f, 66f, 88f), CornerRadius(3f))) }
    private val sidePath = path(66f, 1f, 72f, 4f, 72f, 85f, 66f, 88f)
    private val diagonal = path(-5f, 50f, 71f, 15f, 71f, 93f, -5f, 93f)
    private val triBand = path(-5f, 43f, 71f, 17f, 71f, 35f, -5f, 61f)
    private val leader = Path().apply {
        moveTo(0f, 0f); lineTo(14f, 3f); lineTo(14f, 13f); lineTo(8f, 20f); lineTo(0f, 20f); close()
        fillType = PathFillType.EvenOdd
        for (x in listOf(2f, 7f)) for (y in listOf(2f, 14.5f)) {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(
                androidx.compose.ui.geometry.Rect(x, y, x + 2.5f, y + 3.5f), CornerRadius(.45f)))
        }
    }
    private val uvText = android.graphics.Path().also {
        paint.typeface = monoFace; paint.textSize = 26f; paint.isFakeBoldText = true
        val speed = spec.speed.orEmpty()
        paint.getTextPath(speed, 0, speed.length, 6f, 57f, it)
    }

    private fun path(vararg xy: Float) = Path().apply {
        moveTo(xy[0], xy[1]); for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1]); close()
    }

    private fun DrawScope.print(value: String, x: Float, baseline: Float, size: Float, colour: Color,
                                mono: Boolean = false, bold: Boolean = false, italic: Boolean = false,
                                maxWidth: Float = 56f) {
        paint.typeface = if (mono) monoFace else sansFace
        paint.textSize = size; paint.color = colour.toArgb(); paint.isFakeBoldText = bold
        paint.textSkewX = if (italic) -.18f else 0f
        paint.textScaleX = 1f
        val measured = paint.measureText(value)
        if (measured > maxWidth) paint.textScaleX = maxWidth / measured
        drawContext.canvas.nativeCanvas.drawText(value, x, baseline, paint)
        paint.textScaleX = 1f; paint.textSkewX = 0f; paint.isFakeBoldText = false
    }

    fun draw(scope: DrawScope, expansion: Float, scroll: Float) = with(scope) {
        val p = expansion.coerceIn(0f, 1f)
        if (p > 0f) drawCanister(p)
        drawPath(sidePath, lerp(spec.face, Color.Black, .32f))
        drawPath(facePath, spec.face)
        clipPath(facePath) {
            when (spec.layout) {
                PackageLayout.Portra -> {
                    drawRect(red, size = Size(66f, 15f))
                    print("KODAK PROFESSIONAL", 5f, 9.7f, 5.4f, Color.White, bold = true)
                    print("PORTRA", 5f, 29f, 14f, black, bold = true, italic = true)
                    print(spec.speed.orEmpty(), 5f, 57f, 26f, black, mono = true, bold = true)
                    print("COLOR NEGATIVE FILM", 5f, 64f, 5f, black, mono = true)
                }
                PackageLayout.Ektar -> {
                    print("KODAK PROFESSIONAL", 5f, 10f, 5.4f, Color.White, bold = true)
                    drawRect(gold, Offset(0f, 20f), Size(66f, 22f))
                    print("EKTAR", 5f, 35f, 16f, black, bold = true, italic = true)
                    print("100", 5f, 60f, 22f, Color.White, mono = true, bold = true)
                    print("ULTRA VIVID", 6f, 66f, 5.5f, Color.White, mono = true)
                }
                PackageLayout.TriX -> {
                    print("KODAK", 5f, 10f, 8f, gold, bold = true)
                    drawPath(triBand, gold)
                    print("TRI-X", 6f, 36f, 15f, black, bold = true, italic = true)
                    print("400TX", 5f, 61f, 17f, paper, mono = true, bold = true)
                }
                PackageLayout.Fuji -> {
                    drawRect(Brush.horizontalGradient(listOf(Color(0xFFB5BBC2), Color(0xFFE8EAEC),
                        Color(0xFFD8DCE0), Color(0xFFADB4BC))), Offset(0f, 43f), Size(66f, 45f))
                    for (y in 44..87 step 2) drawLine(Color.Black.copy(alpha = .035f), Offset(0f, y.toFloat()), Offset(66f, y.toFloat()), .25f)
                    drawRect(Color(0xFFC8232C), Offset(5f, 5f), Size(4f, 5f))
                    print("FUJIFILM", 12f, 10f, 7f, Color.White, bold = true, maxWidth = 49f)
                    print(spec.model, 5f, 31f, 14f, Color.White, bold = true, italic = true)
                    print(spec.speed.orEmpty(), 5f, 60f, 21f, black, mono = true, bold = true)
                    print(if (spec.base == FilmBase.Slide) "COLOR REVERSAL" else "PROFESSIONAL", 5f, 66f, 5f, black, mono = true)
                }
                PackageLayout.CineStill -> {
                    drawRect(Color(0xFFD94A38), size = Size(66f, 4f))
                    print("CineStill", 5f, 17f, 9f, paper, bold = true)
                    print(spec.speed.orEmpty(), 4f, 45f, 23f, spec.ink, mono = true, bold = true)
                    print(spec.model, 5f, 56f, 6f, paper, mono = true, bold = true)
                    print("REMJET-FREE", 5f, 65f, 5.5f, paper, mono = true)
                }
                PackageLayout.Ilford -> {
                    drawPath(diagonal, black)
                    print("ILFORD", 5f, 13f, 13f, black, bold = true)
                    print("HP5", 5f, 46f, 24f, paper, bold = true)
                    print("PLUS", 6f, 56f, 8f, paper, bold = true)
                    print("400", 6f, 66f, 8f, paper, mono = true, bold = true)
                }
                PackageLayout.Kodak -> {
                    drawRect(red, size = Size(66f, 15f))
                    print("KODAK", 5f, 10f, 8f, Color.White, bold = true)
                    print(spec.model, 5f, 32f, 12f, spec.ink, bold = true)
                    print(spec.speed.orEmpty(), 5f, 57f, 23f, spec.ink, mono = true, bold = true)
                    if (!spec.retail36) print("MOTION PICTURE", 5f, 65f, 5f, spec.ink, mono = true)
                }
                PackageLayout.Reference -> {
                    print(spec.brand.uppercase(), 5f, 12f, 7f, paper, bold = true)
                    print(spec.model, 5f, 35f, 10f, paper, bold = true)
                    print("PROFILE", 5f, 60f, 6f, paper, mono = true)
                }
            }
            drawMicroPrint()
            drawPoints(fibres, PointMode.Points, Color.White.copy(alpha = .025f), strokeWidth = .55f, blendMode = BlendMode.Overlay)
            drawPoints(darkFibres, PointMode.Points, Color.Black.copy(alpha = .025f), strokeWidth = .35f, blendMode = BlendMode.Overlay)
            val crease = 13f
            drawLine(Color.Black.copy(alpha = .28f), Offset(0f, crease), Offset(66f, crease), 1f)
            drawLine(Color.White.copy(alpha = .18f), Offset(0f, crease + .9f), Offset(66f, crease + .9f), .75f)
            drawArc(Color.Black.copy(alpha = .20f), 0f, 180f, true, Offset(30f, -1.5f), Size(6f, 6f))
            drawLine(Color.White.copy(alpha = .20f), Offset(.5f, 1f), Offset(.5f, 85f), .5f)
            drawLine(Color.Black.copy(alpha = .18f), Offset(65.5f, 1f), Offset(65.5f, 87f), .75f)
            val travel = 25f + (scroll * .18f).coerceIn(-70f, 70f)
            val sheen = Brush.linearGradient(0f to Color.Transparent, .46f to Color.Transparent,
                .5f to Color.White.copy(alpha = .22f), .54f to Color.Transparent, 1f to Color.Transparent,
                start = Offset(travel - 65f, -15f), end = Offset(travel + 65f, 76f))
            if (spec.layout == PackageLayout.Fuji) clipRect(0f, 43f, 66f, 88f) { drawRect(sheen, size = Size(66f, 88f)) }
            if (spec.layout == PackageLayout.Portra) {
                val native = drawContext.canvas.nativeCanvas
                val saved = native.save()
                try { native.clipPath(uvText); drawRect(sheen, size = Size(66f, 88f)) }
                finally { native.restoreToCount(saved) }
            }
        }
        drawLine(Color.White.copy(alpha = .12f), Offset(66.5f, 2f), Offset(66.5f, 86f), .5f)
        for (i in 0..7) drawRect(if (i % 3 == 0) Color(0xFFD8DCE0) else black,
            Offset(68f, 62f + i * 1.6f), Size(2f, if (i % 2 == 0) 1f else .5f))
    }

    private fun DrawScope.drawMicroPrint() {
        val ink = when (spec.layout) {
            PackageLayout.Fuji, PackageLayout.Portra, PackageLayout.Kodak -> black
            else -> paper
        }
        spec.sensitivity?.let { print(it, 5f, 73f, 5.5f, ink, mono = true) }
        spec.process?.let {
            val caption = if (it == "C-41" || it == "E-6") "PROCESS $it" else it
            drawRect(ink.copy(alpha = .8f), Offset(4.5f, 75f), Size(39f, 8f), style = Stroke(.75f))
            print(caption, 6f, 80.8f, 5f, ink, mono = true, maxWidth = 35f)
        }
        if (spec.retail36) {
            print("135-36", 38f, 72.5f, 5f, ink, mono = true, maxWidth = 25f)
            drawRect(red, Offset(46f, 75f), Size(17f, 8f))
            print("36 EXP", 47f, 80.8f, 5f, Color.White, mono = true, bold = true, maxWidth = 15f)
        }
        if (spec.process != null) {
            for (i in 0 until 14) if (i % 3 != 1) drawRect(ink, Offset(49f + i * .7f, 84f), Size(if (i % 2 == 0) .7f else .35f, 3f))
            for (i in 0 until 8) drawRect(if (i % 3 == 0) Color(0xFFD8DCE0) else black,
                Offset(5f + i * 2.4f, 85f), Size(2.4f, 2f))
        }
    }

    private fun DrawScope.drawCanister(p: Float) {
        val x = 52f + 20f * p
        translate(x, 27f) {
            // Clip the moving leader at the felt: its holes remain actual transparent cutouts.
            clipRect(18f, 0f, 34f, 54f) {
                translate(18f - 14f * (1f - p), 17f) { drawPath(leader, spec.base.colour.copy(alpha = spec.base.colour.alpha * p)) }
            }
            drawRoundRect(Color(0xFFA1A1AA).copy(alpha = p), Offset(7f, -4f), Size(6f, 4f), CornerRadius(.6f))
            drawLine(Color.White.copy(alpha = .65f * p), Offset(8f, -3.5f), Offset(11f, -3.5f), .65f)
            val colour = when (spec.layout) {
                PackageLayout.Portra, PackageLayout.Kodak -> gold
                PackageLayout.Fuji -> spec.face
                PackageLayout.Ektar -> spec.face
                else -> Color(0xFF3B3B3F)
            }
            val cylinder = Brush.horizontalGradient(0f to lerp(colour, Color.Black, .45f).copy(alpha = p),
                .35f to lerp(colour, Color.White, .25f).copy(alpha = p),
                .7f to colour.copy(alpha = p), 1f to lerp(colour, Color.Black, .55f).copy(alpha = p), endX = 20f)
            drawRoundRect(cylinder, size = Size(20f, 54f), cornerRadius = CornerRadius(2f))
            drawOval(Brush.horizontalGradient(listOf(Color(0xFF48484D).copy(alpha = p), Color(0xFFB5B5BD).copy(alpha = p),
                Color(0xFF303034).copy(alpha = p)), endX = 20f), size = Size(20f, 4f))
            drawRect(Color(0xFF09090B).copy(alpha = p), Offset(0f, 52f), Size(20f, 2f))
            drawRect(Color(0xFF09090B).copy(alpha = p), Offset(17.5f, 3f), Size(2.5f, 49f))
            for (y in 6..49 step 2) drawLine(Color.White.copy(alpha = .04f * p), Offset(17.8f, y.toFloat()), Offset(19.6f, y + .5f), .3f)
            print(spec.brand, 2f, 17f, 5f, paper.copy(alpha = p), bold = true, maxWidth = 14f)
            print(spec.speed.orEmpty(), 2f, 29f, 7f, paper.copy(alpha = p), mono = true, bold = true, maxWidth = 14f)
        }
    }
}
