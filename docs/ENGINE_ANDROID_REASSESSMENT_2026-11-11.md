# 引擎与安卓适配重新解构，2026-11-11

共创：GPT（OpenAI）。基于 Claude 等后续开发已合入的 Android `72d1afd`，本轮只改源码与验证工具，不打包、不发布、不改 `0.2.00-alpha-2` / versionCode 122。私人照片、派生图像和私人样本统计不进入仓库。

## 结论与本轮范围

当前 Android 已有真正的上游光谱胶片引擎，并非只套颜色 LUT。剩余适配的核心是保留整条物理成像链的数值合同，同时控制源图、GPU 中间结果、读回、缓存与编码的共同峰值。单纯放慢、拆 dispatch、降低预览尺寸，都不能独自解除完整分辨率的内存和单 SSBO 范围限制。

本轮落地四组问题：TIFF 的 ICC/文件显色/JNI 接口，真实胶片性能测试与取消，外存分块原型的容量与边界合同，以及新增上游独立 GPU 数值参考。没有把尚未接入正式管线的原型写成手机全分辨率渲染已经完成。

证据和源码哈希：[本轮验证目录](../tools/native/validation/engine-android-20261111/)。新增脚本的依赖与使用方法见 [native 验证入口](../tools/native/README.md)。

## 本轮修复

| 问题 | 原因及修改 | 已验证的范围 |
| --- | --- | --- |
| ProPhoto TIFF 无效色彩配置 | 旧 ICC 实际 422 字节却宣称 1024，tag 的位置、尺寸和部分类型错误；色彩矩阵也没有准确描述引擎输出。重建合法的 540 字节 ICC v4.3 matrix/TRC，使用引擎 D50 色度列和 ROMM 线性暗部＋1.8 幂曲线 | 旧版在两台主机分别被结构校验和独立 Java CMM 拒绝；新版可解析，包含原色、灰阶、暗部转折的独立 XYZ 参考通过 |
| TIFF 错用预览显色，且污染后续预览 | 导出重写 Session 的 output transform，并以 `exporting=false` 取表。改为每次导出独立获取 file intent，保留 Session 预览变换；无 ProPhoto 工作源时只允许原有 sRGB，拒绝伪标记其他色域 | 真实引擎、十二组 film/nofilm × 目标色域/look 的完整 TIFF 文件对照；HDR 输入；导出前后 Session intent 不变；非法目标拒绝。胶片组关闭随机颗粒等效果以固定比较 |
| TIFF 两个 native 重载共用短 JNI 符号 | 四参数与五参数 `external` 都可能绑定同一个短符号，实参布局不同。保留四参数旧入口，五参数 Kotlin wrapper 调用独立 `exportTiffFdV1` | 除 C++ 调用外，真实 JVM 编译生产 `NativeEngine.kt`，实际解析并调用生产 JNI，三个色域及旧四参数入口通过；FD 仍由调用者关闭，旧 sRGB 文件与新版相同 |
| 性能测试没有真正切入胶片 | 用不存在的 `film` key，且初始 `x_no_film=true` 没有清除。改成 `film_stock` 并明确设置胶片路径；空胶片目录报错，索引安全取模 | 生产参数合同测试；完整 release JVM 测试通过。首次预览仍是无胶片基线，切换与其后导出才是胶片负载 |
| 性能测试取消等不到 native 返回 | 只在 suspend 调用返回后 catch 取消。增加独立取消 watcher，取消开始时直接发 native token；关闭前 join watcher，防止释放后再访问句柄 | 用真实阻塞线程和 latch 验证取消先于 close，正常完成不误发取消；没有声称能抢占已经提交的 GPU 工作 |
| 性能测试内存判断和并发窗口失真 | `memoryClass` 已是 MB，旧代码乘 1024，且 Java heap 上限并非 native/GPU 预算。改为未知预算 0；完整测试生命周期使用已有 transient session lease，避免与候选解码/导出重叠 | Kotlin 全量测试；这是生命周期串行和消除错误判断，不是建立了系统可用内存的硬保证 |
| 分块预算不足仍返回一行 | 0039/0040 的选择器在一个 RGB 行或 halo 都放不下时仍返回 1，原测试又跳过了 rows=1 的容量断言。0041 改为明确拒绝，测试补上该洞 | 两云 89 项边界合同，旧版失败、新版通过；既有 GPU 分块逐字节回归重新运行 |
| 分块原型允许错误边界 | 允许不足 halo、IIR 当作局部滤波、无效几何；大行号相加可溢出；线程创建异常可能析构 joinable thread。0041 增加检查、宽整数计算和已有 worker 的汇合；外存检查 file offset、非空行存储及预留重试 | 主机 ASan/UBSan、合法 GPU 路径比字节；IIR sigma>=3 明确拒绝，尚未实现跨块递归状态 |

