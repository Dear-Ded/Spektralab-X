# 参与贡献 / Contributing

欢迎提 issue 和 PR。维护者人手有限，回复可能慢一些。

## 项目结构

```
app/src/main/cpp/        JNI 桥接层（C++20），连接上游引擎的 C 接口 spk_engine.h
app/src/main/java/...    Kotlin + Jetpack Compose 界面
patches/                 对上游引擎的修改（Adreno 兼容、半尺寸 RAW 解码等）
scripts/                 拉取上游源码、打包引擎资源、生成发布说明
docs/DEVICES.md          设备适配说明
```

上游源码**不在本仓库里**。`scripts/fetch-upstream.sh` 下载固定提交的 SpektraLab，并按文件名顺序应用 `patches/*.patch`。

## 本地编译

需要 JDK 17、Android SDK 35、NDK 27.2、CMake 3.22 和 `glslangValidator`：

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

- 一个 PR 只做一件事。
- 用户能看到的改动写进 `CHANGELOG.md`。
- 发布由维护者负责：改 `VERSION`（只加修订号）并在 `CHANGELOG.md` 加一节，推送后 CI 自动发布。
- 代码风格跟随所在文件：Kotlin 用官方风格，C++ 用 C++20，注释解释“为什么”。
- 提交即表示你同意以 GPL-3.0 发布你的贡献。

## 反馈真机问题

真机反馈是目前最有价值的贡献。请用 Bug 模板，附上应用里 **设置 → 复制诊断信息** 的内容。适配情况汇总在 [docs/DEVICES.md](docs/DEVICES.md)。
