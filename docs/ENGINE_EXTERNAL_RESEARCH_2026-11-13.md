# 外部引擎与算法调研：2026-11-13 第二轮

同日后续针对 LibRaw 与同类 decoder 的所有权复核已形成 0057 / LibRaw 0025，
见 [原始存储回收、固定来源与实测](ENGINE_RAW_STORAGE_LIFETIME_2026-11-13.md)。
下文“完整峰值基本不变”是 0056 的历史结果，不代表后续实现。

GPT（OpenAI）共创。北京时间 2026-11-13 08:46–09:04 查询官方 GitHub API，
按下列提交阅读实现。这里的 HEAD 是查询时快照，不是持续追踪承诺。
本轮由源代码调研推进到 RGB16 工作图转换实施，见
[实施与验证](ENGINE_RAW_WORKING_CONVERSION_2026-11-13.md)。

## 1. 来源与具体发现

| 项目 / 分支 | 固定提交 | 实际阅读的入口与结论 |
|---|---|---|
| SpektraLab / Windows_Linux | `894915fb7f93656d2951eaf11a8be35e4ff60246` | 相比上一轮没有更新。此前新增六条为 Windows 发布与更新检查；不据此更换引擎 pin。差异见[第一轮上游报告](UPSTREAM_UPDATE_RESEARCH_2026-11-13.md)。 |
| LibRaw / master | `a9a14b1970d188c25dade81f8326ff00a6e33649` | 没有新增提交。保持 0.22.2 加 18 个受控 overlay；最新 0.22.3 维护项的取舍沿用第一轮报告。 |
| SAINTTaiYi / master | `b17f6d2bdcd8024bb62abf212d10819115ca2d87` | Android 分批与 RAW 生命周期结论未变；不把带状 GPU 提交当作端到端恒定内存。 |
| thetechgeekko / main | `e70771cf99e61481feb471d5c4ac827a488c73f6` | inset crop 的整数几何、任务票据与缓存租约是具体参考；前者会改变输出几何，未与本轮性能改动捆绑。 |
| Knonoa / main | `6d8dd5a098e83f03e6bb67b222dc55014184c08e` | `engine/src/io/raw_resample.hpp` 在 RGB16 转 float 时直接降采样，提供本轮切入点；其归一化算序不同，不能直接移植后声称逐位一致。[K] |
| vkdt / master | `d240f06994740f3b968117f6d941abcbf6e9023f` | `graph-run-nodes-allocate.h` 先找最后引用，再做 alias 分配；动态纹理数组与 feedback 双缓冲有额外寿命限制。不能只按拓扑相邻就复用显存。[V] |
| darktable / master | `78f66f802183c6d241656f82b03ce6c10c1724d8` | `tiling.c` 使用 factor/maxbuf/overhead、overlap 和 alignment 规划 tile；`pixelpipe_hb.c` 把输入输出 ROI 与 cache 联系起来。分块收益必须扣除 halo，不是只设一个固定 tile 边长。[D][DT] |
| RawTherapee / dev | `64c2d90a3248c7ad41842d9162368858b61aac6a` | `ipresize.cc::Lanczos` 对横纵权重分别归一化；RCD 显式设 9 像素边界，AMaZE 也按带边界的 float tile 工作。去马赛克和重采样的算法选择与调度优化须分别验收。[R][RC][RA] |
| Halide / main | `f8d34e47ae71315c440ad73c7dc3fcbc0270cae1` | tutorial lesson 08 展示 full inline、compute_root、逐行 compute_at 与 storage folding 的带宽/算力取舍。本轮按行转换并复用横向结果，采用这个调度原则，没有增加 Halide 依赖。[H] |
| OpenImageIO / main | `fcfd4092a7e8c1de8b99878a13e17af2b80ac7c2` | `imagebufalgo_xform.cpp::resize_` 根据缩放比扩展采样支撑，对可分离滤波预计算横向权重，按 ROI 并行。可借鉴权重复用；它的坐标、滤波器与边界定义不是我方面积缩放的逐位 oracle。[O] |
| OpenColorIO / main | `22dd9c4faa0d1b7cfb3d64d1a6e9cb454acc6cfd` | Processor 分开生成 CPU/GPU 实现；配置、Processor、CPU/GPU Processor 的缓存层次不同，ClearAllCaches 不清除所有实例缓存。缓存失效必须按实际依赖设计。[C] |
| Spektrafilm / main（agx-emulsion 原仓库入口） | `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc` | `SpectralLUTService` 跟踪感光度、适应与输入 gamut compression；`diffusion.py` 将 scatter 和多次反射 halation 分开，以像素物理尺寸换算半径。`fft_gaussian_filter.py` 的 padding 与边界是算法的一部分。[S][SD][SF] |
| Colour / develop | `7ca0c87a85c10dd5e1fd51c09e46bda1f863dff3` | 本次仅核对 HEAD，不把它列为已做算法逐行审计的项目。已有色彩 oracle 不因其 Python 支持版本更新而替换。 |

