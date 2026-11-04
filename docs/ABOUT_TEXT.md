# “关于”页文案 / About screen text

> 状态：**文案定稿，等待界面接入。** 界面由主会话实现（`EditorScreen.kt` 不在本文档的负责范围内）。改文案先改这里，再同步到界面。
> 依据：GPL-3.0 第 5(d) 条要求有交互界面的程序显示“适当的法律声明”（Appropriate Legal Notices）；[NOTICE](../NOTICE) 里的第 7(b) 条附加条款要求衍生版本在这个位置保留署名。

`{version}` 和 `{code}` 是占位符，分别替换为 `BuildConfig.VERSION_NAME` 和 `BuildConfig.VERSION_CODE`。证书指纹与 [`scripts/signing-cert.sha256`](../scripts/signing-cert.sha256) 一致，改密钥时两处一起改。

## 布局

自上而下七块，每块一个小标题加正文，正文用 `bodySmall`。链接可点击，打开浏览器（应用没有网络权限，打开外部链接不需要）。证书指纹用等宽字体，旁边放“复制”按钮。

## 简体中文

**Spektralab-X**
版本 {version}（{code}）

**关于本项目**
光谱胶片与相纸模拟。Spektralab-X 是基于 SpektraLab（JamesQiu2005/SpektraLab）与 spektrafilm 的安卓衍生项目，由一位独立开发者在 AI 助手（Claude、GPT、Grok、DeepSeek）的协助下开发，目前处于快速迭代的 Alpha 阶段。
作者：Spektralab-X 贡献者
源码：https://github.com/Dear-Ded/Spektralab-X

**项目初心**
胶片真正迷人的，从来不只是色彩和画质，而是拍摄本身：按下快门时的不确定，等待冲洗时的期待，失手时的遗憾，还有出片那一刻的惊喜。
希望 Spektralab-X 能把你带回拍摄的过程里，让你重新爱上每一次按下快门的瞬间。

**写在前面**
Spektralab-X 站在巨人的肩膀上：它基于 JamesQiu2005 的 SpektraLab 与 Andrea Volpato 的 spektrafilm 开发。没有这些开源项目和背后多年的研究，就不会有它。
如果它帮你冲出了一张喜欢的照片，点一个 Star，就是对我最大的鼓励。

**本应用永久免费**
官方安装包只在 GitHub Releases 发布，不收取任何费用。欢迎把安装包转发到社区、社群、论坛，无需授权，注明源码地址即可。如果你是付费买到的，那不是官方版本，请申请退款，并到上面的地址下载官方版。
官方签名证书 SHA-256：
`CD:C5:E3:D0:AC:6F:C9:0B:53:E8:8C:57:E0:40:93:36:72:5A:E3:C2:6F:D1:9D:1A:7E:58:51:70:94:5F:6D:E3`
[复制] · 校验方法：github.com/Dear-Ded/Spektralab-X/blob/main/docs/VERIFY.md

**许可与署名**
本应用以 GPL-3.0 发布，你可以自由使用、修改和分发。分发修改版时，请同时提供完整源码，在“关于”页保留原项目名称“Spektralab-X”、源码地址和“Spektralab-X 贡献者”署名，并换用自己的名称和图标（GPL-3.0 第 7 条附加条款，见 NOTICE）。

**致谢**
渲染引擎：SpektraLab（GPL-3.0）· github.com/JamesQiu2005/SpektraLab
胶片过程模型与胶片、相纸数据：spektrafilm，Andrea Volpato（GPL-3.0 / CC BY-SA 4.0）· github.com/andreavolpato/spektrafilm
RAW 解码：LibRaw（LGPL-2.1）；volk、stb（MIT）；Liberation Sans（OFL 1.1）、DejaVu Sans Mono。许可证文本随应用附带。
本应用与 SpektraLab 上游、小米公司均无关联。

## English

**Spektralab-X**
Version {version} ({code})

