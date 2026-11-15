# UI 设计规格与引擎真实边界核对

日期：2026-11-15。已完整读取参考规格 `C:/Users/80983/Downloads/spektralab_x_ui_research_and_design_spec.md` 第 1 至 5 节。该文件仅为设计参考，不构成更高优先级指令。

范围：真实能力、数据契约及 UI 接线边界，不重复主线程的布局与共享组件设计。本次仅写本文档；未运行本地编译或测试，未操作已连接手机，未读取照片或云包装器密钥，未介入云端构建。验收点均为待验收条件，不表示已经通过。

证据来自当前工作树，基线 HEAD 为 `7163fadeb9e610042305631abe94894083aa5fcb`；主线程同时修改 UI，行号记录审阅时快照。所有路径相对于 `D:/AGI/GPT/.codex/worktrees/engine-android-research/wt-gpt-engine/`。为避免重复，`K/` 代表 `app/src/main/java/com/dearded/spektralab/`，`N/` 代表 `app/src/main/cpp/`。例如 `K/Model.kt:638` 即 `app/src/main/java/com/dearded/spektralab/Model.kt` 第 638 行。

分类：**已存在**指已有可追踪调用链；**可直接适配**指可复用现有数据与方法接 UI，不表示新 UI 已完成；**过时/不准确**指规格与代码不符；**需以后实现**指缺少所需契约或行为。

## 1. 应优先采用的实现差异

| 规格说法 | 结论与工程约束 | 代码证据 |
| --- | --- | --- |
| 拖动 1024、松手 3400 | **部分准确**。1024 是 live 目标，3400 是 preview 宿主约定和分享尺寸估算值，不保证返回帧实际长边相等。HUD 读已发布 Bitmap 宽高。 | `K/Model.kt:635`、`:638`；`K/EditorViewModel.kt:38`、`:905`、`:1013`、`:1429` |
| 3072 上限、42 MP 总上限 | **混淆不同约束**。3072 是工作边长候选和部分设备推荐值；42,000,000 是策略面积天花板，实际 cap 还受 storage buffer 与内存限制。 | `K/DeviceProfile.kt:28`、`:47`、`:70`、`:86`、`:97` |
| 松手即成功定影 | **不准确**。松手只是调度信号；可能等待导出、失败或被更新版本替代。UiState 尚无完整的成功发布事件供扫描动效使用。 | `K/EditorViewModel.kt:854`、`:905`、`:984`、`:1022`；`K/PreviewPublication.kt:47`、`:50` |
| P3 / Ultra HDR 判断 | **P3 有条件存在，HDR 输入不等于 HDR 显示或输出**。gain map 输入还要求 API 34、实际 gain map 及启用高光恢复；当前输出为 SDR。 | `K/DisplayColor.kt:20`、`:34`；`K/Photo.kt:28`、`:48`；`K/Model.kt:630` |
| 队列统一 JPEG 4:4:4 Q98 | **以偏概全**。原生极致路径可以；普通 Bitmap JPEG 为 Q95，极致 Bitmap 兜底也可能退回 Android 编码。 | `K/ProcessingQueue.kt:918`、`:933`、`:982`、`:984`；`N/jpeg444.cpp:83`、`:86` |
| TIFF 与纸框印样完全一致 | **不准确**。TIFF 不经过 Android `FrameSpec.render`，不会烧入其纸框、Logo、签名；须与引擎胶片边缘、日期戳区分。 | `K/ProcessingQueue.kt:846`、`:899`、`:973`；`K/Frames.kt:142` |
| Pad X 冷暖、Y 反差，归零 | **语义不符**。现有 Pad 为浓淡/色彩强度与明暗/影调的组合映射，home 为默认配方，不是 RAW 色温或全部数值 0。 | `K/PalettePad.kt:48`、`:84`；`K/Look.kt:47`、`:86` |
| 曲线用三次 Bezier | **不准确**。应用和 native 使用单调三次 Hermite；显示曲线必须与求值一致。 | `K/Curves.kt:49`、`:86`；`N/tone_curve.hpp:15` |
| 33 品牌、50 边框 | **数量口径需更正**。品牌 ALL 为 37 项含 4 合作款；33 是排除合作款口径，不等于独立图稿数。50 边框含“无”，非空 49。 | `K/Brands.kt:56`、`:75`；`K/Frames.kt:559`、`:560` |
| Aura 与启动从零创建 | **已有实现**。API 33 shader、低版本径向回退、系统关闭动画适配及 Splash/hold/超时/跳过均已有；四色、300ms 淡出是差异。 | `K/Aura.kt:131`、`:154`、`:172`、`:370`；`K/LaunchIntro.kt:68`、`:81`、`:108` |


