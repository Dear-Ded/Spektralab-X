# 引擎优化实施路线，2026-11-09

共创：GPT（OpenAI）。依据 [生态调研](UPSTREAM_ECOSYSTEM_REVIEW_2026-11-09.md) 与
[生产路径复核](ENGINE_DEEP_REVIEW_2026-11-09.md)。用户已允许推送确认通过的源码；继续暂停打包和发布，不增加版本号。

## 按依赖推进

| 阶段 | 实际问题与实施 | 验收标准 | 当前状态 |
| --- | --- | --- | --- |
| 1. 解码维护 | 保留 0.22.2 与 Android overlay，选择性回移植 CRX signed qParam 索引修复；分别研究非有限 metadata 与 Panasonic 移位，避免仅照抄标题 | 真实 decoder 边界的旧版失败/新版通过、sanitizer、合法 RAW 输出对照、补丁重放及 NDK 编译。不能将局部索引修复说成修完所有 decoder 边界 | CRX 四处最小修复与本轮验证完成；其他维护项待评估 |
| 2. 工作源预算 | 将 working 面积与 SHARE 输出面积分开；未知 RAW 尺寸也必须经过准入，旧任务兼容保守估计；峰值学习不混入不确定面积 | 真实尺寸/限制的独立拟合参考、旧任务缺字段回退、未知和连续降档、JVM与 Android 源码编译。不是完整物理内存保证 | 已实现；8 项专项测试及全量 66 项 JVM 通过 |
| 3. 有界输出 | 无纸框极致 JPEG 先按 32 行转换并写匿名 RGBA8 外存文件，释放整图渲染结果，再 scanline 编码；避免额外整图 Bitmap，保留全帧坐标、原量化和编码参数 | full-pack 与多种行带高度的 RGBA8 及完整 JPEG 字节一致；sRGB/P3、float/RGBA16、局部蒙版/纹理；失败时清理未完成文件 | 已实现并通过两云验证；仅输出转换有界，编码系数、源图和 GPU 中间结果仍驻留 |
| 4. RAW 数值合同 | 建立 float DNG、小数 BlackLevel、非均匀多通道重复黑电平的合法合成 fixture；记录矩阵、白平衡、WhiteLevel、原始数域；补齐 AsShotWhiteXY 与 BaselineExposure 的参考合同。再决定真正 float demosaic/转换入口或明确限制 | 独立 DNG 数学参考；不同最大值不暗中改变同一像素刻度；负值/HDR/邻近浮点样本；真实 Pixel/GCam/Samsung 与 Lightroom对照 | WhiteXY 已落地；2026-11-10 严格子集 float LinearRaw 已接入正式 JNI，恢复原始 ASN 并保留浮点。其他浮点格式/custom WB 仍走有诊断的整数兼容；完整 float Bayer、配置文件与手机外观验收未完成 |
| 5. 统一阶段准入与取消 | EngineHost 分配前登记 source、decode scratch、candidate、新旧 float cache、输出/纸框；共享阶段 lease；取消贯穿 view、readback、行转换及编码 | 并发旧编辑/新候选/导出下的预算、失败原子性、取消清理；分开记录 CPU RSS、平台记账和 GPU 逻辑容量。已提交 GPU 工作完成后才释放 | float/整数 view、DI、legacy look 取消已补齐；新增候选与导出生命周期 lease，避免两者重叠。尚非完整字节预算，旧会话/缓存、普通预览和小型缩略 native 不受此 lease 限制 |
| 6. 完整外存分块 | 为 source/negative/print 引入有界 plane，先点运算，再 FIR halo，后 IIR 跨块递归状态与全局统计；逐链替换全帧 SSBO | 多个块宽/高对同一全尺寸参考逐阶段比字节；全局 RNG/物理胶片坐标、边界、算序、量化点保持一致；真实 Adreno/Mali 驱动与热状态验证 | 待实施；dispatch 拆批不等于解除 SSBO 容量限制 |

## 算法与外部方法的吸收条件

正常路径的执行优化保留 float32 算序、原模型及量化边界；改变错误输入处理或数学结果的修复单独记账。
不以强加饱和度、全局曝光补偿或加大 JPEG 文件伪装 RAW 还原。

维护版与新机型快照分开推进。Nikon HE 的额外约 8 B/px 解码空间、构造器 ABI、swab 与 Panasonic 内部变化
都需先进入阶段预算；不能只改相机列表或版本 pin。Canon R6 III 与 Fuji X-E5 先补真实文件。

