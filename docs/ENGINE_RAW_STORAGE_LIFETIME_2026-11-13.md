# RAW 原始存储与最终 bitmap 的重叠回收

GPT（OpenAI）共创，2026-11-13。承接 [0056 工作图转换](ENGINE_RAW_WORKING_CONVERSION_2026-11-13.md)
中“转换阶段变小、完整解码峰值未降”的实测结果。本轮为引擎 0057 与 LibRaw 0025；
引擎 pin、0.22.2 基线、RAW 中性线性默认、胶片模型与发布版本保持原状。

## 结论与测量范围

完整合成 RAW 解码峰值下降，像素与完整元数据保持一致。以下为 AOIDC/GCC 11.4、
OpenMP 4 workers、6000×4000 →1500×1000 的三对独立进程交替测量中位数。
比较基线是已经包含 0056 的生产解码器；测量时没有并行编译负载，文件缓存已预热。

| 路径 | 旧峰值 KiB / MiB | 新峰值 KiB / MiB | 峰值降低 | 旧 / 新耗时中位数 |
|---|---:|---:|---:|---:|
| 整数 LinearRaw，直接生成工作 float | 521,512 / 509.29 | 380,652 / 371.73 | 27.01% | 1232.00 / 1186.11 ms |
| 整数 Bayer，完整 AHD 后生成工作 float | 380,988 / 372.06 | 334,024 / 326.20 | 12.33% | 1371.33 / 1377.92 ms |
| 整数 LinearRaw，保留全尺寸 float 后缩放 | 521,648 / 509.42 | 427,872 / 417.84 | 17.98% | 1613.43 / 1673.11 ms |

本轮是内存寿命改进，耗时没有一致改善；保留完整 float 的样例中位数上升约 3.7%。
最后一行没有运行标准显影的统计扫描，不能称为完整 Android standard_v1 流程测量。
这些结果也不是手机 RSS、耗时、OOM 验收或真实相机画质结果。

## 固定源码调研与所有权

2026-11-13 10:10 北京时间通过官方 GitHub API 复核：
LibRaw master 为 `a9a14b1970d188c25dade81f8326ff00a6e33649`，
SpektraLab Windows_Linux 为 `894915fb7f93656d2951eaf11a8be35e4ff60246`，
Knonoa main 为 `6d8dd5a098e83f03e6bb67b222dc55014184c08e`，均与上一轮快照相同。
通用网页工具未返回可用正文，本轮直接读取官方固定源码；其余成熟引擎的具体调研见
[外部研究记录](ENGINE_EXTERNAL_RESEARCH_2026-11-13.md)，不将本轮局部复核称为重新审计全部算法。

实际阅读 0.22.2 的 `unpack.cpp`、`raw2image.cpp`、`dcraw_process.cpp`、
`mem_image.cpp`、`init_close_utils.cpp`、`phaseone_processing.cpp`、`fp_dng.cpp`、
`libraw_types.h` 与 `libraw.h`。同时复核了 0.22.3 的 `free_image()` 实现与同类 decoder。

- `raw_alloc` 是 LibRaw 在 `recycle()` 中用 `free()` 回收的原始样本所有者；
  raw/color3/color4/float 等字段是其样本视图。浮点转换会替换所有者并清除旧浮点视图。
- `raw2image_ex()` 为处理图另行分配并复制原始样本；Phase One 临时校正先完成并归还。
  本轮没有在这个阶段或解码 worker 工作期间提前释放。
- `dcraw_process()` 成功后，`copy_mem_image()` 读取处理图、gamma curve、histogram、
  几何和方向信息。`dcraw_make_mem_image()` 仍分配独立、已定向的 RGB16 bitmap。
- `free_image()` 释放处理图并重置进度，不能用于本问题；`recycle()` 又会清除输出需要的状态。
  同类 Knonoa decoder 在 bitmap 建好后销毁 LibRaw，仍有这段更早的重叠。

阶段探针直接执行真实 LibRaw：旧过程 process 后约 380,340 KiB，bitmap 后约 521,352 KiB；
新过程释放原始所有者后约 193,412 KiB，bitmap 后约 333,856 KiB，峰值仍约 380,368 KiB。
同一探针保留/释放两种模式的 RGB16 字节哈希相同。生产解码器的测量另由第一张表记录，
没有把这个独立 LibRaw 探针当作完整相机色彩处理 oracle。

