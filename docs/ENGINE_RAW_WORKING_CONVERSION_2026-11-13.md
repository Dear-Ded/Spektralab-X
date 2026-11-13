# RGB16 直接生成工作 float：实施与验证

GPT（OpenAI）共创。固定引擎 `a46627d250545a394ca21522f8a28b0fb89e4c42` 加 0056，
LibRaw 仍为 0.22.2 加 18 个 overlay。对应[外部调研](ENGINE_EXTERNAL_RESEARCH_2026-11-13.md)。
本轮编译、可执行测试与计时全部在现有 AOIDC 云电脑运行，本机仅编辑、调研和整理证据。

## 行为与边界

旧 Android 路径在 LibRaw 完成最终定向的 RGB16 bitmap 后，释放 LibRaw，
分配全尺寸 float RGB，逐样本转换；解码返回时释放 bitmap，随后再缩到工作尺寸。

新路径在同一 RGB16 所有权边界向 host 提供可选 converter，按需转换源行并复用
已有面积滤波的四行横向缓存，直接生成目标 float。现有无 converter 的所有
`decode_raw_compatible` overload 保留，包括六参数取消入口的符号和行为。
引擎不包含 Android 工作尺寸规则；适配层仍用原有 long-edge/pixel-cap 的 fit 算法。

仅在 `integer_compatible16`、确需缩小且不要求完整源图统计时采用新路径。
完整尺寸请求回落到原转换。`float_linear_v1` 和 `float_legacy_quantized`
不启用 converter；精确浮点路径中的负值、超过 1 的值与浮点黑白电平仍按原策略保留。
`standard_v1` 无论 AE 开关都先统计完整、未缩小且未半尺寸的源图，再缩工作尺寸。
中性与物理胶片仍不生成标准显影统计。Bayer 半尺寸选择规则保持。

转换前仍执行 LibRaw 的 `dcraw_make_mem_image`，保留其最终方向和 gamma lookup 语义。
没有直接读 `imgdata.image` 来规避这些步骤。
每个整数码值仍先执行 `float(double(value) / 65535.0)`；面积权重的
double coverage →float →sum 归一化和横纵两遍 float 累加顺序均保留。
不以预乘倒数、别的滤波核或一次双精度加权和代替这些算序。

## 架构与内存

0056 增加只在调用期间有效的 `RawRgb16View` 和独立 `RawFloatImage`。
host 可拒绝转换并保留默认路径；接受结果必须为非空、不放大的 packed RGB float。
尺寸与样本数在发布前验证，异常或取消不替换调用者原有 `DecodedRaw`。
引擎提供的取消 poll 串行化且具有粘性；adapter workers 全部 join 后才返回，
由发起线程抛取消异常，取消优先于转换器异常。

`raw_working_input.hpp` 负责选择规则并记录缩小前实际定向尺寸；JNI 的工作宽高
可以缩小，但 sourceWidth/sourceHeight 仍表示原图。若已有 Bayer binning，
继续使用传感器可见尺寸和 LibRaw flip 恢复完整源尺寸。

新转换阶段仍持有全尺寸 RGB16，绝非恒定内存：主要存储约为
`6*W*H + 12*w*h + workers*(12*W + 48*w)` 字节，加权重和少量 bookkeeping，
workers 最多为 8。这里的 W/H 是解码 bitmap，w/h 是工作图。
去掉的是 `12*W*H` 的 float 中间帧；没有去掉 LibRaw 更早的原始存储和四通道工作图。

## 云端结果

### 数值、取消与完整统计

- `run_rgb16_resize.sh`：520 组尺寸/图案、15,211,650 个输出 float 与冻结的旧两遍
  面积 oracle 逐位一致，同时确认重构后的原 float 路径也一致。覆盖全部 65,536
  码值、非对齐 RGB16、单行单列、无缩放、极小目标、不整数缩放；5 个取消/异常 poll
  和 5 个非法布局保持目标不变。普通、正常 contraction 策略及 ASan/UBSan 均通过。
- `run_raw_working_decode.sh`：8 种整数 LinearRaw 方向、整数 Bayer、精确浮点和
  legacy 浮点共 11 份公共合成输入，检查 97 个策略/尺寸/自定义 WB 组合。
  整数输入另测 converter 的非法零尺寸、放大、样本数不符、抛异常与取消优先级。
  像素逐位一致，原始尺寸、白平衡与相机变换保持。
- ASan/UBSan 对新 decoder 源、adapter、重采样及测试 helper 一起插桩，覆盖其中
  4 份输入、34 个组合及异常/取消。LibRaw 使用生产 archive，未将其称为全 LibRaw 插桩。
- 原 precision dispatcher 回归通过：17 个精确浮点、3 个自定义 WB、5 个 fallback、
  4 个整数组合与 77 个取消点；完整 legacy 像素和元数据比较仍相同，
  独立浮点 oracle 最大误差 `4.76837158203125e-7`，门限 `2e-6`。