前三个 Android 项目的完整来源与许可边界见[第一轮同类报告](PEER_ENGINE_RESEARCH_2026-11-13.md)。
本轮新增生产实现为本项目独立编写，保留原有面积权重与累加顺序；没有复制这些外部项目的实现。
后续如移植 RCD/AMaZE、darktable 或其他源代码，仍须保留对应作者与逐文件许可证，
不能以项目整体许可证代替具体文件核查。

## 2. 算法选择，而非直接替换默认值

### RGB16 转换与面积降采样

采用“生产者按消费者需要的行计算”的方式，消除全尺寸 float 中间帧。
算法仍是现有可分离面积平均：每个码值先执行 `float(double(code) / 65535.0)`，
再沿相同横向、纵向顺序进行 float 累加。保留正权重与现有边界。
这是调度和缓冲寿命优化，数值等价可由冻结 oracle 验证，不依赖主观画质评分。

Lanczos、双三次、EWA 都有具体用途，但改变滤波核、像素中心或边界就会改变图像。
不能用一个“更锐”的显示效果代替物理胶片输入验收，也不能把 OIIO 的另一套
滤波坐标直接套进当前面积权重并要求逐位相同。[R][O]

### RCD / AMaZE 去马赛克

RCD 的比例校正和 AMaZE 的抑制混叠设计值得保留为后续独立实验入口。
源码同时说明它们依赖 CFA、边界扩展、tile overlap 与高光处理等上下文。
不是替换一个函数名即可获得“更好”的 RAW。升级须在同一解包、黑白电平、
白平衡与颜色矩阵下，分别考察斜边、细纹、星点、饱和色和高光；
本轮不把有意改变像素的去马赛克算法混入严格等价的输入转换改动。[RC][RA]

### FIR / IIR / FFT 与物理散射

Spektrafilm 将能量保持的 scatter 与附加反射 halation 分开，混合多个尺度；
这会改变模型，必须有独立的物理参数及材料版本，不能当成无损性能优化。
其 FFT helper 的 `truncate` 默认实参为 7.0，说明文字仍写 4.0，
调研以可执行实现为准；padding 会影响边界，禁止默认认为 FFT 与当前 IIR/FIR 相同。[SD][SF]

我方现有 IIR v1/v2 实验继续保持原状态；进一步优化先固定冲激/DC 增益、
边界、半径与容差，再比较同一核的耗时。物理模型变化和数值近似变化分别记版本。

### LUT 与颜色处理缓存

成熟项目缓存的是具有明确依赖的变换。Spektrafilm 的缓存会关心适应状态和
输入 gamut compression，OCIO 也区分各层 Processor 缓存。[S][C]
我方此前已修完整 gamut key、共享 LUT 生命周期及 view 曲线准备，
本轮不重复引入缓存框架。全流程 LUT 仍须通过 signed/HDR、参数变更、
邻域效应和上下文独立性检查，不能把空间散射、颗粒与全图测光烘成一个点 LUT。
原 LUT 实验未通过的门禁继续有效。