上游 DI/headroom 的 float64 reference 与 guard 方法可加入测试；外部只打印误差、缺 tap 跳过的脚本需补成真正门禁。
CAM16 baked 表需 ARM64/Bionic 对照；minilab、halation、颗粒/RNG 和换 demosaic 模型属于成像变化，需独立效果版本和旧编辑回放策略。
融合核仍保持默认关闭，等待真实手机 FMA/驱动对照。

## 本轮落地与验收

证据入口：[机器记录](validation/optimization-wave1-2026-11-09.json)、
[JPEG 两云日志](../tools/native/validation/jpeg-rows-20261109/)、
[CRX 维护验证](../tools/native/validation/libraw-maintenance-20261109/result.json)。

- CRX 旧版四入口均能复现负索引；新补丁 464 个案例通过 ASan/UBSan。四张公开小米 DNG 与一张 Canon EOS R5 CR3，原始 Bayer 与 half RGB16 共十对输出逐字节相同。文件回归复用生产 archive、只替换同 flags 重编的 CRX 单元；Android 构建则重新应用全部 LibRaw 补丁并编译。
- 工作源与 SHARE 的输出面积分开记账；RSS 探测可用时，未知且无限制的旧任务先安装有限 cap。普通编辑器导出从原始尺寸按本次限制估算，覆盖开启再关闭胶片边框后编辑源较小、队列重开较大的情况。`working_peak_bpp` 单独学习，未知面积不参与；RSS 探测失败仍保留原任务限制，解码器全尺寸 scratch 等仍未纳入统一 lease。
- AOIDC GCC 11 的 strict 和 ASan/UBSan、IBX GCC 14 的 strict/default，各通过 48 个场景、384 次完整 JPEG 文件比较。包含冻结旧 writer、RGBA16、带符号/HDR float、sRGB/P3、局部蒙版/纹理、带 padding 的 stride、四种行高及错误清理。Sanitizer 只覆盖本轮测试和适配单元，现有引擎/codec archive 未整体插桩，LeakSanitizer 未启用。
- 4097×3073、生产 export transform、独立进程下，转换行缓冲由 50,360,324 B 降为 524,416 B。AOIDC 输出阶段 VmHWM：RGBA16 227,996→179,336 KiB，float 276,016→228,268 KiB；IBX 两种编译模式下降 47.2–47.9 MiB。大图同类输出的文件大小和完整文件 FNV-1a 一致；小尺寸回归直接比较全部文件字节。
- 阶段峰值在 transform 准备后、源图分配前重置 `/proc/self/clear_refs=5` 测量，另保留重置前启动峰值。启动仍主导进程总峰值，不能据此宣称整个进程峰值下降，更不能换算手机 RSS 或提速。
- Android NDK r27.2 / Clang 18 ARM64 原生编译通过，新 JNI 导出符号存在；66 项 JVM 测试零失败。没有生成新 APK，也未做手机运行验收。

采用匿名暂存是必要的生命周期安排：`optimize_coding=TRUE` 的 4:4:4 JPEG 会保留整图系数。
直接让整图渲染结果与编码系数重叠，可能使峰值上升。当前先完成转换、释放结果，再编码；
代价是约 4 B/px 的临时磁盘空间，另需容纳最终 JPEG。第三批已为 spool 精确预留空间；
这不预留最终 JPEG、EXIF 或相册空间，不支持预留时的可用量检查也只是快照。
HEIF、纸框和普通 JPEG 仍走原路径。新路径在转换/读取行带及编码前后检查取消；
已提交的 GPU 工作与 `jpeg_finish_compress` 内部第二遍仍不能按行抢占。第一批时取消验收限源码复核与异常清理，第二批补充真实 view 和并行 worker 聚焦测试，见下文。

## 证据边界

先用两台云主机做独立编译器、生产 archive 与数值参考验证，再做 Android NDK/JVM 源码检查。
Linux CPU Vulkan 不代替手机 GPU；公开小米样片不代替用户本人的专业 RAW/UltraRAW。
每个阶段完成后保存实际日志、源码范围和未覆盖项，不提前把路线表标为全部完成。

## 后续第二批源码优化

证据入口：[第二批机器记录](validation/optimization-wave2-2026-11-09.json)、
[WhiteXY 数学合同](DNG_WHITE_XY_2026-11-09.md)、
[取消回归](../tools/native/validation/output-cancellation-20261109/result.json)。

