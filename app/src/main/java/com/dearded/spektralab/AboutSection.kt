package com.dearded.spektralab

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/*
 * The 关于 block in 设置, text as finalised in docs/ABOUT_TEXT.md (edit the
 * text there first). GPL-3.0 section 5(d) asks for these notices in an
 * interactive program, and NOTICE's 7(b) term keeps the attribution here.
 */
private const val SOURCE = "https://github.com/Dear-Ded/Spektralab-X"
private const val VERIFY = "$SOURCE/blob/main/docs/VERIFY.md"
private const val CERT =
    "CD:C5:E3:D0:AC:6F:C9:0B:53:E8:8C:57:E0:40:93:36:72:5A:E3:C2:6F:D1:9D:1A:7E:58:51:70:94:5F:6D:E3"

private val BLOCKS = listOf(
    "关于本项目" to "光谱胶片与相纸模拟。Spektralab-X 是基于 SpektraLab（JamesQiu2005/SpektraLab）与 spektrafilm 的安卓衍生项目，" +
        "由一位独立开发者在 AI 助手（Claude、GPT、Grok、DeepSeek）的协助下开发，目前处于快速迭代的 Alpha 阶段。" +
        "这不是 SpektraLab 官方版本，与 SpektraLab 的作者和所有者无关联，未获其背书。\n作者：Spektralab-X 贡献者",
    "项目初心" to "胶片真正迷人的，从来不只是色彩和画质，而是拍摄本身：按下快门时的不确定，等待冲洗时的期待，失手时的遗憾，" +
        "还有出片那一刻的惊喜。\n希望 Spektralab-X 能把你带回拍摄的过程里，让你重新爱上每一次按下快门的瞬间。",
    "写在前面" to "Spektralab-X 站在巨人的肩膀上：它基于 JamesQiu2005 的 SpektraLab 与 Andrea Volpato 的 spektrafilm 开发。" +
        "没有这些开源项目和背后多年的研究，就不会有它。\n如果它帮你冲出了一张喜欢的照片，点一个 Star，就是对我最大的鼓励。",
    "本应用永久免费" to "官方安装包只在 GitHub Releases 发布，不收取任何费用。欢迎把安装包转发到社区、社群、论坛，无需授权，" +
        "注明源码地址即可。如果你是付费买到的，那不是官方版本，请申请退款，并到源码地址下载官方版。\n" +
        "应用里可以直接更新：只向本仓库的 GitHub Releases 检查和下载，校验 SHA-256 和签名证书后才安装。" +
        "除了检查更新不联网，照片不离开手机。",
    "许可与署名" to "本应用以 GPL-3.0 发布，你可以自由使用、修改和分发。分发修改版时，请同时提供完整源码，在“关于”页保留原项目名称" +
        "“Spektralab-X”、源码地址和“Spektralab-X 贡献者”署名，并换用自己的名称和图标（GPL-3.0 第 7 条附加条款，见 NOTICE）。",
    "致谢" to "渲染引擎：SpektraLab（GPL-3.0）· github.com/JamesQiu2005/SpektraLab\n" +
        "胶片过程模型与胶片、相纸数据：spektrafilm，Andrea Volpato（GPL-3.0 / CC BY-SA 4.0）· github.com/andreavolpato/spektrafilm\n" +
        "胶片建模基于 spektrafilm（film modeling powered by spektrafilm）\n" +
        "RAW 解码：LibRaw（LGPL-2.1）；极致渲染 JPEG：libjpeg-turbo（BSD-3-Clause / IJG）；volk、stb（MIT）；Liberation Sans（OFL 1.1）、DejaVu Sans Mono。许可证文本随应用附带。\n" +
        "本应用是非官方版本，与 SpektraLab 的作者和所有者、小米公司均无关联，未获其背书。" +
        "SpektraLab™ 及其图标是其所有者的商标（注册中）。",
)

@Composable
internal fun AboutSection() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Column {
        Text("关于", style = MaterialTheme.typography.labelLarge)
        Text("Spektralab-X · 版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { open(SOURCE) }) { Text("源码：github.com/Dear-Ded/Spektralab-X") }
        for ((title, body) in BLOCKS) {
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = Palette.Accent)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Text("官方签名证书 SHA-256", style = MaterialTheme.typography.labelMedium, color = Palette.Accent)
        Text(CERT, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        var copied by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(CERT)); copied = true }) {
                Text(if (copied) "已复制" else "复制")
            }
            TextButton(onClick = { open(VERIFY) }) { Text("校验方法") }
        }
    }
}
