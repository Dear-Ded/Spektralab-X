# 校验安装包 / Verify a build

官方安装包只在本仓库的 [GitHub Releases](https://github.com/Dear-Ded/Spektralab-X/releases) 发布，永久免费。如果你在其他地方拿到或付费买到“Spektralab-X”，可以用下面任一方法确认它是不是官方版本。

The official APKs are published only on this repository's [GitHub Releases](https://github.com/Dear-Ded/Spektralab-X/releases) and are free. If you got (or paid for) a "Spektralab-X" APK anywhere else, any of the checks below tells you whether it is an official build.

## 签名证书 / Signing certificate

所有官方版本都用同一个证书签名，SHA-256 为（也记录在 [`scripts/signing-cert.sha256`](../scripts/signing-cert.sha256)，CI 发版时会核对）：

Every official build is signed with the same certificate (also in [`scripts/signing-cert.sha256`](../scripts/signing-cert.sha256), which CI checks before publishing):

```
CD:C5:E3:D0:AC:6F:C9:0B:53:E8:8C:57:E0:40:93:36:72:5A:E3:C2:6F:D1:9D:1A:7E:58:51:70:94:5F:6D:E3
```

### 在手机上 / On the phone

1. 安装 [AppVerifier](https://github.com/soupslurpr/AppVerifier)（开源）。
2. 在 AppVerifier 里选中已安装的 Spektralab-X，或者把 APK 分享给它。
3. 把显示的 SHA-256 与上面的值对比，全部一致才是官方版本。

1. Install [AppVerifier](https://github.com/soupslurpr/AppVerifier) (open source).
2. Pick the installed Spektralab-X in AppVerifier, or share the APK to it.
3. Compare the SHA-256 it shows with the value above; every byte must match.

### 在电脑上 / On a computer

Android SDK 的 `apksigner`：

```bash
apksigner verify --print-certs Spektralab-X-<版本>-arm64-v8a.apk
```

输出里 `Signer #1 certificate SHA-256 digest` 一行（不带冒号的小写十六进制）应与上面的值一致。

The `Signer #1 certificate SHA-256 digest` line (lower-case hex without colons) must match the value above.

## 文件哈希 / Checksums

每个版本附带 `SHA256SUMS`。把它和 APK 下载到同一个目录：

Each release carries `SHA256SUMS`. Download it next to the APK:

```bash
sha256sum -c SHA256SUMS
```

## 构建溯源 / Build provenance

每个官方 APK 都由 GitHub Actions 从本仓库的公开代码编译，并生成构建溯源证明（[artifact attestation](https://docs.github.com/actions/security-for-github-actions/using-artifact-attestations)）。它能证明这个文件是由哪次提交、哪个工作流编译出来的。用 [GitHub CLI](https://cli.github.com/) 核对：

Every official APK is built by GitHub Actions from this repository's public source and carries a build-provenance [artifact attestation](https://docs.github.com/actions/security-for-github-actions/using-artifact-attestations), which records the commit and workflow that produced the file. Check it with the [GitHub CLI](https://cli.github.com/):

```bash
gh attestation verify Spektralab-X-<版本>-arm64-v8a.apk -R Dear-Ded/Spektralab-X
```

## 导出的照片 / Exported photos

官方版导出的 JPEG 会在 EXIF 的 `Software` 字段写入 `Spektralab-X <版本>`。这只能说明照片是用这个应用（或它的修改版）导出的，不能代替签名校验。

JPEGs exported by the official build carry `Spektralab-X <version>` in the EXIF `Software` field. That shows which app exported a photo; it does not replace the signature check.

## 校验不通过 / If a check fails

- 证书不一致：这不是官方版本。它可能是别人的修改版（GPL-3.0 允许，但应改名并提供源码），也可能被植入了别的东西。请卸载，从 Releases 重新下载。
- 付费买到的：官方版免费，请向卖家申请退款。
- 自己编译的版本用的是调试密钥，证书不同属于正常情况，也无法覆盖安装官方版。

- Certificate mismatch: not an official build. It may be someone's modified version (allowed by GPL-3.0, though it should be renamed and come with source) or it may have been tampered with. Uninstall it and download again from Releases.
- If you paid for it: the official build is free; ask the seller for a refund.
- A build you compiled yourself is debug-signed, so a different certificate is expected, and it cannot install over the official build.

## 稳定性改动的真机待验 / Stability changes still to check on a phone

下面几项只能在真机上确认，CI 和云主机（软件 Vulkan）测不到。结果请附上 **设置 → 复制诊断信息**。

These can only be confirmed on a real phone; CI and the cloud host (software Vulkan) cannot reach them. Please attach **Settings → Copy diagnostics**.

| 项目 / Item | 怎么验 / How | 期望 / Expected |
|---|---|---|
| 单缓冲上限实测 / Storage-range probe | 首次启动后看诊断里的“单缓冲上限（实测 …）” | 实测值不超过驱动报告值；Adreno 7xx 为 128 MB；探针失败或崩溃后第二次启动显示“未实测，按保守值” |
| 设备丢失锁存 / Device-lost latch（补丁 0008） | 大图导出时触发 GPU 重置（如 Adreno 上 44.8 MP 以上） | 事件日志只有一次 `VkResult -4` 后紧接 “replacing the engine”，任务降一档完成或明确失败，不卡死 |
| 片边画布上限 / Film-edge canvas cap | 开胶片边框导出 45 MP 以上照片 | 不掉设备；导出尺寸约为 42 MP ÷ 1.6 |
| 大图导出时预览让路 / Preview yields to a large export | 导出 24 MP 以上时拖动滑块、松手 | 拖动流畅；松手后清晰预览最多等 20 秒，不和导出同时渲染 |
| 内存分档 / Memory tiers | 8 GB 机型连续导出全尺寸 | 诊断里 VmHWM 稳定，不被系统杀；需要时日志有 “memory tier … MP -> … MP” |
| HyperOS 上 `clear_refs` | 导出后看日志的峰值一行 | 不带 “(process peak)”；带了说明系统拒绝重置，峰值是整个进程的 |
