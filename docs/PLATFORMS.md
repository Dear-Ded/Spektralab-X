# 平台差异：Spektralab-X（安卓）与上游 SpektraLab（macOS、Windows / Linux） / Platform differences

更新：2026-11-04 · 对应 `0.1.19-alpha` · 上游固定提交 `a46627d` 加本仓库 `patches/` 0001–0005

## 简要说明 / Summary

- **胶片部分是同一个引擎。** 安卓版编译的是上游自己的 C++ 引擎和 Vulkan 着色器，胶片、相纸数据也相同。改动只有 5 个补丁（[PATCHES.md](PATCHES.md)），都是为了在手机 GPU 上跑得动，或让编辑和导出两条线程同时渲染，不改色彩。
- **和上游 Linux 宿主程序比，颜色一致。** 同样的输入、同样的设置，43 组对照里导出图的平均色差是 ΔE00 0.000（RAW、合成图），手机 JPEG 是 0.016 以下。人眼大约在 ΔE00 1 才能分辨，这些差别看不出来。
- **和 macOS 版还没有直接比过。** 手边没有 Mac。macOS 版用苹果自己的 RAW 解码器，安卓和 Windows / Linux 都用 LibRaw，所以同一个 RAW 在 macOS 上本来就会不同。这一点上游自己的 Windows / Linux 版也一样。
- **手机的限制在尺寸，不在颜色。** 小米 12–14 的 GPU 单块显存最多 128 MiB，大约只能处理 800 万像素的画面；拖动滑块时预览只有 1024 像素。
- **全部数字来自云主机上的软件 GPU（lavapipe）**，还没在小米手机上实测。

The film engine is upstream's own code. Against upstream's Linux host the Android colour path matches to quantisation (mean ΔE00 0.000 for RAW and synthetic input, ≤ 0.016 for phone JPEGs). macOS has not been compared directly, and its RAW decoder differs from LibRaw by design. Every number below was measured on lavapipe, not on an Adreno phone.

凡是没有测量、只根据代码或文档推断的地方，下文标 **〔推断〕**。

## 1. 各阶段对照 / Pipeline by platform