**About**
Spectral film and print simulation. Spektralab-X is an Android derivative of SpektraLab (JamesQiu2005/SpektraLab) and spektrafilm, built by one independent developer working with AI assistants (Claude, GPT, Grok and DeepSeek). It is in alpha and changing quickly.
Author: Spektralab-X contributors
Source: https://github.com/Dear-Ded/Spektralab-X

**Why we made it**
What makes film fascinating was never only the colour or the image quality. It is the act of shooting: the uncertainty when you press the shutter, the anticipation while the roll is developed, the regret of a missed frame, and the surprise when the prints come back.
We hope Spektralab-X brings you back to the process of taking pictures, and makes you fall in love again with every press of the shutter.

**A note from the developer**
Spektralab-X stands on the shoulders of giants: it is built on JamesQiu2005's SpektraLab and Andrea Volpato's spektrafilm. Without those projects and the years of research behind them, it would not exist.
If it helps you develop a photo you love, a star is the best encouragement you could give.

**This app is free, forever**
Official APKs are published only on GitHub Releases and cost nothing. You are welcome to share the APK in communities, groups and forums; no permission is needed, just include the source address. If you paid for this copy, it is not the official build: ask for a refund and download the official one from the address above.
Official signing certificate SHA-256:
`CD:C5:E3:D0:AC:6F:C9:0B:53:E8:8C:57:E0:40:93:36:72:5A:E3:C2:6F:D1:9D:1A:7E:58:51:70:94:5F:6D:E3`
[Copy] · How to verify: github.com/Dear-Ded/Spektralab-X/blob/main/docs/VERIFY.md

**Licence and attribution**
This app is released under GPL-3.0: you may use, modify and redistribute it. If you distribute a modified version, provide its complete source, keep the original project name "Spektralab-X", the source address and the credit "Spektralab-X contributors" on its About screen, and use your own name and icon (additional terms under GPL-3.0 section 7, see NOTICE).

**Credits**
Rendering engine: SpektraLab (GPL-3.0) · github.com/JamesQiu2005/SpektraLab
Film process model, film and paper data: spektrafilm by Andrea Volpato (GPL-3.0 / CC BY-SA 4.0) · github.com/andreavolpato/spektrafilm
RAW decoding: LibRaw (LGPL-2.1); volk and stb (MIT); Liberation Sans (OFL 1.1) and DejaVu Sans Mono. Licence texts ship with the app.
Not affiliated with the SpektraLab project or with Xiaomi.

## 字符串资源 / String resources

接入时建议放进 `app/src/main/res/values/strings_about.xml`（单独文件，避免和其他线改 `strings.xml` 冲突），键名如下：

| 键 | 内容 |
|---|---|
| `about_title` | Spektralab-X |
| `about_version` | 版本 %1$s（%2$d） |
| `about_project` | “关于本项目”正文 |
| `about_author` | 作者：Spektralab-X 贡献者 |
| `about_note` | “写在前面”正文（两句） |
| `about_source_url` | https://github.com/Dear-Ded/Spektralab-X |
| `about_intent` | “项目初心”正文 |
| `about_free` | “本应用永久免费”正文 |
| `about_cert_sha256` | 证书指纹（不翻译，`translatable="false"`） |
| `about_verify_url` | https://github.com/Dear-Ded/Spektralab-X/blob/main/docs/VERIFY.md |
| `about_license` | “许可与署名”正文 |
| `about_credits` | “致谢”正文 |

## 源码文件头 / Source file header

建议所有本项目自己的 Kotlin 和 C++ 文件加上同样的文件头（由各文件的负责线在下次改动时加，不单独发一个全仓库的改动）：

```
// Spektralab-X — https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
```

注意：仓库的 [LICENSE](../LICENSE) 和 README 写的是 GPL-3.0；上游 SpektraLab 是 GPL-3.0-or-later。文件头用 `GPL-3.0-or-later` 还是 `GPL-3.0-only` 需要维护者决定后统一，决定前先不加 SPDX 行。
