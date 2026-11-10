# 参与贡献 / Contributing

简体中文 | [English](#english)

欢迎提 issue 和 PR。项目由一位独立开发者维护，回复可能慢一些。中文、英文都可以。

## 最有价值的贡献：真机反馈

目前未大规模真机验证，需要社区反馈：除了开发者自己的手机，各版本只经过 CI 编译和云主机测试。哪怕只有“能打开、能导出”也很有用：

1. 安装最新的 [pre-release](https://github.com/Dear-Ded/Spektralab-X/releases)，打开一张照片并导出。
2. 在应用的 **设置 → 复制诊断信息** 里复制诊断信息（版本、机型、芯片、内存、GPU 探测结果、最近 40 条事件，不含文件名和位置）。
3. 用 [问题反馈模板](https://github.com/Dear-Ded/Spektralab-X/issues/new/choose) 提交。适配情况汇总在 [docs/DEVICES.md](docs/DEVICES.md)。

使用上的问题请到 [Discussions](https://github.com/Dear-Ded/Spektralab-X/discussions)，安全问题按 [SECURITY.md](SECURITY.md) 私下报告。

## 项目结构

```
app/src/main/cpp/        JNI 桥接层（C++20），连接上游引擎的 C 接口 spk_engine.h
app/src/main/java/...    Kotlin + Jetpack Compose 界面
patches/                 对上游引擎的修改（Adreno 兼容、半尺寸 RAW 解码等）
scripts/                 拉取上游源码、打包引擎资源、生成发布说明
tools/                   样片渲染、色彩对照测试
docs/                    文档，索引见 docs/README.md
fastlane/                应用商店描述、图标和每个版本的更新说明
```

上游源码**不在本仓库里**。`scripts/fetch-upstream.sh` 下载固定提交的 SpektraLab，并按文件名顺序应用 `patches/*.patch`。

## 本地编译

需要 JDK 17、Android SDK 36、NDK 27.2、CMake 3.22 和 `glslangValidator`：

```bash
bash scripts/fetch-upstream.sh upstream
bash scripts/stage-assets.sh upstream
SPK_UPSTREAM=$PWD/upstream ./gradlew :app:assembleRelease
```

不想装工具链也可以只推送到自己的 fork，GitHub Actions 会编译并把 APK 放在运行记录的 Artifacts 里。

## 修改引擎

不要直接改 `upstream/` 里的文件，改动会在下次拉取时丢掉。改引擎的流程：

1. 在 `upstream/` 里做修改并编译通过。
2. `upstream/` 是解压的源码包，不是 git 仓库：先在里面 `git init && git add -A && git commit -m base`，改完再用 `git diff` 生成补丁（详见 [docs/PATCHES.md](docs/PATCHES.md)），放进 `patches/`，文件名按序号排列（`0004-简短说明.patch`）。
3. 补丁开头的注释写清楚改了什么、为什么，以及是否影响数值结果。引擎对数值精度要求很严，改动尽量保持结果不变。

## 提交规范

- 一个 PR 只做一件事，提交信息说明改了什么、为什么。
- 用户能看到的改动写进 `CHANGELOG.md`。
- 发布由维护者负责：更新 `VERSION` 为新的显示版本，每次发布都递增独立的 `VERSION_CODE`（包括只改阶段标签），并在 `CHANGELOG.md` 加对应章节和以内部版本码命名的中英文 fastlane 短说明；推送到 `main` 后 CI 自动发布。
- 代码风格跟随所在文件：Kotlin 用官方风格，C++ 用 C++20，注释解释“为什么”。
- 新文件请加上 [docs/ABOUT_TEXT.md](docs/ABOUT_TEXT.md#源码文件头--source-file-header) 里的文件头。
- 不要在代码、文档、提交信息和截图里放个人信息（真实姓名、邮箱、手机号、本机路径）。
- 提交即表示你同意以 GPL-3.0 发布你的贡献，并接受 [NOTICE](NOTICE) 里的附加条款适用于你添加的内容。

## 转载与二次开发

欢迎转载和二次开发。本项目以 GPL-3.0 发布，你可以自由使用、修改和分发，也可以收费分发，本项目不限制这些权利。我们请你做到下面几件事，完整条款见 [NOTICE](NOTICE)：

1. **保留署名**（GPL-3.0 第 7(b) 条）：在“关于”页和文档里保留“Spektralab-X”、本仓库地址 `https://github.com/Dear-Ded/Spektralab-X` 和“Spektralab-X 贡献者”署名，并注明上游 [SpektraLab](https://github.com/JamesQiu2005/SpektraLab) 和 [spektrafilm](https://github.com/andreavolpato/spektrafilm)。也请写上你自己作为二创开发者的署名。
2. **改名换图标**（第 7(c)、7(e) 条）：公开发布的改版请换名字、图标和包名（`applicationId`，现在是 `com.dearded.spektralab`），标明是修改版，不要冒充官方版本。换包名也能避免和官方版签名冲突。
3. **提供源码**（GPL-3.0 第 4–6 条）：分发安装包时同时提供完整对应源码，保留许可证和署名。
4. **胶片数据**：spektrafilm 的胶片和相纸数据以 CC BY-SA 4.0 发布，需署名 Andrea Volpato 并以相同许可共享。

官方版只在本仓库 [Releases](https://github.com/Dear-Ded/Spektralab-X/releases) 发布，永久免费，签名证书和校验方法见 [docs/VERIFY.md](docs/VERIFY.md)。

---

## English

Issues and pull requests are welcome. One independent developer maintains the project, so replies may be slow. Chinese or English are both fine.

**The most useful contribution right now is an on-device report.** The app has not been tested on real phones at scale yet, so community feedback is needed. Install the latest [pre-release](https://github.com/Dear-Ded/Spektralab-X/releases), open and export a photo, copy **Settings → Copy diagnostics** (no file names or locations) and file a [bug report](https://github.com/Dear-Ded/Spektralab-X/issues/new/choose). Even "it opens and exports" helps. Usage questions go to [Discussions](https://github.com/Dear-Ded/Spektralab-X/discussions); security issues follow [SECURITY.md](SECURITY.md).

**Building.** JDK 17, Android SDK 36, NDK 27.2, CMake 3.22 and `glslangValidator`, then the three commands above. Pushing to your own fork also works: GitHub Actions builds the APK and attaches it to the run's Artifacts.

**Changing the engine.** Never edit `upstream/` directly; it is replaced on the next fetch. Make the change there, turn it into a numbered patch in `patches/` as described in [docs/PATCHES.md](docs/PATCHES.md), and explain at the top of the patch what changed, why, and whether numeric results change.

**Rules.** One change per PR. User-visible changes go into `CHANGELOG.md`. The maintainer handles releases (set a new display version in `VERSION`, increment the independent `VERSION_CODE` for every release including label changes, and add a matching `CHANGELOG.md` section and English and Chinese fastlane notes named after that code; CI publishes on a push to `main`). Follow the style of the file you are editing. Add the file header from [docs/ABOUT_TEXT.md](docs/ABOUT_TEXT.md#源码文件头--source-file-header) to new files. Keep personal information (real names, email addresses, phone numbers, local paths) out of code, docs, commit messages and screenshots. By contributing you agree to release your work under GPL-3.0, with the additional terms in [NOTICE](NOTICE) applying to what you add.

**Reposting and derivative works.** Welcome, including paid redistribution, which GPL-3.0 allows and this project does not restrict. Please (1) keep the attribution under GPL-3.0 section 7(b): "Spektralab-X", this repository's address and the credit "Spektralab-X contributors" on the About screen and in the docs, plus upstream [SpektraLab](https://github.com/JamesQiu2005/SpektraLab) and [spektrafilm](https://github.com/andreavolpato/spektrafilm), and add your own credit as the derivative developer; (2) under sections 7(c) and 7(e), give a published modified version its own name, icon and `applicationId` (currently `com.dearded.spektralab`) and do not present it as official; (3) provide the complete corresponding source with every APK, as GPL-3.0 sections 4–6 require; (4) credit Andrea Volpato and share alike for the spektrafilm film and paper data (CC BY-SA 4.0). Full terms: [NOTICE](NOTICE). Official builds are published only on this repository's [Releases](https://github.com/Dear-Ded/Spektralab-X/releases), free; see [docs/VERIFY.md](docs/VERIFY.md).