| 阶段 | macOS（上游桌面版） | Windows / Linux（上游 `spektralab-host`） | 安卓（Spektralab-X） |
|---|---|---|---|
| RAW 解码 | Core Image `CIRAWFilter`，`boostAmount = 0`（关闭苹果的色调渲染），镜头校正按用户设置（`modern_UI/.../Import/ImageDecoder.swift:217–227`） | LibRaw 0.22.2，`decode_raw_compatible`，输出线性 ProPhoto；可选 Lensfun 镜头校正（`engine/host/image_io.cpp` `decode_linear_prophoto`） | 同一个 LibRaw 函数（`spektralab_jni.cpp` `openRaw`）。补丁 0004：DNG 双光源矩阵插值（宿主同样打了这个补丁）。补丁 0003：传感器超过工作尺寸 4 倍时用半尺寸解码（2×2 合并，不做去马赛克）。LibRaw 用 OpenMP（libomp 静态链接，线程数限制为大核数，X-Trans 保持串行），1 / 2 / 4 线程输出逐字节相同。没有镜头校正 |
| 普通图片解码 | ImageIO / Core Image，按嵌入的 ICC 转换 | `image_io.cpp`：取下 ICC 曲线，用配置文件的 D50 色度点经 XYZ 转线性 ProPhoto；无配置文件按 sRGB（`image_io.hpp` 文件头说明这是照 ColorSync / Core Image 的做法） | `ImageDecoder` 解成 sRGB 或 Display P3 的 ARGB_8888，再由 `input_transform.cpp` 转线性 ProPhoto。这段代码在编译时从上游 `image_io.hpp/.cpp` 截取，和宿主的算法相同。其他配置文件由安卓系统先转成 sRGB 或 P3〔推断：与宿主直接读 ICC 会有小差别，未测〕 |
| GPU 后端 | Metal，编译选项 `-fmetal-math-mode=safe`（AGENTS.md「Do not」一节） | Vulkan（桌面 GPU；没有 GPU 时用 lavapipe） | Vulkan 1.0，Adreno（小米 15S Pro 是 Mali）。补丁 0001 / 0002 把大计算拆成小批，输出不变 |
| 精度 | fp32；`spk_math_probe` 发现快速数学就拒绝启动 | fp32；启动时探测 fma（`vulkan_gpu.cpp:313–410`），不精确就改用 Dekker 拆分乘积，拆分乘积也不精确才拒绝启动 | 同 Windows / Linux 的代码。实测 lavapipe（LLVM 19.1.7）的 fma 并不精确（探测残差得 0，应为 7.45e-11），引擎改走 Dekker 拆分乘积并正常启动，所以本仓库全部对照数字本来就走这条路。Adreno / Mali 据报告同样不精确，会走同一条路〔推断，未上真机〕。详见 [PARITY.md](PARITY.md) 第 4.4 节 |
| 工作空间 | 编码 ProPhoto（ROMM，γ1.8） | 同左 | 同左（`Edit.openingParams`：`output_color_space` 为 `ProPhoto RGB`） |
| 屏幕输出 | 画布纹理标记为 ROMM，由 ColorSync 转到显示器（`Canvas/MetalCanvasView.swift:765`），应用自己不做转换 | `render_view`：矩阵加裁切到 sRGB 或 Display P3，不做色域压缩（`desktop/HOST-PROTOCOL.md` 第 73–76 行） | `output_transform.cpp`：矩阵加裁切到 sRGB 或 Display P3，数学代码在编译时从上游 `engine/host/output.cpp` 截取 |
| 导出 | CAM16-UCS 色度压缩进文件色域，关闭亮度肩部（`Canvas/ColourManagement.swift:164`） | `export_frame`：同左 | 同左，用的是同一段上游代码 |
| 调整层（Layer 2） | `Shaders.metal` 的 `layer2`，以 0.3857（ProPhoto 中灰）为支点；有曲线和蒙版（蒙版默认关闭） | `spk_view_grade_body.glsl`：同一套算法，结束时截到 0–1 并量化到 16 位 | `layer2.cpp`：同一套算法和截断量化（第 2.3 节）。曲线（RGB、亮度、红、绿、蓝）按上游 `view_process.cpp` 的 Fritsch–Carlson 插值移植，对照宿主导出平均 ΔE00 0.000、p99.9 ≤ 0.007、最大 ≤ 0.025。没有蒙版。新增了自然饱和度、8 段 HSL、褪色 |
| 屏幕色彩管理 | ColorSync，按显示器的配置文件 | 桌面版（Tauri）把标记好 sRGB / P3 的像素交给 WebView，由它管理显示器〔推断：依赖 WebView 的色彩管理，未测〕 | 屏幕支持广色域时，窗口设为 `COLOR_MODE_WIDE_COLOR_GAMUT`，位图标记 Display P3（`DisplayColor.kt:22–30`）；否则用 sRGB |
| 导出格式 | JPEG、8 位 PNG、16 位 TIFF、DI 包（`Export/Exporter.swift:29`） | tiff16、tiff8、JPEG、PNG；色彩空间可选 sRGB、Display P3、ProPhoto；ICC 由宿主生成；另有 DI / Cineon、`.cube` | JPEG（sRGB，或开启广色域时为 P3，质量 95）和 16 位 TIFF（只有 sRGB，嵌入引擎自带的 `io/sRGB.icc`） |
| 预览尺寸 | 预览默认 2560（`Session.swift:778`） | 引擎默认 `preview_long_edge = 1600`（`params.hpp:452`） | 拖动时 1024，松手后 3400（`Model.kt` `LIVE_EDGE`，[ARCHITECTURE.md](ARCHITECTURE.md)） |

## 2. 实测数字 / Measured

方法、输入和完整的 43 行表格见 [PARITY.md](PARITY.md)。参考是用上游源码编出的 `spektralab-host`，它和安卓用同一套补丁，所以比的是“打了补丁的上游”和安卓之间的差别。两边拿到相同的输入字节；颗粒和眩光关闭，其余效果用产品默认值；ΔE00 逐像素计算。测量于 2026-11-03，Debian 13 云主机，lavapipe，运行目录 run1 / run2。

### 2.1 各组最差情况（ΔE00，平均 / p99.9 / 最大）

| 组 | 用例数 | 导出 sRGB | 导出 P3 | 屏幕 sRGB | 屏幕 P3 |
|---|---|---|---|---|---|
| 合成图（上游 parity 目录的 `make_input`） | 4 | 0.000 / 0.004 / 0.01 | 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 1.19 | 0.000 / 0.000 / 1.40 |
| RAW，6 个场景 × 4 种胶片 | 24 | 0.000 / 0.005 / 0.01 | 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 1.17 | 0.000 / 0.000 / 1.40 |
| 整幅 RAW，两边各自解码（iPhone 6s Plus DNG、Canon SL1 CR2） | 2 | 0.000 / 0.004 / 0.01 | 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 1.19 | 0.000 / 0.000 / 1.41 |
| JPEG（sRGB 6 个、Display P3 4 个） | 10 | 0.015 / 0.072 / 0.24 | 0.016 / 0.072 / 0.21 | 0.023 / 0.994 / 1.83 | 0.021 / 1.166 / 1.91 |
| Layer 2（非中性调整） | 3 | 0.000 / 0.006 / 0.01 | 0.000 / 0.006 / 0.01 | 0.000 / 0.000 / 0.96 | 0.000 / 0.000 / 1.18 |

