# Android 同类引擎源码复核，2026-11-13

本项目基线：Spektralab-X alpha-5 / 125，HEAD `a94d584d2bb33ce721c27b68b28fcdbf598e9098`。
时间：2026-11-13 凌晨，Asia/Shanghai；API 快照主要采于 00:06-00:10，函数与本项目覆盖复核截至 00:26。不是对本日尚未发生更新的预测。
先读 [昨日比较](ENGINE_FORK_COMPARISON_2026-11-12.md)。主线程已完成的 0055 view curve 预计算不列为候选。

接续主线程在 2026-11-13 08:32-08:33 Asia/Shanghai 刷新了 SAINT、thetechgeekko、Knonoa
默认分支官方 commit API，三个 HEAD 仍与下表相同。本轮继续交付已验收的 0055 及独立
LibRaw 0024，A/B/C 保留为有独立验收条件的后续候选；没有把这些候选直接启用为生产行为。

## 交接结论

**三个重点仓库默认分支 HEAD 均与昨日相同，没有新增提交差异。** 本轮补充的是固定版本内尚未覆盖的 Android 适配细节。

**优先候选 A：LibRaw inset crop 到已定向输出位图的坐标适配。** 我方工具已经记录 inset crop，但尚未应用。来源有纯整数 helper、明确接线和独立 host 用例，适合先做局部验证。它是几何语义修正，会改变受影响输入的取景范围，不称为无损性能优化。

候选 B 是兼容 RGB16 转 float 与工作尺寸面积缩放融合；候选 C 是 unpack 前后按真实存储几何准入。B/C 为后续有条件的小步候选，不要求本轮同时实施。窗口 dispatch、IIR、共享 LUT、descriptor/pipeline cache、view curve 等昨日已有研究或本项目已有实现，不重复立项。

## 最新提交：官方 API 核实

已使用 web.run 打开四个官方 API URL 并进行四个 GitHub 定向搜索，均返回空结果；随后直接请求 GitHub 官方公开 REST API：GET /repos/{owner}/{repo}、/branches?per_page=100、/commits?per_page=8，再读取 raw.githubusercontent.com 下的固定完整 SHA 源码。下列时间均为 commit API 的 committer date，所列默认 HEAD 的 author date 与其相同。

| 项目、默认分支 | 最新完整 SHA | UTC / 上海时间 | 相对昨日与 tip 内容 |
| --- | --- | --- | --- |
| SAINTTaiYi/SpektraLab-Android，master | b17f6d2bdcd8024bb62abf212d10819115ca2d87 | 2026-11-10 22:01:12 / 11-11 06:01:12；[API][S-api] | 相同；1.6.2m README 重写。updated_at 变为 11-12 14:36:32 UTC，不代表源码更新。 |
| thetechgeekko/Spektrafilm-android，main | e70771cf99e61481feb471d5c4ac827a488c73f6 | 2026-11-11 09:06:56 / 11-11 17:06:56；[API][T-api] | 相同；发行工作流整理，不是新增引擎优化。 |
| Knonoa/SpektraLabAndroid，main | 6d8dd5a098e83f03e6bb67b222dc55014184c08e | 2026-11-09 03:55:50 / 11-09 11:55:50；[API][K-api] | 相同；Android SDK CI 修复。 |
| hanatos/vkdt，master，补充参考 | d240f06994740f3b968117f6d941abcbf6e9023f | 2026-11-11 13:44:59 / 11-11 21:44:59；[API][V-api] | 相同；合并提交。仓库 pushed_at=11-12 16:04:50 UTC，仍不能据此宣称 master 有新提交。 |

同时核对非默认分支及其 tip 日期：thetechgeekko 的 a684b7461836eb54bd62aa4110249ae6badfba25（11-11 03:51:59 UTC）、1dd6913dd5e5a3badaab5204b9a247c0cd8b4c1c（09:03:42 UTC）、fdc3a00ab10b55e9077c143350ab0c5a425fa788（08:57:30 UTC）均早于 main；Knonoa dev@07b9ec53d5deebe07e38eea2a0c2f5da79f0b528 为 11-08 16:23:01 UTC。vkdt android@0a1f78251f27aa2bcebbf3e4378a99186e0794aa 为 **2025-11-17 16:08:06 UTC**，不是本日 Android 更新。SHA 来自官方 branches API，日期逐个经 commits API 核实。

## 固定版本的执行差异

