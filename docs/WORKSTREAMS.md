# 并行开发分工 / Workstreams

多条开发线同时进行时，每条线只改自己负责的文件，避免互相覆盖。主会话统一派活、审核、合并到 `main`、推送并看 CI。项目全貌见 [PROJECT.md](PROJECT.md)。

## 规则

- 每条线在自己的 git worktree 和分支上工作，**不切换别人的分支，不推送**。
- 只提交下表里属于自己的文件。要改别人的文件，先写明要改什么，由主会话批准或接手。
- **做完一项就提交一项**，不要攒着。提交说明写清：改了什么、怎么验证的、哪些还没在真机上确认。
- 主会话审核后合并到 `main`，推送，CI 通过才算完成；真机确认另记。
- 合并顺序按依赖：底层（引擎补丁、JNI、解码）先合，界面后合。
- 交付前先 `git rebase main`，冲突自己解决；涉及共用文件的冲突交给主会话。

## 当前分工

| 线 | 会话 | 分支 / 工作区 | 负责的文件 | 当前任务 |
|---|---|---|---|---|
| 主会话：界面与新功能 | 主会话 | `main` / `wt-main` | `EditorScreen.kt`（边框区除外）、`AdjustPanel.kt`、`Theme.kt`、`QueueSheet.kt`、`Immersive.kt`、`CategoryBar.kt`、`TopBarIcons.kt`、引导与更新检测新文件、`docs/PROJECT.md`、`ROADMAP.md`、`UI.md`、`WORKSTREAMS.md` | 进行中：界面重构收尾、0.1.19 发布。已合并：首次引导、应用内更新、关于页定稿、新照片的胶片和相纸、被杀后回到原照片、文件选择器记住文件夹、显存实测（2026-11-04） |
| 色彩对照 | colour-parity | `parity-upstream-host` / `spektralab-android` | `tools/parity/`、`docs/PARITY.md`、`output_transform.*`、`patches/`（色彩相关）、本轮另允许 JNI 的 `openBitmap` / `openBitmapHdr` 输入解码 | 进行中。已完成：JPEG/P3 输入改按上游换算（均值 ≤ 0.016）、lavapipe 上的 fma 探测验证、`scripts/parity.sh`、Pixel GainMap DNG 对照用例；待做：Adreno 真机对照 |
| 导出稳定性 | export-stability | `stability-m5` / `wt-stability` | `ProcessingQueue.kt`、`ExportService.kt`、`MediaSaver.kt`、`EngineHost.kt`、`tiff16.cpp`、JNI 中导出与恢复相关函数 | 进行中。已合并：画面检查、TIFF Deflate、导出独立线程（ExportThread）、引擎补丁 0005、极致渲染（整帧路径）；应用侧分条渲染排到正式版 |
| RAW 解码 | raw-decode-2（`local_5a8e66c8-5ae9-4993-a795-1bca201b28ad`，2026-11-03 接替 raw-decode：多轮无提交） | `raw-decode` / `wt-raw` | `SourceLoader.kt`、`Photo.kt`、`ExifCopy.kt`、`patches/`（解码相关）、`cpp/CMakeLists.txt` 的 LibRaw 部分、JNI 的 `openRaw`；本轮另允许 `EditorViewModel.kt`、`ProcessingQueue.kt` 各一处 RAW 打开调用 | 进行中：第 4 项小米 DNG。已合并：描述符直读、多线程解码、LibRaw 加固、DNG GainMap（引擎补丁 0006）、LibRaw 0017 / 0018 |
| 仓库装修 | repo-polish | `repo-polish` / `wt-repo` | `README*.md`、`docs/`（不含 PROJECT、ROADMAP、UI、WORKSTREAMS、PARITY）、`fastlane/`、`.github/`（不含 workflow 逻辑）、`NOTICE`、署名与“关于”文案 | 持续进行：随 main 合入同步 0.1.19 更新说明、README 功能列表、PLATFORMS.md 和商店文案 |
| 风格库扩充 | styles-library | `styles` / `wt-styles` | `Styles.kt`、`app/src/main/assets/styles/`、`docs/STYLES.md`、新增导入与 `.spklook` 文件、manifest 的导入 intent（单独提交） | 进行中。已合并：87 款风格、`.spklook` v1、.cube/HaldCLUT、XMP / spektrafilm / 富士配方导入、胶片方案、导入界面（设置 → 我的风格，以及从其他应用打开 / 分享） |
| 品牌 logo 与边框 | brand-logos | `brand-logos` / `wt-logos` | `assets/logos/`、`Frames.kt`、`Perforations.kt`、`Model.kt` 的边框与片边字段、`EditorScreen.kt` 构图页边框区、`NOTICE` 商标声明段 | 进行中：30 款新边框。已合并：33 个品牌 logo、只加齿孔、上游片边与日期后背全部字段 |
| 调整工具 | adjust-tools | 见会话 | `layer2.*`、`Look.kt`、`AdjustPanel.kt` 调整页内容、新增调色盘与曲线文件 | 进行中。已合并：曲线（RGB / 红 / 绿 / 蓝）、调色盘、颗粒尺寸与扫描锐化（引擎补丁 0007）、效果页分项强度；待做：HSL 补全 |
| 观片台 | light-table | 见会话 | 新增观片台文件、Room 索引、侧边文件 | 进行中。已合并：照片库与侧边文件、网格挑片、放大镜挑片与星级、批量粘贴与导出、胶片小样；待做：逐卷查看 |
| 上游调研 | upstream-watch | 只读，不建分支 | 不改仓库文件，结论交主会话写入文档 | 新线（2026-11-04）：跟踪上游 SpektraLab、spektrafilm 和其他二创项目的新动向，判断哪些值得移植 |

`EditorViewModel.kt` 和 `spektralab_jni.cpp` 是共用文件：改动尽量小，只加新函数，不重排已有代码，合并时由主会话处理冲突。

已暂停：远程处理参考服务端（M4 排到正式版，见路线图）。会话 remote-server 已归档（2026-11-03）。

## 会话管理

主会话负责新开、命名、换新和归档：

- **何时换新**：上下文用量超过约 70%、多次答非所问或重复劳动、长时间没有提交也说不清进度时，主会话让它把未提交的工作提交到分支，写一份交接（做了什么、没做什么、下一步），再新开一个同名会话从交接接着做，旧会话归档。
- **命名**：会话名与上表“会话”列一致（英文短名），标题写中文任务名；换新后沿用同一名字。
- **归档**：一条线的任务全部合并且没有后续任务时归档；暂停的线在确认没有残留文件后归档。
- **新会话的任务书**：先读 `docs/PROJECT.md` 和本页，再读本线相关文档，交付标准与本页规则一致。
