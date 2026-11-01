<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" width="112" alt="SpektraLab icon">

# SpektraLab for Android

**光谱胶片与相纸模拟，在手机上冲洗你的照片。**
非官方安卓移植 · 针对小米数字系列（骁龙 / Adreno）优化 · **Alpha**

简体中文 | [English](README.en.md)

[![Pre-release](https://img.shields.io/github/v/release/Dear-Ded/spektralab-android?include_prereleases&label=pre-release&logo=github)](https://github.com/Dear-Ded/spektralab-android/releases)
[![CI](https://github.com/Dear-Ded/spektralab-android/actions/workflows/android.yml/badge.svg)](https://github.com/Dear-Ded/spektralab-android/actions/workflows/android.yml)
[![License](https://img.shields.io/github/license/Dear-Ded/spektralab-android)](LICENSE)
[![Downloads](https://img.shields.io/github/downloads/Dear-Ded/spektralab-android/total?logo=github)](https://github.com/Dear-Ded/spektralab-android/releases)
![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-informational)

</div>

> [!WARNING]
> **Alpha 版本。** 所有版本都只通过了 CI 编译，**还没有在真机上验证过**。可能闪退、颜色不对或速度很慢。遇到问题请按[反馈问题](#反馈问题)提交，附上应用里的诊断信息。

## 这是什么

[SpektraLab](https://github.com/JamesQiu2005/SpektraLab) 是一个 macOS 胶片模拟软件。它不套滤镜，而是按光谱模拟整条冲印链路：场景光 → 相机与胶片 → 负片显影 → 放大机与相纸 → 相纸显影 → 扫描。胶片和相纸数据来自实测的特性曲线和光谱灵敏度。

这个仓库把上游的 C++ 渲染引擎用 NDK 编译到安卓上，走它自带的 Vulkan 计算后端，再配一个 Jetpack Compose 编辑界面。胶片效果全部来自上游；本仓库负责安卓集成、手机 GPU 适配和界面。

## 功能

**输入**
- 相机 RAW：DNG、CR3、NEF、ARW、RAF、RW2、ORF 等，由 LibRaw 0.22.2 解码
- 手机直出照片：JPEG、HEIC/HEIF、PNG、WebP。Display P3 广色域照片按 P3 交给引擎，不先压成 sRGB
- 可以从相册、文件管理器选择，也可以在其他应用里“分享”或“打开方式”直接打开

**冲印**
- 彩色负片、反转片、黑白负片，配对应的相纸；反转片直接扫描，不经过相纸
- 胶片预览条：每种胶片在当前照片上实时渲染一张小样，点一下切换
- 曝光补偿、自动曝光、画幅（影响颗粒和光晕的物理尺度）
- 放大曝光，黄 / 品红滤镜
- 颗粒、光晕（Halation）、相纸眩光，可分别开关和调强度
- RAW 白平衡：色温 / 色调在解码时生效，算法与上游桌面版一致

**构图**
- 旋转、水平翻转、居中裁剪（原始、3:2、4:3、1:1、16:9、65:24 等）
- 胶片边框：135、半格、XPan、645、6×6、6×7、6×9、6×12、6×17，带片基边缘字样和片孔
- 日期戳：读取照片 EXIF 拍摄时间，按日期后背的格式压印

**查看与导出**
- 拖动滑块时先出快速预览，松手后自动渲染清晰版；新的改动会取消还没完成的旧渲染
- 长按预览看原图，直方图带溢出提示，1:1 查看颗粒细节
- 撤销 / 重做，最多 50 步；预设保存胶片、相纸和效果组合
- 导出 JPEG（保留相机、镜头、曝光、时间等 EXIF，不含 GPS）或 16 位 TIFF（内嵌 sRGB 配置），可选全尺寸或 3400 px 分享尺寸

## 设备支持

主要针对**小米数字系列 10 – 18**。这几代全部使用骁龙旗舰芯片和 Adreno GPU，也就是本项目做驱动适配的对象。

| 机型 | 芯片 | GPU | 状态 |
|---|---|---|---|
| 小米 10 系列 | 骁龙 865 | Adreno 650 | 未测试 |
| 小米 11 系列 | 骁龙 888 | Adreno 660 | 未测试 |
| 小米 12 / 12S 系列 | 骁龙 8 Gen 1 / 8+ Gen 1 | Adreno 730 | 未测试 |
| 小米 13 系列 | 骁龙 8 Gen 2 | Adreno 740 | 未测试 |
| 小米 14 系列 | 骁龙 8 Gen 3 | Adreno 750 | 未测试 |
| 小米 15 系列 | 骁龙 8 至尊版 | Adreno 830 | 未测试 |
| 小米 17 系列 | 骁龙 8 至尊版 Gen 5 | Adreno 840 | 未测试 |
| 小米 18 系列 | 骁龙 8 至尊版 Gen 6 | Adreno | 未测试 |

小米没有发布“16”这一代。各机型的内存、推荐工作尺寸和已知风险见 [docs/DEVICES.md](docs/DEVICES.md)。其他 arm64、安卓 10 以上、支持 Vulkan 的手机理论上也能运行，但不在优化范围内。

## 安装

1. 打开 [Releases](https://github.com/Dear-Ded/spektralab-android/releases)，下载最新 `v…-alpha` 版本里的 `SpektraLab-<版本>-arm64-v8a.apk`。
2. 在手机上安装。小米 / HyperOS 第一次安装未知来源应用时，会要求在“安全”里允许。
3. 以后的版本可以直接覆盖安装，编辑预设会保留。

**用 Obtainium 自动更新：** 添加仓库地址 `https://github.com/Dear-Ded/spektralab-android`，并打开 **Include prereleases**（目前所有版本都是预发布，不打开就找不到）。

### 校验安装包

所有版本都用同一个密钥签名。签名证书 SHA-256：

```
CD:C5:E3:D0:AC:6F:C9:0B:53:E8:8C:57:E0:40:93:36:72:5A:E3:C2:6F:D1:9D:1A:7E:58:51:70:94:5F:6D:E3
```

每个版本附带 `SHA256SUMS`，并由 GitHub Actions 生成构建来源证明，可以这样核对：

```bash
sha256sum -c SHA256SUMS
gh attestation verify SpektraLab-0.1.9-alpha-arm64-v8a.apk -R Dear-Ded/spektralab-android
```

## 使用提示

- **工作尺寸**（设置里调整）决定处理和导出的分辨率。默认长边 4096 px，同时受手机可用内存、GPU 单缓冲上限和 4200 万像素的总上限约束。8 GB 内存的机型（如部分小米 10 / 11）建议 3072。
- 手机直出照片已经由相机曝光和调色过，默认关闭自动曝光。想要最接近真实胶片的效果，请在小米相机专业模式里打开 RAW 拍摄。
- 小米“超级 HDR”照片的增益图目前不会用到，只读取普通的那一层画面。

## 反馈问题

1. 打开应用的 **设置 → 复制诊断信息**。里面有版本、机型、芯片、内存、GPU 探测结果、当前参数和最近 20 条事件，不含文件名和位置。
2. 用 [问题反馈模板](https://github.com/Dear-Ded/spektralab-android/issues/new/choose) 提交，把诊断信息贴进去。

使用上的问题请到 [Discussions](https://github.com/Dear-Ded/spektralab-android/discussions)。胶片效果和 macOS 版不一样时，多半是这个移植的问题，请在这里报告，不要去上游。

## 工作方式

```
Compose 界面 (Kotlin)
   │  JSON 参数，单独的引擎线程
   ▼
JNI 桥接层            app/src/main/cpp/spektralab_jni.cpp
   │  spk_engine.h（上游的 C 接口）
   ▼
SpektraLab 引擎 (C++20) ── Vulkan 计算着色器（SPIR-V）── Adreno GPU
LibRaw 0.22.2（RAW 解码）
```

- 上游源码在 CI 里按固定提交下载，见 [`scripts/fetch-upstream.sh`](scripts/fetch-upstream.sh)；本仓库只放安卓相关代码。
- 对上游引擎的修改全部放在 [`patches/`](patches/)，编译前按顺序打上，说明见 [docs/PATCHES.md](docs/PATCHES.md)。主要是针对 Adreno 驱动的问题：模糊分批提交避免 GPU 看门狗、单次计算不超过 2²⁴ 线程避免图像损坏、RAW 半尺寸解码省内存。
- 着色器在编译时转成 SPIR-V，和胶片数据一起打包进 APK，首次启动时解到应用私有目录，见 [`scripts/stage-assets.sh`](scripts/stage-assets.sh)。

## 自己编译

需要 JDK 17、Android SDK 35、NDK 27.2、CMake 3.22 和 `glslangValidator`（Ubuntu 上是 `glslang-tools`）：

```bash
bash scripts/fetch-upstream.sh upstream
bash scripts/stage-assets.sh upstream
SPK_UPSTREAM=$PWD/upstream gradle :app:assembleRelease
```

没有设置 `SPK_KEYSTORE` 时用调试密钥签名，装不上正式版的位置。完整流程见 [`.github/workflows/android.yml`](.github/workflows/android.yml)，贡献方式见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 版本与发布

版本号写在 [`VERSION`](VERSION)，格式为 `主版本.次版本.修订号-alpha`。能日常使用之前都是 alpha，每次发布只加修订号。只有 `VERSION` 改成新版本号时才会发布，更新内容见 [CHANGELOG.md](CHANGELOG.md)。

## 许可与致谢

本仓库代码以 [GPL-3.0](LICENSE) 发布，与上游一致。APK 里还包含以下第三方内容，许可文本随应用打包在 `assets/engine/licenses/`：

| 内容 | 来源 | 许可 |
|---|---|---|
| 渲染引擎和着色器 | [JamesQiu2005/SpektraLab](https://github.com/JamesQiu2005/SpektraLab)，基于 [andreavolpato/spektrafilm](https://github.com/andreavolpato/spektrafilm) | GPL-3.0 |
| 胶片和相纸数据 | Andrea Volpato，spektrafilm | CC BY-SA 4.0 |
| 黑白胶片、相纸配置 | SpektraLab 产品数据 | 见 `Product-profiles-NOTICE.md` |
| RAW 解码 | [LibRaw](https://www.libraw.org/) 0.22.2 | LGPL-2.1 |
| Vulkan 加载器 | [volk](https://github.com/zeux/volk) | MIT |
| 图像和字体工具 | [stb](https://github.com/nothings/stb) | MIT |
| 日期戳和边缘字样字体 | Liberation Sans、DejaVu Sans Mono | SIL OFL 1.1 / Bitstream Vera |

设计上参考了另外两个安卓移植踩过的坑：[SAINTTaiYi/SpektraLab-Android](https://github.com/SAINTTaiYi/SpektraLab-Android) 和 [thetechgeekko/Spektrafilm-android](https://github.com/thetechgeekko/Spektrafilm-android)。

本项目与 SpektraLab 上游、小米公司均无关联。“小米”“骁龙”“Adreno”是各自所有者的商标。
