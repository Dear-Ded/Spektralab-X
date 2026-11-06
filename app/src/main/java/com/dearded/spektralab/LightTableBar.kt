package com.dearded.spektralab

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * The light table's floating bar (ui-screens_research.md 1): the same pill as
 * the editor's toolbar (64 dp, 12 dp off the edges, Layer1 with a hairline),
 * so the table reads as part of one app. Grid and rolls switch in its
 * segmented track; adding photos is its own round primary button beside it,
 * the way Google Photos keeps search apart from the tabs. While frames are
 * selected the same pill carries the selection's actions instead.
 */

/** Room the floating bar takes at the bottom, for content that scrolls under it. */
internal val FloatingBarSpace = 80.dp

/** The bar's pill: [content] in a 64 dp Layer1 track, scrolled sideways when it does not fit. */
@Composable
internal fun FloatingBar(
    modifier: Modifier = Modifier,
    scroll: Boolean = false,
    trailing: @Composable () -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth()
            // A short fade under the bar, so frames slide away beneath it instead of being cut.
            .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Background.copy(alpha = 0.9f))))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).height(64.dp)
                .background(Palette.Panel, Corners.full)
                .border(1.dp, Palette.OutlineSoft, Corners.full)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        trailing()
    }
}

/** 网格 / 按卷 in the bar's track, and 添加 as the round primary button beside it. */
@Composable
internal fun TableBar(rolls: Boolean, onGrid: () -> Unit, onRolls: () -> Unit, add: AddPhotos, modifier: Modifier = Modifier) {
    FloatingBar(modifier, trailing = {
        Spacer(Modifier.width(8.dp))
        AddButton(add)
    }) {
        Segmented(
            options = listOf("网格", "按卷"), selected = if (rolls) 1 else 0,
            modifier = Modifier.weight(1f),
        ) { i -> if (i == 0) onGrid() else onRolls() }
    }
}

/** The round 添加: a 64 dp inverted circle, its menu naming the two sources. */
@Composable
internal fun AddButton(add: AddPhotos) {
    Box {
        var menu by remember { mutableStateOf(false) }
        IconButton(
            onClick = { menu = true },
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
        ) {
            Box(Modifier.size(64.dp).background(Palette.Accent, Corners.full), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, contentDescription = "添加照片", tint = Palette.OnAccent)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("从相册添加") }, onClick = { menu = false; add.gallery() })
            DropdownMenuItem(text = { Text("从文件添加（RAW / DNG）") }, onClick = { menu = false; add.files() })
        }
    }
}

/** The two ways onto the table. The system pickers need no permission; the document picker also takes RAWs. */
internal class AddPhotos(val gallery: () -> Unit, val files: () -> Unit)

@Composable
internal fun rememberAddPhotos(table: LightTableViewModel): AddPhotos {
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { table.import(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { table.import(it) }
    return remember(pickMedia, pickFiles) {
        AddPhotos(
            gallery = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            files = { pickFiles.launch(arrayOf("*/*")) },
        )
    }
}
