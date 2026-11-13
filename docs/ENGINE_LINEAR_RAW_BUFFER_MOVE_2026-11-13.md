# LinearRaw 原始缓冲移交与外部引擎跟进

GPT（OpenAI）共创，2026-11-13。承接 [0057 原始存储回收](ENGINE_RAW_STORAGE_LIFETIME_2026-11-13.md)
定位的下一处峰值：`raw2image_ex()` 同时保留原始 RGBA16 和复制出来的处理图。
本轮增加引擎 0058 / LibRaw 0026，保持 UI、引擎 pin、RAW 中性线性默认、胶片模型和发布版本。
所有编译与程序测试在云端，输入均为公开生成的合成 DNG。

## 固定源码与本轮取舍

2026-11-13 13:21–13:27 北京时间经官方 GitHub API 复核如下 HEAD。
通用网页工具没有返回可用结果，随后直接阅读官方仓库的固定源码；没有将搜索摘要当作实现依据。
这次重点补查所有权和调度，不把此前已经完成的去马赛克、散射与 LUT 研究重新算作新增审计。

| 项目 / 分支 | 本次固定提交 | 阅读及取舍 |
|---|---|---|
| LibRaw / master | `a9a14b1970d188c25dade81f8326ff00a6e33649` | HEAD 与前轮相同。实际改动仍基于官方 0.22.2 的 unpack、raw2image、dcraw_process、mem_image、utils 与类型定义，不整体升级。 |
| SpektraLab / Windows_Linux | `894915fb7f93656d2951eaf11a8be35e4ff60246` | 与前轮相同；没有据此更换生产 pin。 |
| Knonoa / main | `6d8dd5a098e83f03e6bb67b222dc55014184c08e` | decoder 仍先 process、生成完整 RGB16、销毁 LibRaw，再转 float；没有更早移交 raw2image 所有者。[K] |
| thetechgeekko / main | `e70771cf99e61481feb471d5c4ac827a488c73f6` | `DecodedSourceCache.kt` 将 generation ticket、lease、交付和释放分开。缓存失效不等于可以销毁正在被消费者使用的源。[T] |
| SAINTTaiYi / master | `b17f6d2bdcd8024bb62abf212d10819115ca2d87` | HEAD 与前轮相同，本轮没有新增逐行算法审计。 |
| libvips / master | `5e918bd43a5f2fc8cfcd23fddbfc6704a1fcff3f` | how-it-works 的按需 region、memory 文档的分配器区分、region.c 的像素尺寸/范围/寿命检查，是避免中间整图和安全引用的具体依据。[V][VM][VR] |
| Halide / main | `f8d34e47ae71315c440ad73c7dc3fcbc0270cae1` | lesson 08 的两行循环缓冲将计算调度与算法分开。0056 已按行复用；本轮继续消除更早的完整复制，没有增加 Halide 依赖。[H] |

libvips 的区域求值不代表 LibRaw 自动支持按需解包；Halide 的 storage folding 也不等于
任意邻域算子都可使用两行缓冲。当前完整 RAW 仍有整图解包、处理图和最终 RGB16。
本轮实现独立编写，没有复制上述项目的实现，也没有引入新的库或缓存框架。
更广的算法取舍见 [外部引擎研究](ENGINE_EXTERNAL_RESEARCH_2026-11-13.md)。

## 移交合同

LibRaw 0026 增加本项目的 `spk_dcraw_process_once()`，不是上游已有 API。
原 `dcraw_process()` 和 `raw2image_ex()` 保留复制和重复处理语义；内部共用实现由明确参数控制。
只有一次性入口才可把 `raw_alloc == color4_image` 的整个分配移交给 `imgdata.image`，
随后清空原始所有者和 color4 样本视图；样本内容和实际分配地址保持。

必须同时满足：DNG、整数三通道 LinearRaw、packed/lossless DNG 解码器、LibRaw 自己持有
原始分配、无其他 raw/color3/float 视图、没有 RawSpeed 2/3 processed 标志；
原始/可见/工作尺寸完全相同、stride 为 width×8、无边距、无 crop、无 shrink/Fuji、
方形像素、没有既存处理图、尚未 RAW2_IMAGE，且不在 inline subtract-black 路径。
任何条件不满足就走原分配复制路径；Bayer、浮点、裁切和外部缓冲不做该移交。

