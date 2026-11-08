# Android 引擎适配记录

日期：2026-11-08。基线：Spektralab-X `eada743`，上游 SpektraLab
`a46627d250545a394ca21522f8a28b0fb89e4c42` 加本仓库补丁。
本轮引擎适配共创：**GPT（OpenAI，AI 助手）**。保留全部原作者和数据署名。

本轮完成内存优化和已证实的正确性修复。完整分块、外存支持的大图执行器仍在后续工作中。
Linux 软件 Vulkan 的渲染结果、arm64 APK 编译结果、Android 真机结果分别记录，不互相替代。

## 1. 已实现

### 1.1 缩放只缓存有限行

`app/src/main/cpp/resize.cpp` 原来分配 `nw * h * 3` 个 float 作为横向缩放的整图
中间缓冲。现在最多 8 个工作线程，各保留 4 行横向结果，纵向依次消费；线程创建失败时，
尚未分配的工作在调用线程完成。工作线程启动前分配所有堆内存，分配失败在调用线程上返回。

面积重叠权重的 double 计算、float 截断、归一化、横向 RGB 累加和纵向按 tap 累加的
顺序保持不变，包括零权重。没有改成 FP16、近似采样或整数缩放。支持源/目标同一容器，
拒绝空尺寸、非打包 RGB 和放大请求。

中间像素载荷上限从 `12 * nw * h` 字节变为 `12 * nw * 4 * workers` 字节。
4097 × 3073 → 2049 × 1537 时，旧载荷 75,558,924 字节，新载荷最多 786,816 字节。
权重表、完整输入和输出仍占内存，因此这不是流式 RAW 解码或整条管线的分块执行器。

### 1.2 修复引擎缓存的生命周期和键

补丁 `0016` 把 `SetupCache::tc_lut` 的结果从 `const Vec*` 改为
`std::shared_ptr<const Vec>`。旧指针指向会被 LRU 轮换、插入和淘汰改变的 `vector`
元素；即使查表时加锁，解锁后的 GPU 上传和主机拷贝仍可能读取另一张表或已失效的对象。
现在调用方在上传、拷贝结束前保留不可变载荷的所有权。缓存仍只持有 4 项，读取者占用的
已淘汰载荷在最后一个读取者结束后释放。

补丁 `0017` 修复输入色域压缩键的截断：算法名和三个 `%.17g` 参数原来共同写入
64 字节数组。回归构造出只相差一个 double 步长、旧键完全相同的设置。现在分别追加
算法名和完整参数，默认设置的键保持相同。两项修复都不修改 LUT 数值生成算法。

### 1.3 编辑、导出、观片台传递相同 LUT

用户 LUT 是会话状态，独立于 `Look.values()` 的数组。过去导出和观片台只传递数组，
因此丢失 LUT；现在它们和编辑器一样，先为自己的会话应用 LUT，再应用调整。
观片台缓存键加入版本前缀，旧的缺 LUT 图像不会继续命中。缺失或损坏的已选 LUT 会明确
报错，不再把它默认为“无 LUT”。

`LookState` 在创建候选调整层成功后才交换状态，分配失败保留上一次有效状态。
JNI/Kotlin 的 `setLook` 改为返回 Boolean；`setLut` 在拒绝参数或分配失败时也保留
旧状态。所有调用方检查失败，错误在发生调用的线程读取 `lastError`。
这是 APK 内部 JNI 契约变更，两端必须一起构建。

### 1.4 HDR 增益图按 Android 契约重建

`gainmap.hpp` 实现 SDR 基图在完整 HDR 权重下的恢复：先对增益图归一化码值做双线性
采样，得到 `G`，然后计算：

```text
L = log2(ratioMin) * (1 - pow(G, gamma)) + log2(ratioMax) * pow(G, gamma)
HDR = (linearSDR + epsilonSdr) * exp2(L) - epsilonHdr
```