ICC/TIFF 修复是本轮新功能回归，不能据此认定之前用户 RAW/JPEG 发灰的根因已经重新定位。RAW 默认线性显示、胶片物理参数和已存编辑效果没有被本轮改成自动显色。

## 两台云主机实际承担的工作

| 主机 | 实际执行 | 结果与限制 |
| --- | --- | --- |
| AOIDC，GCC 11.4，llvmpipe LLVM 15.0.7 | 生产 Kotlin ICC、生产 JNI TIFF 和独立参考、sanitizer、当前引擎 GPU 回归、完整 release JVM、Android NDK ARM64 源码检查；额外构建 PIC 引擎/LibRaw/image IO 静态库，让真实 JVM 加载 JNI | 18 suites / 133 tests，0 failure/error/skipped；十二组 TIFF 文件比较；真实 JVM 新旧接口通过；ARM64 语法编译通过。没有运行 Android Bitmap 或手机 GPU |
| IBX，GCC 14.2，llvmpipe LLVM 19.1.7，JDK 21 | 独立 Java CMM、分块合同旧/新对照、上游差异与生态源码审阅、上游 HDR CPU 单测、DI/headroom 独立 GPU 参考 | CMM 4100 RGB 样本对照；89 项分块合同；HDR CPU 九项检查。GPU 参考链接 IBX 原有兼容引擎库，不冒充本轮全部 Android patch 的第二次完整重编 |

重点数值：

- ICC：AOIDC 独立 XYZ 最大绝对误差 `2.1072656249954136e-5`；IBX 4100 RGB 样本最大 `2.4139579690385382e-5`。这验证 profile 数学与可解析性，不等于所有手机查看器支持已经实测。
- 0039/0040 加 0041 后，point chain 901 场景 / 102,764,160 float32，print run 2251 场景 / 75,470,400 float32，均与各自整幅参考逐字节一致。
- 严格 `maxStorageBufferRange=128 MiB` 下，4097×3073 整幅路径按预期拒绝；point chain 的 5 带与 181 带相同，含 halo 的 print run 的 8 带与 32 带相同。它们仍是独立工具中的链。
- 固定 pin 新解包，41 个 engine patch 以 `--fuzz=0` 重放成功，95 个涉及文件与本轮实际测试源码一致。LibRaw 16 个 overlay 沿用已验证版本，本轮没有重新宣称完成其全套重放。
- 新上游 DI/Cineon/Oklab float64 参考：AOIDC 十项最大误差 `2.1025299e-5`，IBX 十项 `2.27185282e-5`；headroom 六项分别 `2.65471088e-6`、`2.94519461e-6`，均在上游门限内。还检查 guard 与重复执行。

这些不是一次“总计多少测试”的同质计数。浮点误差门限、逐字节比较、JVM 控制流和 ARM 编译分别证明不同事情。Sanitizer 覆盖测试及适配 translation unit，预先构建的引擎/codec archive 未整体插桩；该 TIFF sanitizer 运行关闭 LeakSanitizer，不证明全依赖无泄漏。

## 按真实执行链看安卓瓶颈

| 阶段 | 当前合同与瓶颈 | 深度适配方向 |
| --- | --- | --- |
| RAW 解码与校准 | LibRaw、DNG 选定主 profile、FM/CM/ASN/WhiteXY 转换，再入线性工作源。严格子集的 float LinearRaw 已在前一轮接入 JNI；其余 float Bayer/压缩/custom WB 仍有限制 | 选定 IFD 原始元数据归档，解码 scratch 纳入预算；保留 float Bayer 的 demosaic 与矩阵路径，禁止靠改全图最大值隐式归一化。实际相机 profile 与合成数学合同分别验收 |
| SHOOT/负片 | 曝光、RGB→光谱、胶片曲线、耦合、halo/IIR、grain/boost/overscan 共用整幅 image | Pointwise 可先带化；FIR 要 run 半径和；IIR 必须多遍扫描传递状态；boost 等统计先扫帧再渲染。不能给每块独立曝光或独立种子 |
| PRINT/输出变换 | 光谱打印、XYZ→RGB、CAM16、unsharp、CCTF；已有两个可比字节的外存原型，但生产 Pipeline 没调用 | 完成外存 plane 的覆盖追踪、seal 和失败原子性，再替换生产整幅中间 plane；遇到不支持阶段必须有明确能力检查与诊断 |
| CPU 读回/Layer2/缓存 | no-film 的 float 保留精度并支持行读回，但预览 cache 仍可整图 12 B/px；局部蒙版、几何和 LUT 有既定算序 | 接流式 consumer 时保留全帧坐标与量化点；用 cache 生命周期换峰值，而不是随意提前裁剪/改色域；非线性 grade 与几何交换顺序必须独立证明 |
| 导出 | JPEG/TIFF 已有行转换；部分 JPEG 使用外存 spool；优化 Huffman JPEG 编码仍保留整图系数，纸框/HEIF/普通路径各不同 | 扩展统一消费者接口，显式记账最终文件与临时盘；保持 P3/sRGB/ProPhoto 像素、ICC、文件 intent 一致，不以文件 MB 作为分辨率指标 |
| 调度与内存 | lease 已覆盖候选、导出和本轮 benchmark；旧会话、普通预览、小缩略仍不是完整总量预算 | 建立 source/cache/decoder/host staging/GPU live+pool/codec/reserve 的阶段账本；用 token 准入，不再拿 Java heap class 或单 buffer 范围冒充整个进程预算 |

