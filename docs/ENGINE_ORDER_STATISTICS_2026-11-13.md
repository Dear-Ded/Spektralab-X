# 外部算法研究：精确多分位点与测光优化

GPT（OpenAI）共创，2026-11-13。本轮增加引擎 overlay 0059，优化自动曝光与场景纬度统计的
次序统计计算。保留原整数分位点、测光公式、采样策略和默认图像效果；不改 UI、RAW 解码、
胶片/相纸参数、引擎 pin 或发布版本。所有编译和可执行测试在 AOIDC 云端完成。

## 外部实现与取舍

2026-11-13 14:25–14:31 北京时间通过官方 GitHub API 固定当时源码，实际阅读如下实现。
这轮关注多分位数和高斯近似，不把仅查询 HEAD 的其他仓库算作新增算法审计。

| 来源 | 固定版本与实际阅读 | 对本轮的影响 |
|---|---|---|
| NumPy | `768d7ef361e0207a24faf3783d5f668a0f137d46`；`selection.hpp` 的已知 pivot 栈与搜索区间收缩；`_function_base_impl.py` 的多个所需 rank 一起 partition。见 [selection][N] / [quantile][NQ]。 | 借鉴复用已确定边界的原理，独立实现少量 rank 的分治选择；不加入 NumPy 依赖，不搬运其 SIMD/introselect 源码。 |
| libvips | `5e918bd43a5f2fc8cfcd23fddbfc6704a1fcff3f`；`percent.c` 的 histogram→cumulative→normalize→threshold/profile。[V] | 该操作明确面向 uchar/ushort，整数域中有用；把我们的 signed/HDR double 样本量化到这种直方图会改变分位点和 EV，因此不替换生产测光。现有 128 桶显示直方图也不充当精确 rank oracle。 |
| IPOL / Pascal Getreuer | 2013 论文与官方 `gaussian_20131215` 源码；实际阅读 README、extended box、Deriche、Vliet–Young–Verbeek 和递归边界初始化。见 [论文][G] / [源码][GS]。 | extended box 是多遍近似并需要独立暂存；递归实现明确警告大 sigma 单精度误差，且边界是独立的数值合同。保留现有 FIR/IIR 默认与此前诊断模式，本轮不再加入另一种模糊核。 |
| C++ 工作草案 | `nth_element` 的复杂度条款。[C] | 普通重载保证平均线性比较次数，不能把 NumPy 自己的 median-of-medians fallback 误当成我们所用标准库的最坏线性保证。 |

IPOL 源码归档 SHA-256：
`9458287ac105d51e60d87a69a6b9107d7540add2fcab362bd5ee18f359219dfd`。
该归档仅作本机阅读，没有加入生产源码或构建依赖；本轮没有执行它的 benchmark，也没有声称
某种高斯替代在我们的 Vulkan 后端更快或等价。已有边界和大半径实测见
[递归边界研究](ENGINE_ALGORITHM_RESEARCH.md) 与
[大半径验证](ENGINE_IIR_LARGE_RADIUS_2026-11-12.md)。

## 生产实现

旧 `exposure_evs_from()` 对同一份样本进行四次完整范围的 `nth_element`，取得
P1/P5/P99/P99.5；`scene_stats()` 进行五次，取得 P0.1/P1/P50/P99/P99.9。
传统 median 测光则完整排序，只消费一到两个中间值。

0059 增加内部 `core/order_statistics.hpp`。多 rank 选择先求请求 rank 序列的中间项，
然后只在 pivot 左右互不重叠的区间递归；重复 rank 共用结果，已经确定的 pivot 不再进入
下一次 partition。辅助结果是固定大小数组，没有增加堆分配；原工作样本副本仍保留。
对 k 个请求的 rank，每层处理互不重叠区间，平均选择工作量为 O(n log k)，不是最坏时间承诺。

median 使用一次中间项 `nth_element`。偶数个样本再求左半最大值，仍按原顺序计算
`0.5 * (lower + upper)`；奇数直接取中间项。调用者已经排除了空输入，helper 要求非空、
rank 有效且非递减。边界防护没有扩展成新的公开 API。

