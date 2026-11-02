<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" width="112" alt="SpektraLab icon">

# SpektraLab for Android

**Spectral film and print simulation: develop your photos on a phone.**
Unofficial Android port · tuned for Xiaomi's numbered flagships (Snapdragon / Adreno) · **Alpha**

[简体中文](README.md) | English

[![Pre-release](https://img.shields.io/github/v/release/Dear-Ded/spektralab-android?include_prereleases&label=pre-release&logo=github)](https://github.com/Dear-Ded/spektralab-android/releases)
[![CI](https://github.com/Dear-Ded/spektralab-android/actions/workflows/android.yml/badge.svg)](https://github.com/Dear-Ded/spektralab-android/actions/workflows/android.yml)
[![License](https://img.shields.io/github/license/Dear-Ded/spektralab-android)](LICENSE)
![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-informational)

</div>

> [!WARNING]
> **Alpha.** Every release so far has only been built by CI; **none has been run on a real phone yet**. Expect crashes, wrong colours or slow renders, and please [report them](#reporting-problems) with the in-app diagnostics.

<p align="center"><img src="docs/images/samples/compare_dunes.jpg" width="100%" alt="Sample: neutral decode vs Kodak Portra 400"></p>

## What it is

[SpektraLab](https://github.com/JamesQiu2005/SpektraLab) is a macOS film simulator. It is not a filter: it simulates the whole chain spectrally — scene light, camera and film, development, enlarger and paper, paper development, scan — from measured characteristic curves and spectral sensitivities.

This repository builds SpektraLab's C++ engine for Android with the NDK, runs it on its own Vulkan compute backend, and adds a Jetpack Compose editor. The look comes entirely from upstream; this project handles Android integration, mobile-GPU fixes and the UI.

## Samples

Every image below was rendered by the same engine this app ships, with the app's opening parameters and default settings, and no retouching. Left: the neutral decode (RAW as linear ProPhoto, converted to sRGB, metered to middle grey). Right: the film print.

<table>
<tr><td><img src="docs/images/samples/compare_lake.jpg" alt="Leica Q, Kodak Ektar 100"></td></tr>
<tr><td align="center"><sub>Leica Q (Typ 116) · Kodak Ektar 100: back-lit leaves roll into the shoulder, greens warm up, the boat's blue holds</sub></td></tr>
<tr><td><img src="docs/images/samples/compare_fruit.jpg" alt="Canon EOS R5, Fujifilm Velvia 100"></td></tr>
<tr><td align="center"><sub>Canon EOS R5 · Fujifilm Velvia 100: slide-film contrast and saturation without clipping the tomatoes or the lemon</sub></td></tr>
<tr><td><img src="docs/images/samples/compare_skyline.jpg" alt="Google Pixel 2 XL, Kodak Ektar 100"></td></tr>
<tr><td align="center"><sub>Google Pixel 2 XL · Kodak Ektar 100: phone DNGs go through the same spectral chain</sub></td></tr>
</table>

**One frame, six stocks** (Ricoh GR III):

<p align="center"><img src="docs/images/samples/films_dunes.jpg" width="100%" alt="One frame through six film stocks"></p>

**Halation is simulated, not painted.** CineStill 800T has no anti-halation layer, so bright light reflects off the back of the base and re-exposes the emulsion from behind: red halos around the sky. Portra 400, with its backing, shows none:

<p align="center"><img src="docs/images/samples/detail_temple.jpg" width="100%" alt="Halation detail: neutral, Portra 400, CineStill 800T"></p>

<sub>Sample RAWs from [raw.pixls.us](https://raw.pixls.us/), released under CC0. Tooling and parameters: [`docs/SAMPLES.md`](docs/SAMPLES.md).</sub>

## Features

- **Input:** camera RAW through LibRaw 0.22.2 (DNG, CR3, NEF, ARW, RAF, RW2, ORF…); phone photos (JPEG, HEIC/HEIF, PNG, WebP), with Display P3 photos kept in P3; open from the gallery, files, or Share / Open with.
- **Printing:** colour negative, slide and black-and-white films with matching papers; a strip of live per-film previews of the current photo; exposure, auto exposure, film format; print exposure and Y/M filtration; grain, halation and paper glare; RAW white balance (temperature/tint at decode, upstream's algorithm).
- **Framing:** rotate, flip, centred crop; film edge for 135, half frame, XPan, 645, 6×6, 6×7, 6×9, 6×12, 6×17; date back from the EXIF capture time.
- **Viewing and export:** fast preview while dragging, sharp preview on release, stale renders cancelled; hold to compare with the original; histogram with clipping; 1:1 grain view; 50-step undo/redo; presets; JPEG with EXIF (no GPS) or 16-bit TIFF with an sRGB profile, at full working size or 3400 px.

> [!NOTE]
> The app's interface is Simplified Chinese only for now.

## Devices

Tuned for **Xiaomi 10 – 18** (there is no Xiaomi 16): Snapdragon 865, 888, 8 Gen 1 / 8+ Gen 1, 8 Gen 2, 8 Gen 3, 8 Elite, 8 Elite Gen 5 and Gen 6, all with Adreno GPUs. None is verified yet. Memory, recommended working sizes and known risks per generation are in [docs/DEVICES.md](docs/DEVICES.md). Other arm64 phones on Android 10+ with Vulkan should run it but are not a target.

## Install

Download `SpektraLab-<version>-arm64-v8a.apk` from the latest `v…-alpha` [release](https://github.com/Dear-Ded/spektralab-android/releases). Later versions install over earlier ones. With **Obtainium**, add this repository and enable **Include prereleases**.

Signing certificate SHA-256 (the same for every release):

```
CD:C5:E3:D0:AC:6F:C9:0B:53:E8:8C:57:E0:40:93:36:72:5A:E3:C2:6F:D1:9D:1A:7E:58:51:70:94:5F:6D:E3
```

Each release carries `SHA256SUMS` and a GitHub build-provenance attestation:

```bash
sha256sum -c SHA256SUMS
gh attestation verify SpektraLab-<version>-arm64-v8a.apk -R Dear-Ded/spektralab-android
```

## Reporting problems

Open **Settings → Copy diagnostics** in the app (version, device, SoC, memory, GPU probe, current parameters, last 40 events; no file names or locations) and paste it into a [bug report](https://github.com/Dear-Ded/spektralab-android/issues/new/choose). Questions go to [Discussions](https://github.com/Dear-Ded/spektralab-android/discussions). If a render differs from the macOS app, report it here, not upstream.

## How it works

```
Compose UI (Kotlin) ──JSON──▶ JNI (spektralab_jni.cpp) ──spk_engine.h──▶ SpektraLab engine (C++20)
                                                                          ├─ Vulkan compute (SPIR-V) ─▶ Adreno
                                                                          └─ LibRaw 0.22.2
```

Upstream sources are fetched at a pinned commit by [`scripts/fetch-upstream.sh`](scripts/fetch-upstream.sh). All engine changes live in [`patches/`](patches/) and are described in [docs/PATCHES.md](docs/PATCHES.md). Module layout, threading, render tiers and the memory budget are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Building

JDK 17, Android SDK 35, NDK 27.2, CMake 3.22 and `glslangValidator`:

```bash
bash scripts/fetch-upstream.sh upstream
bash scripts/stage-assets.sh upstream
SPK_UPSTREAM=$PWD/upstream ./gradlew :app:assembleRelease
```

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License and credits

GPL-3.0, like upstream. Bundled third-party content and its licences: the SpektraLab engine and shaders (GPL-3.0, after andreavolpato/spektrafilm), spektrafilm film and paper profiles (CC BY-SA 4.0), LibRaw (LGPL-2.1), volk and stb (MIT), Liberation Sans and DejaVu Sans Mono (OFL 1.1 / Bitstream Vera). README sample RAWs come from raw.pixls.us (CC0 1.0). Licence texts ship in the APK under `assets/engine/licenses/`.

Not affiliated with the SpektraLab project or with Xiaomi. Xiaomi, Snapdragon and Adreno are trademarks of their owners.