## 2. 分辨率、调度与成功定影

### 2.1 四个数字

1. **1024 是 live 配置目标，已存在。** `Edit.openingParams()` 写 `preview_long_edge=LIVE_EDGE`，常量 1024（`K/Model.kt:620`、`:635`、`:638`）。渲染循环传 live/preview 字符串档位（`K/EditorViewModel.kt:905`；`K/NativeEngine.kt:99`）。解码缩放从 scale=1 开始，按边长和面积取较小值，小图不会为了徽标补到目标尺寸（`K/SourceLoader.kt:266`；`N/resize.cpp:122`）。
2. **3400 是宿主对 preview 的约定，不是已核实的每帧尺寸保证。** `ExportSize.SHARE` 映射 preview，`SHARE_EDGE=3400` 用于任务像素估算；JNI 注释也称 3400 档（`K/EditorViewModel.kt:38`、`:1070`、`:1429`；`N/spektralab_jni.cpp:351`）。本检出没有 upstream 源码，不能把注释冒充已审阅的上游缩放实现。上游由 `scripts/fetch-upstream.sh:13` 固定提交、`:28` 开始应用补丁；`N/CMakeLists.txt:8`、`:78` 从外部加入。当前能证明宿主发了档位，不能补写“固定 3400 已完成”的实现。
3. **3072 是工作边长候选/推荐值之一。** 候选 2048/3072/4096/6144，首次推荐最多 4096，并按设备面积约束选择；使用已保存的 working_edge 或推荐值（`K/DeviceProfile.kt:70`、`:73`、`:97`；`K/EditorViewModel.kt:203`）。不是 preview 的实际尺寸字段，也不是所有 Adreno 的统一硬上限。
4. **42 MP 是应用策略面积天花板。** maxPixels 综合 storageRange/16、内存预算和 42,000,000；内存估算每像素 100 bytes，预留 1.5 GiB。未知 storage buffer 按保守值，探测结果按 GPU/driver 缓存（`K/DeviceProfile.kt:28`、`:51`、`:54`、`:57`、`:60`、`:86`；`K/StorageProbe.kt:26`）。胶片边缘路径还预留约 1.6 倍面积（`K/EditorViewModel.kt:335`、`:537`；`K/ProcessingQueue.kt:607`、`:778`）。应用层纸框之后可增大画布，所以不能把这个 cap 宣称为所有最终文件的严格像素乘积上限。

“全尺寸”是当前工作尺寸。极致 JPEG 以 workingEdge=0 优先源尺寸，仍带实际 maxPixels，并可因内存/设备失败降档（`K/EditorViewModel.kt:1102`、`:1114`；`K/ProcessingQueue.kt:613`；`K/ExportRetry.kt:10`）。不能将“极致”标为保证原尺寸、无损或已达到 42 MP。

### 2.2 连续控件接线

- Edit 字段变化走 `vm.update(ui.edit.copy(...))`；Look 字段更新 edit.look 后走同一入口。拖动开始调用 `vm.setInteracting(true)`，结束、取消和 pointer coroutine 的 finally 释放 false；不能只更新局部读数。方法见 `K/EditorViewModel.kt:537`、`:854`。
- 曲线和 Pad 已传 `vm::setInteracting`（`K/AdjustPanel.kt:156`、`:157`）；物理滑块有连接（`K/EditorScreen.kt:873`、`:884`、`:952`）。但 Adj() 只传 onChange，未传交互状态（`K/AdjustPanel.kt:221`）。规格要求双阶段回调是接线目标，不能宣称全控件已具备。
- renderLoop 等下一次值/松手；拖动静止约 1200ms 也会进入 preview；开关/预设可立即进入 preview，后者还可能等导出让出 GPU（`K/EditorViewModel.kt:905`、`:912`、`:922`）。不要另造独立的“700ms 定影引擎”；700ms 是历史合并窗口。
- 仅输出变换的 Look 等改动可走 repackLoop，复用既有 tier 缓存并发布新 Bitmap，不一定重跑胶片链（`K/Model.kt:220`；`K/EditorViewModel.kt:595`、`:870`、`:883`）。不能要求每次调色必先 live 再 preview。
- RAW 白平衡是例外：拖动先本地暂存，结束时 setWhiteBalance(k,tint) 重开源文件，不能每个 move 解 RAW（`K/EditorScreen.kt:1318`、`:1328`、`:1336`；`K/EditorViewModel.kt:278`）。