旧代码使用 `1 / gamma`，并在已经非线性变换的 log boost 上插值，两处都会改变
高光。现在采样后使用 `gamma`，逐通道检查元数据。保留 A8/RGB 增益图、实际 Bitmap
字节行跨度、像素中心映射、边缘钳位、基图去预乘及线性 ProPhoto 转换。
这项是正确性修复，HDR 输出允许并应当改变，不宣称与旧错误公式逐位相同。
输入为 SDR 基图、恢复权重为 1 的范围不变；Android 后续版本的 HDR 基图及其他增益图
色彩解释需要另行适配。

公式依据：Android 14 `android.graphics.Gainmap` 的 “Applying a gainmap manually”，
源文件位于 Android Open Source Project 的
`platform/frameworks/base/graphics/java/android/graphics/Gainmap.java`
（标签 `android-14.0.0_r1`）。

### 1.5 取消导出时及时释放资源

导出的 `try/finally` 现在覆盖原生会话的创建过程。句柄在原生调用线程内先交给
清理作用域，再从 `withContext` 返回；即使取消发生在同步解码结束与恢复协程之间，
也不会因返回值被丢弃而泄漏会话。清理仍在 `NonCancellable + ExportThread` 执行。
已解码照片在所有权交给 `openPhotoSession` 前由外层负责回收。

## 2. 数值与字节契约

| 环节 | 契约 |
|---|---|
| 输入 | 线性 ProPhoto、打包 RGB float32，每像素 12 字节；不是 `std430 vec3[]` 的 16 字节步长 |
| 核心结果 | RGBA16，每像素 8 字节；`row_stride_px` 的单位是像素 |
| TIFF 像素 | RGB16，每像素 6 字节；保留调整层之后的 16 位量化和输出变换顺序 |
| Bitmap | RGBA8，每像素 4 字节；按实际字节行跨度写入 |
| 光谱与材料 | 81 个波长、5 nm 间隔；胶片/相纸测量数据、曝光轴和积分顺序不变 |
| 用户 LUT | 编码 ProPhoto RGB、红轴最快、四面体插值；不替换为三线性或降低边长 |
| 颗粒 | 保留原随机序列和全局位置语义；不靠改变种子掩盖条带接缝 |

“保持字节”指相同编译条件、驱动、输入、参数和随机种子下比较实际输出。
不同 GPU 的 float32 舍入和随机渲染不因此自动获得跨硬件逐位一致保证。

## 3. 验证记录

`tools/native/run.sh` 可复现独立数值和缓存回归，Android CI 也执行严格及默认编译模式。
`tools/native/render_regression.py` 对保留了输入哈希、完整参数和实际像素的基线重新渲染，
比较核心输出、sRGB/P3 导出和 sRGB/P3 显示五种结果的全部字节。

| 验证 | 结果 |
|---|---|
| 两台云主机，严格 `-ffp-contract=off` 与默认 `-O2` | 每种模式各 355 组缩放逐字节相同；含 4097 × 3073 大图、HDR/负值、亚正规数和非整数边界 |
| HDR 数学 | 4100 组 double 自然对数参考比较通过；采样与 gamma 顺序测试通过 |
| 调整层失败注入 | `setLook` 保留 LUT；内存分配失败保留上次有效状态；显式清除正常 |
| 缓存 | 轮换、淘汰、4000 次并发读取、缓存析构后的持有，以及截断键回归通过 |
| 地址/未定义行为检查 | 最终补丁集合的 ASan/UBSan 回归通过：350 组缩放、4100 组 HDR 数学、调整状态和缓存检查均无报告 |
| 实际胶片渲染 | Linux 软件 Vulkan 上 10 用例、50 份输出与基线逐字节相同；含曲线、颗粒、光晕、眩光；随机效果使用固定的独立进程种子 |
| RAW 准备 | Leica Q DNG 与 Canon R5 CR3 重新解码、缩放后的 float32 输入与基线逐字节相同，哈希见下表 |
| Android | arm64 Release APK 构建通过；14 项单元测试通过，无失败或跳过 |
| 16 KB 对齐 | APK 内两份 arm64 原生库的所有 LOAD 段均为 `0x4000` 对齐；`zipalign -c -P 16 -v 4` 通过 |
| 真机 | 本轮未执行 Adreno/Mali/Immortalis 实机安装与渲染验证 |