固定来源：
[LibRaw 0.22.2 生命周期](https://github.com/LibRaw/LibRaw/blob/0.22.2/src/utils/init_close_utils.cpp)、
[处理图复制](https://github.com/LibRaw/LibRaw/blob/0.22.2/src/preprocessing/raw2image.cpp)、
[最终输出](https://github.com/LibRaw/LibRaw/blob/0.22.2/src/postprocessing/mem_image.cpp)、
[0.22.3 free_image](https://github.com/LibRaw/LibRaw/blob/cf02d66eb5c19d4e46df7aee0582e47a4918509a/src/utils/utils_libraw.cpp)、
[同类 RAW 输出边界](https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/engine/src/io/raw_decoder.cpp)。

## 实施合同

LibRaw 0025 增加本项目的 `spk_release_rawdata()`，不是声称上游已有同名 API。
它只在 RGB 转换完成、有处理图、有独立原始分配且没有 RawSpeed 2/3 processed 标志时释放。
所有释放都留在 LibRaw 内部，使用与 `recycle()` 相同的所有者和分配器。
随后将原始所有者与六种样本视图置空，保留处理图、全部元数据、gamma、histogram 和进度。
其他情况返回 false，状态不变，维持原有析构释放时点。

引擎 0057 只在 compatible 解码器的 `dcraw_process()` 成功、错误检查通过、元数据完整提取后调用，
然后仍通过原 `dcraw_make_mem_image()` 完成方向和 gamma。原始样本不能再用于同一实例的重复处理；
需要重新 open/unpack。生产解码器本来就每次新建实例，bitmap 创建后照旧销毁它。
精确 float_linear_v1 不经过这条路径；legacy 浮点兼容路径会受益于相同的释放。
新成员不改变 LibRaw 结构布局，但调用者与 LibRaw archive 仍须同步重编链接。

默认像素算法、半尺寸决策、源尺寸、标准显影完整统计、WB、ForwardMatrix、镜头处理和
取消优先级保持。释放后的取消仍抛原取消异常，候选输出不会部分发布。
外部 RawSpeed 的测试是保护条件注入检查，不是对真实 RawSpeed 后端文件的验收。

## 验证与可重放性

- 36 组真实 LibRaw 生命周期案例，涵盖 LinearRaw/Bayer 八方向、legacy 浮点与两类 WB。
  检查提前调用拒绝、重复释放、整份 imgdata 只改变原始指针、重复 bitmap、丢弃后拒绝重处理、
  recycle 后重开，以及 bitmap 脱离 LibRaw 独立存活。另有顺序、外部所有者和 image alias 保护案例。
- 冻结 0056 与候选 0057 分别链接独立 LibRaw archive，同一探针执行 168 组；164 组成功输出的
  所有 float 字节与全部 RawMetadata 字段逐项二进制一致，四组非有限输入拒绝行为一致。
  比较排除对象 padding、指针和计时，覆盖默认/custom WB、半尺寸、八方向及三类精度路由。
- 工作图 169 个策略/尺寸/WB 组合通过。ASan/UBSan 构建包含整个 LibRaw、解码 IO 与适配层，
  并通过上述生命周期、工作图和原精度分发回归；不再只依赖未插桩的 LibRaw archive。
- 原精度回归保持 17 float、3 custom float、5 fallback、4 integer 与 77 个取消案例。
  独立 float oracle 最大绝对误差仍 `4.76837158203125e-7`。按回调编号的测试固定 1 worker，
  LibRaw AHD 的动态 tile 循环只由 0 号 worker 报告进度，回调总数随 tile 分配变化；
  多线程取消另由工作图和真实 JNI 测试覆盖。
- 生产 JNI 翻译单元、真实引擎/解码器的 Bayer 和旋转 LinearRaw 两次通过，保留平台 bitmap 分配 shim。
  每次含 12 个工作图案例、AE-off 完整统计、源尺寸、原 ABI、取消、并发 token、旧缓存和 retry。
- NDK 27.2 / API29 / arm64-v8a 完整 189 步 JNI 编译链接通过；56 个 SPIR-V 文件与 0056 逐文件一致。
  官方归档的 57 个引擎与 19 个 LibRaw 补丁零 fuzz 重放，被测源代码相同。

ARM64 JNI SHA-256：`57b5e43839ab59a8dfc5892f7a7d9fddf4272c1a543397a7d4a531e07b583de5`。
公开日志、原始三对测量、完整元数据对照记录、源哈希及初次取消编号测试失败见
[证据目录](../tools/native/validation/raw-storage-20261113/README.md)。全部编译和程序测试均在 AOIDC；本轮未使用 IBX。
CI 增加完整解码器 sanitizer job，2 jobs / 15 个 run 块通过 YAML 与 bash 语法检查；尚未线上执行 Actions。

## 复现与下一处瓶颈

```bash
# UP = pin + 57 overlays; LIBRAW = official 0.22.2 + 19 overlays.
cmake -S tools/native/float_dng_deflate_host -B /tmp/raw-host -G Ninja \
  -DSPK_UPSTREAM="$UP" -DSPEKTRALAB_LIBRAW_SOURCE="$LIBRAW" -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/raw-host -j2
python3 tools/native/raw_working_fixtures.py /tmp/raw-fixtures --large
B=/tmp/raw-host BUILD_DIR=/tmp/lifetime bash tools/native/run_raw_storage_lifetime.sh \
  /tmp/raw-fixtures/*orientation*.dng
/tmp/lifetime/raw_storage_lifetime --measure-keep /tmp/raw-fixtures/integer-linear-24mp.dng
/tmp/lifetime/raw_storage_lifetime --measure-release /tmp/raw-fixtures/integer-linear-24mp.dng
UP="$UP" B=/tmp/raw-host BUILD_DIR=/tmp/working bash tools/native/run_raw_working_decode.sh \
  /tmp/raw-fixtures/*orientation*.dng
SPEKTRALAB_RAW_THREADS=4 /tmp/working/raw_working_decode \
  --memory-fused /tmp/raw-fixtures/integer-linear-24mp.dng
```

完整 sanitizer 构建和回归命令已固化在 `engine-gpu.yml` 的 `raw-storage` job。
`raw_storage_parity.py` 接受分别链接冻结版/候选版的 `raw_precision_input` 探针，直接比较
输出 float 和 `.metadata` 文件；不能让两个探针误用同一个新版 archive。

LinearRaw 的峰值现在落在原始样本与四通道处理图并存阶段；Bayer 仍有处理图与最终输出重叠。
下一步需分别评估这些拷贝能否安全复用，且保留 Phase One、Fuji、原始校正及外部所有权边界。
本轮仍有数百 MiB 的整图缓冲，没有达到端到端 tile 或 128 MiB 全流程目标。
