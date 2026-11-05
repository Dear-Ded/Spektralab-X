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

## 小米 / HyperOS 4 真机待验 / Xiaomi and HyperOS 4 checks on a phone

以下来自 ROADMAP M6，只能在小米真机（最好是澎湃OS 4 / Android 17）上确认。诊断报告开头以 `*` 开头的固定行（最近一次退出、退出计数、通知状态）会一直保留。

These come from ROADMAP M6 and need a Xiaomi phone, ideally HyperOS 4 / Android 17. The pinned lines starting with `*` at the top of the diagnostics report (last exit, exit counts, notification state) never roll out.

| 项目 / Item | 怎么验 / How | 期望 / Expected |
|---|---|---|
| 焦点通知协议值 / Focus protocol | 启动后看诊断固定行 `notifications …` | `focus protocol` 为 3（HyperOS 3/4）、2 或 1；非小米为 0。`canShowFocus` 未获小米批准时为 false |
| 实时通知上岛与锁屏 / Live Update on island and lock screen | 导出一张大图，切到桌面、锁屏 | 状态栏胶囊、超级岛（HyperOS 3.1+）和锁屏显示进度与剩余时间；完成后消失并发“已冲洗完成” |
| 实时通知被关闭 / Live Updates turned off | 系统设置里关掉本应用的实时通知，再打开“正在处理” | 顶部提示“实时通知已关闭”，按钮打开对应设置页；打开后回到应用提示消失 |
| 通知全关 / All notifications off | 关掉本应用通知，打开“正在处理” | 提示“通知已关闭”，按钮打开应用通知设置 |
| 内存上限 / Memory limiter | `adb shell am memory-limiter status`；可用 `am memory-limiter manual <pid> <MB>` 压低后导出 | 被杀后重开：固定行 `last exit … memory limiter (MemoryLimiter:AnonSwap …)`，计数 +1，任务降一档重试；不出现保活提示 |
| 后台存活 / Background survival | 导出队列 3 张以上，切后台 5 / 15 / 30 分钟（省电策略默认） | 记下哪一档被打断；被打断后重开：任务自动继续，“正在处理”出现一次保活提示 |
| 保活提示跳转 / Keep-alive buttons | 点“省电策略”“自启动” | 分别打开省电策略页（或应用详情页）、自启动页（或应用详情页），不闪退；点过后不再出现 |
| 冻结 / App freezer | 后台导出被冻结后重开 | 固定行计数 `freezer` 或 `system kill during export` +1 |
| 后台释放 GPU 池 / GPU pool released in background | 只开“正在处理”导出（不进编辑器）后切到后台 | 事件日志有 `trim memory level … (app): N MB freed`（N 可为 0 时不记） |
| ADPF 与温控线 / ADPF and thermal lines | 导出一张后看事件日志 | `ADPF: hint session on`；Android 15+ 显示系统给的 moderate / severe 线；发热时有 `export waited for the phone to cool` |
| 导出时编辑流畅 / Editing during an export | 导出 24 MP 时拖动滑块 | 拖动不比空闲时明显卡；导出变慢可以接受 |
| 滑动刷新率 / Refresh rate while scrolling | 开发者选项“显示刷新率”，滑动胶片列表 | 记录实际刷新率（本版未改，供后续参考） |
| 小米相机新照片 / New Xiaomi camera photos | HDR JPEG、DNG、UltraRAW 各导入一张 | 由 RAW 会话负责，这里只记录能否打开 |
| HEIF 导出 / HEIF export | 保存 → HEIF · 全尺寸（sRGB），导出一张带边框的照片；`exiftool -a -G1 file.heic` | 小米相册能打开、颜色与同图 JPEG 一致；EXIF 有拍摄参数和 `Software Spektralab-X`；没有 HEVC/HEIC 编码器的手机菜单里不出现此项。编码器存在不保证支持每个尺寸，失败须明确提示。颜色配置：HeifWriter 1.1 不嵌 ICC，记下 exiftool 是否显示 `colr`（nclx）|
| 印样进队列 / Contact sheet in the queue | 观片台 → 按卷 → 导出印样；排版中杀掉应用再打开；再试一次取消 | “正在处理”显示“印样 · 卷名”、逐帧进度和剩余时间；被杀后重开自动重新排版且只存一张；取消后相册里没有残留 |
| 滑动时实际刷新率（HyperOS） / Refresh rate while dragging | 开发者选项显示刷新率，在编辑器里拖滑杆、曲线、调色板、面板把手；手指停住、抬手、切后台；开省电模式再拖 | Android 15+ 拖动时请求同分辨率最高刷新率，实际值由系统决定；停住约 0.3 s、抬手或切后台后恢复自适应；省电或高温时不升；Android 14 及以下无变化 |

## 「无」真机待验 / 无 checks on a phone

分支 `no-film`：风格、胶卷、相纸、胶片效果、调整都以「无」开头，新照片默认全部为「无」；色彩对照见 [PARITY.md](PARITY.md) 第 7 节。云主机只验证了编译和色彩数学，下面这些要在手机上看。