- 新增引擎 0032 与 LibRaw 0021，保留 WhiteXY 和校准签名，源白平衡与用户选择分开；已知 xy 的矩阵插值使用 Robertson，FM/noFM 使用相同主 profile。旧 ASN 不改算序。
- 25 组合法格式/明确拒绝的合成 DNG、98 次 candidate 解码通过；独立 Python 参考检查白点、校准端点及 WB，默认合成灰阶每像素 RGB 差小于 0.0003；37 次输出矩阵比较的最大绝对误差为 8.9464e-8，固定容差 1e-5。6 对合成旧合同输出、6 张真实 ASN RAW 的 12 对默认/custom 兼容输出逐字节相同。
- 公开 Pixel 8 Pro WhiteXY 样片的兼容/headroom 默认打开均由失败变为成功；两种模式 custom 也成功，源 xy/WB provenance 保持。不能代替用户小米 RAW 或 Lightroom 外观验收。
- 引擎 0031 及 Android token 把取消贯穿 GPU 完成边界、float 读回、行转换和编码；CPU worker 仅轮询，全部 join 后抛出。旧 `last/last_float` 在新打包成功前保持，取消不以破坏恢复能力来换取峰值降低。
- IBX GCC 14 strict/default 各通过 8 个真实 noFilm 取消边界及恢复对照，前 7 个边界未分配 CPU float 读回；两种 4,199,425 像素 CPU 并行转换取消后稳定。TIFF 清理/分类模式与未取消文件一致性在 native harness 验证；JNI 和旧缓存事务仅经源码复核，未做 Android 运行验收。既有 48 场景/384 次 JPEG 文件比较、120 组 noFilm 回归通过。
- AOIDC 完整 LibRaw/图像库重编与 Android NDK ARM64 编译通过。JVM 任务本轮为 `UP-TO-DATE`，既有 66 项报告不作为重新执行的新测试。
- 固定 pin 全量重放通过：32 个引擎补丁、15 个 LibRaw overlay，GNU `--fuzz=0`；92 个引擎文件、22 个 LibRaw 文件与实际候选哈希相同，Android 的 LibRaw 副本一致。修复了 0031 短上下文及旧 LibRaw 0016 的过时上下文，已测试源码保持相同。

在途 GPU dispatch/readback、zlib 当前 strip、JPEG codec 内部阶段仍不能被抢占。
第二批尚未覆盖的旧整数 look-only 分支，第三批已补逐行轮询与 join 后退出；没有新的手机取消或峰值 RSS 测量结论。
当前 WhiteXY 仅覆盖已选主 profile 三通道路径，校准签名最多 255 内容字节，
Camera Profile IFD/ExtraCameraProfiles 选择尚未加入；float RAW 早期量化与完整外存 plane 均未解决。

## 后续第三批源码优化

证据入口：[第三批机器记录](validation/optimization-wave3-2026-11-09.json)、
[浮点 DNG 调查](DNG_FLOAT_PRECISION_2026-11-09.md)。

- 0033 在整数 view 的最终打包、GPU 完成、CPU 分配及读回边界检查取消。AOIDC 实际 float noFilm、整数 noFilm、DI 分别覆盖 8/10/9 个边界，恢复输出一致；legacy look 逐像素参考、活动期取消及 worker 汇合通过。JNI 直接 pack、实际读回复制中取消仍无 Android 运行证据。
- 匿名 RGBA8 spool 用 fallocate 预留精确 extent，EINTR 重试，仅对不支持预留的错误退回有界块数比较。预扩展不绕过 incomplete seal；IBX strict/default、完整小 fixture 的 ASan/UBSan/LSan 与既有 JPEG 文件对照通过。
- 共享生命周期 lease 在 native dispatcher 外可取消等待，完整候选事务、旧句柄退役、导出重试与 native finally 都先于解锁。fitToMemory、RSS baseline/reset、峰值学习和最终诊断在已取得 lease 的窗口内执行，避免等待候选期间的峰值污染。独立测试不构成 Android 并发内存验收。
- 6 个 float32 DNG 实证提前整数化使邻接值合并、负值丢失，远处条带最大值从 2 改 4 时相同区域的生产 compatible 输出减半。禁用转换后 dcraw_process 不接受 float 存储；不能仅清除一个选项。LinearRaw 隔离原型继续核验原始 ASN、per-plane 黑白和矩阵合同，不接入默认路径。

上述第三批描述的是当时状态。2026-11-10 已把有界 LinearRaw 精度子集接入正式入口，并保留原始小数黑白/ASN 诊断；详见 [输入精度集成](INPUT_PRECISION_INTEGRATION_2026-11-10.md)。同批保留 decoder 已提供的合规 F16 SDR，并将宽色域面积判断改为白点适配后的边界判断。

下一阶段仍需完整阶段字节预算、通用原始选定 IFD 元数据归档、float Bayer 与压缩格式、完整外存 plane，以及用户 RAW 和手机 GPU 对照。