### 2.3 HUD 和 350ms 微光

**可直接适配：** 用 ui.image 实际 width/height 显示已呈现尺寸，imageTier 区分 live/preview/full；ui.rendering 表示正在处理，ui.busy 表示打开等阻塞操作。源原始尺寸与工作尺寸分别来自 SourceInfo.sourceWidth/sourceHeight 和 width/height（`K/Model.kt:642`；`K/EditorViewModel.kt:56`、`:62`、`:71`、`:416`）。

**需以后实现：** 精确的“一次成功定影”发布契约。PreviewFrame 已记录 source/session、intent/revision、tier、output、sequence，publish 拒绝错误源、过时或降质帧（`K/PreviewPublication.kt:3`、`:5`、`:47`、`:50`、`:58`）。renderOnce 只有 shown != null 才把图放入 UiState，但 finally 在失败时也会清 rendering（`K/EditorViewModel.kt:984`、`:1022`、`:1030`、`:1031`）。松手、rendering=false、imageTier=preview、或某个固定边长都不能单独当完成事件。

最小接线契约：在**帧通过发布检查之后**暴露只读 source generation、intent/revision、sequence、tier、真实尺寸和输出色域；UI 仅对当前源/当前目标版本的首次成功 preview/full 播放完成微光并按序列去重。Look 重打包按实际接受的新版本处理。allowStaleLive=true 允许陈旧 live 保持响应，不能触发成功定影。若本轮只改 UI、不补发布契约，就保留真实处理状态，不伪造精确定影反馈。

验收点：小图不放大冒充 1024/3400；3072 工作图不显示 3400 实际尺寸；连续 A/B 修改、切图、取消、失败、导出占用、Look 重打包均不误报旧帧完成；结束/取消后交互标志均释放。

## 3. 色域、输入精度与 Ultra HDR

| 能力 | 边界及 UI 连接 | 证据 |
| --- | --- | --- |
| Display P3 显示 | **已存在且有条件**。窗口按 isScreenWideColorGamut 选择 P3/sRGB；引擎转换后 Bitmap 打对应标签。setColorSpace 只是标签，不能把 sRGB 内容变成 P3；JPEG 的 wideGamut 偏好也不是窗口状态。 | `K/DisplayColor.kt:20`、`:34`；`K/EditorViewModel.kt:65`、`:406`；`N/spektralab_jni.cpp:122` |
| 普通照片输入 | 软件 ImageDecoder 按源宽色域选 P3/sRGB；高精度 SDR 可保留 RGBA_F16。不能宣称任意输入 ICC 均原样保留。 | `K/SourceLoader.kt:124`、`:143`、`:151`、`:163`、`:172` |
| Ultra HDR/gain map 输入 | **条件支持**。API 34+ 读取 gain map，实际路由还要求 highlightStops>0；打开失败退回 SDR。徽标看打开后 source.inputPrecision，rgba8_gainmap 才证明走了此路，不能只看来源。 | `K/Photo.kt:28`、`:48`、`:73`；`N/spektralab_jni.cpp:1099`、`:1237`；`K/EditorViewModel.kt:432` |
| F16 输入 | **高精度 SDR 已存在**，native V1 为有限 SDR 范围。OpenedPhoto.linear 还可由普通高光恢复置 true，不能作 gain map 成功标志。 | `K/Photo.kt:15`、`:34`、`:55`；`N/spektralab_jni.cpp:983`、`:992` |
| HDR 显示和输出 | **当前链路未实现规格暗示的 HDR 输出**。opening params 明确 extended_dynamic_range=false，窗口只设宽色域，预览打包 ARGB_8888；编码路径无 gain map 重建封装，不可标 HDR 预览、10-bit HEIF 或 Ultra HDR JPEG。 | `K/Model.kt:630`；`K/DisplayColor.kt:20`；`N/spektralab_jni.cpp:382`；`K/HeifExport.kt:40` |
| 显示与导出色域 | 输出变换有 display/export 差别，导出有 CAM16 映射策略、Adobe RGB 分支另有裁切策略；窗口色域、TIFF ICC、JPEG 偏好不能合并成一个开关。 | `N/output_transform.cpp:57`；`K/ProcessingQueue.kt:833`、`:856` |
| proof / 原图 | renderProof 已有，但不含纸框、JPEG 压缩；before 是解码后 sRGB 8-bit 参考，长边目标 1600，不是 RAW/HDR 原始像素直通。 | `K/EditorViewModel.kt:605`、`:612`；`K/EditorScreen.kt:540`；`N/spektralab_jni.cpp:421`、`:432` |
| 直方图 | 64 bins RGB 数据和 HistogramView 已有；编辑器当前用 MonoHistogram。来自显示 Bitmap 的 8-bit 编码值、约 25 万像素抽样，不是 RAW 传感器或线性 HDR 测光。RGB 视觉可直接接既有数据。 | `K/Histogram.kt:26`、`:28`、`:33`、`:45`、`:62`；`K/EditorScreen.kt:671` |

