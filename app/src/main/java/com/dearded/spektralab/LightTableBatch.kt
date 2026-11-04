package com.dearded.spektralab

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * The selection bar's batch actions (docs/UI.md 2.9): 复制调整 from one frame,
 * 粘贴 onto the selection by group, and 批量导出 through the 正在处理 queue.
 * Pasting writes settings into sidecars; nothing is developed until export.
 */
@Composable
fun BatchActions(table: LightTableViewModel, editor: EditorViewModel, chosen: List<Frame>) {
    val state by table.state.collectAsState()
    val context = LocalContext.current
    var pasting by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }

    // As in the editor: Android 13+ asks for the progress notification when an export starts;
    // a refusal only hides the notification.
    var queued by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        queued?.invoke()
        queued = null
    }
    fun export(format: ExportFormat, size: ExportSize) {
        val run = { table.export(chosen, format, size, editor.ui.value); table.clearSelection() }
        val missing = android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (missing) {
            queued = run
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else run()
    }

    if (chosen.size == 1) TextButton(onClick = { table.copyLook(chosen.first()) }) { Text("复制调整") }
    TextButton(onClick = { pasting = true }, enabled = state.copied != null) { Text("粘贴") }
    Box {
        TextButton(onClick = { exportMenu = true }) { Text("导出") }
        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
            DropdownMenuItem(text = { Text("JPEG · 全尺寸") }, onClick = { exportMenu = false; export(ExportFormat.JPEG, ExportSize.FULL) })
            DropdownMenuItem(text = { Text("JPEG · 分享尺寸（长边 3400）") }, onClick = { exportMenu = false; export(ExportFormat.JPEG, ExportSize.SHARE) })
            DropdownMenuItem(text = { Text("16 位 TIFF · 全尺寸") }, onClick = { exportMenu = false; export(ExportFormat.TIFF, ExportSize.FULL) })
        }
    }
    TextButton(onClick = { table.rate(chosen.map { it.id }.toSet(), pick = 1); table.clearSelection() }) { Text("选中") }
    TextButton(onClick = { table.rate(chosen.map { it.id }.toSet(), pick = -1); table.clearSelection() }) { Text("淘汰") }

    val copied = state.copied
    if (pasting && copied != null) {
        PasteSheet(
            from = copied, count = chosen.size, initial = state.pasteGroups,
            onDismiss = { pasting = false },
            onPaste = { groups ->
                pasting = false
                table.setPasteGroups(groups)
                table.paste(chosen, groups, editor.ui.value.catalog)
                table.clearSelection()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasteSheet(
    from: CopiedLook,
    count: Int,
    initial: Set<LookGroup>,
    onDismiss: () -> Unit,
    onPaste: (Set<LookGroup>) -> Unit,
) {
    var groups by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.PanelHigh) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("粘贴调整", style = MaterialTheme.typography.titleMedium)
            Text("来自「${from.fromName}」，粘贴到 $count 张。粘贴的是设置：每张照片自己测光，日期取自己的拍摄时间。",
                style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft)
            Spacer(Modifier.height(12.dp))
            for (g in LookGroup.entries) {
                val on = g in groups
                Row(
                    Modifier.fillMaxWidth().height(48.dp)
                        .clickable { groups = if (on) groups - g else groups + g }
                        .semantics { role = Role.Checkbox; stateDescription = if (on) "已勾选" else "未勾选" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = Palette.Amber, checkmarkColor = Palette.OnAmber))
                    Spacer(Modifier.padding(start = 12.dp))
                    Text(g.label)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    onClick = { onPaste(groups) }, enabled = groups.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Amber, contentColor = Palette.OnAmber),
                ) { Text("粘贴") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
