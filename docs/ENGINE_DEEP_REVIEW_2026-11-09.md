# 引擎深度适配复核，2026-11-09

共创：GPT（OpenAI）。状态：源码审查与验证中，用户已允许推送确认通过的源码。本轮不生成 APK、打 tag 或发布。
相对公开基线 `6aff78e9d1b7ac0e137526b36781271561ef95f8`（0.1.19-beta）。

## 本轮确认并修复的问题

| 问题 | 实际可达路径和修复 | 验证范围 |
| --- | --- | --- |
| 场景曲线漏加 dispatch base | 0029 新 Vulkan 核原先未读取 0002 分批后端传入的 push constant。后续批次重复处理前段，尾部可能未写。现在索引加 `spk_pc.base` | 新测试配旧核失败；换新核通过。63 组输入，517 像素，cap=0/1、独立/原位缓冲共 252 种执行，400 像素子条带及 51 个尾哨兵；整图/条带逐字节比较。两台 host 复证 |
| 曲线 Double→Float 横坐标合并 | 导入合法的 `.5` 与 `nextUp(.5)` 控制点后，JNI float 数组会把两者变成同一横坐标；native 拒绝整组曲线。仅此类行改传 `-256` 标记和最终 256 点采样，直接使用 Double 控制点求值；普通行保持旧 wire 字节 | 32 种行组合、40,960 个表值逐字节、32 个实际蒙版尾偏移/像素和 35 个坏行拒绝；严格/默认浮点模式及 ASan/UBSan。新增 5 项 JVM 测试。JSON 保留原 Double 点，不把标记写入持久化 |
| 局部蒙版提前裁切可恢复通道 | `local::Tone` 在零曝光也截断负通道，brightness 对大于 2 的值截断。改成与全局一致的条件裁切/原幂公式；未量化路径沿用原嵌套曝光公式。修复覆盖实际 Layer2→Local parser→蒙版应用 | 旧实现失败；新实现 6 个 signed/HDR 案例与独立 double 参考一致。旧非负范围 36,873 组 RGB 逐字节相同。两个编译器 strict/default 通过 |
| “全尺寸”不能保证 RAW 原分辨率 | FULL 只选择渲染档位，普通导出仍按 `workingEdge` 和 `maxPixels` 重开；高质量导出取消长边限制，但仍受 GPU、内存、画布及失败重试降档限制 | 菜单/队列改称“工作尺寸”和“高质量导出（受设备上限限制）”；JPEG/HEIF 成功记录显示实际编码宽高及文件大小。没有提高像素上限或靠增加压缩体积伪装质量提升 |
| 导出失败后的重试可能没有缩小 | 旧逻辑只把设备面积 cap 乘 0.6；24 MP 图配 42 MP cap，重试仍允许 25.2 MP，首个 6144 长边也不限制 6000×4000。现在按已知输出面积和当前 cap 的有效估计取 60%，正 cap 不会退成无限制，也避免 Long 乘法溢出 | 新增 7 项 JVM，覆盖 12/24 MP、已生效 cap、无 cap、连续重试、未知面积、SHARE 估计及数值边界；全套 58 项通过。未在真机制造 OOM/device-loss |

CurveWire 的 `-256` 是 Kotlin/native 内部参数协议扩展，不改变公开引擎 ABI；蒙版和灰尘通过同一个 row writer 计算起点。
旧控制点行和采样行混合传输，普通编辑继续使用原 native double 曲线系数准备。
局部蒙版修复会正确改变原先被提前裁切的图像；不能把这类数学修正称为所有像素保持不变。
恢复测试是满覆盖单线性蒙版和指定曝光/亮度网格，不能外推所有 WB、对比、高光/阴影及多蒙版混合组合。

## 无胶片精度与输出

0030 将无胶片输出保留为 encoded ProPhoto RGB float，完成 Android Layer2 后才量化；胶片/DI 的原整数路线保留。
扩大后的对照包含 15 组 look、sRGB/P3、预览/导出和 8/16 位输出，共 120 个组合、780 行 TIFF 与 60 次缓存重打包。
覆盖阴影/高光极值、黑白场同时作用、混合曝光/对比/亮度/饱和、色轮、五行曲线、正负亮度和白平衡。
旧 HDR 和 signed 回归的最大误差分别从 29,544/2,231 个 16 位码降至 3/1。
逐项结果及编译器差异见 [机器记录](validation/alpha1-2026-11-09.json)。这属于有界误差验证，不能称为任意编辑逐位等价。