RGB float32 一幅需要 `12 × W × H` 字节；4097×3073 是 151,080,972 B，超过 128 MiB。因此单 SSBO 即使把 dispatch 切得很小，descriptor range 仍不合法。RGBA float32 为 16 B/px，容量设计以实际通道数为准。

外存 plane 的 GPU 预算目前只覆盖选择器约定的 3/5 个带缓冲，**不包括**每 worker 的 CPU src/dst、constant table、空闲 GPU pool 和内核页缓存。完整预算、取消、未写满禁止发布，在生产接入前都必须做，不能直接打开实验开关就称为整链有界。

## 上游这次到底新增了什么

检索时间 2026-11-11，固定源码用于可复现对照：

- Android 当前固定基础：`a46627d250545a394ca21522f8a28b0fb89e4c42` 加本仓库补丁。Windows_Linux 检索 HEAD `223205fddbbc01c583977497d9f27c79a49ca8d0`，相对基础 41 commits、约 70 文件。Windows_Linux 与 main 不能当作同一条可直接 fast-forward 的发布线。
- Windows_Linux 新增的独立 DI/headroom 测试，本轮已以固定 SHA 下载校验并用于实际 GPU；不复制其数学到生产。device-loss 提前拒绝已由本仓库 0025 吸收，Sony ARW6 已由 LibRaw 0019 吸收，不重复宣称新增。
- Fuji X-E5 fallback 与 Canon R6 Mark III 新矩阵/CanonColorDataSubVer 66 值得跟进。当前 Android 没有这两项；应保留 DNG/通道/型号准确匹配及 Canon prefix 顺序，先有真实文件再回移植，不能整份覆盖 `colordata`/decoder 抹掉已有 DNG overlay。
- main 检索 HEAD `1952c63537ec576d24422f1065b12495631346a5`，2.3.2，2026-11-10 11:49:21 UTC（北京时间 19:49:21）。其 2.3.x 新成像链主要是 Metal，不能因版本大就直接换掉 Android Vulkan。
- main 2.3.2 的 Intel 启动自检修复不应机械复制：本仓库 Vulkan 早已有 double product 残差参考和实测 split-product fallback，覆盖的是另一条启动路径；仍需真实 Adreno/Mali 对照。

### main 的 HDR 是真正的成像算法更新

`hdr_curves` 用胶片/相纸 medium 的 128 点探测，求中灰 gamma、约 0.8 gamma 的 knee，沿曝光域构造肩部和高光 headroom；256 点 `log2 Y` gain 表覆盖约 [-12,0]。grain-aware gain 用 log 域平滑、斜率限制和局部 detail 限制抑制高亮边缘被暗邻居拉回。

`scan_hdr` 是密度域扫描：按染料层改变密度，三通道 Newton 求解校准白色，再把肩部/颗粒关系映回原密度域。它不是给最终 RGB 多加一个曝光或饱和度滑条。

IBX 已编译并执行上游 `hdr_curve_unit` 九项 CPU 检查，验证 knee、极小 gamma 拒绝和 H 表端点。**这没有移植 HDR 管线。** Vulkan 的 `windows_unported.hpp` 仍显式拒绝相关能力。安卓正式吸收至少需要：

1. 先固定 CPU 数学参考、非法参数和单调性合同，再转译对应 Vulkan kernel；保持 density 与 linear RGB 的域边界，不把两个方案混成一个后期曲线。
2. 让参数、缓存 key、阶段依赖和旧编辑版本都认识 HDR；保留 SDR 及旧编辑回放。
3. 工作链保留 float 高光，显示 headroom、导出 OETF/primaries/metadata、消费者支持一起落地；仅生成大于 1 的像素不会自动成为正确的手机 HDR 文件。
4. 加入全幅/块化、颗粒、白点、跨 GPU 误差和实际显示对照，再决定默认值。当前仍是研究候选，不提前开放 UI。

## 别人怎么做，哪些值得吸收

