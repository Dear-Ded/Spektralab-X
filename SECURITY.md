# 安全策略 / Security Policy

## 支持的版本 / Supported versions

只维护最新的 pre-release。修复不会回推到旧版本。
Only the latest pre-release is supported; fixes are not backported.

## 报告漏洞 / Reporting a vulnerability

发现安全问题请**不要**公开提 issue，在本仓库的 **[Security → Report a vulnerability](https://github.com/Dear-Ded/Spektralab-X/security/advisories/new)** 里私下报告。请写明版本、机型、复现步骤和影响。项目由一位独立开发者维护，一般两周内回复；确认后会在修复版本的更新记录里致谢（你愿意的话）。

Please do **not** open a public issue. Report privately via **[Security → Report a vulnerability](https://github.com/Dear-Ded/Spektralab-X/security/advisories/new)** with the version, device, steps to reproduce and impact. A single maintainer handles reports; expect a reply within about two weeks. With your permission, you will be credited in the changelog of the fixing release.

## 范围 / Scope

属于本项目：应用代码（`app/`）、对上游引擎的补丁（`patches/`）、构建和发布流程（`scripts/`、`.github/workflows/`）。上游 SpektraLab 引擎本身的问题请报告给 [上游](https://github.com/JamesQiu2005/SpektraLab)；LibRaw 的问题请报告给 [LibRaw](https://www.libraw.org/)。拿不准就报到这里，我们会转交。

In scope: the app (`app/`), the engine patches (`patches/`) and the build and release pipeline (`scripts/`, `.github/workflows/`). Issues in the SpektraLab engine itself belong [upstream](https://github.com/JamesQiu2005/SpektraLab), and LibRaw issues to [LibRaw](https://www.libraw.org/). If unsure, report here and we will pass it on.

## 应用的隐私设计 / Privacy by design

- 应用只为更新联网：最多每 6 小时请求一次本仓库的 GitHub Releases 列表，下载新版安装包和 `SHA256SUMS`。不收集、不上传任何数据，照片不离开手机。
- 自动更新只安装 SHA-256 与 `SHA256SUMS` 一致、签名证书与已安装应用一致的安装包；安卓系统还会再校验一次签名。
- 导出的 JPEG 保留拍摄参数；GPS 位置默认不写入，只有在设置里打开“导出保留拍摄位置”才会写。
- 诊断信息只在你点“复制”时生成，不含文件名和位置。

The app goes online only for updates: at most once every 6 hours it fetches this repository's GitHub Releases list, and downloads a new APK and `SHA256SUMS`. It collects and uploads nothing, and photos never leave the phone. The updater installs an APK only if its SHA-256 matches `SHA256SUMS` and its signing certificate matches the installed app; Android checks the signature again. Exported JPEGs keep shooting parameters; GPS location is written only if you turn on "Keep location in exports" in Settings (off by default). Diagnostics are produced only when you tap Copy and contain no file names or locations.

## 冒充的安装包 / Impersonating builds

官方安装包只在 [GitHub Releases](https://github.com/Dear-Ded/Spektralab-X/releases) 发布，永久免费，签名证书 SHA-256 为：

Official APKs are published only on [GitHub Releases](https://github.com/Dear-Ded/Spektralab-X/releases), free, signed with the certificate whose SHA-256 is:

```
CD:C5:E3:D0:AC:6F:C9:0B:53:E8:8C:57:E0:40:93:36:72:5A:E3:C2:6F:D1:9D:1A:7E:58:51:70:94:5F:6D:E3
```

签名不一致的安装包不是官方版本，可能被篡改过，请不要安装。校验方法见 [docs/VERIFY.md](docs/VERIFY.md)。发现冒充官方版本的发布渠道，欢迎提 issue 告知。

An APK with a different certificate is not an official build and may have been tampered with; do not install it. See [docs/VERIFY.md](docs/VERIFY.md). If you find a channel passing itself off as the official build, please open an issue.
