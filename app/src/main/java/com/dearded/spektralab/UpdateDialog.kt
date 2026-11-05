package com.dearded.spektralab

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * The update sheet. An automatic check never interrupts an edit: with a
 * photo open it waits (the ⋯ menu shows a dot) until the user asks or the
 * editor is empty. A manual check always answers.
 *
 * A bottom sheet like 设置 and 导入 rather than a dialog (ui-screens_research.md
 * 5): the head says what this is and how big, the notes scroll in their own
 * card, one full-width primary does the next step, and the quieter choices sit
 * on one line under it instead of stacked in a dialog's corner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDialog(editing: Boolean) {
    val context = LocalContext.current
    val state by Updater.state.collectAsState()
    val prompt by Updater.prompt.collectAsState()
    if (!prompt) return
    if (editing && !Updater.explicit) return
    val s = state
    if (s is Updater.State.Downloading && s.background) return
    if (s == Updater.State.Idle) { Updater.later(); return }

    val dismiss = { Updater.later() }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Palette.Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            when (s) {
                Updater.State.Idle -> Unit
                Updater.State.Checking -> {
                    PanelHeader("正在检查更新", hint = "只连 GitHub 上本项目的发布页。")
                    AuraBar(Modifier.padding(vertical = RowGap))
                    Actions(primary = "后台检查", onPrimary = dismiss)
                }
                Updater.State.UpToDate -> {
                    PanelHeader("已是最新版本", summary = "当前 ${BuildConfig.VERSION_NAME}")
                    Actions(primary = "好", onPrimary = dismiss)
                }
                is Updater.State.Available -> {
                    PanelHeader("新版本 ${s.release.version}",
                        summary = "安装包 ${mb(s.release.apkSize)} · 当前 ${BuildConfig.VERSION_NAME}")
                    Notes(s.release)
                    Actions(
                        primary = "下载并安装", onPrimary = { Updater.download(context, s.release) },
                        quiet = listOf("以后再说" to dismiss, "跳过这个版本" to { Updater.skip(context, s.release) }),
                    )
                }
                is Updater.State.Downloading -> {
                    PanelHeader("正在下载 ${s.release.version}",
                        summary = String.format(Locale.ROOT, "%.0f%% · %s", s.progress * 100, mb(s.release.apkSize)))
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth().padding(vertical = RowGap),
                        color = Palette.Accent, trackColor = Palette.PanelHigh,
                    )
                    Hint("可以收起，下载会在后台继续。")
                    Actions(primary = "后台下载", onPrimary = dismiss)
                }
                is Updater.State.Ready -> {
                    val canInstall = context.packageManager.canRequestPackageInstalls()
                    PanelHeader("${s.release.version} 已下载",
                        hint = if (canInstall) "已核对校验和与签名。安装时应用会关闭，编辑记录不受影响。"
                            else "需要先允许 Spektralab-X 安装应用（只用于自身更新），然后回来点安装。")
                    Notes(s.release)
                    Actions(
                        primary = if (canInstall) "安装" else "去允许",
                        onPrimary = {
                            if (canInstall) Updater.install(context, s.file)
                            else context.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                            )
                        },
                        quiet = listOf("稍后" to dismiss),
                    )
                }
                is Updater.State.Failed -> {
                    PanelHeader("更新没有完成", hint = "也可以到 GitHub Releases 页面手动下载。")
                    Text(s.message, style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurface,
                        modifier = Modifier.padding(vertical = 4.dp))
                    val r = s.release
                    Actions(
                        primary = "重试",
                        onPrimary = { if (r != null) Updater.download(context, r) else Updater.check(context, manual = true) },
                        quiet = listOf("打开发布页" to {
                            dismiss()
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r?.page ?: Updater.RELEASES_PAGE)))
                        }),
                    )
                }
            }
        }
    }
}

/** The release notes in their own Layer2 card, scrolled inside it so the buttons never leave the screen. */
@Composable
private fun Notes(r: Updater.Release) {
    Text(
        r.notes.ifBlank { "（没有更新说明）" },
        style = MaterialTheme.typography.bodySmall, color = Palette.OnSurface,
        modifier = Modifier.fillMaxWidth().padding(vertical = RowGap)
            .background(Palette.PanelHigh, RoundedCornerShape(16.dp))
            .heightIn(max = 280.dp).verticalScroll(rememberScrollState())
            .padding(16.dp),
    )
}

/** One full-width primary (UI2.md 1.1: near-white, full round, a size up), then the quieter choices on one line. */
@Composable
private fun Actions(primary: String, onPrimary: () -> Unit, quiet: List<Pair<String, () -> Unit>> = emptyList()) {
    Spacer(Modifier.height(RowGap))
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Palette.Accent)
            .clickable(role = Role.Button, onClick = onPrimary),
        contentAlignment = Alignment.Center,
    ) { Text(primary, style = MaterialTheme.typography.titleSmall, color = Palette.OnAccent) }
    if (quiet.isNotEmpty()) Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        for ((label, action) in quiet) QuietButton(label, onClick = action)
    }
}

private fun mb(bytes: Long) = if (bytes <= 0) "—" else String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