几何测试只隔离 identity grade 下的裁切、旋转、翻转和缩放。桌面可先调色再重采样，Android 当前先几何再 CPU 调色；
非线性调色不能一般性交换顺序。用户 LUT 的域裁切及其位于 WB/曝光之前的产品顺序仍保留。

## JPEG 几 MB 的判断依据

RAW 和导出的 JPEG 存储内容、编码和位深不同，二者 MB 数不构成画质或分辨率的等价指标。
本次用户确认 19beta 导出格式为 JPEG，但尚无该成片像素尺寸，不能判定本人这一张实际缩小了多少。

源码中普通 JPEG 用质量 95，极致 JPEG 使用 libjpeg-turbo 4:4:4/98。
后续本轮优化已让无纸框、有效 ICC 的极致 JPEG 改走外存行转换，失败进入队列错误/重试，不再为编码回退分配整图 Bitmap；
纸框和缺 ICC 的兼容路径仍保留旧 Bitmap 处理。详见 [实施路线与两云验收](ENGINE_OPTIMIZATION_ROADMAP_2026-11-09.md)。
在 `DeviceProfile` 的默认 4:3 估算下，128 MiB 单缓冲预算对应约 8.39 MP，推荐长边为 3072；
这是代码的预算政策，不是本人小米 13 Ultra 驱动的已读取实测值。普通 4:3 工作图可能为 3072×2304，不能称原始 50 MP。
队列 `fitToMemory`、film-edge 画布预算、系统中断和 device-loss 重试还会进一步降低工作面积。
完成记录现在显示编码器实际接收的最终宽高，包含用户纸框；纸框增加的像素不能视作底图细节恢复。
正常完成后该描述随队列 message 持久化；若文件发布后、DONE 写入前进程退出，现有 settle 恢复仅保留文件名，不回填尺寸描述，文件本身不受影响。

公开小米 13 Ultra 样片补充实测使用生产 RAW 解码、从 JNI 源码抽取的 `fit()`、无胶片浮点渲染、输出转换和生产 4:4:4 JPEG 编码器（质量 98）：

| 样片 | RAW 字节 | 感光元件尺寸 | 实际解码尺寸 | 长边 3072 的 JPEG 尺寸 / 字节 | 长边不限、面积 cap 8,388,608 的 JPEG 尺寸 / 字节 |
| --- | --- | --- | --- | --- | --- |
| `IMG_20230420_152059.dng` | 23,050,360 | 8192×6144 | 3072×4096，实际请求 half-size | 2304×3072 / 5,283,404 | 2508×3344 / 6,053,358 |
| `IMG_20230420_115647.dng` | 8,447,104 | 4096×3072 | 3072×4096，未请求 half-size | 2304×3072 / 3,909,585 | 2508×3344 / 4,478,584 |

另以独立 SOF 解析器读取四张实际 JPEG 的尺寸、8 位精度及 4:4:4 采样，再由 Java ImageIO 完整解码，均与 working/render 输出尺寸一致。
因此这里既有工作尺寸缩小，也有 RAW→JPEG 编码造成的体积差异；不能只用 MB 判断。
这不是用户本人样片，也不等于 Android 普通 Bitmap 质量 95、胶片、几何调整、ICC/EXIF 或 OOM 重试的端到端实测。
`edge=0` 仍有面积上限，不能称原始全分辨率。原始尺寸、SHA、SOF、编码字节和 JVM XML 汇总见
[补充机器记录](validation/alpha1-2026-11-09/raw-jpeg-followup.json)，运行日志见同目录 `run.log`、`export-retry-jvm.log`。
重试的 `outPixels` 只是估计；SHARE 是分享输出估计，并不覆盖更大的 working source 驻留。
本轮已另加 `workingPixels` 用于内存准入与峰值学习；旧任务/未知尺寸根据强制执行的 cap 与长边保守估算，RSS 探测可用且无限制时先安装有限 cap；探测失败仍保留原任务限制。
这修复队列预测，不约束 LibRaw 全尺寸 scratch、编辑器 candidate 或整条 GPU 管线，也不保证每次未知图重试都实际缩小。