合格线：导出平均 ≤ 0.1、p99.9 ≤ 0.5；JPEG 平均 ≤ 0.05。全部通过。屏幕一列的最大值约等于 1，是 8 位取整造成的个别像素差 1 个码值，平均为 0。

### 2.2 修正前后

| 改动 | 用例 | 修前 平均 / p99.9 / 最大 | 修后 |
|---|---|---|---|
| Layer 2：色彩平衡亮度系数 0.3 改为 0.25；先截断量化再进入输出转换 | `layer2_synth_portra` 导出 sRGB | 0.200 / 3.70 / 13.0 | 0.000 / 0.006 / 0.01 |
| 手机照片在本地转线性 ProPhoto（原先由引擎用 CAT02 解码） | JPEG 各组最差 | 0.360 / 3.18 / 9.57 | 0.016 / 0.072 / 0.24 |

### 2.3 这些数字在视觉上意味着什么

- ΔE00 约 1 是人眼刚能分辨的差别（通常的经验值）。表里导出的差别都在 0.02 以下，比这个小两个数量级。
- 本对照比较的是编码成文件**之前**的像素（宿主出 16 位 TIFF，安卓出 16 位数据）。安卓把 JPEG 写成质量 95，这一步的量化没算进去。据 iOS 移植 RA4 的报告，单是 JPEG 编码本身就有约 0.34 ΔE00（转引自 [ROADMAP.md](ROADMAP.md)，不是本项目测的）。也就是说，在实际导出的 JPEG 文件里，编码带来的差别比移植带来的差别大一个数量级以上。

## 3. 已知并能解释的差别 / Known, explained gaps

### 3.1 macOS 的 RAW 解码和 LibRaw 本来就不同

macOS 版用苹果的 `CIRAWFilter`，其相机色彩矩阵、去马赛克和高光处理都是苹果自己的；Windows / Linux 宿主和安卓用 LibRaw。所以同一个 RAW，在 macOS 上和在其他平台上，进入引擎前的场景数据就不一样。上游自己的 Windows / Linux 版也有同样的差别。

**没测**：没有 Mac。差别有多大我们没有数字，这里不做估计。

要把胶片引擎和解码器分开比，可以给两边同一个 16 位线性 TIFF：宿主把未标记的深位 TIFF 当线性 ProPhoto 读，macOS 版也这样读（`image_io.hpp` 文件头）〔推断：这样只比较引擎部分，解码器的差别被排除〕。

### 3.2 屏幕和导出不同，是上游的设计

屏幕走矩阵加裁切，导出走 CAM16 色域压缩。在色域边缘，两者差得最多：比如 ProPhoto 里饱和的深蓝，印片值 (0, 0, 0.39)，屏幕显示成 sRGB (0, 2, 127)，导出时被压得接近黑色，这类像素的 ΔE00 达到 20–30。

上游宿主自己的屏幕和导出之间的平均差：彩色胶片 0.30–0.89，黑白胶片 0.086–0.092。安卓两条路径之间的差，逐用例和上游相同。（[ROADMAP.md](ROADMAP.md) 里早先写的 0.13–0.22，是第一轮用不同的配对算出来的；以本表为准。）

### 3.3 只有安卓才有的限制

| 限制 | 数字 | 来源 / 处理 |
|---|---|---|
| GPU 单块存储缓冲上限 | Adreno 730 / 740 / 750（小米 12–14）只有 128 MiB，约 8 MP，工作尺寸上限 3072；Adreno 650 是 512 MiB，约 33 MP | [DEVICES.md](DEVICES.md)、`DeviceProfile.kt`。一个图像平面必须放进一块缓冲，超了就无法渲染 |
| 总像素上限 | 4200 万像素 | 另一个移植在 Adreno 上测到 4270 万能出图、4480 万会丢失 GPU 设备（`DeviceProfile.kt:34`） |
| 半尺寸 RAW 解码 | 传感器超过工作尺寸 4 倍时启用，解码内存约降到四分之一 | 补丁 0003。缩小前先做 2×2 合并，不做去马赛克〔推断：最终缩小后的画面与先全尺寸解码再缩小的结果有细微差别，未测〕 |
| 驱动超时 | 高通 kgsl 驱动单次提交超过约 2 秒会重置 GPU | 补丁 0001：IIR 模糊每 512 行提交一次。每个线程负责完整的一行，所以输出逐位相同 |
| 单次计算线程数 | Adreno 超过约 2²⁴ 个线程会写坏图像 | 补丁 0002：拆成不超过 2²⁴ 的分块，起始下标通过 push constant 传入，运算不变 |
| 预览尺寸 | 拖动时 1024，松手后 3400；上游引擎默认 1600，macOS 默认 2560 | 为手机 GPU 选的值。颗粒和光晕按像素计算，小尺寸预览上的观感和全尺寸导出会有差别〔推断〕 |