验收点：sRGB 屏不显示 P3 已激活；高光恢复关闭不报 gain map 已应用；F16 SDR 不标 HDR；目标文件色域以实际 ICC 为准；黑色背景不能代替色彩管理。


## 4. 导出格式、尺寸和队列

| 路径 | 已有实现及实际限制 | 证据 |
| --- | --- | --- |
| 普通 JPEG | FULL/SHARE 已有，分享复用 preview tier。普通 Bitmap.compress 为 Q95，不能统一标 Q98 或 4:4:4。JPEG wideGamut 偏好决定目标 P3/sRGB，不改变 SDR 属性。 | `K/EditorViewModel.kt:36`、`:39`、`:1064`；`K/ProcessingQueue.kt:780`、`:984` |
| 原生流式极致 JPEG | extreme、非 HEIF、无应用纸框且 ICC 可用时走原生行编码，质量 98；native 所有分量采样因子 1x1，即 4:4:4。返回 packed 实际宽高。 | `K/ProcessingQueue.kt:920`、`:921`、`:933`、`:937`；`N/jpeg444.cpp:83`、`:86`、`:90` |
| 极致 Bitmap 路径 | 有纸框时先 render Bitmap，再 FrameSpec.render；encode444 需要 ICC 且编码成功，否则可回到 Android Q98 编码，代码有 4:2:0 兜底日志。原生流式失败则进入队列引擎恢复/降档逻辑，不能把两类失败混写。 | `K/ProcessingQueue.kt:680`、`:688`、`:933`、`:955`、`:973`、`:982` |
| 16-bit TIFF | 原生 RGB 16-bit、DEFLATE、ICC 写入已存在；编辑器支持 sRGB/P3/ProPhoto/Adobe RGB。该分支不经过应用纸框渲染。16-bit 文件容器不保证所有源图/上游输入都有 16-bit 原始信息。 | `K/EditorViewModel.kt:42`；`K/ProcessingQueue.kt:846`、`:856`、`:899`；`N/tiff16.cpp:132`、`:160` |
| HEIF | **规格遗漏的已有格式**。需实际设备编码器可用；HeifWriter Q90，输入 ARGB_8888，当前路径按 sRGB；不是 10-bit/HDR HEIF。不可在不支持设备显示可用按钮。 | `K/HeifExport.kt:22`、`:30`、`:40`、`:44`；`K/ProcessingQueue.kt:780`、`:977` |
| 后台队列 | 已有 QUEUED/RUNNING/DONE/FAILED/CANCELLED，任务快照包括 Edit、Look、WB、尺寸、格式、目标色域；有持久化、重试/取消、前台服务和热控制。进度含时间估算且处理时封顶 95%，不是精确 GPU 完成率。 | `K/ProcessingQueue.kt:31`、`:43`、`:187`、`:375`、`:624`、`:1167`；`K/QueueSheet.kt:48`、`:146` |
| Light Table 批量导出 | 目前批量菜单为 JPEG full/share、TIFF full；没有完整编辑器的 HEIF/四种 TIFF 色域入口。批量 enqueue 未传 tiffColorSpace，使用默认 sRGB。 | `K/LightTableBatch.kt:76`；`K/LightTableViewModel.kt:392`；`K/ProcessingQueue.kt:43` |

UI 连接：沿用 `vm.export(format,size,tiffColorSpace)` 和 `vm.exportExtreme()`，队列读现有 jobs、state、phase、progress，取消/重试调用既有方法（`K/EditorViewModel.kt:1049`、`:1054`、`:1055`、`:1064`、`:1102`）。`outPixels` 是提交时估算，不包含全部最终几何/纸框影响；实际输出尺寸以编码结果为准（`K/EditorViewModel.kt:1070`；`K/ProcessingQueue.kt:937`、`:990`）。Job 目前没有可直接供 UI 展示的“实际 JPEG 采样方式”字段，不得根据 extreme 预先承诺本次已经 4:4:4。

