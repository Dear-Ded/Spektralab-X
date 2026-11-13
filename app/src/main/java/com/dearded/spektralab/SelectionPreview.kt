package com.dearded.spektralab

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Co-created with GPT (OpenAI). A card predicts the edit its tap will apply. */
internal fun Edit.forSelectionPreview() = copy(filmEdge = false, dateImprint = false,
    frame = FrameSpec(), borderId = "none")

internal data class SelectionPreviewKey(val source: Long, val edit: Edit, val output: String)

/** Restart on source/recipe changes, dropping the old picture before the new render arrives. */
@Composable
internal fun rememberSelectionPreview(
    ui: UiState,
    edit: Edit?,
    preview: suspend (Edit) -> Bitmap?,
): ImageBitmap? {
    val candidate = remember(edit) { edit?.forSelectionPreview() }
    val render by rememberUpdatedState(preview)
    var image by remember(ui.previewSource, candidate, ui.busy) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(ui.previewSource, candidate, ui.busy) {
        if (ui.source != null && ui.busy == null && candidate != null) image = render(candidate)
    }
    return remember(image) { image?.asImageBitmap() }
}