## 3. 本轮取舍与下一处实际瓶颈

| 候选 | 本轮处理 | 验收依据 |
|---|---|---|
| RGB16 →工作 float 合并转换 | 已实施，0056 + Android adapter | 冻结面积 oracle、真实生产解码/JNI、取消、八方向、标准显影完整统计、ARM64 链接。 |
| 全尺寸 float 中间帧寿命 | 已缩短 | 分阶段 RSS 明显下降；完整解码峰值由更早的 LibRaw 阶段主导，不能宣称本轮消除 RAW OOM。 |
| 整条 RAW 解码峰值 | 明确定位，未承诺降低 | 24 MP 合成 LinearRaw 全流程旧新峰值基本相同，后续要逐项识别 LibRaw 原始存储、四通道工作图与最终 bitmap 的所有权和重叠。 |
| Vulkan 全图 alias / 真正端到端 tile | 保留为独立架构工作 | 先建立引用与 GPU completion 生命周期，处理 feedback/上传/读回及 halo 后再复用；不能直接照搬 vkdt。[V][DT] |
| inset crop | 不与等价性能改动合并 | 会改变几何、源尺寸及统计范围，需要独立的 crop/orientation oracle。 |
| 新去马赛克 / 新散射模型 | 保留为数值变化实验 | 需要公开 RAW、受控图案和明确容差，不能从外部项目说明推导我方画质提升。 |

本轮没有改引擎 pin、RAW 中性线性默认、胶片材料或默认高斯模式。
调研不以项目数量代替验证；实际收益和边界以[实施报告](ENGINE_RAW_WORKING_CONVERSION_2026-11-13.md)为准。

## 固定源码

[K]: https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/engine/src/io/raw_resample.hpp
[V]: https://github.com/hanatos/vkdt/blob/d240f06994740f3b968117f6d941abcbf6e9023f/src/pipe/graph-run-nodes-allocate.h
[D]: https://github.com/darktable-org/darktable/blob/78f66f802183c6d241656f82b03ce6c10c1724d8/src/develop/pixelpipe_hb.c
[DT]: https://github.com/darktable-org/darktable/blob/78f66f802183c6d241656f82b03ce6c10c1724d8/src/develop/tiling.c
[R]: https://github.com/RawTherapee/RawTherapee/blob/64c2d90a3248c7ad41842d9162368858b61aac6a/rtengine/ipresize.cc
[RC]: https://github.com/RawTherapee/RawTherapee/blob/64c2d90a3248c7ad41842d9162368858b61aac6a/rtengine/rcd_demosaic.cc
[RA]: https://github.com/RawTherapee/RawTherapee/blob/64c2d90a3248c7ad41842d9162368858b61aac6a/rtengine/amaze_demosaic_RT.cc
[H]: https://github.com/halide/Halide/blob/f8d34e47ae71315c440ad73c7dc3fcbc0270cae1/tutorial/lesson_08_scheduling_2.cpp
[O]: https://github.com/AcademySoftwareFoundation/OpenImageIO/blob/fcfd4092a7e8c1de8b99878a13e17af2b80ac7c2/src/libOpenImageIO/imagebufalgo_xform.cpp
[C]: https://github.com/AcademySoftwareFoundation/OpenColorIO/blob/22dd9c4faa0d1b7cfb3d64d1a6e9cb454acc6cfd/include/OpenColorIO/OpenColorIO.h
[S]: https://github.com/andreavolpato/agx-emulsion/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm/runtime/services/spectral_lut_compute.py
[SD]: https://github.com/andreavolpato/agx-emulsion/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm/model/diffusion.py
[SF]: https://github.com/andreavolpato/agx-emulsion/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm/utils/fft_gaussian_filter.py