精确 integer rank 仍分别使用 `q*(n-1)/1000` 与 `q*(n-1)/10000`，没有换成插值分位数。
均值、trim 判定、center 权重、log/exp、直方图累计顺序和默认采样均未重排。
排序/选择对相等浮点数的位模式没有唯一保证，所以先扫描 NaN 与混合正负零：
出现这些情况时，多个 rank 恢复原完整范围选择顺序，median 恢复原完整排序。
这只保留历史行为，不表示包含 NaN 的 scene histogram 获得了新的有效输入合同。

没有自定义 Quickselect、SIMD 内核、并行 reduction、近似直方图或新的缓存层。
收益来自少做精确选择工作，仍使用目标平台的标准库。

## 数值与渲染验证

冻结基线为同一 pin 加 0058，候选为加 0059。CPU runner 直接编译真实的完整
`pipeline.cpp` 与 `latitude_fit.cpp` 翻译单元，测试代码没有复制候选生产公式。
`-fno-access-control` 仅用于测试观察已有内部静态函数，不新增生产测试入口。

- 每种编译配置运行 146 个尺寸 × 10 种模式，共 1,460 组。尺寸涵盖 1–128、rank 舍入
  边界、奇偶、非方形与 65,536 样本；模式含常量、HDR 随机、递增/递减、organ pipe、
  半旋转升序、重复值、黑边/稀疏高光、signed 有限值和正负零。
- 独立完整排序 oracle 检查真实 scene 的五个分位点及 helper rank/median，覆盖重复 rank。
  基线/候选 CSV 精确比较四种新测光意图、三种旧测光的 double 位，以及完整直方图与分位点
  的哈希。额外 helper-only 的 infinity/NaN 检查不进入 scene histogram。
- GCC 11.4 Release、GCC 11.4 ASan/UBSan、NDK Clang 18.0.3 的 Linux x86_64 target
  各自的冻结/候选结果均相同。Clang 在 AOIDC 执行，不是第二云或 Android 设备；没有将
  不同编译器之间的结果宣称为跨平台逐位相同。
- sanitizer 插桩真实的两个已变生产翻译单元及测试，未变函数的链接引用由 Release
  engine/core/volk archive 补齐。第一次缺少 support archive 导致链接失败，修正 runner 后通过。
  没有将此称作整个引擎的 sanitizer 构建。
- 两份独立干净的 85 步 Release engine/resources 构建用于实际 Vulkan 对照。黑场、signed/HDR、
  重复值/高光三场景，七种测光加 AE-off，共 24 例；normal 与 forced-split 合计 48 例。
  full/strided GPU 测光、应用 EV 位、整个负片和最终 float 输出均与冻结版逐字节一致。
  为隔离 AE，fixture 关闭颗粒/halation/DIR/glare/扫描模糊，不改变产品默认参数。
- NDK 27.2 / API29 / arm64-v8a 的完整 189 步 JNI 构建通过。56 个 SPIR-V 与冻结版及
  ARM64 资源逐文件一致；全部 59 个 engine overlay 从校验过的官方 pin 归档零 fuzz 重放，
  生产源码与候选相同。反向移除 0059 得到冻结 0058，再应用后恢复候选，验证 CI 基线构造。
- CI 仍为两个 job，增加真实 CPU 冻结对照；最终 YAML 和 16 个 run 块及两个新 runner 的
  shell 语法通过。此次没有运行线上 Actions，也没有新增 APK 或手机验收。

补丁产生的 `.orig`/`.rej` 不是编译输入，最终源码清单排除它们。第一次比较包含历史 `.orig`
时发现备份不同，实际生产文件没有差异；原记录也保留。
结果、原始 CSV、完整输出哈希、工具链、首次失败和重放日志见
[公开证据](../tools/native/validation/order-statistics-20261113/README.md)。
最终 ARM64 JNI SHA-256：
`e4d50a3585ee1f7d744f89e7a9618ee9729bcadbbdb5663ab42bebf0e8b36f09`。