真正的全尺寸输出必须解决下面的驻留与 SSBO 问题，单把 `maxPixels` 提高或去掉保护会把失败推迟到 GPU 分配/驱动阶段。

## 尚未解决的结构问题

| 优先级 | 确定的缺口 | 最小实施方向与验收 |
| --- | --- | --- |
| 高 | candidate 保留旧图时仍无完整字节准入；编辑器启动时计算的 cap 被重用 | 第三批已串行化新增候选与导出生命周期，并将队列拟合/峰值采样置于取得 lease 后；仍需分配前计算 source、scratch、新旧 cache 的增量。旧会话保留，不能把一次只放行一个新增事务当作单事务内存保证 |
| 高 | noFilm `last_float` 为 12 B/px，旧 RGBA16 为 8 B/px；缓存替换成功前新旧结果并存 | 按输入、工作图、输出、film/noFilm、RAW scratch 分账。相同尺寸双结果从 16 增至 24 B/px，额外 8 B/px 瞬时载荷不能遗漏。LibRaw 先解码再 resize，输出像素 cap 不能约束全部解码 scratch |
| 高 | GPU/codec 在途阶段仍无法抢占 | 0031/0033 与 Android token 已覆盖 float/整数 noFilm、DI view、读回安全边界、legacy look 行轮询及 TIFF/JPEG 输出；worker 汇合后退出，旧成功缓存事务保持。malloc 活动期与 JNI 直接 pack 仅源码/编译检查，手机运行取消仍待验收 |
| 高 | 完整 source、negative 和 output 仍驻留；0002 拆 dispatch 与 0019 FIR 行带不解除整图 SSBO 限制 | 增加有容量上限的外存/分块 plane 和 producer/consumer 调度，逐阶段记录读写依赖、全局坐标和数值边界。按下面的执行模型验证后再允许原始大图 |
| 中 | 几何/调色顺序差异 | 为 noFilm 明确统一参考顺序；用非线性曲线、局部蒙版和负值/HDR 的 resize/crop 组合对照，不能只依赖 identity geometry 测试 |
| 中 | 完整 DNG profile、float RAW 与默认曝光策略仍不完整 | 0032/LibRaw 0021 已支持 WhiteXY-only 主 profile 三通道，公开 Pixel 8 Pro 已成功；完整 Camera Profile IFD/长签名、小数/空间黑电平、float 早期量化及 BaselineExposure 政策仍待解决。详见 WhiteXY 数值合同，不以提升饱和度替代校准 |
| 中 | 上游预烘焙 Cmax、minilab、halation 变更各有语义门槛 | Cmax 需 ARM64/Bionic 对照或明确固定标准表的效果版本；minilab 需 density 域 LUT、完整 Vulkan 与材料资源；halation 需旧编辑回放与视觉验收。不能将这些当作透明性能补丁整包覆盖 |