- 生产 JNI translation unit 与实际引擎/解码器链接，仅 shim Android bitmap 分配。
  新增 12 个用例，分别验证 neutral/standard_v1、普通缩小/小目标、三种精度路由；
  AE 关闭时仍比较完整源图 histogram、计数、log2 mean。另用 orientation 8 整数
  输入重跑，工作像素和 source dimensions 均通过。两次都保留旧 ABI/新取消 ABI、
  11 个精确浮点及 12 个兼容路由取消检查点、并发取消、旧会话/缓存和重试验证。
- 原 native adaptation 回归通过，包括 signed/HDR 缩放、layer2、view curve 与 wire。

### 阶段与完整流程计时

6000×4000 →1500×1000，三对独立进程交替运行、没有并行编译负载，取中位数。
这是云端 CPU helper 的温文件缓存结果，不是手机总耗时或相机画质测试。
阶段基准按生产释放时点：旧路径在转换后、面积缩放前释放 RGB16；新路径在
直接生成工作图后释放 RGB16。早期探索性计时保留云端，下面只引用最终基准。

| 指标 | 旧路径 | 新路径 | 解释 |
|---|---:|---:|---|
| RGB16 转换与工作缩放 | 578.316 ms | 113.817 ms | 中位耗时降低约 80.3%。 |
| 上述阶段进程峰值 RSS | 424,968 KiB | 162,300 KiB | 约 415.0 →158.5 MiB；不是整条 RAW 流程的峰值。 |
| 实际合成 LinearRaw 解码到工作 float | 1674.93 ms | 1225.79 ms | LibRaw 4 workers；中位耗时降低约 26.8%。 |
| 完整解码进程峰值 RSS | 521,588 KiB | 521,640 KiB | 都约 509.4 MiB，基本不变；峰值被更早的 LibRaw 阶段主导。 |

三次阶段输出 FNV-1a 均为 `6b5867b9fcd081fc`，实际解码工作图均为
`fce7da97652d8015`。完整较小 fixture 比较直接使用 `memcmp`，不只比较哈希。
本轮确实减少后段驻留和内存带宽，但不能据此宣布解决手机 RAW OOM，
也不保证其他文件尺寸或设备有相同加速比。

### 构建与可重放性

NDK 27.2/API29/arm64-v8a/静态 C++：189 步完整 JNI 构建与链接通过。
`libspektralab_jni.so` SHA-256：
`58cf3276f50f1cd54e3d74a5f98f328d5805e4d2582243afa5ecd12811ac4231`。

新下载的官方 pin 归档 SHA-256 为
`52ef254ffa7c70484a411c269f3f837f75d5a215ed7332d5fdab267317408f05`，
与云端缓存完全一致；用该完整归档干净重放 56 个引擎补丁，`--fuzz=0`，
engine/src 与被测源码一致。官方 LibRaw zip 校验仍为固定的 `02275a04...6655c7`，
单独解压 pristine 后 18 个 overlay 零 fuzz 重放，src/libraw/internal 与 ARM64 复制件一致。
比较排除 GNU patch 的 `.orig`/`.rej` 备份，不排除生产源文件。
56 个 SPIR-V 与 0055 构建逐文件 SHA-256 相同。

第一次 ARM64 配置发现共享依赖路径名为 pristine、实际指向已打过补丁的旧目录，
因重复应用补丁被拒绝。最终构建改用本轮独立、校验过的官方 zip 解压件；
没有修改共享依赖。最终证据使用 `arm64-final` 日志，未把失败配置当作成功编译。

CI 新增同一 RGB16 sanitizer helper，YAML 和 run 段 shell 语法已检查。
尚未宣称线上 Actions 通过。本轮没有执行安卓真机安装或真实相机新样片验收。
公开文本证据见[验证目录](../tools/native/validation/raw-working-20261113/README.md)。

## 复现入口

```bash
BUILD_DIR=/tmp/rgb16 bash tools/native/run_rgb16_resize.sh
SANITIZE=1 BUILD_DIR=/tmp/rgb16-asan bash tools/native/run_rgb16_resize.sh
STRICT=0 BUILD_DIR=/tmp/rgb16-contract bash tools/native/run_rgb16_resize.sh
/tmp/rgb16/rgb16_resize --memory-full
/tmp/rgb16/rgb16_resize --memory-fused

python3 tools/native/raw_working_fixtures.py /tmp/working-fixtures --large
UP=/path/to/patched-upstream B=/path/to/production-host-build \
  BUILD_DIR=/tmp/working bash tools/native/run_raw_working_decode.sh \
  /tmp/working-fixtures/integer-orientation-*.dng
SPEKTRALAB_RAW_THREADS=4 /tmp/working/raw_working_decode \
  --memory-full /tmp/working-fixtures/integer-linear-24mp.dng
SPEKTRALAB_RAW_THREADS=4 /tmp/working/raw_working_decode \
  --memory-fused /tmp/working-fixtures/integer-linear-24mp.dng
```

`B` 必须提供用本项目 LibRaw 18 个 overlay、USE_ZLIB 与同一头文件重编的
`libspk_image_io.a`/`libspk_libraw.a`；不能混用旧 ABI archive。
JNI 入口为现有 `run_jni_raw_cancellation.sh`，新增工作图断言也由同一入口执行。