## 阶段计时

AOIDC / GCC 11.4 / O2 / `-ffp-contract=off`，每项三对独立进程，顺序交替；
每个进程执行 30 次 65,536 样本的真实生产函数，报告每次调用耗时的中位数。
计时前准备输入，计时中保留函数原来的样本复制/分配；没有并发编译。
每项六次累积结果 checksum 相同，未用 sanitizer 计时。

| 阶段 | 样本分布 | 0058 ms/次 | 0059 ms/次 | 耗时降低 |
|---|---|---:|---:|---:|
| legacy median 测光 | 常量 | 0.6875 | 0.2521 | 63.32% |
| legacy median 测光 | HDR 随机 | 3.8238 | 0.7818 | 79.55% |
| legacy median 测光 | 半旋转升序 | 0.6738 | 0.2510 | 62.75% |
| 四种测光意图一起计算 | 常量 | 3.3628 | 3.2184 | 4.29% |
| 四种测光意图一起计算 | HDR 随机 | 3.9741 | 3.7543 | 5.53% |
| 四种测光意图一起计算 | 半旋转升序 | 4.0291 | 3.8125 | 5.38% |
| scene_stats | 常量 | 0.8697 | 0.7099 | 18.38% |
| scene_stats | HDR 随机 | 1.5518 | 1.4174 | 8.66% |
| scene_stats | 半旋转升序 | 0.8486 | 0.6621 | 21.98% |

最明显的是原来完整排序的 median。默认四种测光意图仍有 log/exp、trim 和 center 扫描，
仅降低约 4%–6%；该绝对收益是每次约 0.14–0.22 ms。这些数字属于 CPU 统计阶段，
不是整张导出或手机提速，不证明所有输入分布或标准库都同样受益。
没有改善这轮未修改的 RAW 整图内存瓶颈，也不将此包装成 GPU 算法升级。

## 复现

```bash
# Both source trees and archives must match their own overlay set.
UP=/path/to/engine-through-0059 B=/path/to/candidate-build \
BASELINE_UP=/path/to/frozen-through-0058 BASELINE_B=/path/to/frozen-build \
BUILD_DIR=/outside-repo/stats SANITIZE=1 \
  bash tools/native/run_order_statistics.sh

UP=/path/to/engine-through-0059 B=/path/to/candidate-build \
BASELINE_UP=/path/to/frozen-through-0058 BASELINE_B=/path/to/frozen-build \
BUILD_DIR=/outside-repo/stats-gpu LP_NUM_THREADS=2 \
  bash tools/native/run_order_statistics_gpu.sh

# Use the non-sanitized CPU probes for stage measurements.
python3 tools/native/validation/order-statistics-20261113/aoidc/measure.py \
  /outside-repo/release/baseline /outside-repo/release/candidate /outside-repo/timing
```

没有 support archive 时 Release CPU runner 依赖 section GC 去除未使用函数；sanitizer 模式
必须提供 B。CI 将已构建源码复制后只反向移除 0059，直接编译旧/新两个生产翻译单元，
未变支持函数可共用同一份 Release archive，不需要第二次完整构建。

## 固定来源

[N]: https://github.com/numpy/numpy/blob/768d7ef361e0207a24faf3783d5f668a0f137d46/numpy/_core/src/npysort/selection.hpp
[NQ]: https://github.com/numpy/numpy/blob/768d7ef361e0207a24faf3783d5f668a0f137d46/numpy/lib/_function_base_impl.py
[V]: https://github.com/libvips/libvips/blob/5e918bd43a5f2fc8cfcd23fddbfc6704a1fcff3f/libvips/histogram/percent.c
[G]: https://www.ipol.im/pub/art/2013/87/
[GS]: https://www.ipol.im/pub/art/2013/87/gaussian_20131215.tgz
[C]: https://eel.is/c++draft/alg.nth.element
