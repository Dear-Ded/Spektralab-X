# Spektralab-X 项目总览

这一页是项目的总入口：现状、需求、路线、参考项目、分工和文档索引都从这里找。细节在各专题文档里，本页只写结论和链接。新开的开发会话先读这一页。

更新：2026-11-03 · 当前版本 `0.1.18-alpha` · 下一版 `0.1.19-alpha`

## 1. 项目是什么

- **Spektralab-X**：[SpektraLab](https://github.com/JamesQiu2005/SpektraLab)（JamesQiu2005，GPL-3.0）的安卓衍生版。引擎和胶片数据来自上游与 [spektrafilm](https://github.com/andreavolpato/spektrafilm)（数据 CC BY-SA 4.0）。
- **定位**：针对小米数字系列 10–18（骁龙 / Adreno）优化的光谱胶片冲印应用。开源、永久免费，只在 GitHub Releases 发布。
- **核心目标**：原汁原味输出上游的色彩。新手一键出大师风格；资深用户调整不设上限。
- **开发方式**：独立开发者借助 AI 助手（Claude、GPT、Grok、DeepSeek）开发。小米版稳定后，再立项安卓通用版、Windows 版、iOS 版。
- **仓库**：`Dear-Ded/Spektralab-X`。包名 `com.dearded.spektralab` 不改（改了无法覆盖升级）。

## 2. 技术现状

| 部分 | 现状 | 文档 |
|---|---|---|
| 引擎 | 上游 C++20 引擎，Vulkan 计算后端，固定上游提交 `a46627d`，另加 4 个补丁 | [ARCHITECTURE.md](ARCHITECTURE.md)、[PATCHES.md](PATCHES.md) |
| 解码 | LibRaw 0.22.2；手机照片走 ImageDecoder；Ultra HDR 增益图；DNG 双光源矩阵插值 | — |
| 色彩路径 | 按上游做法：ProPhoto 冲印，屏幕矩阵加裁切，导出 CAM16 压缩；P3 广色域窗口 | [PARITY.md](PARITY.md) |
| 色彩对照 | RAW 与合成图和上游 Linux 宿主逐像素一致（43 组，导出均值 ΔE00 0.000）；手机 JPEG 还差 ≤0.36，正在修 | [PARITY.md](PARITY.md) |
| 编辑 | 胶片 / 调整 / 冲印 / 效果 / 构图五页；调整层（Layer 2）实时重打包；一键风格；预设；边框 15 款 | [UI.md](UI.md) |
| 后台 | “正在处理”队列，按本机耗时估算剩余时间，被杀后续做；实时通知 / 超级岛 | — |
| 界面 | 轻点照片沉浸、保存按钮、居中打开按钮；完整重构按 UI.md 进行中 | [UI.md](UI.md) |
| 构建发布 | GitHub Actions 云端编译签名；`VERSION` 升号且大于最新标签才发布 | [VERIFY.md](VERIFY.md) |
| 远程处理 | 只有接口规范和手机端空接口，正式版再做 | [REMOTE.md](REMOTE.md) |

验证状态：以上全部只经过 CI 编译和云主机测试。真机只试过一次（0.1.18 之前的版本），之后的改动都待真机验证。

## 3. 用户需求清单（按主题）

每条需求的排期和状态以 [ROADMAP.md](ROADMAP.md) 为准。

- **色彩**：原汁原味还原上游；接入相机厂商、色彩实验室、获奖摄影师、手机厂商的调色方案；仿苹果“摄影风格”的二维调色盘；用户可导入自定义色彩方案和胶片数据。
- **编辑**：修图选项更多，全部实时预览，自定义空间不设上限；新手一键风格兜底。
- **界面**：参照标杆、获奖、热门和口碑应用，统一美学标准；跟手、流畅、动效高级；首次使用引导；沉浸式；不自己造轮子。
- **观片台**：多张照片对比挑片；参照 ObscuraFilm 的逐卷查看，按本项目风格改造。
- **边框**：原样还原上游的片边、齿孔、日期戳；再加入更多边框，避免兼容问题。
- **小米适配**：小米 10–18、澎湃OS 4、超级岛、后台调度、小米相机照片。
- **稳定性**：大图不闪退，导出不卡编辑器，被杀不丢东西。
- **远程处理**：用户自己的电脑、服务器或租用显卡做预置后处理或大模型后处理。只留接口，正式版实装。
- **更新提醒**：自动检测新版本并提醒。
- **仓库**：商业项目级装修；醒目提示快速迭代中、欢迎关注点星；访问量和下载量展示；鼓励转载二创但须署名；防倒卖的出处标识；不泄露开发者个人信息。
- **命名**：所有位置统一为 Spektralab-X；指上游时保留 SpektraLab 原名。
- **图标**：深色底、光谱胶片条、扁平（方案 A，已上线）。
- **版本**：能日常使用前都是 alpha，只加修订号，不乱编版本号。

## 4. 开发路线

| 里程碑 | 目标 | 版本 |
|---|---|---|
| M1 真机反馈修复 | 颜色、保存按钮、指引、界面观感 | 0.1.19-alpha |
| M2 色彩还原与风格 | 色差达标；风格、调色盘、导入格式 | 0.1.20-alpha |
| M3 界面与交互 | 按 UI.md 重构；引导；沉浸；观片台 | 0.1.21-alpha |
| M5 架构与稳定性 | 故障恢复、分块导出、内存、解码 | 随各版本 |
| M6 小米深度适配 | 澎湃OS 4、超级岛、保活、小米 DNG | 随各版本 |
| M4 远程处理 | 参考服务端和手机端 | 正式版 |

**0.1.19-alpha 发布条件**：色彩对照（含 JPEG 修正）、导出稳定性第一轮、RAW 解码第一轮、编辑器底部工具栏和沉浸式都合并，CI 通过，CHANGELOG 写好。

## 5. 参考项目

| 类别 | 项目 | 借鉴什么 |
|---|---|---|
| 上游 | SpektraLab（macOS / Windows / Linux 宿主）、spektrafilm | 引擎、色彩路径、默认参数、调整层、边框 |
| 移植 | RA4（iOS）、Zhaoye、thetechgeekko/Spektrafilm-android、SAINTTaiYi/SpektraLab-Android、Negs、spektrafilm-rs、darktable 模块、spektrafilm-ofx | 色差测法、内存计量、GPU 自检、分块导出、事务保存、踩过的坑 |
| 标杆修图 | Halide、Darkroom、Lightroom、VSCO、Snapseed、Photomator、Kino | 布局、手势、滑杆、预设强度、对比 |
| 胶片类 | RNI、Dehancer、Dazz、NOMO、Huji、FilmBox、ObscuraFilm | 胶片选择与命名、模块化效果、逐卷查看 |
| 国内 | 醒图、美图秀秀、黄油相机、一甜、剪映、小米相册 | 二级面板、底部图标栏、用户差评教训 |
| 手机调色 | 苹果摄影风格、小米徕卡、vivo 蔡司、OPPO 哈苏、华为 XMAGE、三星、Pixel | 风格方案、调色盘交互 |
| 组件 | telephoto、haze、Seal（更新检查）、ImageToolbox、Gallery、Miuix | 缩放、模糊、动效参数、网格 |
| 系统 | 小米超级岛文档、Android 15–17 行为变更、澎湃OS 4 | 适配清单 |

各项调研的详细结论记在 [UI.md](UI.md) 附录、[ROADMAP.md](ROADMAP.md) 对应条目和 [REMOTE.md](REMOTE.md)。

## 6. 分工与会话

见 [WORKSTREAMS.md](WORKSTREAMS.md)：每条线的会话、分支、工作区、负责文件、当前任务和会话更换规则。

## 7. 文档索引

| 文档 | 内容 |
|---|---|
| [ROADMAP.md](ROADMAP.md) | 全部需求、里程碑、状态 |
| [WORKSTREAMS.md](WORKSTREAMS.md) | 并行开发分工与会话管理 |
| [UI.md](UI.md) | 界面规范（视觉、页面、动效、无障碍、沉浸、组件） |
| [PARITY.md](PARITY.md) | 色彩对照方法与结果 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 架构 |
| [PATCHES.md](PATCHES.md) | 引擎补丁 |
| [REMOTE.md](REMOTE.md) | 远程处理接口规范 |
| [DEVICES.md](DEVICES.md) | 设备支持 |
| [VERIFY.md](VERIFY.md) | 官方安装包校验 |
| [SAMPLES.md](SAMPLES.md) | 样片的渲染方法 |
| [ABOUT_TEXT.md](ABOUT_TEXT.md) | 应用“关于”页文案 |
