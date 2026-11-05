# 并行开发分工 / Workstreams

多条开发线同时进行时，每条线只改自己负责的文件，避免互相覆盖。主会话统一派活、审核、合并到 `main`、推送并看 CI。项目全貌见 [PROJECT.md](PROJECT.md)。

## 规则

- 每条线在自己的 git worktree 和分支上工作，**不切换别人的分支，不推送**。
- 只提交下表里属于自己的文件。要改别人的文件，先写明要改什么，由主会话批准或接手。
- **做完一项就提交一项**，不要攒着。提交说明写清：改了什么、怎么验证的、哪些还没在真机上确认。
- 主会话审核后合并到 `main`，推送，CI 通过才算完成；真机确认另记。
- 合并顺序按依赖：底层（引擎补丁、JNI、解码）先合，界面后合。
- 交付前先 `git rebase main`，冲突自己解决；涉及共用文件的冲突交给主会话。

## 当前分工（0.1.20，2026-11-05 起）

0.1.20 的主线是 UI 2.0，规范见 [UI2.md](UI2.md)。“负责的文件”写的是本线新增或主要改动的文件；没定下来的写“见会话”，由会话在第一次提交时写清楚，再由主会话补进本表。

| 线 | 会话 | 分支 / 工作区 | 负责的文件 | 当前任务 |
|---|---|---|---|---|
| 主会话：派活、审核、发布 | 主会话 | `main` / `wt-main` | `docs/PROJECT.md`、`ROADMAP.md`（与 repo-polish 协作）、`UI.md`、`UI2.md`、`WORKSTREAMS.md`（与 repo-polish 协作）；共用文件的冲突 | 审核合并各线，跑 CI，发布 0.1.20 |
| 截图流水线 | ui-shots | `ui-shots` / `wt-shots` | CI 自动出图的测试与脚本（截图测试、出图工作流里新增的步骤，不改发版逻辑）；具体文件见会话 | 每次合并后自动生成各页面截图，供逐页检查（UI2.md 第 4 节）和 README 使用 |
| 编辑器视觉 | ui-visual | `ui-visual` / `wt-visual` | `Theme.kt`、编辑器的版式、层级、卡片和面板组件（UI2.md 第 1、2 节）；`EditorScreen.kt` 的视觉部分，与 ui-motion 分界见会话 | 编辑器视觉重做：配色、选中状态、字体与间距、卡片、面板 |
| 交互动效 | ui-motion | `ui-motion` / `wt-motion` | 转场、跟手返回、按压反馈与震动（UI2.md 第 3 节）；`SharedTransitionLayout`、`PredictiveBackHandler` 相关代码；具体文件见会话 | 卡片到大图的共享元素、跟手的预测性返回、面板上拉、按压与震动 |
| 其他页面 | ui-screens | `ui-screens` / `wt-screens` | 观片台、设置、正在处理、导入页、更新弹窗的界面文件（`LightTable*.kt`、`QueueSheet.kt`、`ImportSheet.kt`、`UpdateDialog.kt` 等的布局部分） | 按 UI2.md 第 6 节第 5 步给其他页面换装，再按第 4 节逐页检查 |
| 局部蒙版与灰尘 | edit-effects | `edit-effects` / `wt-effects` | 局部蒙版、灰尘效果的新增文件；`Effects.kt`、`layer2.*` 中相关部分；需要时的引擎补丁；具体文件见会话 | 局部蒙版（ROADMAP M2）、效果页加灰尘（USER_VOICES） |
| 平台与导出 | platform-export | `platform-export` / `wt-platform` | HEIF 导出、印样进“正在处理”队列；`app/build.gradle.kts` 的 targetSdk 36、16 KB 页对齐、Baseline Profile；高刷新率请求；具体文件见会话 | HEIF、印样入队、targetSdk 36、16 KB、Baseline Profile、高刷 |
| 小米深度适配 | xiaomi-m6 | `xiaomi-m6` / `wt-xiaomi` | `JobNotifier.kt`（超级岛）、保活引导、温控调度（`DeviceProfile.kt` 相关部分）、`ApplicationExitInfo` 退出诊断；具体文件见会话 | 超级岛、后台保活、温控、退出诊断（ROADMAP M6） |
| RAW 收尾 | raw-m6 | `raw-m6` / `wt-raw6` | `patches/`（解码相关）、`patches/libraw/`、`SourceLoader.kt`、`Photo.kt`、`ExifCopy.kt`；样片回归库的工具与清单 | OpcodeList3（WarpRectilinear、FixVignetteRadial）、小米 HDR 与色彩空间识别、小米 10–18 和主流相机的样片回归库 |
| 无胶片模式 | no-film-default（`local_f88c8f84-1799-477b-bb43-bc6e3bb08235`） | 见会话 | 先登记为：`Model.kt` 默认值、`Catalog`、`EditorViewModel.kt` 打开流程、引擎直出路径；确切范围由该会话报给主会话 | 所有处理默认“无”：风格、胶卷、相纸、胶片效果、调整层、边框的第一项和默认值都是“无”，新照片打开即原图 |
| 仓库装修 | repo-polish | `repo-polish` / `wt-repo` | `README*.md`、`docs/`（不含 PROJECT、UI、UI2、PARITY）、`fastlane/`、`.github/`（不含 workflow 逻辑）、`NOTICE`、“关于”文案；本轮另负责 `ROADMAP.md` 勾选和本页状态表 | 0.1.20 更新说明草稿、`docs/FAQ.md`、ROADMAP 状态，随合并同步 |

