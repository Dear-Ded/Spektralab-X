# 整链外存分块：设计说明与第一阶段，2026-11-10

状态：设计加第一阶段已实现并在 AOIDC 验证。本文只写已在源码或实测中核对过的事实，未核对的标为待验证。不含私人照片或测量数据。依据：[实施路线](ENGINE_OPTIMIZATION_ROADMAP_2026-11-09.md)、[深度复核](ENGINE_DEEP_REVIEW_2026-11-09.md)，以及上游 pin `a46627d` 加本仓库补丁 0001-0038 的源码。

## 1. 问题与目标

完整 source、negative、output 仍整幅驻留，而 Vulkan 后端按单个缓冲检查 `maxStorageBufferRange`。严格 128 MiB 范围下，一幅整图装不进一个缓冲。目标是让桌面胶片质量在手机内存里跑完：保持原算序、原 float32 存储精度和原量化点，不靠调小参数，也不靠拆 dispatch。

## 2. 已核对的现状

- `Image` 是整幅紧凑 float32，没有 stride 和 padding，所有 kernel 用 `n_threads = h * w` 一维寻址（`engine/src/pipeline/image.hpp`）。
- `vulkan_gpu.cpp` 的 dispatch 对每个缓冲参数按 `requested_bytes` 与 `storage_range_limit_` 比较，超出即返回 `buffer exceeds maxStorageBufferRange`。补丁 0002 只把线程数拆成小于 2^24 的批次，不解除这个单缓冲限制。
- 上游已有 RFC-020 条带执行器（`Pipeline::run_stages_striped`、`strip_plan`、`band_from`、`band_into`）。它只把 Pointwise 阶段按条执行，且每个条带都从整幅 plane 复制出一块新缓冲，每个 run 之后还要分配整幅 `plane_out` 再写回。所以它不降峰值。路线文档记录的 24 MP 实测是 GPU 池峰值 1126 MB 对 1106 MB，慢约 8%。
- `choose_strip_height` 接收 `budget_bytes` 但不使用（RFC-021 未落地）。
- Vulkan 的 `alloc_file_backed` 目前直接退回 `alloc_persistent`，没有真正落盘。
- 阶段表（`kFilmStages` / `kPrintStages`）：Pointwise 为 `film_scale_and_expose`、`film_log_and_curves`、`print_spectral`、`print_linear`、`print_output`；Neighbourhood 为 `film_blurs`、`film_couplers`、`print_scan_finish`；Whole 为 `film_boost`、`film_overscan`、`film_grain`、`print_glare`。
- `film_log_and_curves` 与 `film_couplers` 之间 `cur` 和 `log_e_film` 同时是整幅 plane，比 RFC-020 的账本多一幅。

## 2.1 本设计要换掉什么

不是再造一套执行器，而是把 RFC-020 的整幅 `plane_out` 换成有容量上限的外存 plane：条带直接 produce 到外存、下一阶段 consume，任何时刻 GPU 侧只持有带宽不超过预算的缓冲。

## 3. 有界 plane 与 producer/consumer

- `BoundedPlane`：宽 w、通道 c、总高 H 的逻辑 float32 plane，后备是匿名外存文件（沿用 `rgba8_spool.hpp` 的创建、fallocate 精确预留、失败清理和 seal 约定）。对外只提供 `write_rows(y0, rows, data)` 和 `read_rows(y0, rows, out)`，行号始终是 plane 全局行号。
- 带高由两个上限取小：单缓冲 `rows * w * c * 4 <= range`，以及所有同时存活带缓冲的字节和 <= 预算。唯一选择高度的位置仍是 `choose_strip_height`，实现 RFC-021 预留的预算入口。
- producer 写、consumer 读都带全局 `y0`。禁止隐含偏移：带内像素下标不能被当作帧坐标。
- 外存只是 plane 的后备，不改变 float32 存储精度，不引入新的量化。

## 4. 各类阶段的合同

