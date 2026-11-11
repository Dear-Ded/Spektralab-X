# 文档 / Documentation

Spektralab-X 的所有文档。大部分文档用中文写，标注了 EN 的有英文。
Most documents are in Chinese; those marked EN have English.

## 从这里开始 / Start here

| 文档 | 内容 |
|---|---|
| **[PROJECT.md](PROJECT.md)** | **新读者先看这页。** 项目总览：现状、需求、路线、参考项目、分工和文档索引 · New readers start here (Chinese) |

## 使用 / Using the app

| 文档 | 内容 |
|---|---|
| [项目首页](../README.md) · [README (EN)](../README.en.md) | 介绍、样片、功能、下载、常见问题 |
| [FAQ.md](FAQ.md) | 常见问题：速度、发热、暗红偏紫、屏幕与导出、颗粒、网络权限、核对安装包 · EN |
| [VERIFY.md](VERIFY.md) | 校验安装包：签名证书、SHA256SUMS、构建溯源证明 · EN |
| [DEVICES.md](DEVICES.md) | 小米数字系列 10–18 的芯片、内存、建议工作尺寸、已知风险和 GPU 限制 |
| [XIAOMI_ISLAND.md](XIAOMI_ISLAND.md) | 小米超级岛：官方权限的申请条件、审核步骤和邮件草稿；目前以 Android 标准实时通知为主 |
| [更新记录 / CHANGELOG](../CHANGELOG.md) | 每个版本的改动 |

## 计划 / Plans

| 文档 | 内容 |
|---|---|
| [ROADMAP.md](ROADMAP.md) | 里程碑、验收标准、调研结论 |
| [UI.md](UI.md) | 界面规范（M3 界面重构的依据） |
| [REMOTE.md](REMOTE.md) | 远程处理接口规范草案 v1（正式版提供） |
| [PARITY.md](PARITY.md) | 色彩对照：与上游 Linux 宿主逐像素比较的方法、合格线和结果 |

## 开发 / Development

| 文档 | 内容 |
|---|---|
| [贡献指南 / CONTRIBUTING](../CONTRIBUTING.md) | 编译、改引擎、提交规范、转载与二次开发 · EN |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 模块划分、线程模型、渲染档位、内存预算、生命周期 |
| [PATCHES.md](PATCHES.md) | 对上游引擎的补丁和写新补丁的流程 · EN |
| [ANDROID_ENGINE_ADAPTATION.md](ANDROID_ENGINE_ADAPTATION.md) | 安卓引擎适配：已实现的数值、缓存、HDR、导出修复，云端验证和完整分块渲染的剩余工作；GPT 共创记录 |
| [ENGINE_DEEP_REVIEW_2026-11-09.md](ENGINE_DEEP_REVIEW_2026-11-09.md) | 本轮深审：场景曲线分批、Float 精度、HDR 蒙版、JPEG 尺寸事实，以及内存/取消/全尺寸管线的后续适配 |
| [ENGINE_ANDROID_REASSESSMENT_2026-11-11.md](ENGINE_ANDROID_REASSESSMENT_2026-11-11.md) | 后续版本复核：TIFF/ICC/JNI、上游 HDR、两云证据与执行路线 |
| [ENGINE_EXTERNAL_PLANE_PUBLICATION_2026-11-11.md](ENGINE_EXTERNAL_PLANE_PUBLICATION_2026-11-11.md) | 外存分块完成状态与取消：缺带复现、失败不发布、两云故障注入与 GPU 恢复 |
| [ENGINE_ANDROID_FOLLOWUP_2026-11-11.md](ENGINE_ANDROID_FOLLOWUP_2026-11-11.md) | ASN 校准签名、生产管线失败恢复、外存窗口容量与 CPU payload 准入；双云证据和剩余迁移边界 |
| [UPSTREAM_V2_RESEARCH_2026-11-11.md](UPSTREAM_V2_RESEARCH_2026-11-11.md) | V2.3.2 固定源码研究：minilab 成像变化、Vulkan/HDR 缺口、参数与资源，以及 45 条补丁迁移账 |
| [ENGINE_OPTIMIZATION_ROADMAP_2026-11-09.md](ENGINE_OPTIMIZATION_ROADMAP_2026-11-09.md) | 六阶段引擎优化路线；本轮 CRX 维护、工作源预算、JPEG 外存行转换的实现、两云证据与剩余边界 |
| [DNG_WHITE_XY_2026-11-09.md](DNG_WHITE_XY_2026-11-09.md) | WhiteXY-only DNG 的校准、源白平衡与 FM/Bradford 实现，合成/真实样片对照及 profile 支持边界 |
| [DNG_FLOAT_PRECISION_2026-11-09.md](DNG_FLOAT_PRECISION_2026-11-09.md) | 浮点 DNG 的早期整数化、图像最大值耦合与分数黑电平实证，受限 float LinearRaw 的实现依赖 |
| [UPSTREAM_ECOSYSTEM_REVIEW_2026-11-09.md](UPSTREAM_ECOSYSTEM_REVIEW_2026-11-09.md) | 上游 22 分支、303 个公开 fork 网络、12 个重点衍生源码；LibRaw 维护版/快照、浮点 DNG 数域与验证合同 |
| [RAW_PIPELINE_ADAPTATION_RESEARCH_2026-11-09.md](RAW_PIPELINE_ADAPTATION_RESEARCH_2026-11-09.md) | darktable、RawTherapee、ART、Adobe DNG SDK 和 Android 一手源码调研：显影意图、逐阶段分块合同、导出透明度和实施路线 · EN |
| [NOFILM_ROW_READBACK_2026-11-09.md](NOFILM_ROW_READBACK_2026-11-09.md) | 无胶片 JPEG 分条浮点读回：像素、取消、内存与真机验收边界 · EN |
| [ALPHA1_RELEASE_PREPARATION.md](ALPHA1_RELEASE_PREPARATION.md) | 0.2.00-alpha-1 未发布源码范围、验证入口和剩余条件；当前停止发布准备 |
| [SAMPLES.md](SAMPLES.md) | README 样片的来源、参数和复现方法 · EN |
| [WORKSTREAMS.md](WORKSTREAMS.md) | 并行开发分工：每条线的分支、负责文件、当前任务和会话管理 |

## 许可与署名 / Licence and attribution

| 文档 | 内容 |
|---|---|
| [LICENSE](../LICENSE) | GPL-3.0 全文 · EN |
| [NOTICE](../NOTICE) | 版权声明、GPL-3.0 第 7 条附加条款（署名、名称与图标）、官方渠道、第三方许可 · EN |
| [ABOUT_TEXT.md](ABOUT_TEXT.md) | 应用“关于”页的文案（署名、源码地址、官方渠道） · EN |
| [安全策略 / SECURITY](../SECURITY.md) | 如何私下报告安全问题 · EN |

## 图片 / Images

- [`images/samples/`](images/samples/)：README 样片，由引擎实际渲染，原图来自 raw.pixls.us（CC0）。
- [`images/social-preview.png`](images/social-preview.png)：GitHub 社交预览图（1280 × 640）。
- [`images/make-banners.py`](images/make-banners.py)：从样片生成社交预览图和 fastlane 宣传图（需要 Pillow）。