渲染结果的逐文件 SHA-256、尺寸和逐字节比较结论保存在
[`validation/android-engine-render-2026-11-08.json`](validation/android-engine-render-2026-11-08.json)。
这是本轮改动相对既有 Android 原生链路的回归：输入分别为 1535 × 1025 和
1536 × 1023，不代表全分辨率手机导出已经验收，也不替代桌面参考的独立对照。

| RAW 准备输入 | 缩放后的打包 RGB float32 SHA-256 |
|---|---|
| Leica Q DNG，1535 × 1025 | `5cc041c134fd690ce5c5aff42674f7281b94978f99a802ee96938506359f6a6a` |
| Canon R5 CR3，1536 × 1023 | `c9660e18ccf84b7da4e1eb34e1f0bc3b5558294bfabae6299a7fd6aa4187cd5f` |

云主机构建的 APK 用于编译与布局验证，采用测试签名；没有作为正式 Release 发布。
现有单元测试也不覆盖 Android 真机上的 HDR 解码、LUT 导出或取消时序。

独立进程缩放 RSS：261,108 KiB → 187,976 KiB，约降低 28.0%，输出摘要相同。
这里的参考程序串行运行，新实现允许并行；耗时不作为旧/新产品吞吐或手机提速结论。

## 4. “拉长时间换取画质”的后续实现

这个方向可行，但需要改变数据驻留和执行方式，单纯减小 dispatch 或现有 `strip_rows`
不能让整个源帧突破单缓冲范围。此前在同一严格限制设备上，6016 × 4016 的打包 RGB
需要 289,923,072 字节，超过 `maxStorageBufferRange = 134,217,728`；
`strip_rows = 64` 仍在 `spk_sep_fir_acc` 的整图缓冲检查失败。
该事实属于前期调研基线，不是本轮重新运行的全分辨率验收。

| 工作 | 需要满足的条件 |
|---|---|
| 数据驻留重构 | 输入、负片、输出可按条带驻留或放在文件映射的中间存储；GPU 上不再强制同时保留整平面 |
| 真正的内存预算 | 根据同时存活的缓冲、行宽、halo、暂存和驱动上限推导条带；当前 `choose_strip_height` 仍未使用字节预算 |
| 点运算 | 按全局坐标分块，逐块执行同一核和同一浮点运算，不改光谱积分 |
| 有限邻域 FIR | 为连续模糊累加所需 halo，按原边界规则读取，写回中央有效区域；并行分支按最大 halo 合并 |
| 递归 IIR | 跨块传递正向、反向递归状态并保留扫描顺序；不能用有限 halo 冒充等价 |
| 全局统计 | 自动曝光、全帧测光等先做完整统计，再按固定结果渲染；缩略图统计不能替代严格模式 |
| 颗粒、光晕、眩光 | 颗粒按原层顺序及全局随机索引生成；大尺度/全帧操作独立扫掠或多遍执行，验证边界和频谱 |
| 调度与资源回收 | fence 完成后才复用或销毁缓冲；取消后保留在飞资源至完成，不能直接删逐节点 flush 来“提速” |
| 流式输出 | TIFF/JPEG 按行输出，避免最终打包又重建整帧；预览可继续用现有小图路径 |

存储探测器的超时生命周期已在第三批修复：`VK_TIMEOUT` 不能当作 GPU 已停止运行，
清理保留在飞缓冲、命令池和 fence，确认完成或设备丢失后再销毁。同步清理仍可能超过
两秒诊断期限，细节见 [GPU_PROBE_LIFETIME.md](GPU_PROBE_LIFETIME.md)。

后续验收应至少包含：同输入的整图/条带逐字节比较、IIR 接缝、颗粒空间频谱、
峰值内存与单次 GPU 提交时间、取消和设备丢失恢复，以及真实 Android GPU 的大图长时间
导出。在达到这些条件前，本项目仍不能声称完成“任意大图、桌面全部效果、全设备逐字节一致”。