内存数字必须区分物理压力和平台记账。[MemoryInfo.availMem](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo#availMem)
是系统可用量，不是应用可分配额度；`VmHWM` 只能作为诊断峰值。
Android 官方 [memory overview](https://developer.android.com/games/optimize/memory-overview) 对 Android 17 Memory Limiter 使用 `RssAnon + VmSwap`，
并说明进程状态会改变限额，前台服务本身不等于前台可见应用。应保留 RSS 诊断、增加平台对应指标并随生命周期重做准入；
不能把当前 RSS 启发式写成 OS 硬限，也不能把游戏专属的 Play 指标直接套给本应用。当前 target/compile 36 不提供新增 37 API。

## “用时间换手机硬件”的可实施边界

方向成立，但应按数学依赖拆，不能任意把照片图层独立渲染再合并。

| 阶段类型 | 合适的执行方式 | 必须保持的条件 |
| --- | --- | --- |
| 点运算：矩阵、曲线、density、输出转换 | 带全局坐标的连续行带；处理后落盘或交给下一阶段 | 保留原存储精度、算序和量化点；块边界不重设坐标/胶片尺寸 |
| 有限支撑 FIR/重采样 | 请求块加真实 halo，输出只写有效中心 | 不对每块分别镜像边界，不改变 tap 顺序和两遍中间 float 存储；仅画布外采用原边界规则 |
| IIR/递归模糊 | 沿整行/列的递归状态跨块传递；必要时转置或外存多遍 | 保持原首尾条件、扫描方向和更新次序。每块重启状态会造接缝；当前 IIR 边界改进研究不能静默替换原效果 |
| 全局测光/统计 | 先扫输入取得同一组统计，再执行有界局部阶段 | 原 reduction 顺序改变会改变浮点末位；需记录等价级别或固定归并树 |
| 随机颗粒、蒙版、灰尘 | 固定种子、像素/物理坐标和项目索引 | 不能每个块重新播种、重新归一化或用局部裁切尺寸决定粒径 |
| JPEG/TIFF 输出 | 接编码器 scanline/strip，避免最终整图 bitmap | TIFF 已有行路径；本轮无纸框极致 JPEG 已按 32 行转换至匿名外存，再释放渲染结果并编码。JPEG optimized Huffman 系数和上游 plane 仍整图驻留 |

完整实施需先做固定预算的单链调度和内存高水位实测，再逐个替换仍驻留的中间 plane；
以全尺寸 reference 对每阶段有效区、整张输出字节和多个块宽/高交叉验证。若更换成像模型，则另列效果对照，不伪称调度等价。
外存 I/O 和多遍扫描会变慢，但能降低活跃内存；它不能替代缺少的 RAW 元数据或增加已经丢掉的图像精度。

0028 融合默认关闭仍合理：host 上消除中间 store/load 不改结果，不代表手机编译器不会跨阶段收缩 FMA。
真实 Adreno/Mali 的 off/on 对照、shader 精度、长时间热状态和 device-loss 仍是启用门槛。

## 证据与限制

本轮使用 AOIDC 和 InstaBox 两台云主机，分别运行生产 archive 与独立 GCC 14.2 验证。
16 个 0028-0030 涉及的最终引擎文件按 LF 归一化哈希一致；这证明源码一致，不证明两个驱动输出相互逐字节一致。
严格/默认模式指 harness/adapter 编译策略，不代表重编了两套 engine archive 或 SPIR-V。
全部 30 补丁在固定源上 GNU `--fuzz=0` 重放，91 个文件与 AOIDC 实际引擎源码相同。

测试入口在 [native README](../tools/native/README.md)，结果和证据清单在
[alpha1 源码记录](ALPHA1_RELEASE_PREPARATION.md) 与 [机器记录](validation/alpha1-2026-11-09.json)。
最新三项优化和 66 项 JVM、NDK、JPEG/CRX 验证另记在 [优化记录](validation/optimization-wave1-2026-11-09.json)，不覆盖此前 51/58 项测试的历史快照。
后续 WhiteXY 与取消的实现、98 次合成解码、真实 RAW 对照和 Android 源码编译见
[第二批记录](validation/optimization-wave2-2026-11-09.json)；该次 JVM 任务为 `UP-TO-DATE`。
第三批整数/DI 取消、spool 磁盘预留、候选/导出生命周期及 float DNG 实验见
[第三批记录](validation/optimization-wave3-2026-11-09.json)。各条目分别列出运行、编译和仅源码复核的范围。
此前 RAW 与早期边界失败保留在 [RAW_COLOR_REGRESSION.md](RAW_COLOR_REGRESSION.md)、
[RENDER_BOUNDARY_REGRESSION.md](RENDER_BOUNDARY_REGRESSION.md)，不得把历史失败直接改成当时已通过。

目前没有用户本人小米 13 Ultra 专业 RAW/UltraRAW 原片、相关导出像素数据或 Android 真机 GPU。
Linux llvmpipe、JVM 与 NDK 源码编译各自证明不同层面，不能据此宣称手机全面修复、跨 GPU 字节一致或完整 Lightroom 显色等价。