移动前没有异步消费者；移动后析构由 `imgdata.image` 原有释放路径负责，使用同一分配器。
原始样本视图可能在处理回调前结束，需要重新 open/unpack 才能再次处理。
LibRaw 不增加数据成员或虚函数，但头文件、完整 archive 和消费者仍同步重编。
生产解码器本来每个请求新建实例，引擎 0058 才调用该入口；0057 的后处理释放继续服务未移动的路径。
原 RGB16 输出、方向、gamma、精度路由、源尺寸、标准显影统计和取消边界保持。

## 捕获的回归与独立 oracle

最初 168 组冻结对照通过，但新增“lossless RGB + 非零均匀黑点”后发现亮度不一致。
不是修改容差解决：原有 0051 回调以原始样本指针是否存在识别 RGB 存储，移交后该指针变空，
导致 common maximum 未扣除黑点，64511 错成 65535。初次失败日志保留。

0058 现在由 `CompatibleLibRaw::process_once()` 在移交前记录解包存储类型，黑点回调使用该事实，
其余 DNG、通道、样本格式和黑点限制及公式不变。修正后的冻结对照扩大为 200 组，
196 组成功输出的完整 float 字节和全部元数据字段相同，另四组非有限输入拒绝一致。
另外运行现有独立黑位公式 oracle 的 48 组方向/预算组合：
`clamp((code-black)/(white-black),0,1)`，最大误差 `3.0517578125e-5 < 3/65535`。
CI 同时加入这个数学 oracle，避免只靠候选与候选互比漏掉回归。

## 最终测量

AOIDC / GCC 11.4 / 4 workers，6000×4000 →1500×1000，比较已包含 0057 的冻结版与
修正后的 0058。每项三对独立进程，交替执行顺序、预热文件缓存，测量期间没有并行编译。
下表为中位数；同一项六次的最终工作图哈希均相同。完整逐次数据在证据目录。

| 路径 | 0057 峰值 KiB | 0058 峰值 KiB | 峰值变化 | 0057 / 0058 耗时 |
|---|---:|---:|---:|---:|
| LinearRaw 直接生成工作 float | 380,652 | 334,080 | 降低 12.23% | 1226.16 / 996.777 ms |
| Bayer 完整 AHD 后生成工作 float | 333,988 | 333,896 | 基本不变 | 1375.09 / 1417.67 ms |
| LinearRaw 保留全尺寸 float 再缩放 | 427,920 | 427,936 | 基本不变 | 1690.05 / 1498.85 ms |

第一项约 371.73 →326.25 MiB，耗时降低 18.71%。Bayer 耗时中位数上升 3.10%，没有该路径
提速的证据；全尺寸 float 项耗时降低 11.31%，但后续全图缓冲仍主导峰值。最后一项没有
运行 standard_v1 统计扫描，不能称为完整 Android 标准显影流程测量。三对样本不是手机耗时
或统计显著性证明，合成输入也不能代表全部相机。

独立 LibRaw 阶段探针进一步确认：复制模式 process 后 RSS 约 380,920 KiB，移动模式约
192,960 KiB；移动后的峰值变为 working image 与最终 RGB16 bitmap 共存，约 333,968 KiB。
这只解释所有权变化；其 RGB16 哈希不代替生产色彩 oracle。
原始 RGBA16 约 192 MB、最终 RGB16 约 144 MB 的整图重叠仍存在；保留全尺寸 float 时还有
更大的后续缓冲。本轮尚未实现最终 bitmap 按行交付、端到端 tile 或 128 MiB 全流程目标。

## 验证与复现

- AOIDC GCC 11.4 的整个 LibRaw 与解码 IO 进行 ASan/UBSan 重编，100 组真实处理案例及
  29 个拒绝保护通过。包含 packed/lossless RGB、Bayer 八方向、legacy 浮点、默认/custom WB、
  裁切回退、重复 bitmap、原 API 重处理、移交后拒绝重处理、重开和独立 bitmap 寿命。
- IBX GCC 14.2 独立从官方 0.22.2 归档重放全部 20 个 overlay，完整 LibRaw ASan/UBSan
  构建通过；24 个合成方向文件的 96 组处理和 29 个保护通过。没有在 IBX 声称重跑生产引擎、JNI
  或 200 组冻结对照。RawSpeed 等外部所有者只做拒绝条件注入，未声称真实后端验收。
- AOIDC 修正后的工作图 Release 与完整插桩版本各 232 个策略/尺寸/WB 组合通过。
  原精度分发维持 17 float、3 custom float、5 fallback、4 integer 和 77 个取消案例，独立
  float oracle 最大绝对误差 `4.76837158203125e-7`，低于 `2e-6`。
