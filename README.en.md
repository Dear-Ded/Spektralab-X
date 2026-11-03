<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" width="112" alt="Spektralab-X icon">

# Spektralab-X

**Spectral film and print simulation: develop your photos on a phone.**

Your RAW and phone photos are "exposed" onto film from measured characteristic curves and spectral sensitivities, developed, and printed onto paper.<br>
Unofficial Android derivative of SpektraLab · tuned for Xiaomi's numbered flagships (Snapdragon / Adreno) · free and open source

[简体中文](README.md) | English

[![Pre-release](https://img.shields.io/github/v/release/Dear-Ded/spektralab-android?include_prereleases&sort=semver&label=pre-release&logo=github)](https://github.com/Dear-Ded/spektralab-android/releases)
[![CI](https://github.com/Dear-Ded/spektralab-android/actions/workflows/android.yml/badge.svg)](https://github.com/Dear-Ded/spektralab-android/actions/workflows/android.yml)
[![License](https://img.shields.io/github/license/Dear-Ded/spektralab-android)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/Dear-Ded/spektralab-android)](https://github.com/Dear-Ded/spektralab-android/commits/main)
![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-informational)
[![Official build: free forever](https://img.shields.io/badge/Official%20build-free%20forever-E8A24A)](#download-and-verify)

[![downloads](https://img.shields.io/github/downloads/Dear-Ded/spektralab-android/total?label=downloads&logo=github)](https://github.com/Dear-Ded/spektralab-android/releases)
[![latest pre-release](https://img.shields.io/github/downloads-pre/Dear-Ded/spektralab-android/latest/total?label=latest%20pre-release&logo=github)](https://github.com/Dear-Ded/spektralab-android/releases)
[![stars](https://img.shields.io/github/stars/Dear-Ded/spektralab-android?label=stars&style=flat&logo=github)](https://github.com/Dear-Ded/spektralab-android)
[![visits](https://hits.sh/github.com/Dear-Ded/spektralab-android.svg?view=today-total&label=visits)](https://hits.sh/github.com/Dear-Ded/spektralab-android/)

[Note](#a-note-from-the-developer) · [Download](#download-and-verify) · [Samples](#samples) · [Features](#features) · [FAQ](#faq) · [Roadmap](docs/ROADMAP.md) · [Changelog](CHANGELOG.md) · [Docs](docs/README.md)

</div>

> [!IMPORTANT]
> **Under rapid development (alpha).** Updates land almost daily, and more features are on the way: a redesigned editor, more one-tap styles, a light table, colour-look import and more; see the [roadmap](docs/ROADMAP.md). **Star** the repository, or use **Watch → Custom → Releases** to hear about new versions.
>
> Built by one independent developer working with AI assistants (Claude, GPT, Grok and DeepSeek).

> [!WARNING]
> **Not yet verified on a real phone.** Every release so far has only been built by CI. Expect crashes, wrong colours or slow renders, and please [report them](#reporting-problems) with the in-app diagnostics.

<p align="center"><img src="docs/images/samples/compare_dunes.jpg" width="100%" alt="Sample: neutral decode vs Kodak Portra 400"></p>

## A note from the developer

Spektralab-X stands on the shoulders of giants: it is built on JamesQiu2005's [SpektraLab](https://github.com/JamesQiu2005/SpektraLab) and Andrea Volpato's [spektrafilm](https://github.com/andreavolpato/spektrafilm). Without those projects and the years of research behind them, it would not exist.

I'm not a professional programmer, just someone who loves film and wanted to develop good photos on a phone. Everything here, from the first idea through development and endless tuning, was done step by step with the help of AI assistants, and every cost along the way, from tokens to cloud servers, comes out of my own pocket. It has no commercial purpose and will always be free.

That also means it is far from perfect. There will be bugs, it may not run smoothly on your phone, and the colours won't suit everyone's taste. If you find a problem or have a good idea, please open an [issue](https://github.com/Dear-Ded/spektralab-android/issues) or a pull request. I read every one.

If it helps you develop a photo you love, a star is the best encouragement you could give.

## What it is

Spektralab-X is an Android derivative of [SpektraLab](https://github.com/JamesQiu2005/SpektraLab) (JamesQiu2005/SpektraLab) and [spektrafilm](https://github.com/andreavolpato/spektrafilm).

[SpektraLab](https://github.com/JamesQiu2005/SpektraLab) is a macOS film simulator built on the [spektrafilm](https://github.com/andreavolpato/spektrafilm) engine. It is not a filter: it simulates the whole chain spectrally (scene light → camera and film → negative development → enlarger and paper → paper development → scan) from measured characteristic curves and spectral sensitivities.

This repository builds SpektraLab's C++ engine for Android with the NDK, runs it on its own Vulkan compute backend, and adds a Jetpack Compose editor. The look comes entirely from upstream; this project handles Android integration, mobile-GPU fixes and the UI.

- **A physical model, not a LUT.** Grain, halation and paper glare are computed from the physical parameters of each film and paper.
- **The full chain on the phone.** Vulkan compute shaders run on the Adreno GPU, with submissions split and memory capped per device.
- **Offline.** The app requests no network permission and collects no data; exported photos carry no location.

## Samples

Every image below was rendered by the same engine this app ships, at default settings, with no retouching. They date from 0.1.18 and print colour negatives on Kodak Portra Endura; the app now defaults to Kodak Supra Endura, as the desktop app does, so results look slightly different. The samples will be re-rendered for the next release. Left: the neutral decode (RAW as linear ProPhoto, converted to sRGB, metered to middle grey). Right: the film print.

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

**Input**
- Camera RAW through LibRaw 0.22.2: DNG, CR3, NEF, ARW, RAF, RW2, ORF and more; DNGs interpolate between the dual-illuminant matrices at the shot's white balance
- Phone photos: JPEG, HEIC/HEIF, PNG, WebP. Display P3 photos go to the engine as P3; the Ultra HDR gain map and highlight recovery can be turned on in Settings (off by default, as on the desktop)
- Open from the gallery or a file manager, or straight from another app with Share / Open with

**Printing**
- Colour negative, slide, black-and-white and motion-picture films with matching papers; slides are scanned directly, without paper
- Film strip: a live thumbnail of the current photo on every stock, tap to switch
- One-tap styles: 30 styles in six groups (lab scans, negative, slide, cinema, black-and-white, masters), each setting film, paper, print, effects and adjustments together
- Exposure compensation, auto exposure, film format (sets the physical scale of grain and halation), print exposure, yellow / magenta filtration
- Grain, halation and paper glare, each with its own switch and strength
- RAW white balance: temperature / tint applied at decode, with upstream's desktop algorithm
- Post-print adjustments (Adjust tab): white balance, exposure, contrast, highlights, shadows, black/white point, saturation, eight HSL bands, four colour wheels, fade, vignette, previewed live while dragging

**Framing and borders**
- Rotate, flip, centred crop (original, 3:2, 4:3, 1:1, 16:9, 65:24 and more)
- Film edges: 135, half frame, XPan, 645, 6×6, 6×7, 6×9 and more, with edge print and sprocket holes; plus paper borders such as Polaroid, Instax Mini, a parameter strip and gallery white
- Date back: the EXIF capture time imprinted in a date-back style

**Viewing and export**
- A fast preview while dragging, the sharp render on release; new edits cancel stale renders
- Hold to compare with the original, tap the photo to hide the interface, histogram with clipping, 1:1 grain view
- Undo / redo up to 50 steps; presets save film, paper and effect combinations
- Background export queue: the editor frees up at once, jobs resume after the process is killed, progress notifications support Live Update
- Wide-gamut preview on P3 screens; export JPEG (Display P3 or sRGB, with camera, lens, exposure and time EXIF, no GPS) or 16-bit TIFF

> [!NOTE]
> The app's interface is Simplified Chinese only for now.

## Devices

Tuned for **Xiaomi 10 – 18**. Every one of these generations uses a Snapdragon flagship chip with an Adreno GPU, which is what this project adapts the driver handling for.

| Phone | Chip | GPU | Status |
|---|---|---|---|
| Xiaomi 10 series | Snapdragon 865 | Adreno 650 | Untested |
| Xiaomi 11 series | Snapdragon 888 | Adreno 660 | Untested |
| Xiaomi 12 / 12S series | Snapdragon 8 Gen 1 / 8+ Gen 1 | Adreno 730 | Untested |
| Xiaomi 13 series | Snapdragon 8 Gen 2 | Adreno 740 | Untested |
| Xiaomi 14 series | Snapdragon 8 Gen 3 | Adreno 750 | Untested |
| Xiaomi 15 series | Snapdragon 8 Elite | Adreno 830 | Untested |
| Xiaomi 17 series | Snapdragon 8 Elite Gen 5 | Adreno 840 | Untested |
| Xiaomi 18 series | Snapdragon 8 Elite Gen 6 | Adreno | Untested |

There is no Xiaomi 16. Memory, recommended working sizes and known risks per generation are in [docs/DEVICES.md](docs/DEVICES.md) (Chinese). Other arm64 phones on Android 10+ with Vulkan should run it but are not a target.

## Download and verify

<p>
<a href="https://github.com/Dear-Ded/spektralab-android/releases"><img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" height="60" alt="Get it on GitHub"></a>
<a href="https://github.com/ImranR98/Obtainium"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="60" alt="Get it on Obtainium"></a>
</p>

> [!CAUTION]
> **Official builds are published only on this repository's [GitHub Releases](https://github.com/Dear-Ded/spektralab-android/releases), and they are free forever.** If you paid for "Spektralab-X" in an app store, a file-sharing site or a second-hand marketplace, it is not an official build: ask for a refund and check the signature as shown below.

1. Open [Releases](https://github.com/Dear-Ded/spektralab-android/releases) and download `Spektralab-X-<version>-arm64-v8a.apk` from the latest `v…-alpha` release.
2. Install it on the phone. Xiaomi / HyperOS asks you to allow unknown sources under "Security" the first time.
3. Later versions install over earlier ones; your presets are kept.

**Automatic updates with Obtainium:** add `https://github.com/Dear-Ded/spektralab-android` and enable **Include prereleases** (every release is a pre-release for now, so nothing shows up without it).

### Verify the APK

Every official release is signed with the same key. Signing certificate SHA-256 (also in [`scripts/signing-cert.sha256`](scripts/signing-cert.sha256)):

```
CD:C5:E3:D0:AC:6F:C9:0B:53:E8:8C:57:E0:40:93:36:72:5A:E3:C2:6F:D1:9D:1A:7E:58:51:70:94:5F:6D:E3
```

Each release carries `SHA256SUMS` and a GitHub Actions build-provenance attestation, which proves the APK was built from this repository's public source:

```bash
sha256sum -c SHA256SUMS
gh attestation verify Spektralab-X-<version>-arm64-v8a.apk -R Dear-Ded/spektralab-android
```

On the phone, [AppVerifier](https://github.com/soupslurpr/AppVerifier) checks the certificate; on a computer, use `apksigner verify --print-certs`. Step by step: [docs/VERIFY.md](docs/VERIFY.md).

## Tips

- **Working size** (in Settings) sets the processing and export resolution. On first launch it is picked from the GPU (up to 4096 px; 3072 on Xiaomi 12–14), and it is also capped by free memory, the GPU's single-buffer limit and a 42 MP ceiling.
- Phone photos are auto-exposed with the "balanced" meter, like RAW and like the desktop app. They were already graded by the camera, so for the most film-like result shoot RAW in the Xiaomi camera's Pro mode.
- Ultra HDR and Xiaomi "Super HDR" photos open from their base image by default; with "highlight recovery for phone photos" on in Settings, Ultra HDR photos use the gain map.

## FAQ

<details>
<summary><b>How is this different from a film filter or a LUT?</b></summary>

A filter or LUT maps one colour to another. This engine simulates how the emulsion responds to each wavelength, the dye densities after development, and the print made through an enlarger onto paper. Exposure, the colour of the light and the film format all change the result naturally, and grain and halation change with them.
</details>

<details>
<summary><b>Does it cost anything? Where do I get it?</b></summary>

The official build is free forever and published only on this repository's [GitHub Releases](https://github.com/Dear-Ded/spektralab-android/releases). Anything you paid for elsewhere is not an official build; check the signing certificate as described in [Verify the APK](#verify-the-apk).
</details>

<details>
<summary><b>Why only Xiaomi's numbered phones? Will it run on mine?</b></summary>

Vulkan drivers differ a lot between phone GPUs: the same compute work can render, corrupt the image or reset the GPU depending on the driver. Xiaomi 10–18 are all Snapdragon with Adreno, so the driver issues are concentrated and can be handled together. Other arm64 phones on Android 10+ with Vulkan will probably run it, just without specific tuning. Reports are welcome.
</details>

<details>
<summary><b>Do the colours match SpektraLab on macOS?</b></summary>

On a cloud machine, yes. The engine prints in ProPhoto and the screen and export use upstream's own conversion code; compared pixel by pixel with upstream's Linux host in 43 cases, RAW exports differ by a mean ΔE00 of 0.000 (max 0.015). Phone JPEGs still differ by 0.20–0.36; the cause is known and being fixed. Method and numbers: [docs/PARITY.md](docs/PARITY.md). On-device comparisons are still to come. If colours differ from the desktop app, report it here, not upstream.
</details>

<details>
<summary><b>Does it need the internet? Are my photos uploaded?</b></summary>

No. The app requests no network permission, everything is computed on the phone, and no data is collected. Exported JPEGs keep the camera, lens and exposure details but no GPS location.
</details>

<details>
<summary><b>Why is export slow, and why does the phone get warm?</b></summary>

The full spectral chain is a lot of computation; a full-size export can take tens of seconds on older phones. Long exports make the phone throttle, most visibly on the Xiaomi 11 (Snapdragon 888). Lower the working size in Settings, or export at the 3400 px sharing size.
</details>

<details>
<summary><b>Will there be versions for other platforms?</b></summary>

It is planned. Once the Xiaomi-tuned version is stable enough for daily use, separate projects will start for a general Android version, Windows and iOS. These are plans without dates; see the [roadmap](#roadmap) below.
</details>

<details>
<summary><b>Can I repost it, modify it or build on it?</b></summary>

Yes. Publish your full source under GPL-3.0, credit this project, the upstream projects and yourself as the derivative developer, and give your version its own name and icon. Details: [Reposting and derivative works](#reposting-and-derivative-works).
</details>

## Roadmap

Work proceeds by milestone; the full list, acceptance criteria and research notes are in [docs/ROADMAP.md](docs/ROADMAP.md) (Chinese).

- **Near term (0.1.x-alpha):** fixes from on-device feedback, colour fidelity and the style system, a redesigned UI, export stability, deeper Xiaomi integration (Super Island, background keep-alive, Xiaomi DNG).
- **First stable release:** remote processing, sending photos to your own computer or server for post-processing and back to the phone (draft spec: [docs/REMOTE.md](docs/REMOTE.md)).
- **Later plans:** once the Xiaomi-tuned version is stable for daily use, separate projects for a **general Android version**, **Windows** and **iOS**. These are plans, not promises with dates.

## Star history

<a href="https://star-history.com/#Dear-Ded/spektralab-android&Date">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=dear-ded%2Fspektralab-android&type=Date&theme=dark">
  <img src="https://api.star-history.com/svg?repos=dear-ded%2Fspektralab-android&type=Date" width="600" alt="Star history chart">
</picture>
</a>

<sub>Download counts come from GitHub Releases and include every pre-release APK. The visit counter and the star chart are third-party services, [hits.sh](https://hits.sh/) and [star-history.com](https://star-history.com/). On GitHub the images load through GitHub's image proxy; when this page is viewed on another site or mirror, those services receive your IP address.</sub>

## Reporting problems

1. Open **Settings → Copy diagnostics** in the app. It holds the version, device, SoC, memory, GPU probe, current parameters and the last 40 events; no file names or locations.
2. Paste it into a [bug report](https://github.com/Dear-Ded/spektralab-android/issues/new/choose). Chinese or English are both fine.

Usage questions go to [Discussions](https://github.com/Dear-Ded/spektralab-android/discussions). Report security issues privately as described in [SECURITY.md](SECURITY.md).

## Contributing

On-device reports are the most valuable contribution right now, even if all you can say is "it opens and exports". Code, docs and sample images are welcome too; see [CONTRIBUTING.md](CONTRIBUTING.md).

### How it works

```
Compose UI (Kotlin)
   │  JSON parameters, a dedicated engine thread
   ▼
JNI bridge            app/src/main/cpp/spektralab_jni.cpp
   │  spk_engine.h (upstream's C API)
   ▼
SpektraLab engine (C++20) ── Vulkan compute shaders (SPIR-V) ── Adreno GPU
LibRaw 0.22.2 (RAW decoding)
```

- Upstream sources are fetched at a pinned commit by [`scripts/fetch-upstream.sh`](scripts/fetch-upstream.sh); this repository holds only the Android code.
- Every change to the upstream engine lives in [`patches/`](patches/), applied in order before the build, and is described in [docs/PATCHES.md](docs/PATCHES.md).
- Module layout, threading, render tiers and the memory budget are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (Chinese).

### Building

JDK 17, Android SDK 35, NDK 27.2, CMake 3.22 and `glslangValidator` (`glslang-tools` on Ubuntu):

```bash
bash scripts/fetch-upstream.sh upstream
bash scripts/stage-assets.sh upstream
SPK_UPSTREAM=$PWD/upstream ./gradlew :app:assembleRelease
```

Without `SPK_KEYSTORE` the build is debug-signed and will not install over an official release. The full pipeline is in [`.github/workflows/android.yml`](.github/workflows/android.yml).

The version lives in [`VERSION`](VERSION) as `major.minor.patch-alpha`; until the app is ready for daily use, each release bumps only the patch number. Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## Reposting and derivative works

**Reposts, modified versions and derivative projects are welcome.** This project is released under GPL-3.0: you may use, study, modify and redistribute it, including for a fee. Those are rights GPL grants, and this project does not restrict them. We only ask for the following; the first three are GPL-3.0's own requirements or additional terms permitted by its section 7, and the full terms are in [NOTICE](NOTICE):

1. **Keep the attribution** (GPL-3.0 section 7(b)): in the About screen and the documentation of a derivative version, keep the original project name "Spektralab-X", this repository's address `https://github.com/Dear-Ded/spektralab-android` and the credit "Spektralab-X contributors", and credit the upstream [SpektraLab](https://github.com/JamesQiu2005/SpektraLab) and [spektrafilm](https://github.com/andreavolpato/spektrafilm).
2. **Use your own name and icon** (sections 7(c) and 7(e)): this project grants no trademark rights to the name "Spektralab-X" or its icon. A modified version you publish should have its own name, icon and package name, say "modified from Spektralab-X", and not present itself as the official build.
3. **Provide the source** (GPL-3.0 sections 4–6): whoever distributes an APK must also provide the complete corresponding source, keep the licence and the attributions, and release their changes under GPL-3.0.
4. **Film data:** the film and paper profiles are CC BY-SA 4.0; redistributing them requires crediting spektrafilm (Andrea Volpato) and sharing under the same licence.

When reposting in an article or video, a line such as "Source: Spektralab-X (github.com/Dear-Ded/spektralab-android)" is enough; a derivative project should also carry your own credit as its developer.

## License and credits

The code in this repository is released under [GPL-3.0](LICENSE), like upstream, with the additional terms in [NOTICE](NOTICE). The APK also contains the following third-party content; the licence texts ship in the app under `assets/engine/licenses/`:

| Content | Source | Licence |
|---|---|---|
| Rendering engine and shaders | [JamesQiu2005/SpektraLab](https://github.com/JamesQiu2005/SpektraLab), based on [andreavolpato/spektrafilm](https://github.com/andreavolpato/spektrafilm) | GPL-3.0 |
| Film and paper profiles | Andrea Volpato, spektrafilm | CC BY-SA 4.0 |
| Black-and-white film and paper profiles | SpektraLab product data | see `Product-profiles-NOTICE.md` |
| RAW decoding | [LibRaw](https://www.libraw.org/) 0.22.2 | LGPL-2.1 |
| Vulkan loader | [volk](https://github.com/zeux/volk) | MIT |
| Image and font utilities | [stb](https://github.com/nothings/stb) | MIT |
| README sample RAWs | [raw.pixls.us](https://raw.pixls.us/) | CC0 1.0 |
| Date-back and edge-print fonts | Liberation Sans, DejaVu Sans Mono | SIL OFL 1.1 / Bitstream Vera |

The design draws on lessons from two other Android ports: [SAINTTaiYi/SpektraLab-Android](https://github.com/SAINTTaiYi/SpektraLab-Android) and [thetechgeekko/Spektrafilm-android](https://github.com/thetechgeekko/Spektrafilm-android).

Not affiliated with the SpektraLab project or with Xiaomi. The name "SpektraLab" is managed by the upstream project; Xiaomi, Snapdragon and Adreno are trademarks of their owners.

<div align="center">

**Find it interesting? Give it a ⭐ Star so more people can find it.**

</div>