验收点：普通 JPEG 不误标 Q98/4:4:4；有/无纸框极致分别确认真实尺寸、ICC、采样与回退；TIFF 明示应用纸框装饰不随该路径输出；HEIF 按设备可用性启用；DONE 只来自文件编码、校验和发布成功，不来自进度计时到头（`K/ProcessingQueue.kt:375`、`:907`）。使用授权的合成样张即可，无需私人照片。

## 5. 物理参数、精修语义与历史

### 5.1 物理链路不是每张图都必经全链

胶片、相纸、扫描及物理参数有真实 JSON/JNI 路径，不能只接一套 LUT UI。但 noFilm/DI、scanFilm、正片、黑白的合法组合不同；Catalog.normalize 负责约束。noFilm 关闭引擎胶片边缘和日期，monoPrint 需黑白胶片与相纸，DI 对适用材料才成立（`K/Model.kt:19`、`:89`、`:102`、`:307`；`N/spektralab_jni.cpp:483`）。规格的全链路是模型概括，不是所有输入都执行每一阶段的保证，也不是 JPEG 恢复真实场景光的证明。

下列为现有 UI 范围，不能换成百分比后丢失单位或改变复位值；native 允许范围可能更宽，应沿用应用当前约束。

| 参数 | 当前范围/默认与门控 | 证据 |
| --- | --- | --- |
| 胶片曝光 / 放大曝光 | 曝光 -4..4 EV 默认 0；放大曝光 0.2..3 倍默认 1，不是 EV。 | `K/EditorScreen.kt:873`、`:884` |
| RAW 曝光 / Look 曝光 | RAW 显影 -8..8 EV；Look 曝光另为 -3..3，属于不同阶段。 | `K/EditorScreen.kt:834`；`K/Look.kt:25` |
| 黄 / 品红滤镜 | UI -1..1，wire 乘 40 CC，scanFilm 时禁用。 | `K/EditorScreen.kt:913`、`:920`；`K/Model.kt:281`、`:307` |
| 胶片幅面 | 半格 24、135 36、645 56、6x7 70、4x5 127，单位 mm；不是输出像素尺寸。 | `K/EditorScreen.kt:1315`；`K/Model.kt:111` |
| 颗粒 / 光晕 / 纸眩光 | 颗粒 0..2 默认 1；光晕 0..4 默认 1；眩光 0..30 默认 1。 | `K/Effects.kt:65`、`:69`、`:74` |
| 颗粒面积 / 分层颗粒 | 面积 0.05..1 平方微米默认 0.2；分层颗粒对黑白门控，不能标“颗粒半径 px”。 | `K/Effects.kt:78`、`:82` |
| 散射 / 耦合 | 散射 0..1 跟随光晕启用；耦合 0..1.5。 | `K/Effects.kt:90`、`:103` |
| 反锐化 | amount 0..2，sigma 0.2..3 px 默认 0.7；不是“锐度百分比”。native wire 确有接线。 | `K/Effects.kt:95`、`:98`；`patches/0007-grain-size-and-unsharp-on-the-wire.patch:15` |
| 负片 gamma / 高光光晕增益 | 当前 UI gamma 0.7..1.4；boost 0..3 EV。 | `K/Effects.kt:110`、`:115` |
| 镜头模糊 / 防光晕层 / 滤镜 | lens blur 0..30 微米；noAntihalation 与黑白相机滤镜均有适用条件。 | `K/Effects.kt:119`、`:123`、`:129` |
| 预闪 / labLevels | 预闪 UI 0..0.3，直接扫描分支禁用；labLevels 也有扫描门控。 | `K/Effects.kt:140`、`:145`；`K/Model.kt:295` |
| RAW 白平衡 | 现有 UI 2000..12000 K、tint ±150；原片为 as-shot，0 是契约标记，不能显示 0K。native 可接受更宽 K 范围不代表应擅自扩大 UI。 | `K/EditorScreen.kt:1328`、`:1336`；`N/raw_white_balance.hpp:13` |

复位取 `Edit()`、`Look()` 或该照片初始/as-shot 状态，并保持已有 normalize 和参数门控。多数物理强度默认 1，双击复位不能统一写 0。新的 PrecisionDial 只替换输入方式，必须保留以上范围、单位、步进语义和更新入口。

### 5.2 精修组件可复用什么