对照用的宿主也打了这些补丁，两边又都在 lavapipe 上运行，所以第 2 节的数字不能说明补丁本身是否改变输出，也不能说明分块在 Adreno 上是否正确。补丁说明里“输出逐位相同”的结论来自代码分析（[PATCHES.md](PATCHES.md)），没有单独测过。

### 3.4 HyperOS 屏幕色彩模式

小米的“鲜艳”模式会把色彩拉得比 sRGB / P3 标记更饱和，“原色”模式按标记显示〔推断：依据小米对这两种模式的说明，没有在本机测量〕。所以对色要用“原色”模式（[ROADMAP.md](ROADMAP.md) 第 61 行）。这只影响屏幕，不影响导出的文件。

### 3.5 手机 JPEG 自带一条色调曲线

手机 JPEG 已经经过相机的色调曲线，再冲印一次就等于叠了两条曲线。上游对普通图片也是照原样冲印，这是设计如此，不是移植问题。安卓另外提供了两个可选项：高光恢复（`highlightStops`）和 Ultra HDR 增益图；默认都关闭，关闭时的行为和上游一致。建议需要准确颜色时拍 RAW。

### 3.6 上游有、安卓还没有的功能

和宿主的方法列表（`engine/host/main.cpp:431–432`）以及 macOS 的开关（`Model/FeatureFlags.swift`）对比，安卓目前没有：

| 功能 | 上游 | 安卓 |
|---|---|---|
| 蒙版、色调蒙版、放大机控制 | 代码在，macOS 默认关闭（`masks`、`toneMask`、`enlarger` 为 false） | 没有 |
| DI / Cineon 导出、打印 LUT（`.cube`） | 有 | 没有 |
| 半格双拼（`open_pair`） | 有 | 没有 |
| 镜头校正（Lensfun / Core Image） | 有 | 没有 |
| 场景宽容度拟合（`scene_latitude`）、中性点吸管（`pick_neutral`） | 有 | 没有 |
| 导出软打样（`render_proof`） | 有 | 没有 |
| PNG、8 位 TIFF、P3 / ProPhoto 的 TIFF | 有 | 没有（TIFF 只有 16 位 sRGB） |

反过来，安卓新增了：自然饱和度、8 段 HSL、褪色、一键风格、边框、Ultra HDR 增益图、后台处理队列。对照只比较了两边共有的调整项，安卓新增的这几项没有参与比较。

## 4. 尚未验证 / Not yet verified

| 项目 | 现状 | 要做什么 |
|---|---|---|
| Adreno 真机渲染 | 全部数字都来自 lavapipe | 在小米 12–14 上用调试版本把渲染结果和中间数据导出来，用同样的输入，按 `host_parity.py grade` 评分。最少需要一台 Adreno 7xx 手机 |
| Adreno 上的 fma 探测 | lavapipe 上已测（`tools/parity/fma_check.sh`，[PARITY.md](PARITY.md) 第 4.4 节）：上游 7 种启动情况全部符合预期；fma 不精确时改走 Dekker 拆分乘积，不拒绝启动，画面与拆分路径逐位相同；这正是全部对照数字所走的路径 | 真机上读取设备信息页的 `math_mode`，确认显示 “exact Dekker split product”（或 “fused fma”）；再按第一行做一次 Adreno 像素对照 |
| 和 macOS 版直接比较 | 没有 Mac | 一台装有 SpektraLab 的 Mac，导出同一批输入（先用线性 TIFF 排除解码器差别，再用 RAW），按同样方法评分 |
| Windows 版 | 没测 | 上游自带交叉编译脚本（`engine/build-windows-cross.sh`），可以在真实的 Windows GPU 上跑同样的对照 |
| 真实手机照片、HEIC | JPEG 用例是宿主根据 RAW 场景写出来的，不是手机拍的 | 用小米相机拍的 JPEG、HEIC、Ultra HDR 照片作为输入；HEIC 需要宿主的 libheif 路径 |
| JPEG 编码带来的差别 | 没算进对照 | 把安卓实际写出的 JPEG 和宿主 `export_frame` 写出的 JPEG 解码后比较 |
| 颗粒、眩光 | 随机，没有逐像素比较 | 按上游 `parity_metal_vulkan.py` 的 `grain_*` 用例比统计量 |
| 小米 15S Pro（Mali） | 没测 | 同 Adreno |