**SAINT。** [engine-android.patch][S-patch] 包含 alloc_host_visible、分带 descriptor 对齐、全帧绝对索引及 Pipeline::run_film_banded；run_film_for 通过 SPK_BAND_ROWS 选择该 film executor。窗口与映射读回昨日已研究，不能据此证明全管线按带高限制驻留内存。tiling.md 有历史状态与追加记录，应以实际 patch 分配/调用为准。[S-patch][S-tiling]

nativeRenderBegin 按请求调用 spk_reprint；RAW JNI 声明 RawDecodeCache 并在 mutex 下调用 decode，但本轮所读公开 engine patch 未见这个类的完整实现，不能只凭注释把“保留 unpack 后反复 WB 处理”作为可直接移植的完整候选。色相校正和显示 S 曲线不算等价执行优化。[S-jni][S-raw]

**thetechgeekko 的缓存。** DecodedSourceCache 的 beginRequest/publish/acquire 使用 generation ticket 与 lease，SingleFlight.run 合并同一 decode 请求；withOwnedContext 处理协程切换的取消交接。测试覆盖迟到结果、失效及 lease 构造 OOM。GradeCache 保留 pristine 结果、读者使用 scratch；RenderPayloadCache 缓存 float RGB 并检查合同版本和字节数。这些是具体资源所有权参考，不能据此推断我方现有 publication 有缺陷，也不能把缓存命中直接当作峰值下降。[T-cache][T-cache-test][T-grade][T-payload]

**纹理执行边界。** LutGpuPreview 的 ProxySnapshot.capture 与 PendingGpuSubmission 把 proxy/LUT/gain 作为同一代发布，onSurfaceCreated 可恢复上传；但 uploadProxy/uploadLut 实际使用 RGB16F，fragment shader 在查表前 clamp(lin,0,1)，文件明确 LUT 省略空间效果且默认关闭。它改变精度、支持域和模型范围，不适合作为 signed/HDR 全光谱管线的无损替换。[T-texture]

**工作尺寸反馈。** DraftEdgeCalibrator.record 根据实际 draft 耗时调整尺寸，用三帧中位数检验降档是否换来收益；无明显加速就回升并设下限，迟到的旧尺寸样本被忽略。值得学习这种反馈策略，不直接复制 512/640/896/1024 等初始档位或把硬件分档当成内存保证；本轮不另立实施项。[T-preview][T-preview-test]

**Knonoa 的 Android 接线。** SpektraDroid 的 CMake 实际编入独立 spektra_engine.cpp、filters/cam16/overscan、JNI 和 RAW decoder，没有编入 engine/src/gpu/vulkan_gpu.cpp。因此 APK 路径是独立 CPU 引擎，不能因仓库含 Vulkan 后端就称其已用于 Android。film_side_key、负片缓存及 41³ Lut3::sample 属于该实现；密度坐标夹紧/三线性 LUT 昨日已研究，不重复推荐。[K-cmake][K-engine]

android_image_decoder::decode_linear 的 ProPhoto→线性 sRGB 矩阵没有先做 gamut clamp；display_rgba 的裁剪属于显示边界，应区分。新增可借鉴细节是 RGB16 输出转换时直接面积缩放，见 B。[K-image][K-resample]

**vkdt 的资源规划。** alloc_outputs 记录 nid_last_ref，alloc_alias_memory 模拟生命周期/别名分配，再按 arena heap.vmsize 实际分配，alloc_bind_memory 完成绑定；dt_vkalloc/dt_vkfree 处理对齐和空闲区。动态 texture array 明确不参与后续 alias，source/sink staging 和 feedback 双缓冲也有额外生命周期。这可补充我方资源预估方法，但图接口和屏障迁移面较大，本轮不列第四个候选，也不重做 filmsim 颗粒比较。[V-graph][V-alloc]

## A：RAW 有效区坐标适配，优先

**我方未覆盖证据。** [RAW 输入审计](ENGINE_RAW_INPUT_AUDIT_2026-11-12.md) 第 30 行明确 inset crop 只记录、不新应用；tools/native/raw_color_audit.cpp 仅输出 inset_crop_0/1。固定基础 decode_raw_compatible 将 dcraw_make_mem_image 的完整位图转为 float；本轮检索全部引擎 patches 与 app/src/main/cpp，未见生产 raw_inset_crops 映射/应用。已有用户裁剪和片边几何不等于传感器有效区处理，0055 不涉及此段。[X-raw]

**具体函数与来源。** thetechgeekko 固定 e70771cf99e61481feb471d5c4ac827a488c73f6：

