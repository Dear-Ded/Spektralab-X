# 引擎补丁 / Engine patches

本仓库不改动上游源码本身。上游 [SpektraLab](https://github.com/JamesQiu2005/SpektraLab) 在 CI 里按固定提交下载（见 [`scripts/fetch-upstream.sh`](../scripts/fetch-upstream.sh)），然后按文件名顺序打上 [`patches/`](../patches/) 里的补丁。补丁集合的哈希写进上游目录的标记文件和 CI 缓存键，改动补丁会自动重新解压。

2026-11-09 深审：0029 已补齐 Android 分批 dispatch 的 push-constant base，新回归能使旧 shader 失败；30 个补丁在干净 pin 上 `--fuzz=0` 应用，91 个文件与实际测试引擎一致。最新验证与尚未解决的内存/取消/完整分块边界见 [深审记录](ENGINE_DEEP_REVIEW_2026-11-09.md)。

后续第二批：32 个引擎补丁和 15 个 LibRaw overlay 在干净 pin 上 `--fuzz=0` 重放通过，92/22 个文件与生产候选哈希一致，Android LibRaw 副本也一致。0031 改为标准 U3；LibRaw 0016 的过时上下文对齐此前 0002，代码保持相同。见 [第二批证据](validation/optimization-wave2-2026-11-09.json)。

第三批增加 0033，33 个引擎补丁和 15 个 LibRaw overlay 已再次零 fuzz 重放，92/22 个文件与候选源码哈希一致。该批同时补齐整数 view 取消和 JPEG 外存磁盘预留；浮点 DNG 实验未接入默认解码。见 [第三批证据](validation/optimization-wave3-2026-11-09.json)。

All changes to the upstream engine live here as unified diffs, applied in name order on top of the pinned upstream commit.

| 补丁 | 改动文件 | 解决的问题 |
|---|---|---|
| `0001-iir-chunked-launches.patch` | `pipeline/blur.cpp`、`spk_iir_df_acc.comp` | 上游把一整个平面的 IIR 高斯模糊作为一次 GPU 提交。在大图或降频状态下单次提交可能超过 2 秒，高通 kgsl 驱动会重置 GPU（`VK_ERROR_DEVICE_LOST`）。补丁把每个方向按每 256 行一批提交。每个线程负责完整的一行，分批不改变任何运算，输出逐位相同。2026-11-04 从 512 改为 256：衍生项目 SAINTTaiYi/SpektraLab-Android（fa199b6）用 256 在 44.7 MP 上从设备丢失变成能跑完；本仓库在云主机上对照，52 个用例 468 个输出与 512 逐字节相同（[PARITY.md](PARITY.md) 第 4.7 节）。 |
| `0002-dispatch-chunks-under-2-24.patch` | `gpu/vulkan_gpu.cpp`、全部计算着色器 | Adreno 驱动在单次计算超过约 2²⁴ 个线程时会写坏图像（顶部出现乱码条），而上游正好在这个位置把工作组折到第二个维度。补丁把每次 dispatch 拆成不超过 2²⁴ 线程的一维分块，起始下标通过 push constant 传给着色器，着色器的一维下标统一加上它。 |
| `0003-raw-half-size-budget.patch` | `io/raw_decoder.cpp`、`io/raw_decoder.hpp` | 拜耳传感器的像素超过工作尺寸 4 倍时，用 LibRaw 半尺寸模式（2×2 合并，不做去马赛克）解码，而不是先建一张全尺寸浮点图再缩小。5000 万像素 DNG 的解码内存约降到四分之一。预算按线程设置，默认 0 表示全尺寸，桌面版行为不变。 |
| `0004-dng-dual-illuminant-matrix.patch` | `io/raw_camera.hpp`、`io/raw_decoder.cpp` | DNG 通常带两组色彩矩阵（手机一般是标准光源 A 和 D65），LibRaw 只取日光那一组。补丁按 DNG 规范（Adobe DNG SDK 的 `dng_color_spec`）用实际白平衡迭代求出色温，在两组 ColorMatrix（含 CameraCalibration、AnalogBalance）之间按色温倒数插值，再交给 LibRaw 原有的转换流程。只改相机矩阵，不改白平衡倍率；白点不暖于日光那一组的标定光源时，用的就是 LibRaw 原来那组矩阵（仅有浮点舍入差异）。样片实测：Pixel 2 XL（4605 K）红通道平均变化 2.6/255，Leica Q（8530 K）不变。 |
| `0005-lock-print-lut-caches.patch` | `core/print_lut.hpp`、`core/print_lut.cpp`、`pipeline/engine.cpp` | 编辑器和导出各用一条引擎线程后，两个会话可能同时第一次用到同一种相纸。上游的 `PrintLutLibrary::get` 缓存和 `engine->print_lut_buffers`（相纸查找表、DI 取景表的 GPU 副本）在懒加载时都不加锁，并发写 `std::map`/`unordered_map` 是数据竞争。补丁给两处各加一把 mutex，只包住查表和首次填充，其他逻辑不变；表只上传一次。锁的顺序固定为先查表锁、后 GPU 的 dispatch 锁，不会反过来。单线程时行为和输出逐位不变。 |
| `0006-dng-opcode-gain-map.patch` | `io/dng_gain_map.hpp`（新增）、`io/raw_decoder.cpp` | LibRaw 0.22.2 会解析 DNG 的 OpcodeList2，但没有 DNG SDK 时一项都不执行，小米、Pixel 的 DNG 因此缺了镜头阴影校正，四角暗 3–4 倍。补丁在 unpack 之后、dcraw_process 之前把 GainMap 乘到拜耳数据上（先减黑电平、乘增益、再加回），opcode 列表出错时不改任何像素。没有 GainMap 的文件输出逐字节不变。OpcodeList3 见 0009。 |
| `0007-grain-size-and-unsharp-on-the-wire.patch` | `core/params.cpp`、`tests/parity_schema.py` | 上游 a46627d 的线上参数里没有颗粒尺寸和扫描锐化，应用无法调。补丁加三个仅原生字段：`grain_particle_area_um2`（默认 0.2，0.02–2）、`scanner_unsharp_sigma`（默认 0.7，0–5）、`scanner_unsharp_amount`（默认 0.7，0–3），超范围拒绝。不传或传默认值时输出与未打补丁逐字节相同。注意 preview_mode 档会把两者置零，快速预览里看不到。 |
| `0008-latch-device-lost.patch` | `gpu/vulkan_gpu.cpp` | 上游 PR #64 的思路：第一次提交返回 `VK_ERROR_DEVICE_LOST` 后用原子标志锁存，此后 `submit_sync` 和 `flush` 都立即失败，不再碰已丢失的 VkDevice（原来 `flush` 在丢失后仍返回成功，之后的分配、上传、读回会接着打到死设备上）。错误文案保留 `(VkResult -4)`，应用据此替换引擎一次（`EngineHost.recover`）。正常渲染不受影响，只多一次原子读。 |
| `0009-dng-opcode-list-3.patch` | `io/dng_opcode_list3.hpp`（新增）、`io/raw_camera.hpp`、`tests/dng_opcode_list3.cpp` | LibRaw 0.22.2 把 DNG 的 OpcodeList3 原样读进 `rawopcodes[2]`，但不执行。补丁在 `dcraw_process` 的 `pre_converttorgb` 回调里（去马赛克后、色彩转换前，即 DNG 的第 3 阶段）执行 WarpRectilinear（镜头畸变和横向色差，徕卡）和 FixVignetteRadial（径向暗角，iPhone），公式、半径归一化和双三次重采样（A = -0.75）照 Adobe DNG SDK 的 `dng_lens_correction.cpp`。整张列表先解析、校验：截断、参数块长度不对、非有限值、中心越界，任何一项都整表放弃，不改一个像素；其他 opcode 跳过；恒等的 warp 不执行。富士旋转布局和非方形像素不处理。云主机 28 个样片两种模式对照（2026-11-05）：只有 iPhone 6s Plus（暗角，平均亮度 +36%）和两张徕卡（畸变）变化，其余 25 张（含 5 张小米 DNG：OL3 为空列表或恒等 warp）浮点帧 SHA-256 与未打补丁时相同。早先说的“小米 OL3 截断”是解析脚本把 TIFF 条目里内联的 4 字节值当成了偏移，实际是 count = 0 的空列表。 |
| `0010-storage-range-by-requested-size.patch` | `gpu/vulkan_gpu.cpp` | 帧缓冲池复用时会借出比请求大至 max(请求, 64 MiB) 的块，dispatch 却按块容量检查 `maxStorageBufferRange` 并绑定整块，放得下的平面在复用后会误报“buffer exceeds maxStorageBufferRange”。Adreno 7xx 上限 128 MiB，正好是应用按它定尺寸的平面大小。补丁按 `requested_bytes` 检查和绑定。没有着色器读 `length()`，各核按 meta 计数自限，输出逐字节不变。 |
| `0011-atomic-cancel-flag.patch` | `pipeline/pipeline.hpp` | `spk_cancel` 在另一条线程上不加锁写 `Progress::cancelled`，渲染在节点之间读它，普通 `bool` 是数据竞争，编译器可以把读提到循环外。改成可拷贝包装里的 `std::atomic<bool>`（每次渲染都 `session->progress = Progress{}`），写 release、读 acquire，现有用法不变。不涉及运算，输出逐字节不变。 |
| `0012-persistent-descriptor-pool.patch` | `gpu/vulkan_gpu.cpp` | 每次 dispatch 都新建、销毁一个 VkDescriptorPool，全分辨率渲染有几百次 dispatch。改成后端只建一个池（按最宽的核 8 个存储缓冲），每次 dispatch 后 reset。dispatch 全程持有 dispatch 锁且提交后等队列空闲，reset 时描述符集不在使用中。超过 8 个绑定的核报清楚的错误。输出逐字节不变。 |
| `0013-reprint-reuses-print-constants.patch` | `pipeline/pipeline.cpp`、`pipeline/pipeline.hpp` | 每次印放都重算放大机常数（中灰探针、预闪偏移、三次常驻上传），开了黑白场还要从 GPU 读回三张扫描表。这些量在同一个管线里只会被 live 参数改动（其余改动都会重建管线），所以 `apply_live_delta` 把它们标记为过期，未过期时直接复用；扫描表在 build 时按与 `upload_persistent_f32` 相同的 float 截断保留主机副本，不再读回。输出逐字节不变。 |
| `0014-inline-args-mapped-ring.patch` | `gpu/vulkan_gpu.cpp` | 每个 `inline_bytes` 小参数（元数据、矩阵、LUT 范围）原来都要经暂存缓冲上传，单独一次提交加队列等待。补丁建一块 64 KiB 常驻映射的主机可见存储缓冲，在 dispatch 锁内把小参数拷进去并按 `minStorageBufferOffsetAlignment` 对齐绑定；装不下、没有合适内存类型或设 `SPEKTRAFILM_VULKAN_INLINE_RING=0` 时走原路径。着色器读到的字节不变，输出逐字节不变。 |
| `0015-vulkan-pipeline-cache.patch` | `gpu/vulkan_gpu.cpp` | 上游每次启动都把约 49 条计算管线重新编译一遍（`vkCreateComputePipelines` 传 `VK_NULL_HANDLE`）。补丁建一个 `VkPipelineCache`，宿主用 `SPEKTRAFILM_VULKAN_PIPELINE_CACHE` 指定文件时跨启动保存。文件头带 GPU 名、厂商/设备号、驱动版本、`pipelineCacheUUID`、长度和 FNV-1a 哈希，任何一项不符或文件损坏就丢弃、从空缓存开始；先写临时文件再改名。应用把它放在 `cacheDir/vk-pipelines-<versionCode>.bin`，旧版本的文件启动时删掉（`EngineHost.kt`）。缓存只保存驱动对同一份 SPIR-V 的编译结果，输出逐字节不变。 |
| `0016-setup-cache-shared-lut.patch` | `core/setup_cache.hpp`、`core/setup_cache.cpp`、`pipeline/pipeline.cpp` | 缓存曾返回 `vector` 元素的裸指针，解锁后上传或拷贝 LUT 时，另一会话的 LRU 轮换、插入、淘汰会让指针指向另一张表或失效。改为共享、不可变的 LUT 载荷，调用方在上传和拷贝结束前持有所有权。缓存仍保留 4 项；读取者尚未结束的旧载荷延后释放。数值计算不变。GPT（OpenAI，AI 助手）参与共创。 |
| `0017-setup-cache-complete-gamut-key.patch` | `core/setup_cache.cpp` | 输入色域压缩的算法名和三个 `%.17g` 参数曾一起写入 64 字节缓冲，尾部截断会把不同设置误当成同一张 LUT。逐个追加完整参数；默认设置的键不变，数值计算不变。回归覆盖仅相差一个 double 步长、旧键相同的参数。GPT（OpenAI，AI 助手）参与共创。 |
| `0018-fir-demand-without-kernel-allocation.patch` | `core/numeric.{hpp,cpp}`、`pipeline/blur.{hpp,cpp}` | 卷积核和 halo 查询共用半径舍入函数，省去查询时的整条数组分配、指数和归一化。正 sigma 的核和半径保持相同；非正 sigma 在无符号转换前返回 identity。3,600 个边界与拒绝分配测试通过，不改变 FIR/IIR 阈值或光学参数。GPT（OpenAI，AI 助手）参与共创。 |
| `0019-bounded-fir-row-scratch.patch` | `gpu/gpu.hpp`、`gpu/vulkan_gpu.cpp`、`pipeline/blur.cpp` | 普通 FIR 的纵向中间结果按行暂存，供后续横向卷积读取；两个方向仍以相同顺序累加，减少大图活跃 GPU 缓冲容量，保留原 float32 算法。已在上一版 beta 发布。 |
| `0020-small-vulkan-pool-requests.patch` | `gpu/vulkan_gpu.cpp` | 小请求不复用容量过大的空闲缓冲，减少预览占用；大图和持久缓冲仍走各自的容量约束。已在上一版 beta 发布。 |
| `0021-dng-forward-matrix.patch` | `io/raw_camera.hpp`、`io/raw_decoder.cpp` | 支持有效 DNG ForwardMatrix，经 XYZ D50 转到线性 ProPhoto，去除已应用相对白平衡；缺失 FM 或未能核实校准签名时保留兼容转换。详见 RAW 验证记录。 |
| `0022-raw-white-balance-temperature.patch` | `host/white_balance.cpp` | 修正 Robertson 表 325 mired 处 u 值，避免约 3000 K 附近色温偏移。 |
| `0023-raw-explicit-white-temperature.patch` | `io/raw_camera.hpp`、`io/raw_decoder.{hpp,cpp}` | 将已知用户色温传入两种解码模式，明确选择双光源矩阵插值，避免反求中性点的多解导致目标偏移；保留旧入口与默认流程。 |
| `0024-raw-custom-without-as-shot.patch` | `io/raw_decoder.{hpp,cpp}`、`tests/raw_threads.cpp`、`tools/raw_render.cpp` | 有效 custom WB 不再要求未使用的源 WB；源元数据缺失时数组为零、标志为 false，默认解码仍拒绝。内部 C++ 布局变化要求消费者同步重编。 |
| `0025-vulkan-device-lost-preparation-guards.patch` | `gpu/vulkan_gpu.cpp`、`pipeline/pipeline.cpp`、Vulkan 测试与 CMake | 选择性跟进上游 Windows_Linux 的失效设备准备阶段保护，保留 Android 参数环、缓冲池、条带等补丁；锁后再检查，清理仍可用。注入失效后两种参数环模式各 16 入口检查。 |
| `0026-cam16-hoist-table-invariants.patch` | `core/cam16.cpp` | 吸收 Mac 主线 CAM16 的常量外提，减少色域表二分中的重复计算；保持运行时 float64 表生成、公开入口和串行调度，不引入预烘焙跨平台表。 |
| `0027-di-precompute-channel-curves.patch` | `core/digital_intermediate.cpp` | 只预拆 FilmHost 的各通道曲线，减少反复分配与复制，保持原插值、DIR 算序与完整 DI 拟合；不带入 minilab 或 log-positive 提前返回通路。 |
| `0028-vulkan-pointwise-fusions.patch` | `pipeline/pipeline.cpp`、Vulkan shader/registry、CMake 与测试 hook | 将 main Metal 的 DIR subtract+curve 和 XYZ→RGB+CAM16 两处融合转译到 Vulkan，保留分离核与原表达式顺序。默认关闭，只有 `SPK_VULKAN_FUSION=on` 显式启用；unset/off/其他值仍走旧核。同一 Linux llvmpipe 上 494 例、1,490,286 个有效 float32 值逐位一致，含完整渲染与分块尾部；不代表手机或跨 GPU 等价，也不列为默认手机提速。 |
| `0029-scene-curve-vulkan.patch` | `core/params.{hpp,cpp}`、`pipeline/scene_latitude.cpp`、`pipeline/pipeline.{hpp,cpp}`、Metal/Vulkan shader、Vulkan registry 与 CMake | 接入胶片前 SHOOT 层五行 `scene_curve`（rgb/luma/r/g/b），在测光中灰附近 -8..+8 stops 上依次处理 luma、RGB 与分通道曲线。空字符串精确旁路；显式恒等采样表不承诺逐位旁路。Android 编辑器、sidecar、风格导入导出、复制/重置和缓存失效已接入；无胶片时暂停但保留该编辑。 |
| `0030-nofilm-float-grade-boundary.patch` | `pipeline/engine.cpp`、`pipeline/view.hpp`、`pipeline/view_process.cpp` | 增加内部未调色 float view 结果，让 Android 无胶片路径先完成 CPU Layer2，再量化和输出变换，避免负值/HDR 在 RGBA16 边界提前丢失。公开 C `spk_result` ABI 不变；胶片/DI 保留旧路径。适配层缓存整图 RGB float（12 B/px），不是外存分块；几何/缩放仍先于 Android 调色，不能宣称任意非线性调色与上游先调色再缩放逐位等价。 |
| `0031-view-output-cancellation.patch` | `pipeline/engine.cpp`、`pipeline/view.hpp`、`pipeline/view_process.cpp` | 在 noFilm encode flush、几何/缩放完成、float CPU 读回分配前后检查取消；已提交 GPU 工作完成后才退出。Android generation token 继续贯穿 CPU 行转换、Bitmap/repack、JPEG/TIFF；所有 worker 汇合后才抛出，成功打包后才替换旧缓存。IBX 两编译模式聚焦测试及旧 JPEG/noFilm 回归通过，不能抢占在途 dispatch、读回或 codec 内部阶段。GPT（OpenAI）共创。 |
| `0032-dng-as-shot-white-xy.patch` | 新 `io/dng_white_xy.hpp`、`io/raw_camera.hpp`、`io/raw_decoder.{hpp,cpp}` | 与 LibRaw 0021 配套，将已知 xy 经 Robertson 和完整校准端点插值派生源白平衡；支持 FM/Bradford，源元数据不被 custom WB 替换。只扩展 WhiteXY-only 主 profile 三通道路径；既有 ASN 输出回归保持字节一致。详见 [实现与限制](DNG_WHITE_XY_2026-11-09.md)。GPT（OpenAI）共创。 |
| `0033-integer-view-readback-cancellation.patch` | `pipeline/view_process.cpp` | 为整数 view 增加最终 GPU 打包前、完成后、CPU 分配前和读回后的安全取消边界；Android DI adapter 传入同一 generation token，旧无输出变换的 look/直接打包按行轮询并在 worker 汇合后报告。实际 noFilm 整数/DI 分别 10/9 个边界及恢复对照通过；不能抢占在途 GPU 工作。GPT（OpenAI）共创。 |
| `0034-nofilm-float-row-readback.patch` | `pipeline/view.hpp`、`pipeline/view_process.cpp` | 内部 noFilm float view 可同步交付最多 32 行，Android 4:4:4 JPEG 直接调色并写入匿名 spool，省去整幅 CPU float 结果。沿用全图坐标、原数值与坏帧采样检查，false/throw/cancel 失败时不交付 JPEG；预览、胶片/DI 和 TIFF 保留原路径。内部 C++ Request 布局变化，消费者需同步重编；不代表完整 RAW 分块或全程峰值下降。见 [实现与验证](NOFILM_ROW_READBACK_2026-11-09.md)。GPT（OpenAI）共创。 |

0042 后续补充：[`0042-external-plane-publication.patch`](../patches/0042-external-plane-publication.patch) 将外存原型拆为独立 `external_plane.hpp`，追踪逐行覆盖和封存状态，失败或取消不发布输出，并共享 point/FIR worker 执行器。合法 kernel 数值与顺序保持，仍未接入生产 Pipeline。两云故障注入、逐字节 GPU 回归及恢复验证见 [报告](ENGINE_EXTERNAL_PLANE_PUBLICATION_2026-11-11.md)。GPT（OpenAI）共创。

0043–0045 后续补充：ASN 的校准签名与非单位相机矩阵修复、生产 Pipeline 失败恢复和结果提交、外存窗口精确容量与 CPU payload 准入。新 RAW 仍默认线性显示，外存 helper 仍未接入生产；双云对照、适用范围与未解决问题见 [源码跟进记录](ENGINE_ANDROID_FOLLOWUP_2026-11-11.md)。GPT（OpenAI）共创。

0046–0048 后续补充：Vulkan 实际分配与传输暂存分项统计、参数构建失败的事务边界，以及默认关闭的生产相纸印放外存尾段。每个 halo window 前回收旧尺寸闲置块，保留已有数值算序与失败后原输出。48 个补丁零 fuzz 重放，97 个受影响文件与测试源码一致；并非完整 RAW/胶片分块或进程内存保证。实现、配置和验证见 [生产尾段记录](ENGINE_PRINT_TAIL_INTEGRATION_2026-11-11.md)。GPT（OpenAI）共创。

0049 与 LibRaw 0023 配套保存 DNG `ProfileDynamicRange`，可选标准显色拒绝 HDR 或损坏的显式标记，默认线性解码不变。49 个引擎补丁零 fuzz 重放，97 个受影响文件与构建源码一致；45 个合成 DNG 与旧解码像素一致，完整 ARM64 原生库重编通过。没有实现完整 HDR 配置，详见 [动态范围合同](DNG_PROFILE_DYNAMIC_RANGE_2026-11-11.md)。GPT（OpenAI）共创。

0050 补齐 OpcodeList3 畸变、暗角及临时通道复制阶段的取消检查，OpenMP worker 汇合后才抛出；原数值保持。双云 80 组像素/完整元数据对照、取消/恢复和 ARM64 对象编译见 [RAW 镜头校正验证](ENGINE_RAW_LENS_CANCELLATION_2026-11-12.md)。GPT（OpenAI）共创。

0051 修正整数三通道 LinearRaw 的均匀非零 RGB 黑点缩放：LibRaw 的未用第四黑点槽不再阻止 common maximum 同步降低。只改变这一受限输入的像素，不改源元数据、Bayer、浮点或非均匀黑点策略；RAW 默认保持线性。双云与 Windows 参考、冻结对照、取消和 ARM64 编译见 [黑点归一化验证](ENGINE_LINEAR_RAW_BLACK_2026-11-12.md)。GPT（OpenAI）共创。

0052 新增显式 `SPK_IIR_BOUNDARY=triggs-v1` Vulkan 实验核，按常量延拓修正递归模糊的三个反向终态，保留完整 float-pair 状态与 float32 中间平面。仅接受有效 IIR sigma `[3,256]`，默认仍调用原 shader；这会有意改变实验图像，尚非手机默认算法或流式 IIR。双云独立延拓参考、默认冻结字节、alias 与 ARM64 编译见 [边界修正验证](ENGINE_IIR_BOUNDARY_2026-11-12.md)。GPT（OpenAI）共创。

0053 增加独立 `SPK_IIR_BOUNDARY=triggs-v2`，将同一递归滤波改为值、一阶差和二阶差状态，CPU 预合成小系数及边界坐标变换，减少大半径相消误差。实验 sigma 支持域为 `[3,1024]`，默认旧核与 triggs-v1 保持。双云大半径、混合 FIR/IIR、完整流程和冻结输出验证见 [大半径实验](ENGINE_IIR_LARGE_RADIUS_2026-11-12.md)。不是手机默认切换或物理模型升级。GPT（OpenAI）共创。

0054 在 Vulkan 整数转换与结果缓存前检查完整 RGB 的 NaN/无穷，并核对验证工作组计数；CPU 只回读 8 字节，异常时返回首个像素坐标/通道或不完整扫描错误。接入胶片输出、源图、打印缓存和 view/proof，正常有限负值、HDR 与成像运算保持原有定义。Android 异常帧重试同步改为先重建引擎再降档，避免复用故障状态。云端旧/新故障对照、正常像素哈希、API 37、30MP 和完整 JNI 构建见 [异常 RGB 与恢复修复](ENGINE_FINITE_OUTPUT_RECOVERY_2026-11-12.md)。GPT（OpenAI）共创。

0055 将 view 五行曲线表的 Fritsch-Carlson 斜率与切线改为每条曲线只准备一次，保持 double 算序和 float 表项。双云各 286 条曲线、130,611 个采样点逐位一致；108 组实际 Vulkan view/几何/proof 的完整 23,387,688 字节与旧版一致。ARM64 JNI 和异常输出回归通过。性能数值、复现命令及原始证据见 [曲线预计算验证](ENGINE_VIEW_CURVE_PREPARATION_2026-11-13.md)。GPT（OpenAI）共创。

本轮上游递归边界与核近似的独立复核见
[`ENGINE_ALGORITHM_RESEARCH.md`](ENGINE_ALGORITHM_RESEARCH.md)；0052 只接入默认关闭的 Vulkan 边界候选。

## LibRaw 补丁

LibRaw 固定在 0.22.2（与上游宿主一致）。`app/src/main/cpp/CMakeLists.txt` 在构建目录里复制一份 0.22.2，按文件名顺序打上 [`patches/libraw/`](../patches/libraw/) 的补丁再编译；补丁的哈希写进标记文件，改动补丁会重新复制。不整体升到 0.22.3：它改了 X-Trans 去马赛克，富士 RAF 的输出会和上游不同，所以只回移植需要的修复。

| 补丁 | 来源 | 解决的问题 |
|---|---|---|
| `0001-openmp-wavelet-denoise-size` | LibRaw #842（a6268d4） | OpenMP 分支的 `wavelet_denoise` 用未初始化的 `size` 分配内存。0.22.3 已正式修复；我们仍在 0.22.2 上，暂时保留，升级时删除。 |
| `0002-ljpeg-idct-table-once` | 本项目 | `ljpeg_idct` 的余弦表第一次调用时才填，多线程有竞争；改为只初始化一次。0.22.3 和 master 仍未修。 |
| `0003-xtrans-serial-tiles` | LibRaw #845 | X-Trans 去马赛克的瓦片重叠，多线程输出随调度变化；保持串行，与单线程输出一致。 |
| `0010`–`0016` | thetechgeekko/Spektrafilm-android（GPL-3.0） | 损坏或恶意 RAW 的分配与工作量上限（TIFF 元数据、LJPEG、DNG tile）；补丁头注明原文件和 sha256。 |
| `0017-normalize-model-orig-on-stack` | LibRaw #876（1a8fd32，0.22.3） | `GetNormalizedModel()` 的 `orig` 是函数静态变量，编辑和导出两个线程同时打开 RAW 时可能串用对方的机型名、色彩矩阵和白电平。改成局部变量。单线程输出不变。 |
| `0018-lossy-dng-curve-init` | LibRaw #862（62f7098，0.22.3） | 有损（JPEG）DNG 带 OpcodeList2 但没有 PolynomialMap 时，`lossy_dng_load_raw` 的色调表没初始化，栈里的内容会进像素。改成先填默认曲线，再由 opcode 覆盖。只影响有损 DNG（LinearRaw）；补丁 0006 的 GainMap 只处理拜耳 DNG，两者不重叠。 |
| `0019-sony-arw6` | 上游 SpektraLab b5467e8（PR #60）的 `third_party/libraw-arw6` 覆盖层，内含 LibRaw master 的 ARW6 解码器 | 0.22.2 解不了索尼 A7 V（ILCE-7M5）的有损压缩 ARW（Compressed / Compressed HQ，TIFF 压缩 32766）。上游以整文件覆盖的方式叠加；这里改成相对 0.22.2 加本目录补丁的差异，内容与上游覆盖层一致。新增 `sony_arw6.cpp`，其余只加 A7 V 的识别、裁切、色彩矩阵和黑白电平。`LibRaw::version()` 仍为 0.22.2；其他文件解码不变。 |
| `0020-crx-signed-quantizer-index` | LibRaw 0.22.3，`cf02d66eb5c19d4e46df7aee0582e47a4918509a` 的四处索引保护 | CRX 的 signed qParam 与 Golomb QP 可产生负余数；按维护版规则夹到首个表项，避免负索引。保留非负值算序，不声称修完极端移位边界。464 个 sanitizer 案例及五张真实 RAW 的十对输出验证见 [记录](../tools/native/validation/libraw-maintenance-20261109/result.json)。GPT（OpenAI）共创。 |
| `0021-dng-as-shot-white-xy` | 本项目，依据 Adobe DNG 1.7.1 / SDK | 保存 AsShotWhiteXY、完整有界校准签名与 parsed flags，按 selected RAW IFD/IFD0 回退传递，供引擎 0032 使用。超出 255 内容字节或 NUL 格式不支持时明确标记，不截断比较；不新增 Camera Profile IFD 选择。内部结构布局改变，头文件和整个 LibRaw archive 必须同步重编。GPT（OpenAI）共创。 |
| `0022-dng-render-metadata` | 本项目，依据 Adobe DNG 1.7.1 / SDK | 保留有界主 profile 曝光、黑位与色调曲线元数据，标记 extra/mixed profile；不改变线性像素。见 [验证](../tools/native/validation/dng-render-metadata-20261109/README.md)。GPT（OpenAI）共创。 |
| `0023-dng-profile-dynamic-range` | 本项目，依据 Adobe DNG 1.7.1 / 固定 SDK | 解析 UNDEFINED8 的 version/range/hint，区分缺失、SDR、HDR 与损坏值，沿用 selected-profile IFD 来源合同；不执行 HDR 曲线。内部结构改变，整个 archive 和消费者同步重编。GPT（OpenAI）共创。 |
| `0024-sony-ycbcr-cache-index-mask` | LibRaw `d7e09495734c527c9fdcd154e6435e06d20bb942`，0.22.3 的两行维护修复 | HuffTable 的 8192 项缓存按低 13 位寻址，合法索引及解码/消费顺序不变。81,920 个合法组合与 24,576 个受控高位组合通过；ASan/UBSan、合成 DNG 对照、18 补丁重放和完整 ARM64 JNI 链接见 [实施报告](ENGINE_SONY_HUFFMAN_CACHE_2026-11-13.md)。未把局部索引测试称为真实 Sony 故障文件复现。GPT（OpenAI）共创。 |

`crx.cpp` 在固定归档中为 CRLF。CMake 仅规范构建复制件的换行，再应用 LF 补丁；
该操作版本计入 stamp，不修改 pristine 依赖。本轮 ARM64 NDK 构建已重新应用整个 LibRaw 补丁集并通过编译。

0016 源自第三方的算法改动保持不变，当前仅更新其上下文以适配此前 0002 的一次性余弦表初始化。
原上下文引用已经删除的 `if (!cs[0])` 两行，依赖 GNU 默认 fuzz；重排后全套可按零 fuzz 重放，最终源码哈希不变。

## 写一个新补丁

```bash
bash scripts/fetch-upstream.sh upstream     # 上游 + 已有补丁
cd upstream && git init -q && git add -A && git commit -qm base
# 修改 engine/ 下的文件
git diff > ../patches/0005-short-name.patch
```

- 一个补丁只解决一个问题，文件名以序号开头。
- 在本文件的表格里写清楚动机和是否改变输出。
- 补丁必须用 LF 换行（`.gitattributes` 已设置）。
- 能提交给上游的修复，也请到上游提一份。