## 5. 第二批：曲线、条带输出和上游算法复核

第二批从 `cdc7274` 继续，只修改源码、测试和记录，版本保持 `0.1.19-alpha`。
共创继续署名 GPT（OpenAI，AI 助手）。

`ToneCurve` 将每条曲线的差分、斜率与单调性限制计算一次，再采样 256 点；保持旧 double
表达式和 float 转换。补丁 `0018` 让 `Blur::fir_radius` 与卷积核构建器共用半径函数，
不再为查询 halo 而计算、归一化并丢弃整个核。

TIFF 导出改为 `Rgba16Rows` 按 32 行条带转换，工作线程各持有自己的转换缓存，保留全图坐标、
蒙版与灰尘几何、输出变换、16 位量化和原编码布局。JNI 不再建立完整的调整后 RGBA16 副本。
编码失败和异常路径关闭文件并清理失败文件。完整引擎结果仍驻留，因此只完成了输出端的流式化。

| 第二批验证 | 结果 |
|---|---|
| 严格 / 默认原生回归 | 两种模式各 355 组缩放、4,100 组 HDR、状态失败注入及缓存测试通过 |
| 曲线字节 | 两种模式及 ASan/UBSan 各 1,787 条曲线、1,148,285 个 double/float 采样相同 |
| 半径查询 | 两种模式及 ASan/UBSan 各 3,600 个舍入边界相同，查询在拒绝分配时成功，非正 sigma identity 正常 |
| 条带输出 | 严格、默认与 ASan/UBSan 各 162 组合 × 5 种行高；RGB16 与完整 TIFF 相同；provider 分配失败正确返回 |
| 实际胶片 | 重新链接宿主引擎和 Android 共用处理代码；10 用例、50 文件与原基线逐字节相同，RAW 准备哈希也相同 |
| Android | 含 `0018` 的 arm64 Release 原生库构建通过；GitHub 完整 APK、单元测试、16 KB ELF/ZIP 检查与截图 CI 均通过；未执行真机测试 |
| TIFF 内存 | 4097 × 3073 独立进程峰值 RSS 312,696 → 221,044 KiB，约下降 29.3%；文件 36,575,160 字节且摘要相同 |

这些新测试在同一台 x86_64 云主机、llvmpipe 软件 Vulkan 上执行。第二台云主机当前受服务冷却限制，
本批没有把第一批的跨云复证算作自己的结果。
GitHub Ubuntu 24.04 的独立 runner 另行通过严格 / 默认原生数值测试和 APK 集成构建，但没有运行胶片 GPU 回归。
完整胶片比较见 [`validation/android-engine-depth-render-2026-11-08.json`](validation/android-engine-depth-render-2026-11-08.json)，
数值与内存记录见 [`validation/android-engine-depth-2026-11-08.json`](validation/android-engine-depth-2026-11-08.json)。

源码提交 `528d4c2` 的 Android CI 为 `42464217204`（第 2 次尝试），截图 CI 为 `42464217277`。
首次构建在所有补丁应用成功后，下载固定 `libjpeg-turbo` 依赖时遇到 HTTP 504；只重跑失败任务后通过。
`VERSION=0.1.19-alpha publish=0`，没有更新版本或发布 Release。

对上游继续复核后，还发现递归模糊末端状态引起显著的左右翻转不对称，且 FIR/IIR 在 sigma=3
的切换处有核宽度跳变。已加入真实 Vulkan 诊断和独立的 Triggs/Sdika CPU 修正候选；
120 组候选相对显式延拓参考的最坏误差为 `4.42701431069e-11`，五种分段长度保持相同扫描字节。
这些会改变旧输出的算法修正尚未替换产品 shader，详细模型、原始数据和适配条件见
[`ENGINE_ALGORITHM_RESEARCH.md`](ENGINE_ALGORITHM_RESEARCH.md)。

## 6. 第三批：FIR 存储调度与实际产品预算