- [raw_inset_crop.h::insetCropForBitmap，L51][T-inset]：有效矩形由 raw 坐标减 margin，按输出大小 inward rounding，再按 flip 位反射/转置。
- [raw_decoder.cpp::insetGeometryOf，L2572][T-decode]：unpack 后保存有效 user flip、Fuji 标记和 raw_inset_crops[0]；finishDecode 在 L2815 映射到处理位图并按 cropX/cropY 拷贝。
- 原始功能提交为 [4db30b12da2d4b30f9f6d729dde66dfe55ecd973][T-inset-origin]，2026-11-07 20:22:19 UTC，官方 API 已核实。

**触发条件。** dcraw_process/make_mem_image 的可见位图仍含传感器屏蔽边带，LibRaw 同时报告了更小、合法的有效区。来源以 Sony A1 II 为例；本轮没有复验真实相机文件。缺少、全帧、越界、过小或 Fuji 45° inset 不应触发裁剪。[T-inset]

**局部实施。** 首版仅 integer_compatible16：在 unpack 后保留 inset，在独立 RGB16 位图转 float 时使用一个显式 crop 结果，保持原位图 stride，并正确处理 LibRaw flip、half-size 与 pixel-aspect。不能再应用一次编辑器旋转。无有效 inset 的输入维持当前输出；float LinearRaw、headroom 和标准显色统计另验。受影响照片的尺寸/测光可能改变，应作为几何语义修正，不能称为全图像素不变。

**最小验收。**

1. 纯整数函数覆盖 flip 0..7、非零 margin、奇数起点、half-size、两个轴不等比例，以及无报告/全帧/越界/过小/Fuji 条件。
2. 像素编码坐标的合成 RGB16 位图独立反投影，核对矩形及每个保留像素；来源 [test_raw_inset_crop.cpp::main，L50][T-inset-test] 有具体用例且失败非零退出。
3. 接线覆盖源/输出尺寸、方向、工作尺寸及测光顺序；无 inset 的冻结输入逐字节不变，受影响输入等于全位图显式矩形切取的参考。

**本轮已有局部核验。** 在内存中按来源整数公式转写 JS，对可见宽 {9,12,17}、高 {7,10,13}、flip 0..7、缩放除数 {(1,1),(2,2),(2,1),(1,2)} 共 **288 组几何、21,344 个像素足迹**，与独立的输出像素→传感器足迹枚举比较，**0 差异**。oracle 在 flip 反投影后检查 col*W >= x0*scaledW、(col+1)*W <= x1*scaledW 及行条件，未复用被测矩形作为预期。这只核验坐标公式，不是本项目 C++ 或真实相机验证。

## B：RGB16 转 float 与工作尺寸面积缩放融合

**缺口/触发。** 我方 spektralab_jni.cpp::open_raw 在约 L777-781 先持有完整 decoded.rgb，再 fit/resize_area_rgb，完整 float 源与缩小目标会重叠。raw.reset 后再分配 float、resize 四行 worker cache 都已实现，不重复推荐。新切入点仅限 integer_compatible16、工作尺寸小于解码尺寸，且 no-film standard 没有要求完整未缩放统计。

**准确来源。** Knonoa 固定 6d8dd5a098e83f03e6bb67b222dc55014184c08e 的 [engine/src/io/raw_resample.hpp::raw_output_to_float，L14][K-resample]，由 [decode_raw_compatible，L319][K-raw] 在 raw.reset 后调用。其入口从已定向 RGB16 按面积权重直接生成较小 float 输出。

**实施边界。** 借鉴“转换即采样”，保留我方 fit 的宽高、resize.cpp::area_taps 的权重及四行 cache。不能直接复制源算法声称等价：来源按 coverage/scale 生成 float 权重，我方先转 float coverage 再按 sum 归一，舍入路径不同。应保留每个 code 先按 float(double(code)/65535.0) 转换，再沿当前 float 两遍累加顺序。也不能换成 thetechgeekko finishDecode 的整数 step 抽点。[K-resample][T-decode]

**验收。** 冻结“完整转换→当前 resize”作为数值基线，覆盖 1px/奇数/非整数比例/面积 cap/方向/half-size/16-bit 边界/取消，packed float 要求逐字节一致，失败不发布半结果。[RAW lifecycle](RAW_LIFECYCLE_2026-11-10.md) 的 no-film standard 仍需完整源统计，首版排除；float LinearRaw/HDR/signed 合同不改。