| 规格项 | 状态与接线方法 | 证据 |
| --- | --- | --- |
| 自然饱和度、8 色 HSL | **已存在/可直接适配**。保留 Look 对象与每色 Hue/Sat/Light 的结构，不新增第二套局部模型。 | `K/Look.kt:25`、`:44`、`:133`；`K/AdjustPanel.kt:103` |
| 4 色轮 | master/shadow/mid/highlight 的真实参数已有，但当前交互是分区滑块，不能说图形色轮已完成。新色轮须映射同一组参数、保留亮度与复位。 | `K/Look.kt:40`；`K/AdjustPanel.kt:143` |
| 2D Pad | PalettePad 已有拖动、细调、触觉反馈和 TalkBack；x 是 muted/rich，y 是 airy/deep，Look.resolved 映射多个字段。双击回 home，规格的双指归零需另实现；不能改为 temperature/tint。 | `K/PalettePad.kt:48`、`:64`、`:84`、`:166`；`K/Look.kt:47`、`:86` |
| RGB 曲线 | 多点与 RGB/R/G/B 通道已有，内部还有 luma 行；控制点须严格递增，沿用相同 Hermite 求值，不能为视觉换 Bezier。 | `K/Curves.kt:49`、`:86`、`:149`、`:190`、`:206`；`N/tone_curve.hpp:15` |
| 50 步撤销 | 容量 50 已存在，记录 Edit，约 700ms 邻接变化合并；没有严格的手势 begin/end 事务。长拖停顿可形成多步，不能承诺“一次手势永远一步”。RAW WB 单独重开文件，不能因 Edit 历史存在便宣称 WB 一定可撤销。 | `K/EditHistory.kt:8`、`:19`；`K/EditorViewModel.kt:580`、`:683`、`:695`、`:1424`、`:1425` |

验收点：拨盘、曲线和 Pad 改动能进入相同 Edit/Look wire；复位回真实默认；切 film/scan/noFilm 后门控正确；RAW WB 松手才重解；曲线显示与 native 采样一致；撤销按钮使用真实 undo/redo 数量，不画固定 50 次可用。


## 6. 风格、装饰、比较和观片台

| 规格项 | 核对结论、接线与验收 | 证据 |
| --- | --- | --- |
| 8 组 87 预设 | **已存在**。Style.ALL 静态列表 87：lab 5、camera 25、negative 12、slide 5、bw 11、cine 7、phone 16、photographer 6。UI 还有热门/我的/收藏/最近等组织项，不应算成新增引擎组；可用性应保留 catalog 材料过滤。新 FilmStripBar 接既有选中/导入/真实预览入口。 | `K/Styles.kt:158`、`:257`、`:353`、`:390` |
| 真实预设缩略图 | selectionPreview 用对应配方与 native 渲染，交互/导出/热状态时会退让。缓存或空态可以显示，但不能把静态色块标成已完成的引擎预览。 | `K/EditorViewModel.kt:754`、`:772` |
| 外部导入 | **已有但格式有范围**。.spklook、.cube、合法 PNG Hald、特定 XMP/DNG、可识别 JSON、Fuji 配方 TXT 均有处理；不等于任意 JSON/TXT/XMP 完整兼容。Hald 有尺寸/结构校验，XMP 为近似映射，Adobe CameraProfile 不支持，逐图 WB 不作为通用风格导入。必须沿用导入结果/警告。 | `K/StyleImport.kt:68`、`:138`；`K/PresetImport.kt:52`、`:111`、`:172`、`:188` |
| 50 边框、品牌、日期 | 50 预设含 none；品牌 ALL 为 37 项含 4 合作款。Android 纸框与品牌图案经 FrameSpec，原生胶片边缘与石英日期进 Edit wire；日期受 noFilm/幅面限制，不能对每个边框无条件开启。切胶片边缘可调整裁剪，不是纯视觉描边。 | `K/Frames.kt:142`、`:559`；`K/Brands.kt:56`；`K/Model.kt:458`、`:495`、`:502` |
| 原图比较 | 按住比较已存在；单击画布当前切换 chrome，双击复位缩放。规格要求的“点按锁定比较”不能与单击语义直接混用，需要独立按钮/状态；这部分不能报告已实现。比较时现有代码保留几何但隐藏应用装饰。 | `K/EditorScreen.kt:561`、`:567`、`:570`、`:577`、`:607` |
| 星标、多选、批量贴配方 | **已存在**，沿用 LightTableViewModel 和 Sidecar，不因替换布局重复建库。复制分类为胶片相纸、曝光、RAW WB、效果、Look、边框；WB 仅对 RAW 目标生效，日期按目标照片重建，未知尺寸的边缘裁剪会跳过。 | `K/LightTableViewModel.kt:214`、`:303`、`:323`；`K/FrameStore.kt:311`、`:334`、`:349`、`:366`、`:371` |
| “全部物理配方”批量同步 | **当前不完整**。EFFECTS 复制旧颗粒/光晕/眩光/散射/耦合/面积/锐化字段，但未覆盖 preflash、negativeGamma、highlightBoostEv、lensBlurUm、noAntihalation、cameraFilter、labLevels；LAYER2 整体复制 Look。StyleRecipe 保存范围也不能自动当作 pasteLook 范围。UI 必须描述真实可复制分类，完整新物理字段复制需以后补齐。 | `K/FrameStore.kt:350`、`:358`；`K/StyleRecipe.kt:12` |
| 暖白灯箱开关 | **主线程并行新增，已有源码、待验收**。最终复核时已出现 rememberSaveable 的 backlit、light_table_backlit 偏好、暖白 ground 与按钮切换。不能继续列为完全缺失，也不能仅凭源码标为实机通过；检查切换、重启保持和不重跑引擎。 | `K/LightTableScreen.kt:125`、`:126`、`:129`、`:142`、`:164`、`:165` |
| 网格直达 Editor 共享转场 | **规格超出现状**。AppScreens 外层用 AnimatedContent 切 EDITOR/TABLE；SharedTransitionLayout 只包观片台内部网格/胶卷与 loupe。不能以已有 sharedFrame 证明网格至 Editor 已共享转场，新增需另做实机验证。 | `K/LightTableNav.kt:100`、`:105`、`:142`、`:172`、`:198`；`K/Transitions.kt:38`、`:60` |

