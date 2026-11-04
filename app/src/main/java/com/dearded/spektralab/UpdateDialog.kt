package com.dearded.spektralab

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * The update dialog. An automatic check never interrupts an edit: with a
 * photo open it waits (the ⋯ menu shows a dot) until the user asks or the
 * editor is empty. A manual check always answers.
 */
@Composable
fun UpdateDialog(editing: Boolean) {
    val context = LocalContext.current
    val state by Updater.state.collectAsState()
    val prompt by Updater.prompt.collectAsState()
    if (!prompt) return
    if (editing && !Updater.explicit) return
    val s = state
    if (s is Updater.State.Downloading && s.background) return

    val dismiss = { Updater.later() }
    when (s) {
        Updater.State.Idle, Updater.State.Checking -> {
            if (s == Updater.State.Idle) { Updater.later(); return }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text("正在检查更新…") },
                text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(onClick = dismiss) { Text("后台检查") } },
            )
        }
        Updater.State.UpToDate -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("已是最新版本") },
            text = { Text("当前 ${BuildConfig.VERSION_NAME}") },
            confirmButton = { TextButton(onClick = dismiss) { Text("好") } },
        )
        is Updater.State.Available -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("新版本 ${s.release.version}") },
            text = { Notes(s.release, "安装包 ${mb(s.release.apkSize)} · 当前 ${BuildConfig.VERSION_NAME}") },
            confirmButton = {
                TextButton(onClick = { Updater.download(context, s.release) }) { Text("下载并安装") }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = dismiss) { Text("以后再说") }
                    TextButton(onClick = { Updater.skip(context, s.release) }) { Text("跳过这个版本") }
                }
            },
        )
        is Updater.State.Downloading -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("正在下载 ${s.release.version}") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(String.format(Locale.ROOT, "%.0f%% · %s", s.progress * 100, mb(s.release.apkSize)),
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = dismiss) { Text("后台下载") } },
        )
        is Updater.State.Ready -> {
            val canInstall = context.packageManager.canRequestPackageInstalls()
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text("${s.release.version} 已下载") },
                text = {
                    Notes(s.release, if (canInstall) "已核对校验和与签名。安装时应用会关闭，编辑记录不受影响。"
                        else "需要先允许 Spektralab-X 安装应用（只用于自身更新），然后回来点安装。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (canInstall) Updater.install(context, s.file)
                        else context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                        )
                    }) { Text(if (canInstall) "安装" else "去允许") }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text("稍后") } },
            )
        }
        is Updater.State.Failed -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("更新没有完成") },
            text = { Text(s.message + "\n\n也可以到 GitHub Releases 页面手动下载。") },
            confirmButton = {
                val r = s.release
                if (r != null) TextButton(onClick = { Updater.download(context, r) }) { Text("重试") }
                else TextButton(onClick = { Updater.check(context, manual = true) }) { Text("重试") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dismiss()
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.release?.page ?: Updater.RELEASES_PAGE)))
                }) { Text("打开发布页") }
            },
        )
    }
}

@Composable
private fun Notes(r: Updater.Release, footer: String) {
    Column {
        Text(
            r.notes.ifBlank { "（没有更新说明）" },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(10.dp))
        Text(footer, style = MaterialTheme.typography.labelSmall, color = Palette.OnSurfaceSoft)
    }
}

private fun mb(bytes: Long) = if (bytes <= 0) "—" else String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