24 MP 完整 float RGB 为 288,000,000 字节；此候选可去掉这块中间存储，但 RGB16 位图、RAW store、元数据和输出仍占内存，不能把这当作整个进程 RSS 的保证降幅。分别测解码和缩放峰值；没有实测收益就不推进。

## C：unpack 前后按真实存储几何准入

**缺口。** 本项目 RawHalfSizeBudget/0003 选择 Bayer binning，JNI fit 和 ExportMemory.kt 估算工作面积；不等于 unpack 的完整 RAW store、margin、pixel-aspect 及实际存储类型准入。float LinearRaw 的已有严格 limits 也不能外推给整数兼容路径。本轮未见下面来源函数对应的双次检查。

**准确来源/触发。** thetechgeekko 固定 e70771cf99e61481feb471d5c4ac827a488c73f6 的 [raw_decode_budget.h::qualifiedLargeBayerHalfGeometry，L113][T-budget]，及 [raw_decoder.cpp::declaredDecodeDimensionsExceedLimit / decodeGeometryExceedsLimit / selectRawStoreLimit，L1489/L1530/L1542][T-decode]。若 max(raw_width,width+left_margin)、aspect 拉伸或 unpack 最终元数据扩大分配，仅看缩小工作源会低估；largeBayerHalfAllowed 还核对 unpack 保存的 sizes/iparams 和真实 raw/color/float plane，不能只凭 filters>1000 放宽。

**局部实施/验收。** 首步仅做溢出安全的 geometry/存储估算 helper，以及 unpack 前、dcraw_process 前两处复核。Bayer 单平面与 LinearRaw 3/4 平面分开估算，half-size 仍计完整 RAW store。用合成 metadata 验证 margin 放大、极端 aspect、奇数 half padding、假 Bayer/float 多平面、乘法溢出、拒绝后 out 不变；来源 [test_raw_decoder_resource_limits.cpp][T-budget-test] 有实质用例。已获准输入数值应不变，新增拒绝条件单独记录。

来源 admittedDecodePixelLimit 有 12 MiPixels 下限，RAM 档位和 28 B/px 来自其项目经验，不直接抄作我方预算。这个 helper 也不是总进程预算；后续只在实际接入必要域时扩展，不重复昨日统一账本大项。[T-budget]

## 许可证

| 来源 | 实际声明与适用边界 |
| --- | --- |
| SAINT | [LICENSE][S-license] 为 GPLv3 文本，固定 README 明确代码 GPL-3.0-or-later；[LICENSE-SPEKTRAFILM.txt][S-data-license] 对 profile/LUT 及直接衍生物为 CC BY-SA 4.0。执行层与材料数据分开处理。 |
| thetechgeekko | [LICENSE][T-license] 及候选源码标记 GPLv3；[NOTICE][T-notice] 另要求保留 Akshay Sharma/Android 项目及 Andrea Volpato 的可见 attribution。LibRaw 选择 LGPL-2.1-only。其旧资产的 GPL 声明不外推成当前 spektrafilm 数据的统一许可；A/C 不需要复制资产或更换 LibRaw。 |
| Knonoa | GitHub 自动检测 license=null，但实际 [license/LICENSE][K-license] 是 GPLv3；[NOTICE][K-notice] 声明 engine/application GPL-3.0-or-later，[材料许可][K-data-license] 是 CC BY-SA 4.0。helper 无单独宽松标头，B 按仓库代码归属/GPL 路线处理。 |
| vkdt | [readme licence 段][V-readme] 默认 BSD-2-Clause，另标文件有 GPLv3 例外；所查 alloc.c/graph-run-nodes-allocate.h 未另标 GPL。若改编 allocator，保留 [Johannes Hanika 的 BSD notice][V-license]；不把 filmsim 数据和全部依赖一并当 BSD。 |

本轮只新增本研究文档，未改生产代码/CI/版本/README，未读取私有图片、认证文件或云包装脚本。没有运行同类 APK/真实手机 GPU；A 仅公式已局部核验，B/C 尚未在本项目实现或验收。改编候选源码时保留来源、作者及修改说明，不复制无关 profile/LUT/ICC。

## 固定来源