| 类别 | 合同 | 验证方式 |
| --- | --- | --- |
| 点运算 | 带内每个像素的输入、元数据常量与整幅时完全相同，不读邻域和全局统计。带划分不得改变 kernel 的算序与浮点表达式 | 多块高、多块序、多线程下与整幅基线逐字节比较 |
| FIR halo | 一个 run 的 halo 是各阶段半径之和，不是最大值。halo 在 plane 边缘处截断而不是反射，这样 kernel 在带边缘的反射点与整幅一致 | 三个以上条带、同一 run 内两个 FIR 阶段，逐字节 |
| IIR 递归 | 沿整行或整列的递归状态跨块传递，保持扫描方向、首尾条件和更新次序。每块重启状态会造成接缝，禁止 | 对原效果逐字节；若需转置，转置位置按原图算序记录 |
| 全局统计 | 先整幅扫一遍得到同一组统计（例如 `boost` 的帧最大值，自动曝光），再执行有界阶段。归并树顺序改变会改变浮点末位，需固定归并树或明确等价级别 | 与整幅统计比位 |
| 颗粒 RNG | 原种子、原物理全帧坐标、原项目索引。不得每块重新播种，不得用块尺寸决定粒径 | 多块高下逐字节 |
| 输出 | 接 TIFF strip / JPEG scanline 行路径，不再整图 Bitmap | 既有 `run_output.sh`、`run_jpeg_rows.sh` |

## 5. 为什么第一阶段选 print_linear 链

选择条件：无 halo，不读全局统计，不依赖坐标，输入输出都是 RGB float32。核对结果：

- `node_xyz_to_rgb` 调用 `spk_matmul3`，`node_gamut_compress` 调用 `spk_cam16ucs_compress`，`node_cctf` 调用 `spk_cctf_encode_matrix`。三个 kernel 的像素下标都只来自 `gl_GlobalInvocationID` 加 `spk_pc.base`，由 `meta.value[0]` 限界，没有帧坐标，没有跨像素读取。
- CAM16 kernel 的逐像素计算只读 `constants` 和 `cmax` 表，不读邻域。`SPK_VIEW_STATS` 路径里有对统计缓冲的 atomicAdd，该宏在管线路径是否启用待验证；第一阶段的测试要确认未启用，或把它作为分块边界之外的统计另行处理。
- 链的常量（`xyz_to_rgb`、CAM16 表、`output_matrix`、CCTF 模式）都是帧级常量，由 `baked_` 持有，分块时只上传一次。
- 本阶段不包含 `node_edr`，因为它依赖校准表，尚未逐项核对。

## 6. 第一阶段范围与验收

范围：对一幅外存输入 plane，按带执行 `xyz_to_rgb -> gamut_compress -> cctf`，输出写入外存 plane，且每次 GPU 缓冲小于严格的 128 MiB 范围。

验收（沿用 `tools/native/run_*.sh` 风格，在 AOIDC 上）：

1. 与整幅基线（同三个 kernel 依次对整幅分配）逐字节相同。
2. 带高：1、3、32、65 以及不整除总高的值；带宽至少两种；带顺序：正序、逆序、乱序；线程：单 worker 与多 worker 并发消费。
3. 输入包含负值、HDR、signed zero、次正规数和邻近浮点值，仿照 `vulkan_fusion.cpp` 的输入集。
4. 峰值内存：独立进程测 `/proc/self/status` 的 `VmHWM`（沿用 `jpeg_rows.cpp` 的 `clear_refs=5` 重置做法），并记录 `Gpu::begin_memory_observation` 的 live 峰值。整幅基线与分块各自独立进程。
5. 把严格范围测试放进验收：整幅输入超过范围时基线失败而分块通过。

## 7. 不做什么

- 不改变任何阶段的数学或算序，不改变默认渲染。分块路径默认关闭，由环境变量或参数显式开启。
- 不宣称手机内存或 Adreno 行为。云端 llvmpipe 的结果不能换算手机。

## 8. 下一阶段（按顺序，每个单独验收）

1. 扩到整个 Pointwise run，包括 `print_spectral`、`film_scale_and_expose`（先核对 `scene_latitude` 的统计）。
2. FIR halo：把 run halo 之和接到外存 plane 的读取上。
3. 全局统计阶段（`boost` 的最大值）：先扫一遍再带化。
4. IIR 跨块状态。
5. 颗粒：保持全帧坐标和种子。
6. 与 TIFF/JPEG 行输出对接，去掉整图输出缓冲。

## 9. 第一阶段实测结果（2026-11-10）

补丁 0039 只新增 `engine/src/pipeline/bounded_point_chain.hpp`：外存 plane、唯一的带高选择函数 `choose_band_rows`、以及对 `spk_matmul3`、`spk_cam16ucs_compress`、`spk_cctf_encode_matrix` 逐带执行的 producer/consumer。引擎里没有任何调用点，默认渲染不变。验证程序是 `tools/native/bounded_point_chain.cpp`，入口 `tools/native/run_bounded_point_chain.sh`。全部为合成像素。

环境：AOIDC，GCC 11.4，llvmpipe（LLVM 15.0.7），pin a46627d 加 0001-0039 补丁，严格 128 MiB 存储范围（`SPEKTRAFILM_VULKAN_STRICT_LIMITS=1`）。

