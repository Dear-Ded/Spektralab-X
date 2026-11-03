# 并行开发分工 / Workstreams

多条开发线同时进行时，每条线只改自己负责的文件，避免互相覆盖。统一由主会话审核、合并到 `main`、推送并看 CI。

## 规则

- 每条线在自己的 git worktree 和分支上工作，**不切换别人的分支，不推送**。
- 只提交下表里属于自己的文件。要改别人的文件，先写明要改什么，由主会话协调。
- 每完成一轮：在自己的分支提交，说明改了什么、怎么验证的、哪些还没在真机上确认。
- 主会话审核后合并到 `main`，推送，CI 通过才算完成；真机确认另记。
- 合并顺序按依赖：底层（引擎补丁、JNI、解码）先合，界面后合。

## 当前分工

| 线 | 分支 | 负责的文件 | 目标 |
|---|---|---|---|
| 主会话：界面重构与新功能 | `main` | `EditorScreen.kt`、`AdjustPanel.kt`、`Styles.kt`、`Theme.kt`、`QueueSheet.kt`、新增界面文件（观片台、调色盘、引导、更新检测）、`docs/ROADMAP.md`、`docs/UI.md` | 按 [UI.md](UI.md) 重构编辑器；观片台；调色盘；首次引导；更新检测 |
| 色彩对照 | `parity-upstream-host` | `tools/parity/`、`patches/`（只限色彩相关）、`output_transform.*` | 以上游 Linux 宿主为基准逐像素对照，查 FMA 探测 |
| 导出稳定性 | `stability-m5` | `ProcessingQueue.kt`、`ExportService.kt`、`MediaSaver.kt`、`EngineHost.kt`、`tiff16.cpp`、JNI 中导出与恢复相关的函数 | GPU 故障恢复、分块导出、事务式保存、峰值内存计量、导出前画面检查 |
| RAW 解码 | `raw-decode` | `SourceLoader.kt`、`Photo.kt`、`ExifCopy.kt`、`patches/`（只限解码相关）、`cpp/CMakeLists.txt` 中 LibRaw 部分、JNI 中 `openRaw` | 多线程解码、LibRaw 加固、小米 DNG |
| 仓库装修 | `repo-polish` | `README*.md`、`docs/`（不含 ROADMAP、UI、WORKSTREAMS）、`fastlane/`、`.github/`（不含 workflow 逻辑）、`NOTICE`、署名与“关于”文案 | 商业项目级装修、开发中提示、署名与防冒充 |

`EditorViewModel.kt` 和 `spektralab_jni.cpp` 是共用文件：改动尽量小，只加新函数，不重排已有代码，合并时由主会话处理冲突。

已暂停：远程处理参考服务端（M4 排到正式版，见路线图）。