[S-api]: https://api.github.com/repos/SAINTTaiYi/SpektraLab-Android/commits/b17f6d2bdcd8024bb62abf212d10819115ca2d87
[T-api]: https://api.github.com/repos/thetechgeekko/Spektrafilm-android/commits/e70771cf99e61481feb471d5c4ac827a488c73f6
[K-api]: https://api.github.com/repos/Knonoa/SpektraLabAndroid/commits/6d8dd5a098e83f03e6bb67b222dc55014184c08e
[V-api]: https://api.github.com/repos/hanatos/vkdt/commits/d240f06994740f3b968117f6d941abcbf6e9023f
[S-patch]: https://github.com/SAINTTaiYi/SpektraLab-Android/blob/b17f6d2bdcd8024bb62abf212d10819115ca2d87/patches/engine-android.patch
[S-tiling]: https://github.com/SAINTTaiYi/SpektraLab-Android/blob/b17f6d2bdcd8024bb62abf212d10819115ca2d87/docs/tiling.md
[S-jni]: https://github.com/SAINTTaiYi/SpektraLab-Android/blob/b17f6d2bdcd8024bb62abf212d10819115ca2d87/app/src/main/cpp/spektralab_jni.cpp#L739
[S-raw]: https://github.com/SAINTTaiYi/SpektraLab-Android/blob/b17f6d2bdcd8024bb62abf212d10819115ca2d87/app/src/main/cpp/spektralab_raw_jni.cpp#L74
[S-license]: https://github.com/SAINTTaiYi/SpektraLab-Android/blob/b17f6d2bdcd8024bb62abf212d10819115ca2d87/LICENSE
[S-data-license]: https://github.com/SAINTTaiYi/SpektraLab-Android/blob/b17f6d2bdcd8024bb62abf212d10819115ca2d87/LICENSE-SPEKTRAFILM.txt
[T-cache]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/app/src/main/java/com/spectrafilm/app/DecodedSourceCache.kt
[T-cache-test]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/app/src/test/java/com/spectrafilm/app/DecodedSourceCacheTest.kt
[T-grade]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/app/src/main/java/com/spectrafilm/app/GradeCache.kt
[T-payload]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/app/src/main/java/com/spectrafilm/app/RenderPayloadCache.kt
[T-texture]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/app/src/main/java/com/spectrafilm/app/LutGpuPreview.kt
[T-preview]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/app/src/main/java/com/spectrafilm/app/AdaptivePreview.kt#L145
[T-preview-test]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/app/src/test/java/com/spectrafilm/app/AdaptivePreviewTest.kt
[T-inset]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/lib/libraw/src/main/cpp/raw_inset_crop.h#L51
[T-decode]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/lib/libraw/src/main/cpp/raw_decoder.cpp
[T-inset-origin]: https://github.com/thetechgeekko/Spektrafilm-android/commit/4db30b12da2d4b30f9f6d729dde66dfe55ecd973
[T-inset-test]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/lib/libraw/src/test/host/test_raw_inset_crop.cpp#L50
[T-budget]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/lib/libraw/src/main/cpp/raw_decode_budget.h
[T-budget-test]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/lib/libraw/src/test/host/test_raw_decoder_resource_limits.cpp
[T-license]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/LICENSE
[T-notice]: https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/NOTICE.md
[K-cmake]: https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/SpektraDroid/app/src/main/cpp/CMakeLists.txt#L42
[K-engine]: https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/SpektraDroid/app/src/main/cpp/spektra_engine.cpp
[K-image]: https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/SpektraDroid/app/src/main/cpp/android_image_decoder.cpp#L45
[K-raw]: https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/engine/src/io/raw_decoder.cpp#L317
[K-resample]: https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/engine/src/io/raw_resample.hpp#L14
[K-license]: https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/license/LICENSE
[K-notice]: https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/license/NOTICE.md
[K-data-license]: https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/license/SPEKTRAFILM_LICENSE.txt
[V-graph]: https://github.com/hanatos/vkdt/blob/d240f06994740f3b968117f6d941abcbf6e9023f/src/pipe/graph-run-nodes-allocate.h
[V-alloc]: https://github.com/hanatos/vkdt/blob/d240f06994740f3b968117f6d941abcbf6e9023f/src/pipe/alloc.c#L117
[V-readme]: https://github.com/hanatos/vkdt/blob/d240f06994740f3b968117f6d941abcbf6e9023f/readme.md#L98
[V-license]: https://github.com/hanatos/vkdt/blob/d240f06994740f3b968117f6d941abcbf6e9023f/LICENCE.bsd2
[X-raw]: https://github.com/JamesQiu2005/SpektraLab/blob/a46627d250545a394ca21522f8a28b0fb89e4c42/engine/src/io/raw_decoder.cpp#L375