第三批继续只改源码、测试及证据，`VERSION` 保持 `0.1.19-alpha`，共创署名 GPT（OpenAI）。

### 行带 FIR 与小请求内存池

补丁 `0019` 对大于 16 MiB 的普通 Vulkan FIR 平面启用行带中间存储。
输入和输出仍完整驻留，纵向写入至多 8 MiB（或至少一行）的 scratch；横向只读取同一行，
按原全局位置写回。保留两个 float32 存储边界与运算顺序，每带完成后再复用 scratch。
已有 consume-input 的两平面复用不改，小图继续用原来的两次 dispatch。

scratch 的精确容量分类随池块保留，普通请求不能把它借作更小缓冲；相同容量可以复用。
补丁 `0020` 另对小于 64 KiB 的普通请求设 `max(2 * request, 4 KiB)` 的复用容量上限，
修复常量借到整个闲置图像平面的内存放大。大请求仍使用已有复用策略。

`tools/native/fir_rows.cpp` 用冻结的原 GPU 两遍卷积作比较，覆盖单点、单行、单列、
核半径大于尺寸、HDR 负值、不同通道半径、reflect/mirror、正负及零累加权重。
产品路径还覆盖横竖图、65537 × 33、1000003 × 2，以及 6001 × 4001。
独立内存池检查覆盖小常量、bounded 释放后的 4 MiB 普通请求、普通块提升为 bounded、
相同容量复用，避免测试被其他小块或小请求策略遮蔽。

| 条件 | 结果 |
|---|---|
| 严格 Vulkan 限额、最终测试源码 | 368 用例，74,180,082 个 float32 值逐字节相同；不放宽 `maxStorageBufferRange` |
| `--large` 扩展容量实验 | 370 用例，218,240,088 个 float32 值逐字节相同 |
| 2049 × 1025 活跃缓冲容量 | 100,811,040 → 83,993,088 字节 |
| 6001 × 4001 活跃缓冲容量 | 1,152,480,288 → 872,713,908 字节，减少 279,766,380 字节 |
| 小请求与 scratch 池容量 | 小请求上限、精确容量、释放后分类和相同容量复用均通过 |
| 实际胶片回归 | 最终引擎与 Android 共用处理代码重新链接，10 案例 × 5 输出全部逐字节相同；RAW 准备哈希也相同 |
| Android 编译与测试 | 最终补丁的 arm64 Release 原生构建通过，19 项 Release 单测及 3 项实际解码测试通过 |

以上为同一 AOIDC 云主机上的 llvmpipe 软件 Vulkan。容量指标是测试中同时活跃的 GPU buffer
逻辑容量，不是整个 App RSS、驱动真实分配峰值或手机节省比例。两种比较均保留一份独立
accumulator 输入，即使该组合没有启用累加。计时包含同步、读回、池清理且与其他构建有重叠，
不用于宣传固定提速。行带可以花更多时间来节省存储。

`--large` 依赖引擎在 CPU Vulkan 上既有的容量探测扩展；严格限额的独立运行不包含该 24 MP
案例。完整输入、输出依旧受各自 SSBO 范围限制，因此不能据此声称严格 128 MiB 的 Android
设备已能处理该尺寸。CI 的 `engine-gpu` 明确设置 `SPEKTRAFILM_VULKAN_STRICT_LIMITS=1`。

原始记录：
[大图 CSV](validation/android-engine-fir-rows-2026-11-08.csv)、
[大图日志](validation/android-engine-fir-rows-2026-11-08.txt)、
[严格限额 CSV](validation/android-engine-fir-strict-2026-11-08.csv)、
[严格限额日志](validation/android-engine-fir-strict-2026-11-08.txt)。
最终胶片逐文件摘要见
[完整比较](validation/android-engine-product-render-2026-11-08.json)，
安卓原生集成构建见 [构建日志](validation/android-engine-product-build-2026-11-08.txt)，
解码集成测试见 [解码日志](validation/android-photo-geometry-2026-11-08.txt)。