`EditorViewModel.kt`、`EditorScreen.kt` 和 `spektralab_jni.cpp` 是共用文件：改动尽量小，只加新函数，不重排已有代码，合并时由主会话处理冲突。四条界面线（ui-visual、ui-motion、ui-screens、ui-shots）动 `EditorScreen.kt` 前先在提交说明里写明改了哪一段。

已暂停：远程处理参考服务端（M4 排到正式版，接口已留，见路线图）。

## 归档记录

### 0.1.19（2026-11-04 发布，会话已全部归档）

| 线 | 会话 | 分支 | 交付 |
|---|---|---|---|
| 色彩对照 | colour-parity | `parity-upstream-host` | 与上游 Linux 宿主逐像素对照（RAW 平均 ΔE00 0.000，手机 JPEG / P3 ≤ 0.016）、fma 探测验证、`scripts/parity.sh`、补丁 0001 改为 256 行、4:4:4 编码核对（[PARITY.md](PARITY.md)） |
| 导出稳定性 | export-stability | `stability-m5` | 画面检查、事务式保存、GPU 设备丢失恢复、TIFF Deflate、导出独立线程、引擎补丁 0005 / 0008、内存分档、极致渲染（4:4:4 JPEG） |
| RAW 解码 | raw-decode → raw-decode-2 | `raw-decode` | 描述符直读、LibRaw 多线程、LibRaw 加固、DNG GainMap（补丁 0006）、LibRaw 0017–0019（索尼 A7 V）、镜头型号读取 |
| 风格库扩充 | styles-library | `styles` | 87 款风格、`.spklook` v1、.cube / HaldCLUT / XMP / spektrafilm / 富士配方导入、胶片方案、俗称搜索 |
| 品牌 logo 与边框 | brand-logos | `brand-logos` | 33 个品牌 logo、只加齿孔、上游片边与日期后背全部字段、30 款新边框（共 50 款）、E.3 兼容规则 |
| 调整工具 | adjust-tools | `adjust-tools` | 曲线、调色盘（每款风格有默认点）、颗粒尺寸与扫描锐化（补丁 0007）、效果页分项强度 |
| 观片台 | light-table | `light-table` | 照片库与侧边文件、网格与放大镜挑片、批量粘贴与导出、胶片小样、逐卷查看、手动装卷、导出印样 |
| 上游调研 | upstream-watch | 只读 | 上游与二创项目新动向，结论写进 ROADMAP 和 PLATFORMS |
| 远程处理服务端 | remote-server | — | 2026-11-03 暂停并归档，接口规范见 [REMOTE.md](REMOTE.md) |

## 会话管理

主会话负责新开、命名、换新和归档：

- **何时换新**：上下文用量超过约 70%、多次答非所问或重复劳动、长时间没有提交也说不清进度时，主会话让它把未提交的工作提交到分支，写一份交接（做了什么、没做什么、下一步），再新开一个同名会话从交接接着做，旧会话归档。
- **命名**：会话名与上表“会话”列一致（英文短名），标题写中文任务名；换新后沿用同一名字。
- **归档**：一条线的任务全部合并且没有后续任务时归档；暂停的线在确认没有残留文件后归档。
- **新会话的任务书**：先读 `docs/PROJECT.md` 和本页，再读本线相关文档，交付标准与本页规则一致。