| 项目与固定源码 | 实际阅读到的实现 | 采用与不采用的边界 |
| --- | --- | --- |
| darktable `c96cf1d0de8fff468e9eddee28621e91f83bdf1a`，`src/develop/tiling.c` | 先扣 resident input/output 和 overhead，再用模块 factor/maxbuf/overlap/alignment 规划一维或二维 tile；CPU/OpenCL 分账 | 吸收模块账本与 halo/对齐合同。其 soft-limit/fallback 启发式不能直接当作本项目 strict budget 的证明 |
| RawTherapee `64c2d90a3248c7ad41842d9162368858b61aac6a`，`rtengine/dcrop.cc` | crop origin、skip、请求边界和 tile 规划随局部处理传播，全局坐标显式保留 | 吸收 ROI/坐标接口；不能据一个 crop 实现就宣称其整个输出过程内存有界 |
| Knonoa/SpektraLabAndroid `6d8dd5a098e83f03e6bb67b222dc55014184c08e` | shared_ptr 句柄表和 generation token 管生命周期；独立 CPU 光谱/LUT 模型；LibRaw→线性 ProPhoto→线性 sRGB；JNI 仍分配整幅 RGBA8 再复制 Bitmap | 生命周期思想可借鉴。它有独立近似和 RNG，不是保持当前桌面胶片字节的直接替代；也没有从根上解决整图 Bitmap 峰值 |

本轮还查了上游 fork/API 第一页和已有生态资料，不称为穷尽所有 fork。外部做法先落入本项目的数值和资源合同，再决定吸收，避免把“另一个软件能打开”当作相同成像模型。

## 下一阶段可执行路线

| 优先级 | 具体交付 | 通过条件 |
| --- | --- | --- |
| P0 | 为 external plane 加覆盖追踪/seal、取消和失败后不发布；为每 worker staging、GPU 常量/池、codec 建统一账本 | 缺带、重复/重叠策略、磁盘失败、取消、worker 创建失败均有注入；准入前拒绝超限；合法字节不变 |
| P0 | 将已证明的默认 print point/FIR run 接入实际 Pipeline，作为能力受限路径；不支持配置明确回退或拒绝 | 同一真实胶片参数下整幅/多块高/乱序输出逐阶段比字节；确保没有另一个整幅 plane 或 idle pool 抵消收益；记录整进程与逻辑 GPU 峰值 |
| P1 | 推进 SHOOT pointwise、全局统计、IIR 跨块状态、颗粒全帧坐标，再接 TIFF/JPEG consumer | 同种子、物理像幅、扫描方向、边界和统计归并顺序保持；每类独立验收，不以大 halo 近似 IIR |
| P1 | 完善 float Bayer/压缩 DNG 与相机 profile、用户实际 RAW/UltraRAW 对照 | 同一像素不因远处高光改变刻度，白点/负值/HDR 保留；将线性默认显示与可选相机风格分开；Lightroom/相机 JPEG 色彩管理后比较 |
| P2 | 有版本隔离地移植 main HDR、评估新机型和融合核 | CPU oracle、Vulkan 浮点误差门禁、真实文件/驱动证据、旧编辑回放与导出格式一起完成 |

目前不能声明已解决：完整生产链的严格内存上限、完整 float Bayer、所有 DNG profile、手机 GPU/热状态/最终外观、main 的移动 HDR。这些不是给照片加饱和度或修改 JPEG 体积就能补齐的问题。本轮没有 APK，无法用云端 llvmpipe 结果代替手机执行。

## 主要资料

- [Windows_Linux 固定对照](https://github.com/JamesQiu2005/SpektraLab/compare/a46627d250545a394ca21522f8a28b0fb89e4c42...223205fddbbc01c583977497d9f27c79a49ca8d0)
- [main HDR 曲线源码](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/src/core/hdr_curves.cpp)；同一 pin 的 `scan_hdr.cpp`、`hdr_curve_unit.cpp` 和 `windows_unported.hpp`
- [darktable tiling](https://github.com/darktable-org/darktable/blob/c96cf1d0de8fff468e9eddee28621e91f83bdf1a/src/develop/tiling.c)
- [RawTherapee detail crop](https://github.com/RawTherapee/RawTherapee/blob/64c2d90a3248c7ad41842d9162368858b61aac6a/rtengine/dcrop.cc)
- [Android 衍生实现](https://github.com/Knonoa/SpektraLabAndroid/tree/6d8dd5a098e83f03e6bb67b222dc55014184c08e)
- [JNI 命名与解析](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/design.html#resolving-native-method-names)
- [ICC.1:2022 规范](https://www.color.org/specification/ICC.1-2022-05.pdf)、[ROMM RGB 资料](https://www.color.org/chardata/rgb/ROMMRGB.pdf)
- [Vulkan limits](https://docs.vulkan.org/spec/latest/chapters/limits.html)
