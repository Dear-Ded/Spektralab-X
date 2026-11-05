# 常见问题 / FAQ

这里收的是用户问得最多、也最容易误会的几个问题。每条答案后面写了出处：上游的 issue、本仓库的测量文档，或者代码里的具体数值。没测过的地方明说没测过。

These are the questions users ask most, and the ones most easily misunderstood. Each answer cites its source: an upstream issue, a measurement document in this repository, or the actual value in the code. Anything not measured is said to be not measured. English follows the Chinese.

- [为什么慢？](#为什么慢)
- [为什么耗电、发热？](#为什么耗电发热)
- [暗红色为什么会偏紫？](#暗红色为什么会偏紫)
- [屏幕上看到的和导出的为什么不一样？](#屏幕上看到的和导出的为什么不一样)
- [颗粒为什么像彩噪？](#颗粒为什么像彩噪)
- [为什么要网络权限？](#为什么要网络权限)
- [怎么核对安装包是不是官方的？](#怎么核对安装包是不是官方的)
- [English](#english)

## 为什么慢？

因为每一帧都在手机 GPU 上把整条冲印链路算一遍：场景光谱曝光到胶片、负片显影、经放大机印到相纸、相纸显影、扫描。它不是查一张颜色表。

参考数字：在云主机的软件 GPU（lavapipe，用 CPU 模拟）上，一张 2420 万像素的照片按全尺寸冲一次要 12.8 秒，GPU 内存池峰值 1106 MB；缩到 1024 像素宽时是 0.5 秒、43 MB（[PARITY.md](PARITY.md) 第 6 节）。这是云主机的数字，**手机上的耗时还没测**，不同机型差别会很大。

应用为此做了这些事：

- 拖滑杆时只渲染长边 1024 像素的快速预览，松手后再出 3400 像素的清晰版（`Model.kt` 的 `LIVE_EDGE`，[ARCHITECTURE.md](ARCHITECTURE.md)）。
- 调整页、风格强度、调色盘只重新打包上一次冲好的结果，不重跑引擎，所以拖起来跟手。
- 导出在单独的线程里跑，编辑器不用排队等它（[PLATFORMS.md](PLATFORMS.md)）。
- 处理尺寸受 GPU 单缓冲上限约束：小米 12–14 的 Adreno 7xx 约 800 万像素，工作尺寸最高 3072（[DEVICES.md](DEVICES.md)）。超了就先缩小，否则根本出不了图。

想更快：在设置里调小工作尺寸，或用 3400 像素的分享尺寸导出。

## 为什么耗电、发热？

光谱模拟让 GPU 长时间满载，耗电和发热是计算量本身带来的。长时间全尺寸导出时，降频最明显的是小米 11（骁龙 888），导出变慢不一定是 bug（[DEVICES.md](DEVICES.md)）。

应用读取系统的温控余量（Android 的 `getThermalHeadroom`），余量超过 0.85 时：后台的胶片预览卡片和观片台小样暂停冲洗，把 GPU 留给当前画面；极致渲染最多等 2 分钟再开始（`EditorViewModel.kt`、`LightTableFilm.kt`、`ProcessingQueue.kt`）。耗电具体多少**还没在真机上测**。

## 暗红色为什么会偏紫？

这是上游引擎目前的已知限制，不是安卓移植引入的。上游作者在 [SpektraLab #12](https://github.com/JamesQiu2005/SpektraLab/issues/12) 里的答复是：这和光谱重建的欠采样有关，是引擎的计算方式决定的，不太好修。

引擎要先从照片的 RGB 反推出一条光谱，再拿去曝光胶片。三个数推一整条光谱本来就有多解，饱和的暗红落在这类推算最不稳的区域。本项目的色彩路径和上游逐像素一致（[PARITY.md](PARITY.md)），所以上游有的这个问题这里也有，而且我们不承诺单独修。遇到时可以在“调整”里用 HSL 的红色把色相往回拉一点。

## 屏幕上看到的和导出的为什么不一样？

这是上游的设计，安卓版照搬了。引擎在 ProPhoto RGB 这个很大的色域里冲印，离开时：

- **屏幕**：矩阵换算后直接裁到 sRGB 或 Display P3，不压缩色域；
- **导出**：用 CAM16 把超出文件色域的颜色压进去，饱和色会显得淡一些。

上游作者在 [SpektraLab #22](https://github.com/JamesQiu2005/SpektraLab/issues/22) 里解释过“导出变淡”：画布是 ProPhoto，导出到 P3 或 sRGB 会有不可避免的压缩。实测上游自己屏幕和导出之间的平均色差，彩色胶片 ΔE00 0.30–0.89，黑白 0.086–0.092；安卓两条路径之间的差逐用例和上游相同（[PLATFORMS.md](PLATFORMS.md) 第 3.2 节）。

另外三个常见原因：

- 拖动时看的是 1024 像素的快速预览，颗粒和光晕按像素计算，和全尺寸导出观感不同。预览右下角会标“快速预览”或“预览”。
- 小米“鲜艳”屏幕模式会把颜色拉得更饱和，对色请在“设置 → 显示 → 色彩模式”选“原色”〔推断，依据小米对两种模式的说明，没在本机测量〕。
- 导出 JPEG 时尽量选 Display P3（默认开启），饱和色被截掉的更少。

## 颗粒为什么像彩噪？

因为彩色负片的颗粒本来就是彩色的：三层乳剂各自有染料云，叠起来就是带颜色的颗粒。上游维护者在 [SpektraLab #55](https://github.com/JamesQiu2005/SpektraLab/issues/55) 里的答复是“这就是真实彩色负片颗粒的样子”，并且不打算加 AI 降噪。

觉得太花，可以在“效果”页：

- 调低颗粒强度，或调小颗粒尺寸（µm²）；
- 关掉“分层颗粒”，颗粒会更均匀；
- 换黑白胶片，黑白片只有一层银盐颗粒，是单色的。

颗粒细节在 1:1 查看里最准，缩小的预览上会被放大成色块。

## 为什么要网络权限？

只为检查和下载更新。从 0.1.19 起，应用启动时最多每 6 小时向本仓库的 GitHub Releases 要一次版本列表；有新版本时，你点“下载并安装”，或者在不计流量的 Wi-Fi 下自动下载安装包和 `SHA256SUMS`。没有统计、没有广告，除了请求本身什么都不发，照片不离开手机（[SECURITY.md](../SECURITY.md)、`Updater.kt`）。

不想联网就在设置里关掉“自动检查更新”；关掉后应用不发任何网络请求，其他功能照常用。新增的四个权限是 `INTERNET`、`ACCESS_NETWORK_STATE`、`REQUEST_INSTALL_PACKAGES`、`UPDATE_PACKAGES_WITHOUT_USER_ACTION`，后两个只用于安装自己的更新。

## 怎么核对安装包是不是官方的？

官方安装包只在 [GitHub Releases](https://github.com/Dear-Ded/Spektralab-X/releases) 发布，永久免费。在别处付费买到的不是官方版本。所有官方版本都用同一个证书签名，SHA-256：

```
CD:C5:E3:D0:AC:6F:C9:0B:53:E8:8C:57:E0:40:93:36:72:5A:E3:C2:6F:D1:9D:1A:7E:58:51:70:94:5F:6D:E3
```

- **手机上**：装 [AppVerifier](https://github.com/soupslurpr/AppVerifier)，把安装包分享给它，对比上面的指纹。应用的 设置 → 关于 里也印着同一个指纹。
- **电脑上**：`apksigner verify --print-certs`，以及 `sha256sum -c SHA256SUMS` 和 `gh attestation verify`（构建溯源证明）。

完整步骤见 [VERIFY.md](VERIFY.md)。应用内更新也会先核对 SHA-256 和签名证书，对不上就不装。

---

## English

### Why is it slow?

Every frame runs the whole printing chain on the phone's GPU: scene spectra exposed onto film, negative development, the enlarger printing onto paper, paper development, scan. It is not a colour lookup table.

For reference: on a cloud machine's software GPU (lavapipe, which runs on the CPU), one full-size 24.2 MP frame takes 12.8 s with a 1106 MB GPU pool peak; scaled to 1024 px wide it takes 0.5 s and 43 MB ([PARITY.md](PARITY.md) §6). Those are cloud numbers; **phone timings have not been measured yet** and will vary a lot by device.

What the app does about it:

- While a slider is dragged it renders only a 1024 px preview, then a sharp 3400 px one on release (`LIVE_EDGE` in `Model.kt`, [ARCHITECTURE.md](ARCHITECTURE.md)).
- The Adjust tab, style strength and the palette pad only repack the last developed result without re-running the engine, so they track the finger.
- Exports run on their own thread, so the editor does not wait behind them ([PLATFORMS.md](PLATFORMS.md)).
- The working size is capped by the GPU's single-buffer limit: about 8 MP on the Adreno 7xx of Xiaomi 12–14, so at most 3072 px ([DEVICES.md](DEVICES.md)). Larger photos are scaled down first; otherwise they cannot render at all.

To make it faster, lower the working size in Settings, or export at the 3400 px share size.

### Why does it drain the battery and get warm?

Spectral simulation keeps the GPU busy for a long time; the power and heat come from the work itself. During long full-size exports the Xiaomi 11 (Snapdragon 888) throttles the most, so a slow export is not necessarily a bug ([DEVICES.md](DEVICES.md)).

The app reads Android's thermal headroom (`getThermalHeadroom`). Above 0.85, background film-strip previews and light-table thumbnails pause to leave the GPU to the current photo, and an extreme render waits up to 2 minutes before starting (`EditorViewModel.kt`, `LightTableFilm.kt`, `ProcessingQueue.kt`). Actual battery use **has not been measured on a phone**.

### Why do dark reds turn purple?

This is a known limitation of the upstream engine, not something the Android port introduced. In [SpektraLab #12](https://github.com/JamesQiu2005/SpektraLab/issues/12) the upstream author answered that it comes from undersampling in the spectral reconstruction, follows from how the engine computes, and is hard to fix.

The engine first infers a spectrum from the photo's RGB, then exposes the film with it. Three numbers do not pin down a whole spectrum, and saturated dark reds fall where that inference is least stable. This port's colour path matches upstream pixel for pixel ([PARITY.md](PARITY.md)), so the same issue shows up here, and we do not promise a separate fix. When it happens, the red band in the Adjust tab's HSL can pull the hue back a little.

### Why does the screen look different from the export?

That is upstream's design, and the Android port follows it. The engine prints in the very wide ProPhoto RGB space. On the way out:

- **Screen**: a matrix conversion clipped into sRGB or Display P3, with no gamut compression.
- **Export**: CAM16 compresses colours outside the file's gamut into it, so saturated colours look a little paler.

The upstream author explained the "paler export" in [SpektraLab #22](https://github.com/JamesQiu2005/SpektraLab/issues/22): the canvas is ProPhoto, and exporting to P3 or sRGB involves unavoidable compression. Measured on upstream's own host, screen and export differ by a mean ΔE00 of 0.30–0.89 for colour films and 0.086–0.092 for black and white; the Android pair differs exactly as upstream's does, case by case ([PLATFORMS.md](PLATFORMS.md) §3.2).

Three other common causes:

- While dragging you see the 1024 px fast preview. Grain and halation are computed per pixel, so they look different from a full-size export. The corner label says "快速预览" (fast preview) or "预览" (preview).
- Xiaomi's "Vivid" screen mode oversaturates; to judge colour use Settings → Display → Colour mode → "Original colour" (inferred from Xiaomi's description of the modes, not measured here).
- Export JPEGs in Display P3 when you can (on by default); fewer saturated colours get clipped.

### Why does the grain look like colour noise?

Because colour-negative grain is coloured: each of the three emulsion layers has its own dye clouds, and together they make coloured grain. In [SpektraLab #55](https://github.com/JamesQiu2005/SpektraLab/issues/55) the upstream maintainer answered that this is what real colour-negative grain looks like, and that AI denoising is not planned.

If it is too busy, on the Effects tab:

- lower the grain strength or the grain size (µm²);
- turn off layered grain for a more even texture;
- switch to a black-and-white film, whose single silver layer gives monochrome grain.

Grain is shown most faithfully in the 1:1 view; a scaled-down preview enlarges it into colour blotches.

### Why does it need network access?

Only to check for and download updates. Since 0.1.19 the app fetches this repository's GitHub Releases list at launch, at most once every 6 hours; when a new version is out it downloads the APK and `SHA256SUMS` when you tap Download and install, or automatically on unmetered Wi-Fi. No analytics, no ads, nothing sent beyond the request itself, and photos never leave the phone ([SECURITY.md](../SECURITY.md), `Updater.kt`).

To stay offline, turn off "Check for updates automatically" in Settings; the app then makes no network requests and everything else works as before. The four new permissions are `INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION`; the last two are used only to install the app's own updates.

### How do I check that an APK is official?

Official APKs are published only on [GitHub Releases](https://github.com/Dear-Ded/Spektralab-X/releases) and are free forever. A copy you paid for elsewhere is not official. Every official build is signed with the same certificate, SHA-256 above.

- **On the phone**: install [AppVerifier](https://github.com/soupslurpr/AppVerifier), share the APK to it and compare the fingerprint. The app's Settings → About shows the same fingerprint.
- **On a computer**: `apksigner verify --print-certs`, plus `sha256sum -c SHA256SUMS` and `gh attestation verify` (build provenance).

Step by step: [VERIFY.md](VERIFY.md). The in-app updater also checks the SHA-256 and the signing certificate and refuses to install on a mismatch.