- 最终生产 JNI 翻译单元重新链接新 IO/LibRaw，旋转 LinearRaw 与 Bayer 各通过一次；每次含
  12 个工作图案例、AE-off 完整统计、源尺寸、原 ABI、23 个取消阶段、并发 token、旧缓存和 retry。
  只有 Android bitmap 平台分配使用 shim；host 复用没有修改的 engine/core/volk archive。
- NDK 27.2 / API29 / arm64-v8a 完整 189 步 JNI 构建通过，回调修正后再次增量编译链接。
  最终库 SHA-256 为 `48d36c06feb9bc944b8994fe29d8f3cd4a36d3be0e054164a875b3d1ff5cdeaf`。
  58 个引擎 / 20 个 LibRaw overlay 从已验证官方归档零 fuzz 重放，与最终被测源码一致；
  Android 的 LibRaw 复制件也逐文件一致。56 个 SPIR-V 与 0057 逐文件相同。
- CI 为 2 jobs / 15 个 run 块，最终 YAML 和所有 shell 语法通过；云端实际执行了新增数学
  oracle 与本轮 runner，但没有将这些结果称为线上 Actions 已通过。

公开日志、最初失败、最终输入哈希、三对测量、工具链与独立云证据见
[验证目录](../tools/native/validation/raw-linear-move-20261113/README.md)。本机与云端输入逐项核对，
没有上传私人样片、派生资料或 handoff。

```bash
# UP = original pin + 58 overlays; LIBRAW = official 0.22.2 + 20 overlays.
cmake -S tools/native/float_dng_deflate_host -B /tmp/raw-host -G Ninja \
  -DSPK_UPSTREAM="$UP" -DSPEKTRALAB_LIBRAW_SOURCE="$LIBRAW" \
  -DSPK_BUILD_RAW_COLOR_AUDIT=ON -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/raw-host -j2
python3 tools/native/raw_working_fixtures.py /tmp/raw-fixtures --large
B=/tmp/raw-host BUILD_DIR=/tmp/raw-move bash tools/native/run_raw_linear_move.sh \
  /tmp/raw-fixtures/integer-orientation-*.dng /tmp/raw-fixtures/integer-bayer-orientation-*.dng \
  /tmp/raw-fixtures/integer-lossless-orientation-*.dng
python3 tools/native/linear_raw_black_regression.py /tmp/black-oracle --probe /tmp/raw-host/raw_color_audit
UP="$UP" B=/tmp/raw-host BUILD_DIR=/tmp/raw-working bash tools/native/run_raw_working_decode.sh \
  /tmp/raw-fixtures/*orientation*.dng
SPEKTRALAB_RAW_THREADS=4 /tmp/raw-working/raw_working_decode \
  --memory-fused /tmp/raw-fixtures/integer-linear-24mp.dng
```

完整 sanitizer 配置在 `engine-gpu.yml`。冻结对照继续使用 `raw_storage_parity.py`，必须将
旧/新探针分别链接各自匹配的 IO/LibRaw archive；完整元数据排除 padding、指针和计时。

## 固定来源

[K]: https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/engine/src/io/raw_decoder.cpp
[T]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/app/src/main/java/com/spectrafilm/app/DecodedSourceCache.kt
[V]: https://github.com/libvips/libvips/blob/5e918bd43a5f2fc8cfcd23fddbfc6704a1fcff3f/doc/how-it-works.md
[VM]: https://github.com/libvips/libvips/blob/5e918bd43a5f2fc8cfcd23fddbfc6704a1fcff3f/doc/libvips-memory.md
[VR]: https://github.com/libvips/libvips/blob/5e918bd43a5f2fc8cfcd23fddbfc6704a1fcff3f/libvips/iofuncs/region.c
[H]: https://github.com/halide/Halide/blob/f8d34e47ae71315c440ad73c7dc3fcbc0270cae1/tutorial/lesson_08_scheduling_2.cpp

LibRaw 原始依据：
[分配](https://github.com/LibRaw/LibRaw/blob/0.22.2/src/decoders/unpack.cpp)、
[处理图复制](https://github.com/LibRaw/LibRaw/blob/0.22.2/src/preprocessing/raw2image.cpp)、
[处理顺序](https://github.com/LibRaw/LibRaw/blob/0.22.2/src/postprocessing/dcraw_process.cpp)、
[最终 bitmap](https://github.com/LibRaw/LibRaw/blob/0.22.2/src/postprocessing/mem_image.cpp)。