- 逐字节一致：901 个用例、102,764,160 个 float32 值，与测试里独立写出的整幅三 kernel 基线 memcmp 相同。覆盖 6 个输出色彩空间、尺寸 1x1 至 512x300、带高 1/3/32/65/整高、正序/逆序/乱序、1 与 4 个 worker。输入含负值、HDR、signed zero、次正规数和邻近浮点值。另有 96 个 `choose_band_rows` 边界检查（带宽不超过存储范围与字节预算）。
- 严格范围：4097x3073 整幅 `spk_matmul3` 被拒绝（buffer exceeds maxStorageBufferRange）。分块在同一范围下通过：682 行、5 带、最大带 33,529,848 B。17 行、181 带、逆序单 worker 的结果与它逐字节相同，三个 37 行窗口与整幅 dispatch 逐字节相同。
- 峰值内存（4097x3073，独立进程，阶段前用 clear_refs=5 复位 VmHWM）：整幅 VmHWM 801,008 KiB、GPU live 302,161,944 B；分块（341 行）VmHWM 178,480 KiB、GPU live 33,529,848 B；严格范围下分块 177,912 KiB。三次输出 FNV-1a 同为 3afc671bf4770eef。整幅一次只能在 CPU 设备放宽范围时运行，严格范围下它会失败。

边界：这是 llvmpipe CPU 设备，不是 Adreno/Mali，不能换算手机 RSS，也没有热状态测量。GPU 链由一把互斥锁串行，多 worker 只重叠外存读写，未测速度。输入输出 plane 在外存，页缓存没有单独记账。该链没有接入 Pipeline，不含 `node_edr`，没有覆盖 DI 或融合 kernel 路径。

下一步：把这条链接入 `run_stages_striped` 的 Pointwise run 以替换整幅 `plane_out`，再扩到整个 Pointwise run（见第 8 节）。

## 10. 第二阶段实测结果（2026-11-10）

补丁 0040 新增 `engine/src/pipeline/bounded_print_run.hpp`：带 halo 的默认打印 run（xyz→rgb、CAM16、unsharp、CCTF）外存执行。run 的垂直 halo 是各阶段半径之和。每个带读取窗口 `[y0-halo, y0+rows+halo)` 并在 plane 边缘截断，内部依次执行四个阶段，只把中心 `rows` 行写回外存。unsharp 直接复用 `Blur::gaussian` 和 `Blur::lincomb`，与 `node_unsharp` 调用相同的 FIR 和 lincomb kernel，不是手抄实现。引擎里没有调用点，默认渲染不变。验证程序是 `tools/native/bounded_print_run.cpp`，入口 `tools/native/run_bounded_print_run.sh`。全部为合成像素。

环境：与第一阶段相同（AOIDC，GCC 11.4，llvmpipe，pin a46627d 加 0001-0040 补丁，严格 128 MiB）。

- 逐字节一致：2251 个用例、75,470,400 个 float32 值，与测试里独立写出的整幅四阶段基线（含 unsharp）memcmp 相同。覆盖 3 个输出色彩空间、5 种 unsharp (sigma, amount) 配置（包括关闭、halo=0、halo=2、halo=5）、尺寸 1×1 至 255×131、带高 1/3/32/65/整高、正序/逆序/乱序、1 与 4 个 worker。另有 288 个 halo 与带高选择边界检查（窗口不超过存储范围与字节预算）。
- 严格范围：4097×3073 整幅窗口被拒绝（spk_matmul3: buffer exceeds maxStorageBufferRange）。分块在同一范围下通过：sigma=0.7、amount=0.7 时 halo=2、405 行、8 带、最大窗口 20,108,076 B。另一带高（97 行、32 带、逆序单 worker）的结果与它逐字节相同。

边界：同第一阶段，llvmpipe CPU 设备，不是 Adreno/Mali，不能换算手机。GPU 链由一把互斥锁串行。未测峰值内存降幅（第一阶段已证实从 801 MB 降到 178 MB）。该链只覆盖默认打印 run 且 scanner、overscan、EDR、DI、融合均关闭时才走外存路径；默认的 `unsharp_mask = {0.7, 0.7}` 会生效并带 2 行 halo。未接入 Pipeline。

下一步：把它接入 `run_stages_striped` 的 Pointwise run（第 8 节第 1 项），扩到整个 Pointwise run，再处理全局统计、IIR、颗粒与行输出（第 8 节第 2-6 项）。
