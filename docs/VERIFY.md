# 校验安装包 / Verify a build

官方安装包只在本仓库的 [GitHub Releases](https://github.com/Dear-Ded/spektralab-android/releases) 发布，永久免费。如果你在其他地方拿到或付费买到“Spektralab-X”，可以用下面任一方法确认它是不是官方版本。

The official APKs are published only on this repository's [GitHub Releases](https://github.com/Dear-Ded/spektralab-android/releases) and are free. If you got (or paid for) a "Spektralab-X" APK anywhere else, any of the checks below tells you whether it is an official build.

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
gh attestation verify Spektralab-X-<版本>-arm64-v8a.apk -R Dear-Ded/spektralab-android
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
