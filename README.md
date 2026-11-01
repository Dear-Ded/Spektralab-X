# SpektraLab for Android

[![android](https://github.com/Dear-Ded/spektralab-android/actions/workflows/android.yml/badge.svg)](https://github.com/Dear-Ded/spektralab-android/actions/workflows/android.yml)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

[SpektraLab](https://github.com/JamesQiu2005/SpektraLab) 光谱胶片与相纸模拟的非官方安卓移植，个人自用，主要在小米 13 Ultra（骁龙 8 Gen 2）上使用。

An unofficial, personal Android port of SpektraLab's spectral film and print simulator. Not affiliated with the SpektraLab project.

> **状态：早期开发中。** 第一版代码已经写完，还在修编译错误，目前没有可用的安装包。

## 功能

- 打开 RAW（LibRaw 0.22.2 解码）或相册里的 JPEG、PNG、HEIF 等图片
- 选择胶片和相纸：彩色负片、黑白负片、反转片（反转片直接扫描，不经过相纸）
- 调节曝光、放大曝光、黄/品红滤镜、颗粒、光晕、眩光
- 拖动滑块时先渲染快速预览，停下后自动出清晰版
- 导出 JPEG 或 16 位 TIFF（内嵌 sRGB 色彩配置）到 `Pictures/SpektraLab`
- 支持从其他应用"分享"或"打开方式"直接打开图片

## 工作方式

渲染引擎直接来自上游，没有改动：

```
Compose 界面 (Kotlin)
   │  JSON 参数
   ▼
JNI 桥接层  app/src/main/cpp/spektralab_jni.cpp
   │  spk_engine.h（上游的 C 接口）
   ▼
SpektraLab 引擎 (C++20)  ── Vulkan 计算着色器 ── 手机 GPU
LibRaw (RAW 解码)
```

- 引擎、着色器和胶片数据取自上游 `Windows_Linux` 分支的固定提交，见 [`scripts/fetch-upstream.sh`](scripts/fetch-upstream.sh)。本仓库只放安卓相关的代码。
- 引擎用 NDK 编译成 arm64-v8a，走上游的 Vulkan 后端。
- 着色器在编译时转成 SPIR-V，和胶片数据一起打包进 APK，见 [`scripts/stage-assets.sh`](scripts/stage-assets.sh)。
- GPU 单个存储缓冲区有大小上限，超过上限的图片会先缩小。工作尺寸可以在应用设置里调整。

## 安装

编译成功后，APK 会发布在 [Releases](https://github.com/Dear-Ded/spektralab-android/releases) 页面。下载后在手机上直接安装，每个版本可以覆盖安装上一个。

需要安卓 10 或更高版本，以及支持 Vulkan 计算的 arm64 设备。

## 自己编译

GitHub Actions 每次推送都会编译，流程见 [`.github/workflows/android.yml`](.github/workflows/android.yml)。在本地编译需要 JDK 17、Android SDK 35、NDK 27.2 和 `glslangValidator`：

```bash
bash scripts/fetch-upstream.sh upstream
bash scripts/stage-assets.sh upstream
SPK_UPSTREAM=$PWD/upstream gradle :app:assembleRelease
```

没有设置 `SPK_KEYSTORE` 时，会用调试密钥签名。

## 许可与致谢

本仓库的代码以 [GPL-3.0](LICENSE) 发布，和上游一致。APK 里还包含以下第三方内容，许可文本随应用一起打包在 `assets/engine/licenses/`：

| 内容 | 来源 | 许可 |
|---|---|---|
| 渲染引擎和着色器 | [JamesQiu2005/SpektraLab](https://github.com/JamesQiu2005/SpektraLab)，基于 [andreavolpato/spektrafilm](https://github.com/andreavolpato/spektrafilm) | GPL-3.0 |
| 胶片和相纸数据 | Andrea Volpato, spektrafilm | CC BY-SA 4.0 |
| RAW 解码 | [LibRaw](https://www.libraw.org/) 0.22.2 | LGPL-2.1 |
| Vulkan 加载器 | [volk](https://github.com/zeux/volk) | MIT |
| 图像和字体工具 | [stb](https://github.com/nothings/stb) | MIT |
| 日期印字字体 | Liberation Sans、DejaVu Sans Mono | SIL OFL 1.1 / Bitstream Vera |

胶片模拟的效果全部来自上游项目。如果效果和 macOS 版不一样，多半是这个移植的问题，请不要去上游报告。
