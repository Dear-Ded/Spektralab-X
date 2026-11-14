package com.dearded.spektralab

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.first

/**
 * The full render at one image pixel per screen pixel, to judge grain and
 * halation, which the fitted preview cannot show. Pan with one finger.
 */
@Composable
internal fun DetailViewer(bitmap: android.graphics.Bitmap, onClose: () -> Unit,
                          title: String = "1:1", note: String? = null) {
    // A full-screen layer in the activity's own window, not a Dialog: a dialog is
    // a second window, and opening or closing it brings the system bars back
    // (docs/UI.md, 沉浸式). One window, one insets controller.
    var label by remember { mutableStateOf(true) }
    SystemBarsHidden(true)
    Box(Modifier.fillMaxSize().zIndex(10f)) {
        // UI2.md 3.8: the viewer shrinks to 90 % under the finger (eased progress, shifted
        // away from the swiped edge, corners rounding) with the editor showing around it;
        // a cancel springs it back to full screen.
        val back = rememberBackProgress()
        PredictiveBack(progress = back, onBack = onClose)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        val h = rememberScrollState()
        val v = rememberScrollState()
        // Start in the middle of the frame, where the subject usually is.
        // maxValue is Int.MAX_VALUE until the first layout, so wait for the real range.
        LaunchedEffect(bitmap) {
            androidx.compose.runtime.snapshotFlow { h.maxValue to v.maxValue }
                .first { (a, b) -> a != Int.MAX_VALUE && b != Int.MAX_VALUE }
            h.scrollTo(h.maxValue / 2)
            v.scrollTo(v.maxValue / 2)
        }
        Box(Modifier.fillMaxSize().predictiveBack(back).background(Color.Black)) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val viewW = maxWidth
                val viewH = maxHeight
                Box(Modifier.fillMaxSize().horizontalScroll(h).verticalScroll(v)
                    .pointerInput(Unit) { detectTapGestures(onTap = { label = !label }) }) {
                    // At least the screen in both directions, the picture centred in it: a render
                    // narrower or shorter than the screen sits in the middle, not in the top-left corner.
                    Box(Modifier.sizeIn(minWidth = viewW, minHeight = viewH), contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = with(density) { Modifier.width(bitmap.width.toDp()).height(bitmap.height.toDp()) },
                        )
                    }
                }
            }
            // Clear of the cutout and the curved edges; a tap on the photo hides it.
            Row(
                Modifier.align(Alignment.TopEnd)
                    .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .chrome(label)
                    .background(Color.Black.copy(alpha = 0.5f), Corners.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f, fill = false).padding(horizontal = 8.dp)) {
                    Text("$title · ${bitmap.width}×${bitmap.height}", color = Color.White,
                        style = MaterialTheme.typography.labelSmall)
                    if (note != null) Text(note, color = Color.White,
                        style = MaterialTheme.typography.labelSmall)
                }
                // Back to the fitted photo: 适应 / 1:1 as a visible pair (ui2_icecream.md 6.9).
                TextButton(onClick = onClose) { Text(tr("适应"), color = Color.White) }
            }
        }
    }
}