验收点：预设数量从实际列表而非新硬编码文案取值；导入失败/近似项有原有报告；比较松开即还原且不写 Edit；批量贴配方只改变所选分类，未覆盖字段不被文案承诺；并行新增的暖白灯箱须验证真实行为，Editor 共享转场须区分现有观片台内部转场。

## 7. 现有 Aura 与启动动画

### 7.1 Aura 不是待创建的空白能力

- **已有路径：** API>=33 尝试 RuntimeShader，构建失败回退径向光；API29..32 直接径向光（`K/Aura.kt:154`、`:295`、`:370`）。shader 路径约 33ms 更新一次、在生命周期 STARTED 时推进，有软件 Canvas 的 CPU 对应实现；不是规格所写“全部以 rememberInfiniteTransition 驱动”（`K/Aura.kt:55`、`:172`、`:186`）。
- **视觉差异：** 现有颜色为 Blue/Teal/Red/Yellow/Green，亮度常量 0.55、reach 0.34，主带 SrcOver；低版本五个 blob，Screen 混合。并非规格四色、0.18..0.32 和三个球（`K/Aura.kt:45`、`:51`、`:53`、`:78`、`:122`、`:414`）。主线程可调现有参数，不必另建同功能独立时钟。
- **动画偏好：** motionAllowed() 读取系统 ValueAnimator.areAnimatorsEnabled，关闭时用静态表现；AuraBar 也遵循此条件（`K/Aura.kt:131`、`:173`、`:373`、`:425`）。新扫描微光和新过渡也需遵守同一偏好，不能因新样式丢失现有契约。
- **出现位置差异：** 编辑器无图状态有 Aura，有图时该分支不画；但 BusyOverlay 仍可在照片已存在时叠 Aura。观片台空态有 Aura，导入进度 AuraBar 则不只存在于空态（`K/EditorScreen.kt:639`、`:770`；`K/LightTableScreen.kt:186`、`:505`）。因此“严格只在启动/空态/极致等待”尚不是已保证的现状；审阅时 QueueSheet 也没有自动对应的极致专属 Aura 分支（`K/QueueSheet.kt:48`、`:146`）。
- **300ms 淡出：** 现有无图 Aura 受分支控制，并无图像发布后 300ms alpha-to-zero 的独立契约。若本轮要满足规格，应按真实 image 是否存在控制可见性；不能让彩色层在实际调色画布周围残留。此项是 UI 适配，不是引擎渲染完结事件。

### 7.2 启动流程已经具备的时序

`MainActivity` 先安装系统 Splash；只对 savedInstanceState==null 且 launcher ACTION_MAIN+CATEGORY_LAUNCHER、动画允许、launch_intro 设置开启的启动叠 LaunchIntro。外部分享打开不是同一路径（`K/MainActivity.kt:43`、`:45`；`K/LaunchIntro.kt:62`、`:81`）。

