# SpektraLab V2 上游研究（2026-11-11）

GPT（OpenAI）共创；6.1 Sol high 子 agent 调研，主 agent 复核关键发布与平台边界。

状态：本轮只读研究收尾报告。固定版本、关键设计/移植边界、参数与 UI 发包、45 补丁语义迁移表已完成；未构建/运行 V2。研究不改变本项目依赖 pin、源码或发布物。

## 版本对象

- 研究对象是 JamesQiu2005/SpektraLab 正式发布 `spektralab-v2.3.2`，commit `1952c63537ec576d24422f1065b12495631346a5`，发布于 `2026-11-10T11:50:09Z`；查询时 `main` 同 SHA。2.3.2 的发布主题为 Intel Mac compatibility，并不是 Android/Vulkan 完整移植公告。具体修正 fast-math 启动探针；官方称在 Mac mini 2018 + RX 590 测试，Rosetta 与 Apple silicon 整图逐 bit 一致，其他 Intel/iGPU 未试。这些是官方验证声明，本研究没有复跑。
- SpektraLab 2 的首次对应发布为 `spektralab-v2.2.0`，commit `243d3a362a6443db34109a93b622230699596d68`，2026-11-08。2.3.0 为 `4cbf6a56966a2c8a9d6e8d794aac8457f5165255`。
- 当前旧分支 `main_v1` 为 `ee8f20b93eb60dcf4d7fcd800a087c8094aff8b6`，`Windows_Linux` 为 `223205fddbbc01c583977497d9f27c79a49ca8d0`。不根据分支名字推断它们彼此的祖先关系。
- andreavolpato/spektrafilm 当前 `main` 为 `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc`，官方 tags 为 v0.3.3/v0.3.2/v0.2.0-legacy/v0.1.0-alpha；未发现其正式 V2 release。它是原始项目，不应把 James 的 V2 版本号套用给它。
- 我方基线是 `a46627d250545a394ca21522f8a28b0fb89e4c42` 加 0001..0045。GitHub 官方 compare API 对该 pin 与 V2 SHA 返回 `404: No common ancestor`；因此这里进行文件语义对照，不宣称可直接 fast-forward 或 cherry-pick。

