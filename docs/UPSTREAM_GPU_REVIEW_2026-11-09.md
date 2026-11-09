# Upstream main GPU and execution review, 2026-11-09

第 1 至 7 节记录上游源码审查和迁移评估；第 8 节补充随后完成的 Vulkan 融合实验及其证据。这不是手机性能验收：未更换 Android pin，未在 Apple 或 Android GPU 上运行这些新实现。

## 1. 精确基线与结论

- 本次 main：`d4b8145f6f9bdade15cda014776e99521adf0b5f`，上游 2.2.2。此前读取的 `d90cf9a005d5c7eb4c0bc1eb9cac17459c2a9289` 到此提交没有 engine 文件变化；本文源码链接统一固定到 d4b。
- Android pin：`a46627d250545a394ca21522f8a28b0fb89e4c42`，加本仓库 `patches/0001` 至 `0026` 及 Android adapter。不能用 main 文件覆盖这些修改。
- Windows/Linux：`a4289ab99bc09fc998e6148dc3d496f9079e5857`。其 device-loss 准备阶段保护已选择性进入 Android `0025`；这是另一分支的工作，不等于本次 main 审查的新成果。
- 本地审查副本：`D:/AGI/GPT/other/spektralab-backend-research/upstream-review-20261109/main`。GPU 两后端、pipeline、engine、disk_checkpoint、DigitalDecode 六个关键文件的 `git hash-object --no-filters` 均与 d4b GitHub tree blob SHA 相同。

main 值得吸收的是两个保持原算序的 Metal 融合、按实际收益准入的 pre-grain checkpoint、GPU 等待/执行统计和明确的缓存失效策略。它不是一套已经完成 Android 适配的全新执行器：Vulkan 没有获得这些 Metal 融合、磁盘 checkpoint 或 Metal 二进制归档；Mac 主应用仍调用整图引擎，计划中的 bands 目前只改变进度数量。

## 2. 确定缺陷：不能整套替换 Metal 后端