系统 Splash 交出图标矩形和 wipe 进度，淡出约 120ms 后标记 intro visible；LaunchIntro 从真正可见首帧计时，避免在系统 Splash 背后消耗 hold（`K/MainActivity.kt:48`、`:57`；`K/LaunchIntro.kt:73`、`:106`）。当前 hold 1500ms，等待 engine ready 上限 2600ms，正常淡出 420ms；可见 300ms 后点按跳过，quick 淡出 180ms（`K/LaunchIntro.kt:68`、`:69`、`:70`、`:110`、`:115`、`:122`、`:132`）。

ready 取的是引擎初始化状态，**不是照片已导入、preview 已接受或定影完成**（`K/LaunchIntro.kt:90`；`K/MainActivity.kt:82`）。已有 Aura 背景、图标衔接、名称翻牌和落字触觉反馈；不能因规范只提极光就漏掉已有启动行为（`K/LaunchIntro.kt:138`、`:176`、`:181`）。

验收点：launcher 正常启动、旋转/进程恢复、外部打开、关闭启动偏好、系统关闭动画、慢初始化和点按跳过分别验证；不能把规范的“加载照片后 300ms”直接套到现有启动 420ms 上而宣称已经一致。新增颜色外观不应让已有超时、跳过和 Splash 对齐失效。

## 8. 其余规格说法与交付验收入口

| 原规格位置 | 核对结论 | 证据/验收入口 |
| --- | --- | --- |
| 1.1 SDK/ABI/依赖 | min29、compile/target36、arm64-v8a，Compose BOM2026.01.00、lifecycle2.8.7、Roborazzi1.77.0、Robolectric4.17 均有配置。语言百分比没有作为构建事实计算；针对 Adreno 不等于排他硬件支持承诺。 | `app/build.gradle.kts:19`、`:24`、`:28`、`:103`、`:112`、`:130` |
| 1.2 架构 | JSON 参数、JNI、引擎专用线程、C++20/Vulkan、LibRaw0.22.2 有证据。编辑与导出各自使用其线程/会话，UI 不可直接在主线程调用 native 渲染。 | `K/Model.kt:307`；`N/spektralab_jni.cpp:1`；`N/CMakeLists.txt:17`、`:40`、`:158`；`scripts/fetch-upstream.sh:14` |
| 2.3 等宽数字 | UiMono 与 tnum 已有可复用；新增 Token 不代表 HDR 或引擎能力变化。 | `K/Theme.kt:171` |
| 4.1 HUD | undo/redo、导出、图像 tier、busy/rendering 已有；真实完成事件见第 2 节，不可由动画计时构造。 | `K/EditorViewModel.kt:49`、`:683`、`:1064` |
| 4.2 新组件名称 | FilmStripBar/PrecisionDial/ColorWheels 等为设计目标；旧组件有对应模型不代表这些命名的新组件已经完成。具体布局由主线程处理。 | 第 2、5、6 节中的真实入口和缺口 |
| 5 分阶段截图验证 | 依赖存在不等于截图测试已通过，更不能证明 GPU/native/HDR/导出行为。本文未运行任何本地构建测试；主线程的云端构建与连接手机验收状态需由其报告。 | `app/build.gradle.kts:84`、`:130`；本文第 1 段范围 |

主线程可直接执行的功能验收清单（待验收，不是完成状态）：

1. 用公开或合成小图、非标准比例图验证 HUD 真实尺寸；修改/松手/切图/失败不产生伪完成微光。
2. 逐个连续组件确认真实 Edit/Look 更新、开始/结束/取消回调；RAW WB 只在提交时重开；复位值和单位与第 5 节一致。
3. 对 sRGB/P3 显示、高精度 SDR、gain map 输入分别确认 source.inputPrecision 和实际窗口目标；不出现虚假的 HDR 输出标识。
4. 普通 JPEG、极致无纸框、极致有纸框、TIFF 各目标 ICC、可用 HEIF 分别检查文件元信息、尺寸、装饰和采样；回退后文案不保留未经证实的承诺。
5. 核验 Aura 的照片存在/阻塞打开/空态/队列分支，以及启动超时、跳过、关闭动画；核验批量粘贴缺失字段的文案与行为一致。

本文提供的差异结论不依赖私人照片、真实设备命令或本地编译结果。没有新增引擎分辨率硬编码，也没有将任何设计阶段、主线程代码或待验收项标记为已完成。