官方证据：[2.3.2 release](https://github.com/JamesQiu2005/SpektraLab/releases/tag/spektralab-v2.3.2)、[2.2.0 release](https://github.com/JamesQiu2005/SpektraLab/releases/tag/spektralab-v2.2.0)、[branches API](https://api.github.com/repos/JamesQiu2005/SpektraLab/branches?per_page=100)、[原项目 tags](https://api.github.com/repos/andreavolpato/spektrafilm/tags)、[compare API](https://api.github.com/repos/JamesQiu2005/SpektraLab/compare/a46627d250545a394ca21522f8a28b0fb89e4c42...1952c63537ec576d24422f1065b12495631346a5)。

## 已确认的两个核心边界

### 成像设计改变：minilab 色彩校准

官方 issue #97 报告新版同胶片相纸与旧版颜色不同。维护者明确说明加入主观色彩校准、截至 2026-11-10 只校准 Portra 400，并以 Capture One Pro Standard 解码样本为基础。这是开发者声明的设计变化，不能把颜色不一致直接归为通用 bug。

源码印证：`pipeline.cpp:333-347` 在 `__APPLE__` 下默认尝试载入胶片/相纸/输出空间对应的 minilab 表；环境变量 `SPK_V2_REFERENCE_OPTICAL` 只要存在就会绕过，值为 `0` 也属于存在。非正片、非黑白、非 DI、非 scan_film 才进入。`table.side != 0` 才启用，故不是所有 pair 都自动变为 LUT 路径。

正式 `index.json` 当前只有 **一个 film|paper pair**：`kodak_portra_400|kodak_portra_endura`。两份 `.lut3d` 是该同一 pair 的 sRGB 和 ProPhoto RGB 目的空间校准；文件名 `_pp` 不是第二种相纸。其他支持的目的空间选择 ProPhoto 校准，再走它自己的输出变换。两表均 65³，3 通道 float32 的采样值载荷各为 3,295,500 bytes，另有格式头/索引和临时读取/校验开销；这里没有把它当成实测进程峰值。

`print_spectral` 的 minilab 路径取代 enlarger_spectral→print_exposure→print_curves，再继续 scan_spectral。输入是胶片恢复的 log-positive RGB，输出是纸 CMY density；不是最后的 RGB 调色表，也不能交给 `spk_print_lut_table`。#97 中维护者“只加一个小 LUT、未改计算代码”的表述是开发者对意图的说明，不能代替这一实际节点位置。不能把它等同于我方对旧光谱路径的纯性能优化。

不在目录里的 pair 继续原光学路径；已列 pair 的 LUT 缺失、损坏或 profile identity 不匹配会失败，不能默默 fallback。`SetupCache::set_resources_dir` 也只在 Apple 初始化并强制检查 minilab 目录。`params.cpp` schema、Swift `FilmParams`/`EngineWire`、desktop `params.ts` 没有 minilab enable 字段，因此固定版本还没有可直接复用的客户端显式开关；官方 #97 所说未来增加设置属于计划。2.3.0 恢复所有 pair 的 enlarger 参数后，`minilab_enlarger` 用现状与 rest 的光学 mid-grey 常量推导 gain/offset，再进入 minilab 核，不能跳过这层而只搬表。

证据：[issue #97](https://github.com/JamesQiu2005/SpektraLab/issues/97)、[固定 pipeline.cpp](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/src/pipeline/pipeline.cpp#L330)、[资源目录](https://github.com/JamesQiu2005/SpektraLab/tree/1952c63537ec576d24422f1065b12495631346a5/engine/resources/minilab)。

### Vulkan 移植缺口：V2 新 HDR 扫描核

V2 仍保留 Vulkan 代码，不代表 V2 新路径已完整移植。minilab 默认启用代码仅存在于 Apple 编译分支；其颜色设计不能直接在 Android 上得到相同结果。

正片 HDR 扫描 `scan_hdr` 会在 `node_scan_spectral` 调用 `spk_scan_hdr_expand`；正式 CMake Vulkan kernel 列表、`vulkan_gpu.cpp` registry 和 Vulkan 源目录均没有该核。正常 non-Apple `Pipeline::build` 先调用 `windows_unported_error`，对 `scan_film && (scan_hdr_stretch || scan_hdr_headroom > 1)` 明确返回 `scan_hdr is not implemented by the Windows Vulkan backend yet`。增量设置也在变更 session 前保守拒绝 stretch/headroom>1。**因此正常请求提前失败，不是先尝试不存在的 SPIR-V。**

若内部调用绕过这道门，`VulkanGpu::dispatch` 在 `find_kernel` 失败处返回 `Vulkan kernel not ported: spk_scan_hdr_expand`；只有已注册 kernel 找不到文件时才返回 `cannot open Vulkan shader <path>`（`get_pipeline`）。这些是从代码确认的失败路径，尚未注入执行验证。这是特定新路径的移植覆盖缺口，不是已经实测的全平台通用缺陷，也不表示普通 SDR Vulkan 路径必然失败。

官方 issue #106 仍在请求用 Rust/Tauri 移植 V2 到 Windows。不能用旧 Windows 界面或旧 Vulkan 文件存在，替代 V2 Windows 产品验收。

证据：[issue #106](https://github.com/JamesQiu2005/SpektraLab/issues/106)、[scan HDR 调用](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/src/pipeline/pipeline.cpp#L1715)、[Vulkan 清单](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/CMakeLists.txt#L111)。

## 引擎、颜色和 HDR 的语义对照

| 范围 | 固定 V2 源码事实 | 对我方 pin + 45 的含义 |
|---|---|---|
| 结构 | 仍是 C ABI → session/pipeline → film/print Stage/Chain → Gpu；并非换成另一套 CUDA 引擎 | 许多数值组件可独立阅读，但平台分支与图节点不同，不能整树覆盖 |
| 光谱主干 | RGB→胶片曝光/密度→纸曝光/密度→光谱扫描→XYZ/输出空间仍在；新增 minilab 路由与扫描 HDR | 原光学与新 look 应分别验收 |
| RAW native compatible | LibRaw `output_color=4`、`output_bps=16`、identity gamma、关闭 auto brightness，RGB16 再除 65535；`half_size=0` | 线性传递函数不等于保留 signed/HDR；该兼容路径仍量化且裁切，不能替代我方默认 RAW 线性/浮点边界 |
| RAW headroom | 独立 `HeadroomLibRaw::convert_to_rgb_loop` 采集 float 转换；有 source/support/白平衡验证 | 和胶片 scan_hdr 是不同层；V2 比 pin 的 raw_decoder 更少，不是我方 DNG 扩展的升级替代 |
| native RAW 差异 | 对 pin 文件语义 diff 显示 V2 移除了 raw_camera 适配、OpenMP worker scope、若干 metadata、headroom 自定义 WB 重载及兼容路径在分配 float 前释放 LibRaw 的优化 | 迁移要保留 pin 本来就有的能力，不能只数 45 个补丁 |
| 色彩 | IO 输出默认 ProPhoto RGB，输出 CCTF=true；中间节点有线性 XYZ/RGB、输出 gamut 和可选编码 | 保持我方 RAW 默认与明确定义的边界，不因 V2 版本号改变默认 |
| EDR | 原 `extended_dynamic_range` → print_render.edr_enabled，默认 false；仍是打印层节点 | 与新 Enable HDR/gain map 分开，不把 EDR 当新 scan_hdr 的同义词 |
| 正片 scan_hdr | host `ScanHdrModel` 按染料层在各自 knee 上移动 shoulder、提升扫描前密度/光量；谱积分后、gamut 后还有输出 scale | 是成像数学改变，不能用对最终 RGB 乘一个 gain 代替 |
| HDR gain curve | 新 `spk_session_hdr_curve` 对本 session medium probe 返回256点 gain/ks/rho等；不渲染完整图，不适用于 DI/已启用 print EDR 等拒绝条件 | 可作为独立研究组件；不是完整 Android gain-map 导出功能 |
| Apple HDR 产品 | Swift 制作 SDR/HDR rendition 与 grain-aware gain map，Metal/ImageIO/平台显示与文件容器配合 | 需要 Android 独立实现和验证，不由 C ABI 升级自动获得 |

HDR release 的产品宣称：2.3.0 支持 0.5–4 stops peak（默认2），目前 Apple gain-map 文件，Photos/Preview/Safari 可读，Lightroom/Camera Raw 当时只读 SDR。上述兼容性是官方发布声明，本研究未安装这些应用或验证导出文件。

### 参数 schema 与实际 UI 发包

`params.cpp:223-226` 新增四个字段：

| wire 字段 | 层与有效范围 | native 默认 |
|---|---|---|
| scan_hdr_headroom | SHOOT，0..16 | 0；<=1 不启用额外 headroom |
| scan_hdr_stretch | SHOOT，bool | false；正片 SDR stretch 优先于 headroom |
| scan_hdr_output_scale | PRINT，0.0625..1 | 1 |
| grain_seed | SHOOT，0..2147483647 | 0；每次重新抽取，非0固定 grain realization |

Swift `FrameEdit.engineWire` 从 StockCatalog 收集常规字段，再仅在正片 Enable HDR 开启时叠加 `HDRGain.sdrWire`：固定 grain_seed，可加 scan_hdr_stretch。第二份 HDR rendition 发送 stretch=false、headroom=H、scale=1/H，之后恢复 stretch、headroom=0、scale=1。`FilmParams.sometimesSent` 与 `step` 明确在 HDR/stock 关闭或切换时发送四个 rest 值，避免字段从字典消失却留在 native session 的旧值；这是值得吸收的状态合同。它不是 Android 当前 UI 已实现的能力。desktop `params.ts` 只有旧 `extended_dynamic_range` 发包，不能拿它证明 V2 HDR 桌面全覆盖。

### API、ABI 与资源

固定 pin `engine/include/spektrafilm/spk_engine.h` 对 V2 `engine/include/spektralab/spk_engine.h` 的 diff 显示既有 C ABI 函数/结构未在此头文件改变布局，新加 `spk_session_hdr_curve`；因此不能泛称所有 ABI 已破坏。include 路径、项目命名和资源 `spektrafilm_constants.bin`→`spektralab_constants.bin` 改名确实要求包装/构建更新；新增函数与 header 不能拿旧 so 链接。还必须分别处理 params schema、capabilities、不支持功能和 JNI 内部 C++ 类型，它们不受“C struct 未改”保证。

我方 pin 的 `pipeline/view_process.cpp/.hpp` 在固定 V2 树没有对应文件，V2 应用用 Swift/Metal 承担相当一部分 host view/export 逻辑。我方 no-film signed/HDR float、row output、view cancellation 等边界仍需保留或重新接入；不能因新增 HDR gain-map 名称就删除。

## 平台与内存边界

| 后端/平台 | 源码实证 | 不可越过的结论边界 |
|---|---|---|
| Metal | 新 minilab/scan_hdr 与 Apple checkpoint 有实现/编译分支 | 本研究未构建 Metal 或证明色彩拟合误差 |
| Vulkan | 同步队列、显式 staging；registry 仍缺 DI encode、scene latitude mapping、mask、overscan、date、scan_hdr 等；unsupported guard 按名拒绝 | 这些不少在我方 pin 已支持，V2 main 的支持集不能当作我方功能上界 |
| CPU | portable C++ setup/core；lavapipe 是 CPU Vulkan，官方覆盖文档明确真实 Windows/硬件验证范围有限 | 不等于另有完整 native CPU 图像渲染后端 |
| CUDA | 检查的 native CMake/Gpu factories/kernel tree 无 CUDA 后端 | 不扩展为对所有历史 Python 分支/外部衍生项目的断言 |
| Android | 本轮正式树没有证据表明 V2 Mac HDR/Metal 全流程已有 Android 生产实现 | 仍需我方 JNI、Vulkan、资源、内存与真机验收 |

V2 `Pipeline::choose_strip_height` 明确忽略 node_count 与 budget_bytes；仅服从 requested_rows 或整幅。已有 striped 不等于预算准入。源/目标仍有完整 plane，host vector、GPU pool、upload/read staging 可能并存。V2 Vulkan 小请求仍可取 pool 中较大的块，descriptor pool 每 dispatch 创建，inline 参数仍逐个 upload；它不替代我方 0012/0014/0020 和 0039..0045 的限制。

新增 `DiskCheckpoint` 是 Apple-only 的进程唯一 pre-grain 临时 SSD slot，以 owner/key 保护，避免保留 per-session GPU plane，并不是 external-plane 的按 band 预算合同；恢复仍要 GPU image。`run_film_checkpoint` 有局部 factory/progress Cleanup，但常规 film/print 和 striped 仍有提前失败留下 progress、band 状态的旧模式，Progress.cancelled 仍为普通 bool。0044 的修复不应因存在 checkpoint cleanup 就删掉；新 checkpoint 入口也要一并带入 done=false/发布失败合同。

统一 staging/metadata 预算迁移时继续采用我方 0045 的 **payload 上限**，不冒充 RSS/device total 上限：在分配/线程启动前计算 worker×(source window + destination core)、coverage/strip metadata、checked size_t 乘加及32位 RGB shader `UINT32_MAX/3` 像素上限；实际 allocation capacity 和未释放 staging 必须另计。多 executor 共享一个 Gpu 时 per-executor mutex 不足以提供全局并发内存界，须外部 lease 或串行准入。V2 minilab 常驻采样、HDR双rendition、gain-map纹理、checkpoint恢复和完整读回各自的重叠生命周期要加入总账。

## 0001..0045 的迁移账

下表是源码语义对照，**不是 git apply 成功声明**。“保留”表示需求与实现合同仍需保留；“重接”表示 V2 文件/图/平台不同，要手工迁移并复用回归；“部分替代”不能直接删补丁。V2 没有共同祖先的 API 返回已记录，故不建议45张patch机械重放。

| # | 我方补丁 | V2 判定及迁移动作 |
|---|---|---|
| 0001 | iir-chunked-launches | 保留/重接：V2仍两次IIR，不能证明移动长任务与取消响应界 |
| 0002 | dispatch-chunks-under-2-24 | 保留：V2按maxComputeWorkGroupCount折2D，不等于我方float精确索引chunk界 |
| 0003 | raw-half-size-budget | 保留：V2 half_size=0，无我方native RAW预算选择 |
| 0004 | dng-dual-illuminant-matrix | 保留：V2未采用我方raw_camera双光源合同 |
| 0005 | lock-print-lut-caches | 保留/重接：懒缓存仍存在；V2 engine print_lut_buffers lookup/fill无我方锁 |
| 0006 | dng-opcode-gain-map | 保留：V2新HDR gain-map是输出容器，和DNG输入opcode无关 |
| 0007 | grain-size-and-unsharp-on-wire | 保留：V2相关内部参数存在，新schema未替代这三个wire字段 |
| 0008 | latch-device-lost | 保留：V2 Vulkan无我方持久device-lost状态边界 |
| 0009 | dng-opcode-list-3 | 保留：不是V2输出gain-map可替代项 |
| 0010 | storage-range-by-requested-size | 保留：V2descriptor仍用b->bytes，必须区分请求/池容量 |
| 0011 | atomic-cancel-flag | 保留：V2 Progress.cancelled仍plain bool |
| 0012 | persistent-descriptor-pool | 保留：V2dispatch仍建descriptor pool |
| 0013 | reprint-reuses-print-constants | 重接：V2仍每print刷新；新增minilab_enlarger与HDR参数需完整失效键 |
| 0014 | inline-args-mapped-ring | 保留：V2inline args走独立upload，不是mapped ring |
| 0015 | vulkan-pipeline-cache | 保留：V2内存pipelines_ map不等于我方driver cache持久化 |
| 0016 | setup-cache-shared-lut | 保留：V2tc_lut返回LRU vector内Vec*，仍需要跨解锁持有ownership |
| 0017 | setup-cache-complete-gamut-key | 保留：V2key仍algorithm+knee的窄键，不能覆盖全部输入 |
| 0018 | fir-demand-without-kernel-allocation | 保留：V2fir_radius仍调用gaussian_kernel_1d并分配ignored Vec |
| 0019 | bounded-fir-row-scratch | 保留/重接：V2普通FIR仍全plane临时量，无我方row scratch界 |
| 0020 | small-vulkan-pool-requests | 保留：小请求拿大buffer风险仍在 |
| 0021 | dng-forward-matrix | 保留：V2raw_decoder未采用我方ForwardMatrix适配 |
| 0022 | raw-white-balance-temperature | 保留/重接：native RAW适配较窄，保留我方WB温度算法与证据 |
| 0023 | raw-explicit-white-temperature | 保留：不能由V2界面曝光/HDR控件替代 |
| 0024 | raw-custom-without-as-shot | 保留：V2headroom仍强制camera/as-shot，未有我方定制WB分支 |
| 0025 | vulkan-device-lost-preparation-guards | 保留：准备/分配路径也需一致latch，不能只检查submit |
| 0026 | cam16-hoist-table-invariants | 部分替代：本就选摘自V2 d90；完整迁移避免重复套，但保留我方serial/math验收 |
| 0027 | di-precompute-channel-curves | 已有同源优化：V2FilmHost有xs/normal/dir_by_channel；删除重复diff前确认余下DI/默认路径 |
| 0028 | vulkan-pointwise-fusions | 重接：V2只有对应Metal融合，Vulkan registry无我方融合核；继续opt-in与floatmemcmp门 |
| 0029 | scene-curve-vulkan | 部分替代/重接：V2有host/Metal scene_curve；保留我方Vulkan核、chunk push constants与输入上限校验 |
| 0030 | nofilm-float-grade-boundary | 重接：V2没有我方native view图；signed/HDR边界与probe清curve须保留 |
| 0031 | view-output-cancellation | 重接：同上，V2Swift任务取消不证明JNI/native行写取消 |
| 0032 | dng-as-shot-white-xy | 保留：V2未替代我方DNG白点选择 |
| 0033 | integer-view-readback-cancellation | 重接：保留我方输出读回/取消合同 |
| 0034 | nofilm-float-row-readback | 重接：保留row sink和浮点输出边界，不能退回全幅UNORM16 |
| 0035 | dng-render-metadata | 保留：V2缺失pin中若干metadata，尤须避免丢失来源和意图 |
| 0036 | setup-cache-reference-illuminant | 保留：V2tc_lut_key无reference_illuminant维度 |
| 0037 | raw-compatible-progress-cancellation | 保留/重接：V2native LibRaw路径无我方进度/取消桥 |
| 0038 | gain-map-whole-list-validation | 保留：DNG输入列表原子验证与Apple输出gain map不同 |
| 0039 | bounded-point-chain | 保留/重接：V2没有external-plane executor；新节点只在满足合同后入图 |
| 0040 | bounded-print-run | 保留/重接：新minilab、scan_hdr、DI等必须显式拒绝/另验，不能暗扩支持 |
| 0041 | bounded-execution-contracts | 保留：预算/halo/FIR条件不被V2strip_rows替代 |
| 0042 | external-plane-publication | 保留：seal/失败取消/最后发布合同仍是独立需求 |
| 0043 | dng-asn-calibration-signature | 保留：V2未有我方CC签名选择与FM门 |
| 0044 | pipeline-failure-recovery | 重接：常规入口仍有旧状态清理模式，并补新checkpoint入口；局部candidate最后发布 |
| 0045 | bounded-window-payload | 保留：统一host staging/metadata与RGB索引上限；新增V2常驻/双图成本另算 |

## 分级吸收路线与验收

1. 继续保持我方 RAW 线性默认与当前固定基线。先取可独立验证的数值/资源读取组件，避免直接更换 look。
2. minilab 校准作为显式的新成像模式研究；先证明唯一已列 pair 的两个校准空间、未列 pair 路由和 RAW 解码依赖，再考虑移植 kernel。
3. scan_hdr 的 host 数学可以隔离研究，Android 启用前必须补 Vulkan 核、参数序列化、未启用时逐字节不变和实际设备内存验证。
4. 增益图导出大量依赖 Swift/Metal/ImageIO，属于产品层功能移植；不能由 C++ 引擎升级自然获得。

优先级 P0：保留 pin+45 与既有默认；只吸收能独立回归的状态合同（例如 Swift sometimesSent 的rest重置）和已选摘数值优化，不切 look。P1：单独实现 opt-in minilab Vulkan 模式，准确移植log-positive→纸CMY与enlarger映射、资源hash门、两个目的空间选择；RAW输入一致下做Apple固定样本对照、未列pair与opt-out逐字节不变、缺/损资源明确失败。P2：正片scan_hdr host模型+Vulkan核与shader registry先独立验算，覆盖<=1/off不变、stretch优先、同seed双图、比例/有限值/膝点连续性及高光；再接medium gain curve。P3：Android显示/文件gain-map产品功能独立实现，验证目标解码器、SDR fallback、色彩metadata、HDR重建和内存，不把Apple release兼容性宣称搬成Android承诺。

生产预算路线不因V2而跳步：先在现有pipeline的已验证print尾部接窄支持集（无DI/overscan/newHDR，FIR-only，保留RAW线性默认）；按0045准入后启动worker，最后seal成功才提交结果。随后每次引入新图节点重新审核halo、不可分块节点、常驻资源和双rendition并发。默认scanner unsharp {0.7,0.7} 要按原tier比例解析，不可写死核半径；镜头blur=0、EDR=false、scan_film=false、ProPhoto/CCTF既定路径作为首个默认print验收集。

## 资源许可记录

仅记录正式仓库文件的适用说明，不作额外许可推测：`license/LICENSE` 为代码的 GPL-3.0 文本；`license/NOTICE.md` 说明引擎/应用为GPL-3.0-or-later，`engine/resources/profiles` 的原28 profiles未改动。`license/SPEKTRAFILM_LICENSE.txt` 为 profiles、LUT及其直接衍生的CC BY-SA4.0；NOTICE明确把 `spektralab_constants.bin` 中print LUT及 `resources/minilab/` 两表列为该范围。minilab README把材料来源与许可指向 profiles及license文件，不能另当自有、不受原数据说明约束的RGB LUT。`resources_product/NOTICE.md` 单列制造商数据表拟合的产品自有数据及CineStill alias来源；它不改变那两份minilab表的上述范围。

## 证据定位与验证范围

已完成官方 release/issue/API 与固定 SHA 源码阅读；没有构建或执行 V2，没有验证官方安装包表现。云端 blobless clone 两次遇 TLS/连接失败，已通过官方 raw GitHub 固定 SHA 下载必要文件到本轮私有的固定源码证据目录，不依赖可变 main 内容。私有证据目录含 `release-2.3.2.json`、`release-2.3.0.json`、`release-2.2.0.json`、`issue-97.json`/comments、`issue-106.json` 和固定 `tree-2.3.2.json`。没有声称有完整git历史或完整本地archive。

关键固定源码链接（均同1952c635 SHA）：

- [pipeline](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/src/pipeline/pipeline.cpp)：build non-Apple guard、minilab启用、scan_hdr、print_spectral、strip policy、run入口。
- [windows_unported.hpp](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/src/pipeline/windows_unported.hpp)：正常请求失败边界。
- [Vulkan registry/dispatch](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/src/gpu/vulkan_gpu.cpp)：kKernels、dispatch约498行、get_pipeline约924行。
- [minilab index](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/resources/minilab/index.json) / [README](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/resources/minilab/README.md)：一个pair、两空间与资源合同。
- [schema](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/src/core/params.cpp) / [scan_hdr模型](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/src/core/scan_hdr.hpp)。
- [HDRGain](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/app/SpektraLab/Sources/Engine/HDRGain.swift) / [EngineWire](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/app/SpektraLab/Sources/Engine/EngineWire.swift) / [FilmParams](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/app/SpektraLab/Sources/Model/FilmParams.swift)：实际发包与字段重置。
- [C ABI](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/include/spektralab/spk_engine.h)、[raw_decoder](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/src/io/raw_decoder.cpp)、[PORT_COVERAGE](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/PORT_COVERAGE.md)。PORT_COVERAGE为项目自述，运行覆盖不由本研究重复背书。
- [NOTICE](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/license/NOTICE.md) / [资源产品NOTICE](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/resources_product/NOTICE.md)。

我方当前基线的双云验证与 V2 研究分别记录，详见[源码修复与验证](ENGINE_ANDROID_FOLLOWUP_2026-11-11.md)。其中的原生渲染、取消恢复、内存预算与 ARM64 检查均不能证明 V2 已可替换生产引擎。
