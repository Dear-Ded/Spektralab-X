# GPU 与 Android 原生二进制核查（2026-11-11）

共创：GPT（OpenAI）。对象为固定上游 `a46627d250545a394ca21522f8a28b0fb89e4c42` 加 0001..0048，Android 当前源码与 `USE_ZLIB` 修复。只编译 `.so`、检查自有源码与公开格式；未生成 APK、未安装、未发布、未改 pin 或版本。软件 Vulkan 结果不代表 Adreno。

## 可复核结果

| 检查 | 实证 |
|---|---|
| Android 原生完整构建 | NDK `27.2.12479018`、API 29、arm64-v8a、RelWithDebInfo、`c++_static`，186/186 步成功 |
| 调度与资源 | 4 vCPU Xeon E5-2667 v2，约 8 GiB RAM；可见祖先 cgroup `cpu.max=max 100000`、`memory.max=max`。独占 `-j4`，wall 78.51 s、user 280.05 s、sys 20.93 s、CPU 383% |
| 编译内存指标 | GNU time 最大 RSS 326,632 KiB，是命令/子进程的最大值，不能当作四并发进程合计峰值；未记录全 cgroup 峰值 |
| ELF | 30,581,928 bytes，ELF64、小端、AArch64；3 个 LOAD 段对齐均 `0x4000`。未检查 APK ZIP 对齐，因为未打包 |
| 运行库 | NEEDED 仅 `libandroid.so`、`liblog.so`、`libjnigraphics.so`、`libz.so`、`libdl.so`、`libm.so`、`libc.so`；没有动态 `libomp.so`、`libc++_shared.so` 或 `libvulkan.so` 依赖 |
| 符号 | 38 个已定义 JNI 函数导出；LibRaw 静态归档引用 `uncompress` 和 `__kmpc_*`，实际编译命令包含 `USE_ZLIB`、OpenMP。JNI 的 zlib 依赖与 Deflate 解码编译开关分别检查 |
| shader | 53 个 SPIR-V 模块逐个 `spirv-val --target-env vulkan1.0` 成功。Android 构建与匹配宿主构建的 53 份模块 SHA256 逐份一致 |

本轮 `.so` SHA256：`47991f9a2574ed5e53c82c08203ccbed9d04641f2c463bbaeda905ee8d199c00`。这证明交叉编译/链接/ELF 布局，不证明手机能加载或图像数值、速度、热量已过实机验收。

## SPIR-V 布局与数学合同

实际 glslang 为 11.8.0，命令 `-V --target-env vulkan1.0`，没有额外优化或 fast-math 参数；SPIRV-Tools 为 2022.2-dev。53 模块共 513,240 bytes，均为 SPIR-V 1.0，只声明 `Shader` 能力；浮点类型全部 FP32，整数全部 32 位，无 FP16/FP64 运算、Int64 或 subgroup 能力要求。51 个模块 local size 为 `(256,1,1)`，两个数学探针为 `(1,1,1)`。

SSBO 使用 set 0、连续 binding、std430 标量数组；反汇编数组 stride 为 4，RGB 像素为三个连续 float。chunk base 是 offset 0 的 4-byte push constant。内联参数环依据实际 `minStorageBufferOffsetAlignment` 且至少 4 字节对齐；非 coherent staging 的 flush/invalidate 依据 `nonCoherentAtomSize` 向上扩展或截到真实 allocation 末尾。ELF 16 KiB 页对齐、SSBO offset 对齐、非 coherent atom 对齐是三个不同合同。

反汇编共 703 个 `NoContraction`，0 个 `RelaxedPrecision`，显式 `Fma` 仅在数学探针、IIR 双 float、几何双 float 三个模块。IIR/几何通过 specialization constant 0 选择 FMA 或 Dekker split；启动探针对 256 组有界正常输入逐项精确验证高/低字，失败拒绝后续 dispatch。llvmpipe 本轮 FMA residual 不符、Dekker 通过。探针并未覆盖全部有限值、denormal、无穷/NaN，也不是整管线跨驱动逐 bit 证明。

