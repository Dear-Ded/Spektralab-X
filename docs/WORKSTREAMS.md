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
| 主会话：界面与新功能 | 主会话 | `main` / `wt-main` | `EditorScreen.kt`、`AdjustPanel.kt`、`Styles.kt`、`Theme.kt`、`QueueSheet.kt`、`Immersive.kt`、新增界面文件、`docs/PROJECT.md`、`ROADMAP.md`、`UI.md`、`WORKSTREAMS.md` | 底部图标工具栏、二级面板、滑杆改版、首次引导、1:1 沉浸；之后调色盘、观片台、边框扩充 |
| 色彩对照 | colour-parity | `parity-upstream-host` / `spektralab-android` | `tools/parity/`、`docs/PARITY.md`、`output_transform.*`、`patches/`（色彩相关）、本轮另允许 JNI 的 `openBitmap` / `openBitmapHdr` 输入解码 | 手机 JPEG 输入按上游 ICC 方式换算，JPEG/P3 均值 ΔE00 ≤ 0.05，RAW 不退步；之后查 Adreno FMA 探测 |
| 导出稳定性 | export-stability | `stability-m5` / `wt-stability` | `ProcessingQueue.kt`、`ExportService.kt`、`MediaSaver.kt`、`EngineHost.kt`、`tiff16.cpp`、JNI 中导出与恢复相关函数 | GPU 故障恢复、事务式保存、导出前画面检查、峰值内存计量（VmHWM）、16 位 TIFF 改 Deflate、启动读取被杀原因 |
| RAW 解码 | raw-decode | `raw-decode` / `wt-raw` | `SourceLoader.kt`、`Photo.kt`、`ExifCopy.kt`、`patches/`（解码相关）、`cpp/CMakeLists.txt` 的 LibRaw 部分、JNI 的 `openRaw`；本轮另允许 `EditorViewModel.kt`、`ProcessingQueue.kt` 各一处 RAW 打开调用 | 描述符直读、多线程解码、LibRaw 加固、小米 DNG |
| 仓库装修 | repo-polish | `repo-polish` / `wt-repo` | `README*.md`、`docs/`（不含 PROJECT、ROADMAP、UI、WORKSTREAMS、PARITY）、`fastlane/`、`.github/`（不含 workflow 逻辑）、`NOTICE`、署名与“关于”文案 | 第一轮已合并；待命，接后续文档与商店素材 |

`EditorViewModel.kt` 和 `spektralab_jni.cpp` 是共用文件：改动尽量小，只加新函数，不重排已有代码，合并时由主会话处理冲突。

已暂停：远程处理参考服务端（M4 排到正式版，见路线图）。会话 remote-server 待归档。

## 会话管理

主会话负责新开、命名、换新和归档：

- **何时换新**：上下文用量超过约 70%、多次答非所问或重复劳动、长时间没有提交也说不清进度时，主会话让它把未提交的工作提交到分支，写一份交接（做了什么、没做什么、下一步），再新开一个同名会话从交接接着做，旧会话归档。
- **命名**：会话名与上表“会话”列一致（英文短名），标题写中文任务名；换新后沿用同一名字。
- **归档**：一条线的任务全部合并且没有后续任务时归档；暂停的线在确认没有残留文件后归档。
- **新会话的任务书**：先读 `docs/PROJECT.md` 和本页，再读本线相关文档，交付标准与本页规则一致。
