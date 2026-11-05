# 引擎补丁 / Engine patches

本仓库不改动上游源码本身。上游 [SpektraLab](https://github.com/JamesQiu2005/SpektraLab) 在 CI 里按固定提交下载（见 [`scripts/fetch-upstream.sh`](../scripts/fetch-upstream.sh)），然后按文件名顺序打上 [`patches/`](../patches/) 里的补丁。补丁集合的哈希写进上游目录的标记文件和 CI 缓存键，改动补丁会自动重新解压。

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
