package com.dearded.spektralab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * 卷 in the selection bar (手动装卷, docs/UI.md appendix D phase 2): pack the
 * selection into a new roll, move it onto one of the user's rolls, or put it
 * back into the automatic film-and-day rolls.
 */
@Composable
fun RollSelectionActions(table: LightTableViewModel, chosen: List<Frame>) {
    val manual by table.manualRolls.rolls.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val inManual = remember(manual, chosen) {
        val ids = manual.flatMap { it.frames }.toSet()
        chosen.any { it.id in ids }
    }
    Box {
        QuietButton(tr("装卷"), enabled = chosen.isNotEmpty()) { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(tr("装成一卷…")) }, onClick = { menu = false; naming = true })
            if (manual.isNotEmpty()) {
                HorizontalDivider()
                for (r in manual) {
                    val full = r.frames.size >= Roll.LENGTH
                    DropdownMenuItem(
                        text = { Text(tr("移到「{0}」 {1}/{2}", r.name, r.frames.size, Roll.LENGTH)) },
                        enabled = !full,
                        onClick = { menu = false; table.moveToRoll(chosen, r.id, r.name) },
                    )
                }
            }
            if (inManual) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text(tr("放回自动分卷")) }, onClick = { menu = false; table.releaseFromRolls(chosen) })
            }
        }
    }
    if (naming) {
        RollNameDialog(
            title = tr("装成一卷"),
            hint = if (chosen.size > Roll.LENGTH) tr("{0} 张，超过 {1} 张的部分续为卷 2", chosen.size, Roll.LENGTH) else tr("{0} 张", chosen.size),
            initial = tr("我的卷"),
            confirm = tr("装卷"),
            onDismiss = { naming = false },
            onDone = { name -> naming = false; table.packRoll(chosen, name) },
        )
    }
}

/** A roll's name, for packing and renaming. Up to 40 characters; blank is refused. */
@Composable
fun RollNameDialog(title: String, hint: String, initial: String, confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val ok = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.PanelHighest,
        title = { Text(title) },
        text = {
            Column {
                if (hint.isNotEmpty()) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    label = { Text(tr("卷名")) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (ok) onDone(name.trim()) }),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }, enabled = ok) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消")) } },
    )
}