[metal_gpu.cpp:798-810](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/gpu/metal_gpu.cpp#L798) 的 `MetalGpu::trim_pool` 在释放一个空闲候选时，先执行 `delete b`，下一行又读取 `b->bytes` 累加 `freed`。只要确实有块被 trim，便执行释放后的对象访问，属于确定的 C++ 生命周期错误。pin 对应实现是在 delete 之前累加。

该结论来自实际源码及同段 diff，不是 sanitizer 运行记录；本轮没有运行 Apple 二进制。最小上游修复是先保存/累加 bytes 再删除，随后用 ASan 和真实 trim 返回字节数回归检查。Android 不编译这个 Metal 实现，因此本次并不是 Android 新暴露的运行时故障，但迁移时不能携带它。

## 3. 两个具体融合：收益真实，字节等价须重新建立

### 3.1 DIR：减法与曲线插值

- [pipeline.cpp:1323](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/pipeline/pipeline.cpp#L1323)：`node_dir_couplers` 在 Apple 路径派发 `spk_dir_finish`；Vulkan 仍先 `lincomb` 再 `curve_interp`。
- [dir_finish.metal:3](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/shaders/dir_finish.metal#L3)：每个像素的三个通道使用原来的乘加形式产生曲线输入，再调用已有 `interp_channel`。没有把曲线改成近似函数。
- 可消除一个 RGB float32 中间平面的写后读；按像素数据量推算，24 MP 平面为 288 MB，一次写加一次读为 576 MB。这是消除的中间数据量，不等于 live 峰值下降 288 MB；原管线已会在节点间释放消费完的输入，须另测峰值与带宽收益。
- Android 最小移植：新增 GLSL 核和 kernel registry，保留原组合路径作为 oracle/回退，先不改 command submission。新核只有点运算，不引入 halo 或随机数坐标变化。

### 3.2 XYZ 到 RGB 与 CAM16 gamut compression

- [pipeline.cpp:1806](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/pipeline/pipeline.cpp#L1806) 的 `node_xyz_gamut`，由 `print_linear` 在 Apple + `cam16ucs` 时选择。
- [gamut.metal:176](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/shaders/gamut.metal#L176)：先逐项计算三个 float RGB 分量，再调用与独立 kernel 共用的 `cam16_compress_pixel`。它没有把前后矩阵代数相乘，也没有删除中间 float 算序。
- 同样可省一个完整 RGB 临时平面和一次 dispatch。CAM16 中涉及非线性、极小数、边界分支，验证成本高于 DIR，应在 DIR 后迁移。

### 3.3 上游已有证据及其边界

[fusion_checks.cpp:20](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/tests/fusion_checks.cpp#L20) 直接创建 Metal backend；以分离核输出对融合核作完整 float32 `memcmp`，交错执行顺序，并分别记录 native GPU 时间和 wall time。输入含负值及大于 1 的值；CAM16 测例用 sRGB setup、一个矩阵和一组固定参数。它不是六色域、全部曲线或全部手机 GPU 的覆盖。本次仅审查测试代码，未重新执行该 Metal 测试。

[build.sh:14](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/build.sh#L14) 仍明确 safe math、precise FP32 和运行时 math probe；这些约束不能在移植时省略。Metal 测试字节相同不推出 Vulkan 字节相同，也不推出不同手机 GPU 之间字节相同。

Android 接入前应做到：同一设备上，旧核与新核比较所有中间 float32 字节；覆盖曲线 knot 两侧、夹取端点、负值、HDR、六输出色域、CAM16 lightness 开关与 knee 边界，以及工作组尾部和二维 dispatch 展开边界。检查 SPIR-V 的 `NoContraction`/实际算序，并使用现有 fused/split FMA 探针。不同 GPU 的结果另列误差与设备名单，不能将软件 Vulkan 结果冠以真机字节承诺。

## 4. Checkpoint：缩短重复编辑，不是外存分块引擎

[engine.cpp:1106](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/pipeline/engine.cpp#L1106)、[pipeline.cpp:2233](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/pipeline/pipeline.cpp#L2233) 和 [disk_checkpoint.cpp](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/pipeline/disk_checkpoint.cpp) 构成实际实现：

1. 图的切点固定在 `film_grain` 之前，并运行时核对 stage 序列没有移位。恢复后仍执行 grain 及其后续节点；随机种子重新按原 grain sampler 规则生成。
2. key 从当前全部公开参数重建，仅排除 `grain_amount`、`grain_active`、`grain_sublayers_active`，另含 tier、尺寸、stage version；session identity 隔离源图。非 grain 编辑主动释放 checkpoint，同名 stock reload 也不复用。
3. 首次冷渲染只记录前缀；实际 grain 编辑且前缀已观察过后才尝试保存。全进程只保留一个临时文件，预算 1 GiB，非强制测试时跳过小于 20 ms 的前缀。
4. 保存/恢复计时参与准入：写入两倍耗时不划算则拒绝，首次读回后若写加读不划算就退出缓存。失败、短文件、错误 header 退回正常计算，结果只在成功读完整后交付。
5. 保存调用 `flush` 后从 `contents` 写出；恢复先 `alloc(bytes_)` 创建整个 RGB 平面再读入。它仍要求整图源、整图恢复平面、后续工作集与输出。

实现只在 `__APPLE__` 下启用，依赖共享内存 `contents` 和 Darwin `F_NOCACHE`。Android Vulkan 的 device-local buffer 不等于可直接读写的 host pointer；可借鉴 cache key、原子失败和收益准入，但需要通过 `read`/`write` 或显式 staging 搬运，并按手机空闲空间、热状态及 I/O 成本设预算。不可简单移除 `#ifdef`。

上游 [scene_curve_checkpoint_checks.py:24](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/tests/scene_curve_checkpoint_checks.py#L24) 将 forced-full 与 checkpoint 的 12 步输出逐数组比较，并要求 trace 确有命中。Android 还需断点文件故障、低空间、两个 session 抢占、取消和设备丢失测试；普通功能测试不能代替这些路径。

真正的低内存原图方案仍须把 source、intermediate、output 改成可分段存储，按节点分别处理 pointwise、FIR halo、IIR 状态携带和 Whole 统计。把 pre-grain 平面写到磁盘不解除单 SSBO 大小限制，也不让 1 GiB 预算自动变成 1 GiB GPU 可用内存。

## 5. 调度、资源、缓存的实际差异

| 项目 | main 实际实现 | Android 迁移判断 |
| --- | --- | --- |
| Metal dispatch | 编码到 command buffer，但 [pipeline.cpp:1882](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/pipeline/pipeline.cpp#L1882) 仍每节点 flush，以限制未完成资源占用 | 不能理解为整帧无等待批提交；已有 Android 批提交实验不能因此直接开启 |
| Vulkan dispatch | [vulkan_gpu.cpp:488](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/gpu/vulkan_gpu.cpp#L488) 每次上传 inline、建毁 descriptor pool，`submit_sync` 提交后 `vkQueueWaitIdle`；`flush` 为 no-op | main Vulkan 没有 Metal 两个融合，也没有更先进的 Android 调度器；覆盖当前文件会丢改进 |
| GPU 统计 | [metal_gpu.cpp:841](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/gpu/metal_gpu.cpp#L841) 记录 command 数、GPU 时间、最长 wait、最长 commit-to-start；engine progress 暴露字段 | 优先借鉴。Vulkan 需检查 timestamp 支持、valid bits/period；没有校准时不能将 CPU 与 GPU 时钟直接相减，也不能填造 queue time |
| Metal 编译缓存 | [metal_gpu.cpp:1033](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/gpu/metal_gpu.cpp#L1033) 读取随包 `.metalar`，miss 回 safe IR，required 模式仅作验证 | Metal 原生二进制不能用作 Vulkan pipeline cache。Android `0015` 已有带设备/驱动身份校验的运行时缓存，保留；可补冷/热 cache 命中耗时记录 |
| Metal 生命周期 | flush/pipeline/encoder 增加局部 autorelease pool，显式 retain encoder/device；borrowed decode 不重复计 persistent bytes | 可借鉴所有权与审计方式，不能直接照抄 Objective-C/Metal API；同时必须排除本报告的 trim UAF |
| Setup cache | main 的 `tc_lut` 仍返回缓存容器内原始指针，key 仍只拼部分 gamut 参数 | 必须保留 Android `0016` shared ownership 和 `0017` 完整 key；不要因 main 新增 baked data/minilab 而回退这些修复 |
| 整图缓存 | main `negative_for` 仍另建完整 file-backed negative 并 copy | Android pin 的 persistent promotion、阶段 trim 和现有池策略不能丢失；Mac file-backed memory 不等于通用 Vulkan 外存资源 |

Vulkan 时间戳约束的原始依据：[Khronos Vulkan Queries](https://docs.vulkan.org/spec/latest/chapters/queries.html)。此处建议只涉及计时可观测性；没有假定所有 Android queue 都提供相同精度。

### 当前 Android 必须保留的执行改造

- `0001/0002` 的 IIR 和 dispatch 计数分段；`0010` 依据 requested size 检查 descriptor storage range。
- `0008/0025` 的 device-loss latch 与准备阶段/锁内重查；失败后不得继续获取旧设备资源。
- `0012` 的复用 descriptor pool、`0014` 的对齐 mapped inline ring、`0015` 的 Vulkan pipeline cache。
- `0013` 的 reprint 常量复用、`0016/0017` 的 setup cache 所有权与完整 key。
- `0018/0019/0020` 的 FIR demand、受限行缓冲及小请求池上界。它们已是 Android beta 的既有能力，不能在 beta2 中重新宣称为新功能。
- pin 自身已有的临时平面消费、persistent promotion、阶段 trim，加 Android adapter 的线程/取消/设备恢复行为。

## 6. 主应用的 bands 与无胶片路径

[SpkEngine.swift:478](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/app/SpektraLab/Sources/Scheduler/SpkEngine.swift#L478) 在 `.bands(rows)` 分支只计算 `bandCount`，注释明确引擎仍渲染整帧。因此不能从 scheduler 的 band 类型、预算图或进度显示推断 main 已实现完整流式渲染。engine 内已有 striped API 和 IIR sweep 也不能等同于产品入口已使用它，更不能等同于 source/output 已离开整图常驻。

[DigitalDecode.swift:7](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/app/SpektraLab/Sources/Engine/DigitalDecode.swift#L7) 的 no-film 不是直接展示供胶片使用的 linear RAW：它保留 `CIRAWFilter` 默认 boost、tone map 和 gamut mapping，仅调整白平衡/镜头矫正。内部 CIContext 使用 extended-linear sRGB + RGBAh，但 [第 89 行](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/app/SpektraLab/Sources/Engine/DigitalDecode.swift#L89) 已输出 ROMM/ProPhoto RGBA16，随后创建 `rgba16Unorm` 纹理交给通用 Layer2。

因此，main 本身并不是负值/HDR float 一直保留到 Layer2 的 oracle。对 Android 的启发是区分“胶片前的 scene-linear 输入”与“无胶片的默认照片显影”，明确 tone map、gamut mapping、CCTF 和最终量化的责任及顺序。已记录的 Android noFilm 提前 UNORM 裁切不能靠宣称复制了 Mac float 通路解决；若目标是在 Layer2 曝光降低时恢复超过 1 的高光，仍需单独设计 float 中间接口并重新验证预览/导出。

DigitalDecode 虽按约 4 MP band 调 CI 输出，目的也是降低 CI 临时缓存；[第 69 行](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/app/SpektraLab/Sources/Engine/DigitalDecode.swift#L69) 已分配完整结果 buffer，不能计为外存流式照片管线。

## 7. 建议后续实施顺序与验收

| 顺序 | 范围 | 必须证明的结果 |
| --- | --- | --- |
| 1 | 保持 pin，吸收 GPU 时间与缓存命中统计；不改像素算序 | 支持/不支持时间戳均能工作；错误时间不写成有效值；渲染字节及资源生命周期不变 |
| 2 | 只移植 DIR 融合；随后 XYZ/CAM16 融合 | 每个真机 GPU 上新旧中间 float32 全字节比较；边界矩阵/曲线、negative/HDR、六色域；峰值和耗时独立测量 |
| 3 | Android 专用 pre-grain cache，先小预览和显式开关 | cache on/off 同输入同 seed 一致；保守失效、失败原子性、收益准入；记入 staging/RSS/文件占用，不只看 GPU pool |
| 4 | 真正 bounded source/intermediate/output 与节点分段执行 | 原图而非缩图，通过 advertised `maxStorageBufferRange`；halo/state/global统计正确；预览与导出尺度一致；资源峰值有上界 |
| 独立产品决策 | noFilm 默认显影与 float Layer2 | 明确 scene/display 色域、曝光与量化顺序，负值和 HDR 色块及真实 RAW 回归；不冒充与 Apple 私有解码逐字节相同 |

已选择性移入的 `0026` CAM16 setup 常量提升，以及 main minilab、baked table、scene-curve 等材料/颜色变更，应参见本轮上游总评和各自数学证据。不得把模型输出变化与本报告的等价执行优化混为同一类。全量跟随 main 既会改变照片语义，也会丢失 Android 已验证的内存/恢复保护。

## 8. 后续实装：0028 Vulkan 融合，默认关闭

已新增 [0028-vulkan-pointwise-fusions.patch](../patches/0028-vulkan-pointwise-fusions.patch)，在 pin + `0001` 至 `0027` 上为 Vulkan 实装 DIR subtract/curve 和 XYZ-to-RGB/CAM16 两个融合核。旧核和分离入口完整保留；未合并前后矩阵、未引入 fast-math，也未为得到相同结果而修改旧核算式。两个核保留 Android 一维分段 dispatch 的 base 索引和现有资源所有权。

**手机默认仍走原分离路径。** 只有精确的 `SPK_VULKAN_FUSION=on` 才启用实验融合；环境变量未设置、`off` 或其他值都走旧路径。它是开发诊断入口，普通 Android 用户无法据此方便地恢复渲染，因此不能把环境回退当成正式用户控制，也不能把本实验列为本轮默认手机提速。没有依据 host 厂商或软件 Vulkan 结果自动信任手机 GPU。

独立目录 `/data/work/gpt-vulkan-fusion-20261109` 使用 Release、native RAW/host/testing 关闭、构建并行度 1，实际链接生产引擎 archive。最终构建和 [run_vulkan_fusion.sh](../tools/native/run_vulkan_fusion.sh) 均退出 0；[vulkan_fusion.cpp](../tools/native/vulkan_fusion.cpp) 得到：

- 494 个 case、1,490,286 个有效 float32 值逐字节相同。包括 48 个 DIR 独立核 case，覆盖曲线 knot 与相邻值、端点、负值/HDR；432 个 XYZ/CAM16 独立核 case，覆盖六色域、lightness 开关和三组 knee。
- 12 个完整流水线 case 实际设置 fusion off/on，比较六色域乘以 paper/DI；输入为 67×49 合成 scene-linear HDR 图，Portra 400/Portra Endura，关闭 grain 和 auto-exposure。这不是实样 RAW 成图验收。
- 另外两个完整渲染确认环境未设置和非法值均使用分离路径，并与明确的 off 逐字节相同；XYZ 融合 timing 标记用于确认实际选中的分支。
- 尾部哨兵未被改写。仅降低 group-X 上限的测试钩子覆盖非零 base 的多次一维 chunk；没有越过 advertised buffer limits，也没有运行真正二维 dispatch。
- 对照输出含 640 个非有限值，融合输出保留其相同位模式。这些来自已有强负值 CAM16 域，不能将此次结果写成“所有输入输出均有限”。

设备为 llvmpipe LLVM 15.0.7、Mesa 23.2.1、Vulkan 1.3.255；math probe 实际选择 Dekker split 路线，fused-FMA residual 不满足该探针。尚未覆盖 Adreno、Mali、硬件 fused-FMA 路线或跨 GPU 字节一致性。保留 GLSL 表达式并不足以证明驱动跨原 SSBO 物化边界后的 contraction/rounding 与原来相同，真机准入仍需分别运行新旧核对照。此次未测手机耗时和资源峰值。

最终补丁 `git apply --check` 和 GNU patch `--fuzz=0` 均通过，七个应用后源码与独立工作树逐字节一致；原 `spk_curves`、`spk_matmul3`、`spk_lincomb3`、`spk_cam16ucs_compress` 和 `spk_view_cam16` 的 SPIR-V 也与原生产构建逐字节一致。记录位于 `D:/AGI/GPT/other/spektralab-backend-research/vulkan-fusion-20261109/evidence/`，包括完整/增量构建日志、最终测试日志、设备信息、源码应用 SHA256 和原核 SPIR-V 对比。

最终引擎 archive SHA256 为 `79d642c96b2c7b50bd711b38f896a3dfb469f6993228a27db078d9528cbe338a`。该独立构建不含并行开发的 `0029/0030`；叠加后的生产构建和其他功能回归须单独记录，不能用本次结果代替。
