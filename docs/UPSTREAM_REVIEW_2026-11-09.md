# 上游引擎源码评估，2026-11-09

共创：GPT（OpenAI）。本文记录本轮实际读取的 Git tree、源码和验证结果。源码判断、Linux host 实测与 Android 真机结论分开；没有把提交标题、上游自述或一次 host 计时当作手机验收。

## 1. 分支、时间和迁移边界

| 对象 | 固定提交 | 本轮用途 |
| --- | --- | --- |
| Android 引擎 pin | [`a46627d250545a394ca21522f8a28b0fb89e4c42`](https://github.com/JamesQiu2005/SpektraLab/commit/a46627d250545a394ca21522f8a28b0fb89e4c42) | 保持 [fetch-upstream.sh](../scripts/fetch-upstream.sh) 的基线，加本仓库适配补丁 |
| Windows_Linux | [`a4289ab99bc09fc998e6148dc3d496f9079e5857`](https://github.com/JamesQiu2005/SpektraLab/commit/a4289ab99bc09fc998e6148dc3d496f9079e5857) | 核查 Vulkan、RAW 和 Sony overlay；选择性吸收准备阶段 device-loss 保护 |
| 初次检查的 main | [`d90cf9a005d5c7eb4c0bc1eb9cac17459c2a9289`](https://github.com/JamesQiu2005/SpektraLab/commit/d90cf9a005d5c7eb4c0bc1eb9cac17459c2a9289) | 2.2.1；0026 最初参照的源码快照 |
| 本文最终核查的 main | [`d4b8145f6f9bdade15cda014776e99521adf0b5f`](https://github.com/JamesQiu2005/SpektraLab/commit/d4b8145f6f9bdade15cda014776e99521adf0b5f) | 2.2.2；提交时间 2026-11-09 02:47:04 UTC，即北京时间 10:47:04 |

2026-11-09 本轮重新查询 GitHub REST，main HEAD 是 d4b。固定 [d90 到 d4b 的 compare](https://github.com/JamesQiu2005/SpektraLab/compare/d90cf9a005d5c7eb4c0bc1eb9cac17459c2a9289...d4b8145f6f9bdade15cda014776e99521adf0b5f) 为两次提交、六个文件，涉及 Mac 菜单、工程版本和手册，没有 engine 改动。因此此前读取的引擎差异仍适用，但 d90 不能继续写成最新版本。

GitHub [a4289ab 到 d4b 的 REST compare](https://api.github.com/repos/JamesQiu2005/SpektraLab/compare/a4289ab99bc09fc998e6148dc3d496f9079e5857...d4b8145f6f9bdade15cda014776e99521adf0b5f) 明确返回 `404 / No common ancestor`。这是远端仓库的结果，并非本地浅克隆造成的误判。不能将 main 当作 Windows_Linux 的普通快进升级；也不能直接覆盖文件，否则会丢掉 Android 已有内存、取消、缓存所有权、RAW 色彩和 Vulkan 适配。

下面的源码链接统一固定到 d4b；它们表示读取的实现，不冒充首次引入该实现的提交。0025 的实际修复提交单独标明。

## 2. 本轮选择性吸收

| 项目 | 精确范围 | 当前证据与结论 |
| --- | --- | --- |
| [0025](../patches/0025-vulkan-device-lost-preparation-guards.patch) | 扩展现有 0008：在 pool/queue 锁内、inline ring 写入之前重查 device-loss；清理仍可进行 | 来源是 Windows_Linux 的 [`53299f734aa6aa7ee13f55c19b28494f1beb6dcb`](https://github.com/JamesQiu2005/SpektraLab/commit/53299f734aa6aa7ee13f55c19b28494f1beb6dcb)，a4289ab 是后续分支快照。Linux llvmpipe 注入已记录的 lost latch 后，ring 开/关均拒绝 16 个工作入口、保留清理；旧实现对照在首次 alloc 失败。不是实际 Android 驱动故障实验 |
| [0026](../patches/0026-cam16-hoist-table-invariants.patch) | CAM16 Cmax 二分中的观察条件常量移到表外、J 行常量移到行外；保留 double、原公式、串行调度、公开接口和运行时建表 | strict、default 和实际生产 archive 三路均通过：每路六色域、276,480 个 Cmax double，以及 metadata、矩阵、网格逐字节相同；公共正逆变换另有边界回归。不导入 baked table 或四线程调度 |
| [0027](../patches/0027-di-precompute-channel-curves.patch) | FilmHost 构造时预拆三个通道的 x、normal、DIR 曲线；删除每次 curve 查询的两个临时 vector | 保持原 `di_constants` 签名、完整拟合、`interp`、double 样本及顺序，不导入 minilab 的 early return。云端 GNU `--fuzz=0` 应用、实际 core 重编完成；strict/default/生产 archive 三路均逐字节通过。每路 20 stocks、120 组合，其中 94 成功、26 与旧版匹配的拒绝 |

0026 的一次 host 诊断计时分别为 strict 2320.62→1638.21 ms、default 2315.64→1693.76 ms、archive 2339.85→1654.77 ms。测试不设性能门槛；不能将这些 Linux 数字换算成手机加速比例。逐字节结果限定于此次同一编译器、libm 和输入环境，不是跨架构承诺。

## 3. Core、颜色与数据：逐项决策

| 项目及实际实现 | 可获得的收益或行为变化 | Android 决策和必要验证 |
| --- | --- | --- |
| [CAM16 inverse 与 setup](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/core/cam16.cpp#L136)：提取与 hue/chroma 二分无关的项 | 避免大量重复 pow/sqrt，不需要改变表精度或 gamut 语义 | 已抽取为 0026；[完整表 oracle](../tools/native/cam16_setup.cpp) 和 [runner](../tools/native/run_cam16_setup.sh) 覆盖六输出空间及公共 API |
| [CAM16 runtime 并行](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/core/cam16.cpp#L263)：三个 jthread 加调用线程分担四组行 | 可降低冷 setup 延迟，但增加同时运行的 CPU 工作 | 暂不导入固定四路。需与 Android 现有 worker budget、前台渲染、热状态共同限额，分别测冷启动、预览并发和持续导出 |
| [Cmax baked loader](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/core/cam16_bake.hpp#L27) | 直接读取预生成 float64 表，省去 runtime setup | 暂不导入，原因是跨平台等价和打包尚未验证，不是表精度较低。详见第 4 节 |
| [FilmHost::curve](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/core/digital_intermediate.cpp#L81) | 将重复的交错到平面数据整理提前，减少 allocator 压力 | 抽取为 0027；只复制已有 double，保留查询、插值、端点与算序。两处实际调用分别传 normalized_curves 和成员 curves0，地址选择与这两个调用相符 |
| [DI `log_positive_only`](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/core/digital_intermediate.cpp#L279) | 为 minilab 在构造反转曲线后提前返回，不计算完整 DI 颜色拟合 | 不随 0027 导入；它改变接口和输出需求，不能当作现有 DI 的透明提速 |
| [MinilabCatalog](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/core/minilab.cpp#L32) 和 print 路径 | 经负片反转后的 log-positive RGB 映射到相纸 CMY density，再走相纸 scan/output；会改变当前 optical-print 模型的效果 | 暂不导入，需要完整 Vulkan 实现、资源验证、旧编辑语义和视觉验收；不是把一个普通 RGB LUT 放进现有接口即可。详见第 5 节 |
| [scene_curve 参数](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/core/params.hpp#L319) 与 [解析](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/core/params.cpp#L380) | 在测光中灰附近 -8..+8 stops 上增加 rgb/luma/r/g/b 五行输入曲线；luma gain 先于 rgb、分通道曲线 | 属于胶片前 SHOOT 层，不等于 Android 胶片后 Layer2 曲线。初审要求补 Vulkan、UI/持久化、参数导入、缓存失效和曝光顺序；后续已由 0029 与 Android 集成实现，验证及剩余限制见第 10 节 |
| [latitude_fit.cpp](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/core/latitude_fit.cpp) | 抽出复用的 stop_histogram，并增加用于显示的 channel_histogram | 未发现自动 fit 核心数学被替换的证据；不把代码整理说成新的自动曝光算法。若迁移，应先比较相同 histogram/fit 输出再接 UI |
| [apply_halation_preset](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/core/params.cpp#L575) | back-reflection 条件由材料自身 antihalation=no 改成用户当前有效设置 ah=no；其他胶片关闭防光晕层也会使用 CineStill 测量的回反射模型 | 这会改变已有编辑的光晕和高光，不是等价性能优化。暂缓，需产品明确效果版本和回放策略，并测试多材料、尺寸与高光分布 |
| [基础 blob loader](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/core/blob.cpp) | 常量文件改名，loader 的品牌/路径处理有差异；基本 SPKR v1 数据未换 | 保持 Android 资源路径，不能只跟随文件名更改。新 minilab/CAM16 是旁路资源格式，不等于基础 blob 全部升级 |

`preview_mode` 的 grain/blur 简化块在本轮 pin 与 main 对照中相同，不列为上游新增能力，也不据此宣称 Android 新增了质量降级。

Git tree 的 blob 对照确认：`colour`、`curves`、`hanatos`、`numeric`、`printing`、`profile`、`spectral`、`camera_filters`、`halation_psf`、`print_lut` 的对应源码未改变；基础 profiles、product profiles、neutral filters 和 paper grades 数据亦未换。此处“未改变”指固定 pin 与 d4b 的同路径 Git blob 相同，不是整个 pipeline 输出不变。

基础常量从 `engine/resources/spektrafilm_constants.bin` 改名为 `spektralab_constants.bin`，两者 Git blob 均为 `147b6d3a99c9b213a1e6adab590740084e488217`，长度均为 9,450,196 bytes。因此没有依据称其为新一代光谱数据包。加载代码和新增旁路资源仍需要单独评估。

## 4. 预烘焙 Cmax 的真实格式与门槛

main 的 [cam16 README](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/resources/cam16/README.md) 与 loader 表明，每个色域文件保存 64×720 个 native-endian float64 Cmax 值，共六个色域。prefix 包括版本、色域、观察条件、矩阵、网格、维度和二分边界；payload 校验 FNV，读取时检查长度、prefix、有限性及范围。缺失或无效时回到 runtime 建表。

这是一份生成环境的精确 double 快照，不能称作低精度近似。但 header 没有显式绑定编译器/libm；观察条件和矩阵一致，不自动证明整个 inverse、pow/exp/trig 和二分过程在另一架构得出相同每一位。因此本轮只拿走不改变公式的 invariant hoist。

后续可实施的准入是：用目标 Android NDK/Clang/Bionic 在 ARM64 上完整生成六表，与候选文件逐 double 对照；同时测试损坏、错版本、错字节序、缺文件和资源升级。若不能逐字节一致，应明确采用“固定标准表”的新渲染版本，不能静默把 host 快照称作原手机算法的同义缓存。还需比较 APK 增量、冷读取与冷建表、峰值内存和热机启动，才能选择随包或设备本地缓存。

## 5. Minilab 是新显影模型，不是一般 look LUT

实际 [index.json](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/resources/minilab/index.json) 只列出 **Kodak Portra 400 → Kodak Portra Endura** 一组材料关系，分别为 sRGB 和 ProPhoto RGB 目的校准；其他支持的输出空间使用 ProPhoto 校准后再做输出转换。不能将一个 pair 的结果推广成所有胶片/相纸都有新校准。

每张 LUT 的实际文件是 28-byte header 加 `65^3 × 3 × sizeof(float)`，共 3,295,528 bytes。loader 验证维度、输入域、payload 有限性、文件 hash、film/paper 原始 profile hash，以及派生 profile 声明的 base hash。未列出的材料对走旧 optical 路线；已列出但资源缺失/损坏会报错，不会静默回退到另一种显影效果。

数据入口是恢复后的 log-positive RGB，输出是相纸 CMY density；它既不是输入 camera RGB 到 display RGB，也不是现有 `spk_print_lut_table` 的可直接替代品。main 的 [SetupCache::set_resources_dir](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/core/setup_cache.hpp#L47) 仅在 `__APPLE__` 下初始化 minilab。

Android 若接入，最小完整工作包括 Vulkan 采样核、pipeline stage/key、完整资源清单与 hash、旧编辑的 optical/minilab 路径标记、预览/导出一致性，以及材料缺失的明确错误。应以相同输入、曝光、DIR、胶片、相纸和输出空间对照旧 optical 与新路径，先审中灰、彩色 patch、负值/HDR、肤色与饱和色，再决定产品默认。上游配套检查源码已读，本轮没有运行 Android minilab，也没有主张其视觉优劣已经验证。

## 6. RAW/DNG 不能回退到 main

[main raw_decoder.cpp](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/io/raw_decoder.cpp#L43) 仍以 LibRaw `output_color=4`、linear gamma、全尺寸 AHD 和 uint16-compatible 输出为基本路径。该 tree 没有 Android 当前使用的 `raw_camera.hpp`；也没有本轮已建立的 DNG ForwardMatrix、known-K 自定义白平衡和 source WB validity 集成。

| 交界 | main 与 Android 当前差异 | 保留/迁移要求 |
| --- | --- | --- |
| DNG 色彩矩阵 | main 未包含 0021 的 ForwardMatrix/profile 路线 | 保留 Android 0021；不能用旧 LibRaw ROMM 输出覆盖本轮真实 RAW 修复 |
| 自定义 K/tint | main 没有 0022/0023 的修正表与显式已知色温接口 | 保留 known-K 前向选择；不得回到离轨 tint 通过 inverse-neutral 多根反推色温的流程 |
| 缺失源 WB | [capture_source_metadata](https://github.com/JamesQiu2005/SpektraLab/blob/d4b8145f6f9bdade15cda014776e99521adf0b5f/engine/src/io/raw_decoder.cpp#L79) 对源 camera WB 仍作无条件校验；valid custom 也可被未使用的 as-shot 挡住 | 保留 0024 的 validity flag、原始 metadata 与有效 custom 放行；默认和非法 custom 仍严格失败 |
| headroom 接口 | main 只有默认 headroom 入口，没有 Android 自定义 WB/selected-K 的完整转发 | 保留 compatible/headroom 两模式共同的参数、矩阵和失败原子性回归 |
| 内存与线程 | main 不包含 Windows/Android 的 RawDecodeThreadScope、worker budget 及此处已采用的解码内存释放时序 | 必须保留实际 Android 改造；全文件替换将改变峰值和并发 |

`raw_headroom.cpp/.hpp` 的基础转换和 `image_writer.cpp/.hpp` 在 pin 与 d4b 之间 Git blob 相同；变化和兼容风险主要在调用者及输出政策。该结论不代表目前已实现完整 Adobe DNG 默认显影；WhiteXY-only、BaselineExposure、完整 profile 等限制继续见 [RAW 回归说明](RAW_COLOR_REGRESSION.md) 和 [输入精度说明](ANDROID_INPUT_PRECISION.md)。本文没有把 main 当成已通过真实 RAW 比较的新 oracle。

Sony ARW6 支持已经在公开 beta 的 [libraw/0019](../patches/libraw/0019-sony-arw6.patch) 中，不是 beta2 新增。overlay 首次来源为 [`b5467e83b629d610e82903837fa8a40c9a0f5909`](https://github.com/JamesQiu2005/SpektraLab/commit/b5467e83b629d610e82903837fa8a40c9a0f5909)。到 a4289ab 的 compare 是 ahead 32、44 个改动文件，没有 libraw-arw6；两次未截断 tree 的 overlay subtree SHA 同为 `7c48f6ea79fba36273aa22b17b6a24babc93c1b5`，12 个 blob 相同。Android beta `6aff78e` 与当前 0019 的 Git blob 同为 `1bab5fb0e1028a2086072164d51902c96487243c`。不能把分支发布日期较新误写成 Sony 支持又升级了一次。

## 7. GPU、Metal、checkpoint 和 noFilm

完整源码、函数行号和后续步骤见 [GPU 专项评估](UPSTREAM_GPU_REVIEW_2026-11-09.md)。对本轮算法选择有直接影响的结论是：

- 固定 main 的 DIR finish、XYZ→RGB/CAM16 融合是 Metal 路线；上游 Vulkan 尚未获得对应融合。初审后的 Android 移植见第 10 节 0028，默认关闭；仍须在同一 Android 设备上比较旧核/新核中间 float32，不能拿 Metal 或 Linux llvmpipe 测试替代手机验收。
- checkpoint 是 Apple 专属的 pre-grain 整平面临时文件缓存，恢复时仍分配整个平面；主 app 的 bands 也只用于进度。它们不证明已经完成 bounded source/intermediate/output 或解决手机单 SSBO 限制。
- main Metal `trim_pool` 存在 `delete b` 后读取 `b->bytes` 的明确源码生命周期错误。本轮没有运行 Apple sanitizer；Android 不编译该实现。该问题是不能整套移植的具体理由，不能扩大成未验证的运行时故障结论。
- Apple noFilm 使用带默认 tone/gamut mapping 的 CIRAW 显影，然后输出 ROMM RGBA16 再进 Layer2；不是负值/HDR float 无量化直通。Android noFilm 的量化边界应单独设计，不应以“照抄 main float 管线”描述一个并不存在的端到端路径。
- Android 现有 setup cache shared ownership、完整 key、descriptor/inline-ring/pipeline-cache、buffer 上界与分阶段释放需要保留；main 新功能不是抹去这些改动的理由。

## 8. 0027 的可复现对照和限制

[di_setup.cpp](../tools/native/di_setup.cpp) 编译时直接 include 冻结原始 TU 与当前生产 TU，以宏重命名两个公开函数，避免把 baseline 链接成当前实现。内部边界探针调用各自实际 FilmHost，不重抄插值公式。strict/default 分别编译旧新 TU；第三路公开函数从本次实际 CMake archive 提取，仅匿名内部探针从重命名的当前 TU 提取。

冻结 `digital_intermediate.cpp` 的 LF SHA256 为 `cb136c69faca30bc65f21b2e0dbc3b208a2d56c1375a9533210f9fbb53cd4bba`，runner 强制检查。资源包括 upstream base/product profiles 和 Android profiles-extra；按产品 DI 规则排除相纸、正片及 BW。每个适用 stock 比较六组 DIR/amount/gamma/输出空间，完整 DiConstants、live offsets、所有 knot 和 nextafter 邻点、端点外查询及负值/HDR develop 输入。

部分控制组合在旧算法也会因 `no usable neutral contrast` 等原因拒绝；测试必须比较 bool、诊断和所有已构造的字段/向量，并单列 `matched_rejections`，不能把共同拒绝记作成功配置。任何旧成功/新失败，或诊断/字段差异都会失败；成功结果还必须全部 finite。

```sh
UP=/data/work/gpt-engine-impl-20261108/upstream \
B=/data/work/gpt-engine-impl-20261108/build-host \
BASE_DI=/path/to/frozen/digital_intermediate.cpp \
BUILD_DIR=/path/to/di-0027-evidence \
bash tools/native/run_di_setup.sh
```

必须把 runner 记录的生产 CPP/archive SHA 与本次重编日志一并保留；仅看到 archive 对照通过，不能证明 archive 含有补丁，因为原版与原版也会相等。这个实验验证 setup 和内部曲线边界，不取代最终 Vulkan 像素、真实 Android 编译与多设备回归，也不将一次计时当成稳态性能基准。

## 9. 证据位置与下一步

以下是本次工作站的研究证据路径，不是 APK 资源，也不是其他 checkout 必然存在的目录：

| 证据 | 位置 |
| --- | --- |
| main 固定 tree 与读取的源码 | `D:/AGI/GPT/other/spektralab-backend-research/upstream-review-20261109/main-d4b-tree.json`；同目录 `main/` |
| 0025 红/绿对照、source SHA、build/transfer 记录 | `D:/AGI/GPT/other/spektralab-backend-research/upstream-vulkan-20261109/VALIDATION.md` 与 `cloud-evidence/` |
| 0026 三路全部结果、环境与 SHA | `D:/AGI/GPT/other/spektralab-backend-research/cam16-0026-evidence/` |
| 0026/0027 冻结原始 source | `D:/AGI/GPT/other/spektralab-backend-research/sources/upstream-wl/engine/src/core/`；由 runner 各自核验指定 LF SHA，不以该浅克隆的 ancestry 作证据 |
| 本仓库可复跑入口 | [run_cam16_setup.sh](../tools/native/run_cam16_setup.sh)、[run_di_setup.sh](../tools/native/run_di_setup.sh)、[run_vulkan_device_lost.sh](../tools/native/run_vulkan_device_lost.sh) |

0027 每路比较 1,048,404 个 double，其中 curve/develop 边界值 511,440 个是总数的子集，不重复计数。生产源码 SHA 前缀 `ffcef5cc`、重编 archive 前缀 `6d98a281`；完整值由 runner 日志记录。一次 strict 5134→4928 ms、archive 5097→4882 ms 只作 host 诊断。包含 0027 的 Android `assembleRelease` 已成功；29 项 JVM 测试沿用此前相同 Kotlin 源码的通过记录，本次任务显示 UP-TO-DATE，不能写成重新执行了 29 项。

初审阶段完成了 0027 等价验证与 Android 重编，并将 DIR/XYZ 融合及 scene curve 列为后续迁移。随后已完成下面的 0028-0030 实现，开发目标转为 `0.2.00-alpha-1`；这不是已发布声明。Cmax 跨平台表、minilab 与 halation 效果变化仍须各自验证。每次照片语义变化都需要明确输入、版本和验收依据。

## 10. 初审后的 0028-0030 落地状态

此节记录 2026-11-09 后续开发，保留前文对固定上游 tree 的判断。上游 pin 没有整体切换，Android 原有补丁仍保留；不能把这三项称为 main 全量同步。

| 已落地项目 | 实现边界 | 已执行验证与剩余限制 |
| --- | --- | --- |
| [0028 Vulkan pointwise fusion](../patches/0028-vulkan-pointwise-fusions.patch) | 将 Metal 的 DIR subtract+curve、XYZ→RGB+CAM16 思路分别转译为 Vulkan；保留原算式顺序、分离核和旧入口。只有 `SPK_VULKAN_FUSION=on` 启用，默认及非法值均走分离核 | 独立 0001-0028 Linux llvmpipe 构建的 494 例、1,490,286 个有效 float32 值逐位相同，含六色域 paper/DI 的 12 次完整 off/on 渲染、尾工作组和非零 base 的 1D 分块。640 个非有限参考值按位相同，不冒充全 finite。没有真机 Adreno/Mali、硬件 FMA 路线或实际二维 dispatch 验证；本轮不作为默认手机提速 |
| [0029 scene curve](../patches/0029-scene-curve-vulkan.patch) 与 Android 集成 | SHOOT 层 `scene_curve` 五行采样表，在测光中灰附近 -8..+8 stops 处理 luma、RGB、各通道；空字符串精确旁路。Android UI、sidecar、风格导入导出、队列快照、复制/重置和 SHOOT 缓存失效均接通；无胶片时暂停但不丢弃编辑 | 最终叠加 0028-0030 的 InstaBox strict/default 两路，各 63×517 shader 输入最大相对误差 2.17357e-6；parser 与 paper/DI 公共 API 检查通过，含 empty/clear、缓存、whole/strip、medium probe 隔离、非法 delta 原子失败及旧基线不变。EV 1 局部曲线对输入×2、EV 0 的最大码差为 0。JVM/UI 验证单列，不把 native API 测试当成真实 Android 操作 |
| [0030 no-film float boundary](../patches/0030-nofilm-float-grade-boundary.patch) 与 Android 输出适配 | 内部 view request/result 承载未调色 RGB float，公开 C `spk_result` ABI 不变。仅无胶片先在 CPU 完成 Layer2，再量化和输出转换；胶片/DI 保留原 RGBA16 路径。新的未量化路径使用原 shader 的曝光/亮度幂公式，避免旧近黑 LUT 近似经输出变换放大 | 最终生产 archive 的 strict 回归：两组负值/HDR 旧提前裁切错误 29,544/2,231 个 UNORM16 码降至 3/1；56 组合最大误差 1 个 UNORM16 码、0 个 UNORM8 码，364 TIFF 行与 28 缓存重打包通过，裁切/旋转/翻转/缩放和六种非法请求通过。InstaBox 最终 strict/default 复证相同矩阵上界。不是任意调色的逐位 GPU 等价 |

0029 的 Android 控制点生成 129 点表；导入的 2-4096 点原生表保留原 CSV、以只读方式显示，重置才改为可编辑控制点。旧风格缺失字段不覆盖已有曲线，显式 identity 才重置；默认参数不发送该字段。显式对角采样表涉及 log/exp/interpolation，不等于空字符串的逐位旁路。scene curve 不与胶片后的 Layer2 曲线合并。

0030 仍缓存整图 RGB float，每像素 12 bytes，比旧 RGBA16 的 8 bytes 增加 50%；输入、输出和单 SSBO 约束没有因此消失。几何/重采样仍发生在 Android CPU 调色之前，而上游参考可先调色再重采样；非线性调色不能一般性交换顺序。当前几何回归采用范围内输入和 identity grade，不能扩写成全组合等价。用户 LUT 的域裁切以及位于 WB/曝光之前的顺序也未在本轮重构。该方案是 Android 内部精度边界修复，不是复制 main 的端到端 float 路线。

三个可复跑入口分别为 [run_vulkan_fusion.sh](../tools/native/run_vulkan_fusion.sh)、[run_scene_curve.sh](../tools/native/run_scene_curve.sh)、[run_nofilm_float.sh](../tools/native/run_nofilm_float.sh)，依赖和浮点模式见 [native README](../tools/native/README.md)。`STRICT=0` 只改变相关 harness/adapter 编译，不会重新编译已存在的 archive 或 SPIR-V。融合 runner 本身使用编译器默认浮点模式。

证据分别保存在工作站 `D:/AGI/GPT/other/spektralab-backend-research/vulkan-fusion-20261109/evidence/`、`scene-curve-0029/results/`，以及 AOIDC `/data/work/gpt-alpha1-20261109/nofilm-float-final.log`。InstaBox 最终叠加测试日志名为 `final-scene-{1,0}.log`、`final-nofilm-{1,0}.log`；此前独立 0029 的 `tests-ev.log` 不替代最终叠加日志。两台云主机最终源码清单记录在 `scene-curve-0029/results/final-source-{ibx,aoidc}.json`，这些路径不是随 APK 发布的样片或资源。真实 RAW 的既有记录仍见 [RAW_COLOR_REGRESSION.md](RAW_COLOR_REGRESSION.md) 与其机器证据；本轮没有用户本人 13 Ultra 原片，也不据此宣称 Lightroom/Adobe 等价。

## 11. 后续深审修正

第 10 节是首次叠加快照，未含随后发现的 scene-curve dispatch-base 修复及扩大后的测试，不能替代最新证据。
0029 Vulkan 核现已加 `spk_pc.base`，新测试在旧 shader 上失败；新 shader 在两云均通过 252 种分批/原位执行和条带逐字节对照。
Android 还修复 Double 控制点转 Float 后合并导致曲线失效、局部蒙版提前裁切 signed/HDR 的问题。
noFilm 扩大至 15 looks/120 组合、780 行 TIFF、60 repack；AOIDC 最大误差 2/1 个 UNORM16/UNORM8 码，InstaBox 为 1/1。
最新两云 16 个引擎文件清单名为 `precision-source-{ibx,aoidc}.json`；GCC14 日志在 `results/ibx-precision-final/precision-*.log`。
源码编译、JVM、完整胶片和输出对照以 [alpha1 机器记录](validation/alpha1-2026-11-09.json) 为准。
内存、取消、真实原分辨率输出的未解边界见 [深审记录](ENGINE_DEEP_REVIEW_2026-11-09.md)。当前暂停打包、提交和发布。