### 原照片尺寸与导出预算

`SourceLoader` 保留解码器报告的、已处理 EXIF 方向的原图尺寸；`Photo` 将其传给普通及
Ultra HDR 的 JNI open，native session 不再把缩小后的工作图误记为原图。6000 × 4000 的照片
按 3072 长边导入后，工作图是 3072 × 2048，原尺寸仍是 24 MP。极致导出按原图重开时，
内存预测、任务像素数与并发控制因此使用真实原尺寸。此更改是预算元数据，不改变引擎输入像素、
物理像素 pitch、胶片参数或色彩公式。

解码后的后备缩放同时检查长边和面积上限，移除原先只在面积超过 5% 后才缩小的分支。
`PhotoGeometryTest` 使用真正的 Android 图像解码路径（Robolectric native graphics，API 34），
三项 JPEG 测试全部通过：原尺寸保留、EXIF 90° 旋转后的两套尺寸，以及面积/长边共同限制。
这不代表 OEM HEIF、增益图或 Android 真机 JNI 已完成验收。

### 热调度和探测生命周期

按 Android Thermal 指南，热余量改为单线程异步采样，完成后至少隔 10 秒才排下一次；
并发调用最多排一个任务，失败和 executor 拒绝也限频。最近有效值保留至多 30 秒，过期返回
NaN。调用方仅用于调度阈值，NaN 不触发主动降效。诊断文案明确这是未来 10 秒预测，
1.0 为 severe 阈值。5 项限频、并发、失败与失效测试通过，完整 Release 单测共 19 项通过。

存储探测器把提交和完成分开，timeout 保留整个在飞资源图；fence 或 device-idle 完成后才释放。
提交返回 `DEVICE_LOST` 同样先走完成清理，不能把成功提交的生命周期误作成功等待。
9 种生产方法生命周期回归与 4 KiB 实际 Vulkan smoke 通过。异常 idle 无法确认完成时保留资源，
同步清理可能等待较久；没有注入真实 GPU 挂起或真机设备丢失。

热采样证据见 [thermal 日志](validation/android-thermal-sampler-2026-11-08.txt)，
探测修复与限制见 [GPU_PROBE_LIFETIME.md](GPU_PROBE_LIFETIME.md)。
外部方法研究见 [ENGINE_SCHEDULING_REFERENCES.md](ENGINE_SCHEDULING_REFERENCES.md)，
F16、ICC、HDR 与 RAW 模式的后续适配见 [ANDROID_INPUT_PRECISION.md](ANDROID_INPUT_PRECISION.md)。

递归模糊末端条件、sigma=3 的 PSF 跳变、完整管线外存执行及 Adreno/Mali/Immortalis 长时间
真机导出仍未完成；这批调度优化没有替换这些成像算法或修改材料数据。

### GitHub 独立验收

源码 `66bae00` 的三条 CI 已全部通过：Android `42467813871`（第 2 次尝试），
截图/解码 `42467813877`，GPU `42467813880`（第 3 次尝试）。前两条失败构建均停在
固定 `libjpeg-turbo` 下载的 HTTP 504；所有补丁已应用，只重跑失败任务即可通过，未更换依赖版本。

GPU runner 为 Ubuntu 24.04、llvmpipe LLVM 20.1.2，与 AOIDC 的 LLVM 15.0.7 不同。
在公布的 128 MiB SSBO 限额下，368 案例、74,180,082 个 float32 值逐字节一致；
9 项 probe 生命周期与 4 KiB 实际 smoke 也通过。这里分别比较每个设备上的旧/新调度，
没有将两个驱动的渲染输出互相比较后声称跨设备一致。

Android CI 完整 APK、Release 单测、两种原生编译模式的数值回归、两份库的 16 KB LOAD 对齐
及 APK `zipalign -P 16` 均通过。`VERSION=0.1.19-alpha publish=0`，release job 跳过。
运行号、源码提交、artifact 和原始结果摘录见
[CI 验证记录](validation/android-engine-product-ci-2026-11-08.json)。