| 项目 / Item | 怎么验 / How | 期望 / Expected |
|---|---|---|
| 新照片默认无 / New photo opens on 无 | 设置「新照片的胶片和相纸」保持「无」，打开一张 JPEG 和一张 RAW | 风格、胶卷、相纸、胶片效果、调整的第一项「无」都是选中的；画面和长按看到的原图一样 |
| 老用户迁移 / Upgrade | 旧版里设过「固定为」的手机升级后打开设置 | 仍是「固定为」原来的胶片和相纸；没设过的显示「无」 |
| 胶片无 → 选胶片 / 无 then a film | 无胶片下点任意胶片 | 出片和 0.1.19 同一张照片同一胶片一致；相纸、曝光、效果恢复可用 |
| 冲洗后续步骤变灰 / Later steps disabled | 胶片「无」时点 ② ③ ④ | 控件变灰，上方提示「胶片是「无」…」；RAW 白平衡仍可调 |
| 相纸无（负片）/ Paper 无 on a negative | Portra 400 + 相纸「无」 | 数字中间片：中性、偏平的正像，没有橙色片基；冲洗标题显示「数字中间片」 |
| 相纸无（反转片）/ Paper 无 on a slide | Velvia + 相纸「无」 | 与原来的「直接扫描」一样 |
| 效果无 / Effects 无 | 有胶片时 ④ 选「无」，再选「胶片自带」 | 无颗粒、光晕、眩光、锐化；切回后与默认一致 |
| 调整无 / 调整 无 | 调过几项后点「调整」页的「无」 | 所有调整回到中性，可撤销 |
| 无胶片 + 调整层 + 边框 / 无 with Layer 2 and a border | 胶片「无」，调曝光、曲线，加数据条边框，导出 JPEG 和 TIFF | 调整和边框都在；数据条不写胶片名；片边、日期不出现 |
| 无胶片裁剪旋转 / Crop and turn on 无 | 胶片「无」下裁剪、旋转 90°、翻转 | 预览和导出的方向、范围与有胶片时相同 |
| 撤销 / Undo | 无 → 胶片 → 撤销 | 回到无，画面即时恢复 |
| 旧编辑 / Old edits | 观片台里打开 0.1.19 编辑过的照片、旧预设、旧排队任务 | 都按原来的胶片和相纸打开，输出不变 |
| 复制粘贴 / Copy and paste | 无胶片照片复制「胶片与相纸」粘到另一张 | 目标也变成无胶片 |
| 观片台按胶片分组 / Rolls by film | 几张无胶片照片 | 不被算进任何胶片 |
| 内存 / Memory | 4096 档、50 MP 照片无胶片全尺寸导出 | 不比有胶片时更容易失败；记下事件日志的峰值 |

## 引擎补丁 0010–0015 与深度拆解的真机待验 / Engine patches 0010–0015 and deep-dive checks on a phone

0010–0015 在云主机 lavapipe 上逐字节对照过（`scripts/parity.sh`，与改动前逐字节相同），下面这些只有 Adreno 真机能回答。依据见 `tools/work/engine_deepdive.md`（不在仓库里）。

0010–0015 were checked byte for byte on lavapipe (`scripts/parity.sh`, identical to the build before them); only an Adreno phone can answer the items below.

| 项目 / Item | 怎么验 / How | 期望 / Expected |
|---|---|---|
| 存储缓冲上限与池复用 / Storage range after pool reuse (0010) | 在 128 MiB 机型（小米 12–14）上用接近上限的工作尺寸，连续切换胶片、导出两次 | 不再出现 “buffer exceeds maxStorageBufferRange”；第二次和第一次一样能出图 |
| 取消渲染 / Cancel during a render (0011) | 全尺寸渲染中途返回或切换照片 | 渲染在下一个节点停下（事件日志 “render cancelled”），不卡住、不闪退 |
| 描述符池复用 / Descriptor pool reuse (0012) | 连续编辑 10 分钟、导出 5 张 | 无 `vkAllocateDescriptorSets` / `vkCreateDescriptorPool` 错误；出图与 0.1.19 相同 |
| 重印不重算常数 / Reprint reuses print constants (0013) | 开“冲扫黑白场”，拖放大曝光和黄/品红滤镜，再拖眩光强度 | 每次松手出图都对；和 0.1.19 同参数导出逐字节相同 |
| 小参数环形缓冲 / Inline-argument ring (0014) | 正常编辑、导出 | 出图与 0.1.19 相同；若异常，设 `SPEKTRAFILM_VULKAN_INLINE_RING=0`（调试版）对比 |
| 管线缓存 / Pipeline cache (0015) | 安装后冷启动两次，记下第一张预览出图时间；更新应用版本后再启动 | 第二次冷启动更快；`cacheDir` 里只有一个 `vk-pipelines-<版本号>.bin`；文件坏了（手动截断）也能正常启动 |
| IIR 每批行数 / IIR lines per launch (engine_deepdive 第 6 节第 10 项，未改代码) | 光晕重的风格（CineStill 800T）导出 24 MP，看 GPU 时间；调试版改 `kIirLinesPerLaunch`（256 → 512 / 1024）对比 | 找出不触发设备丢失的最大值，再决定是否按设备调 |
| Adreno 设备参数 / Adreno device limits | 诊断里的 GPU 信息，或 `vulkaninfo` | 记下 `maxStorageBufferRange`、内存类型（是否有 DEVICE_LOCAL+HOST_VISIBLE）、`maxPushConstantsSize`、`minStorageBufferOffsetAlignment`、子组大小、`math_mode`（fused 或 split） |
| 每次渲染的提交次数和耗时 / Submits and time per render | 设 `SPEKTRAFILM_TRANSFER_TIMINGS=1`（调试版）渲染一张 24 MP | 记下上传/读回次数和总耗时，量化 0014 在 Adreno 上的收益 |