`NoContraction` 不是全局启用：例如 `spk_matmul3`、`spk_lut2d_cubic`、FIR、spectral epilogue 没有该装饰；3D print LUT 有 37 个，scene curve 有 24 个。没有声明 RTE/DenormPreserve/SignedZeroInfNanPreserve execution mode。Khronos [浮点规则](https://docs.vulkan.org/spec/latest/appendices/spirvenv.html#spirvenv-fp) 允许未禁止的重排并定义各操作精度；[GLSL.std.450 Fma](https://registry.khronos.org/SPIR-V/specs/unified1/GLSL.std.450.html) 也不能代替本引擎的实测误差合同。

宿主 C++ Release 实际命令为 `-O3 -DNDEBUG`，Android setup 为 `-O2 -g -DNDEBUG`，均未全局加入 `-ffp-contract=off`。Android `hanatos.cpp.o` 反汇编发现 115 条 `fmadd/fmla/fmsub` 指令。它们不能据此判错，但禁止宣称整个 setup 已逐指令保持非融合或跨架构逐 bit 相同。不得为了形式统一擅改胶片数值；先做 setup 数值对照和物理设备参考。

## 设备能力和内存边界

后端实际要求 workgroup 256、每 stage/set 至少 8 个 storage buffer。Vulkan 1.0 的基础最低保证分别只有 128 个 invocation、4 个 stage storage buffer，因此“模块为 Vulkan 1.0”不等于支持所有 Vulkan 1.0 设备；代码已有明确拒绝。参见 [Khronos limits](https://docs.vulkan.org/spec/latest/chapters/limits.html#limits-minmax)。

`maxStorageBufferRange` 是每个被 shader 绑定范围的上限，不是 GPU 总内存/单进程预算。128 MiB 对 RGB FP32 是 11,184,810 像素，对四 float 是 8,388,608 像素；多块 live、池、传输 staging、CPU 数组、bitmap 和 driver 仍要相加。CPU 软件设备扩大 range 的探针没有授权真实 GPU 超过其公告范围。当前 dispatch 分成 1D chunk，既尊重设备 group count，又不超过 2^24 线程并传 base；保留历史 Adreno 规避策略不等于本轮验证了所有 Adreno 型号。

0046 记录实际 Vulkan allocation，并区分 compute、upload/read staging、inline ring；它仍不是 RSS/driver 总量。0048 仅把 glare 后受支持的 print tail scratch 外存分带，仍保留完整 negative、最终 output 等 live 对象，不能声称全分辨率链总内存有界。新机 subgroup/16 位存储只能作为另立合同的优化候选；没有实证支持用 FP16 替换当前 FP32 来解除容量问题。

## 烘焙资产

实际 `spektrafilm_constants.bin` 为 9,450,196 bytes，SHA256 `f2c06600606fa169171f58b6639d2669f372a2b39ebe9c72e3edeff5f6d7d31d`。`SPKR` version 1，小端 16-byte header、71 个 104-byte TOC entry、8-byte 数据 offset 对齐。逐项检查 shape×dtype size、文件界限和互不重叠通过。

| 存储 | entry 数 | payload bytes | 用途 |
|---|---:|---:|---|
| f64 | 54 | 17,648 | 观察者、光源、矩阵、滤镜等常量 |
| f32 | 16 | 3,453,120 | 8 份 `(33,33,33,3)` print LUT 及 8 份 `(3,33)` 密度轴 |
| f16 | 1 | 5,971,968 | `(192,192,81)` Hanatos 光谱 LUT，与参考资产存储精度相同 |

Blob reader 将资产扩展为 double；setup 积分生成 `(192,192,3)` 表后上传 FP32。f16 资产不是 shader FP16：没有半精度 shader 能力要求。print LUT 的输入是胶片 CMY 密度、第三密度轴最快、RGB 通道连续，经 trilinear 插值输出；它不能直接作为 scene RGB→胶片预览表。其配对胶片、输出空间、参数和缺失 glare 的合同仍需保留。

依赖固定情况：LibRaw 0.22.2 加当前 16 个 overlay、libjpeg-turbo 3.2.0（Android SIMD ON）、Vulkan-Headers `b5c8f996196ba4aa6d8f97e52b5d3b6e70f7e4e2`、volk `d979819fd03de3a7606d6e23d6ff4968942599da`、stb `2c980bb59875b0d32144a71867fbdebb2f77cd20`。glslang 由发现的宿主工具提供，当前脚本没有版本/hash pin，是可复现构建需补记录的真实缺口；本次不改依赖。

原始 modules/blob 表、ELF、符号、选定编译命令与 time 指标见 [验证目录](../tools/native/validation/gpu-binary-audit-20261111/)。完整反汇编保留在独立验证环境；不将二进制 `.so` 作为发布物加入仓库。
