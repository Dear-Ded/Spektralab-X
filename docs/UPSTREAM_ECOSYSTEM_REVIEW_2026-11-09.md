# 上游与衍生生态复核，2026-11-09

共创：**GPT（OpenAI）**。日期采用北京时间。本报告只记录在线查询、固定源码与本地生产路径审查；没有编译外部移植、运行外部测试、修改产品源码或升级依赖。外部作者的设备测试单独标记，不能当成本项目测试通过。

## 1. 发现范围与审计材料

机器材料目录：`D:/AGI/GPT/other/spektralab-backend-research/ecosystem-20261109/`。
`ecosystem-fetch.ps1`、`ecosystem-sources.ps1`、`ecosystem-targets.ps1`、`ecosystem-inventory.ps1` 位于其父目录，记录查询方法。
随仓库保存的精简证据位于 [validation/upstream-ecosystem-2026-11-09/](validation/upstream-ecosystem-2026-11-09/)：`network-inventory.csv`、`branches.json`、`priority-targets.json` 和带公开来源/时间/范围限制的 `provenance.json`。完整源码与 PR 正文仅保存在机器研究目录。

本轮 GitHub REST 查询得到：

- JamesQiu2005/SpektraLab 的 `branches?per_page=100` 返回 22 分支；全部名称与 SHA 保存于 `branches.json`。
- 该库自身是 andreavolpato/spektrafilm 的 fork。SpektraLab 直接 fork 分页为 100 + 34；原 Python 库直接 fork 分页为 100 + 66。
- 递归检查上述结果中 fork 数非零的节点，另外取得 `amirm11248/spektrafilm-GrainOnly`、`fengk623/SpektraLab-Windows`、`HeckerBoat/SpektraLab`。去重后 **303 个公开 fork**，与查询时根网络 `network_count=303` 一致；完整仓库名、默认分支、push 时间和许可字段见 `network-inventory.csv`。这证明此次公开网络清单覆盖，不代表审过 303 个项目的所有代码，也不包括私有、删除或脱离 fork 网络的衍生。
- 单独仓库搜索保存四份响应：`SpektraLab` 39 项、`Spektrafilm` 60 项、`SpektraLab-Android` 10 项、`JamesQiu2005 in:readme` 29 项；结果互相重叠，也含无关引用，不能相加当项目总数。搜索捕获了重命名项目如 Silverphase、ObscuraFilm、SpectralDarkroom、FilmS，以及 OFX、Rust、ComfyUI、darktable、LR/PS、web 等关联项目。
- 上游 issues/PR 的 `state=all&per_page=100` 响应各自未达到 100，保存为 `issues-1.json`、`pulls-1.json`。issue 描述属于缺陷线索，关闭状态也不等于本项目已修复。

原始 JSON 保留了 REST 返回数据；`branch-tips.json` 的部分进一步 commit 查询遇到 HTTP 限额，记录错误而没有补造日期/提交标题。分支 HEAD 清单的首次请求成功，不受后续限额影响。网页搜索工具也调用过，但本轮没有返回可引用正文，因此实际证据来自 GitHub REST、raw 固定 SHA 与已有 Git checkout。

## 2. 此次查询的分支 HEAD

| 分支 | SHA |
| --- | --- |
| Windows_Linux | `d13a46ec5497f5596ead9900ec685abaf5e0750e` |
| main | `d4b8145f6f9bdade15cda014776e99521adf0b5f` |
| windows_linux2 | `69e8a6a205ee78694904064e4159f25b92242a29` |
| main_v1 | `ee8f20b93eb60dcf4d7fcd800a087c8094aff8b6` |
| v2-ui | `4a46ecc1b6e929c05ec8ef8202b598030b6e40e4` |
| assist/port-base-1.3.4 | `0d540485a90b7b56923621c281a39abac1e54cc5` |
| assist/tauri-ui-1.3.4 | `a45b6db7be6c5149e8b5714da195ad0aabe50000` |
| assist/vulkan-engine-1.3.4 | `445e314e28b9b455d2457471fadaffd28c07270b` |
| assist/windows-1.3.4 | `7ed05806f74af50960b3ec7de370ab397b17830e` |
| ci/desktop-windows-linux-only | `f0644bc84653891924dc97a57976e3d263088d2f` |
| ci/desktop-windows-linux-only-wl | `82a9438e9a070be6029457edcbe9d6e712975f47` |
| ci/windows-asset-on-mac-release | `2f557edacb1625a78722b6b30bff539aedc2d999` |
| claude/spektralab-windows-linux-port-mi7r55 | `afd2bae633f22ec49a4d3750ffb2b7f2239f9b57` |
| codex/pr14-ci-repair-20261025 | `fcb071102d0133a28cc1ac7a395ac81c5ef23ecd` |
| codex/pr14-clean-package-20261023 | `f1a2897b1f330624b654cf4fe7dc51da59fa9fa9` |
| codex/pr17-input-codecs-20261028 | `751ca6b0d7a036431dec1cd8316fffa87fc06ecb` |
| codex/windows-ci-session-ledger-20261018 | `fc678a386f14317ad04b085a324bb412bf9cb6d5` |
| codex/windows-ci-tls-20261023 | `4710793bf1294e25fb09421109052242cdf2091a` |
| codex/windows-full-migration-20261018 | `97b9b2243217036c5ea8eef2951147b0e39f0cf9` |
| codex/windows-memory-baseline-20261018 | `b302e067fa30c3074e3cf9e73e080b5d85fd43fe` |
| fix/exposure-filmstrip-20261030 | `2ca735ad60a998e925bf346f57d80012bdb5c6bf` |
| gh-pages | `8b3147b5abb147206b9babcf0d6c6c1f501427b0` |

Windows_Linux 在本轮已从此前审查的 a4289ab 前进两次提交。固定 [compare](https://github.com/JamesQiu2005/SpektraLab/compare/a4289ab99bc09fc998e6148dc3d496f9079e5857...d13a46ec5497f5596ead9900ec685abaf5e0750e) 只有 `engine/CMakeLists.txt`、`gpu_vulkan_di_encode.cpp`、`gpu_vulkan_headroom.cpp` 三文件变化，来自 [PR94](https://github.com/JamesQiu2005/SpektraLab/pull/94)。没有产品算法改动。该 HEAD 的提交时间为 2026-11-09 03:55:32 UTC，即北京时间 11:55:32；这是查询快照，不是永久“最新”。main 仍 d4b，既有 main 专项评估继续适用。报告落地时以 `git ls-remote` 再查 main、Windows_Linux、windows_linux2，三者仍为上表 SHA；复核时间 **2026-11-09 15:19:51 北京时间（07:19:51 UTC）**。

其他分支本轮完成 HEAD 枚举、部分 tip 元数据核对，**没有完成逐分支全历史算法审计**；不能因为分支名带 CI 或 UI 就宣称 engine 全部相同。windows_linux2 尤其不能因名称推断为应升级的后继版本。

## 3. 重点衍生源码冻结

| 仓库 | 查询时默认 HEAD | 本轮读取范围与边界 |
| --- | --- | --- |
| thetechgeekko/Spektrafilm-android | `0f1ddd1142a716729e636de3f73fc91e0d5fdc5e` | 独立 C++/CPU/Vulkan port，源自 Python；最新 RAW decoder、README、tree，以及原本地 fa5135a 到该 HEAD 的 diff。不是 SpektraLab Vulkan 的直接替换 |
| SAINTTaiYi/SpektraLab-Android | `08183cd4de3d910107a730e149fda9f7e8ce6254` | README、JNI、RAW/pipeline 文件；引擎基于 assist/vulkan 445e314。README 内仍并存“已修通用 dispatch”和旧 known issue，优先看代码 |
| Shawn-SU-1/zhaoye | `fc9802af8cb98560b61df75e04c0a5ed57583d7b` | README、裁剪 engine、Vulkan 数学探针与 RAW/pipeline；基线 fd891c6。含独立片边 raster 适配 |
| Knonoa/SpektraLabAndroid | `6d8dd5a098e83f03e6bb67b222dc55014184c08e` | native CPU 重实现、RAW adapter、LibRaw pin 脚本、parity comparator、RAW 验证记录；保留的 desktop engine 目录不等于手机正在执行它 |
| NaoeRiki21/SpektraLab_Android | `597588127c4f1372a14b02a871a3c71794cd6d31` | tree、README、匹配的 RAW/pipeline；未逐核验收 |
| Godoshi9/SpektraLab-CrossPlatform | `0084ae32b6d1822ebfa116e56b292dfaac2c4f39` | 基于同一 a466 pin 的 Windows/Android 外壳；README、匹配 engine 源码，Android 2048 工作图、8-bit 输出；不是全尺寸方案 |
| Broksd/SpektraPLUS | `46fe1067a594550768aedd54330faa2b9da2daa8` | tree、README、匹配 RAW/pipeline；没有据此宣称所有同名功能数学一致 |
| JohnWick996/spektralab-cpu | `be791cb606ab21d7ee7b47220171a1540d6e12ee` | Mesa/lavapipe 启动包装与原始引擎副本；“CPU”是软件 Vulkan，不是新 f64 CPU oracle |
| JamesQiu2005/spektralab-engine | `1f6cad722d0fae4d6dc569590697b718193724fc` | 独立 Apple C ABI engine，README/tree；不附材料资源，不具备 Vulkan 替换条件 |
| JamesQiu2005/SpektraLab_library | `f236e03b4c8eea622fd7360c4d6277ada48b180c` | MIT 的目录/会话 SDK，处理图片位置、评级、相册和 Capture One 互操作；README 明确不解码、渲染或缓存。不是另一个渲染引擎 |
| Minerchu/SpektraLab-iOS | `716f7e124229d95b887a1bbc5bea35b295946790` | ImageDecoder.swift、EngineBridge.swift、README/tree；Core Image 与 LibRaw fallback、显示色彩合同；不把旧 issue71 的颜色差异直接套到此 HEAD |
| abc123-hubish/Harmony-SpektraLab | `b08f2a3aa99bc7179e7d422935a2f90413655159` | tree 只有 6 项、README 明说未上传应用工程源码；不能审其 Vulkan/RAW/16-bit 导出，也不能吸收二进制当源码补丁 |

每库 `<owner>__<repo>.json` 包含 repo metadata、固定 commit 和未截断 tree；匹配的文本源码保存在同名目录。原网络其他项目与搜索命中只做发现/分层，未逐项目执行。不存在“整个生态已全部审计”的结论。

## 4. 新确认的输入合同缺口

### 浮点 DNG 在 compatible 路线会在 float RGB 入口之前损失数域

外部 [geek RAW decoder](https://github.com/thetechgeekko/Spektrafilm-android/blob/0f1ddd1142a716729e636de3f73fc91e0d5fdc5e/lib/libraw/src/main/cpp/raw_decoder.cpp#L1705) 对 `SampleFormat=3` 单独拒绝/转交具备保真合同的路径，原因是 dcraw bitmap 会先量化。本项目独立源码复核确认：生产 JNI 当前选择 compatible；LibRaw 默认 `CONVERTFLOAT_TO_INT`，FP DNG 的转换可能按 tmax 重标到 16383、将负值归零再存 ushort，随后 compatible 输出 RGB16/65535。下游改成 float 或 noFilm float，并不能恢复这一步丢失的负值、HDR 与原浮点刻度。

这与已知“RAW compatible16 有量化”有关，但**浮点 DNG 可在标准化之前发生按图最大值重标**是本轮补充的具体输入合同边界。当前 headroom 路线有样本格式拒绝，不能把该拒绝误写成 compatible 也已防护。证据级别是源码证明转换路径；本轮没有浮点 DNG 设备样片复现。

最低验证：构造浮点 DNG，使用负值、0、0.18、1、>1、两份仅最大值不同的图，记录解码阶段原始/处理最大值和逐像素数值；分别要求显式拒绝或保留声明数域。属于输入数学/政策修复，不是透明提速。仅复制外部 fallback 到 Android display decoder 会失去线性 RAW 合同，不能作为无损修复。

### 小数与空间黑电平：避免把近似规范化说成精确支持

geek decoder 对 GCam/Pixel fractional RATIONAL BlackLevel 有专门预处理；[固定代码](https://github.com/thetechgeekko/Spektrafilm-android/blob/0f1ddd1142a716729e636de3f73fc91e0d5fdc5e/lib/libraw/src/main/cpp/raw_decoder.cpp#L1543) 采用 nearest integer。这是一种明确近似，不是恢复完整 DNG 小数黑电平模型。

本项目生产路径独立复核：LibRaw CFA/singleton 分支存在截断，linear 分支采用 +0.5 舍入；虽然保存了 `dng_fblack/fcblack`，未发现用于像素归一化的消费者。**64.06 在两种策略下都为 64，不能称作已复现的 GCam 错色。** 应选 64.49/64.51 等区别样本以及通道不等小数，检验真正差异。

Samsung LinearRaw 的 uniform 2×2×3 黑电平，本项目 LibRaw 已经按通道平均再清 repeat dimensions，因此不能根据外部有 normalization 就声称本项目不支持。nonuniform 2×2×3 在该处理下会丢失空间分布；本轮无对应设备 fixture，只列合同限制。需要 RAW IFD 准确选择、同尺寸不同黑电平 pattern 合成样本及独立 DNG 数学参考，再决定拒绝/支持，不能先改文件里的元数据掩盖差异。

## 5. LibRaw：维护版与新增机型快照必须分别看

### 0.22.3 的确存在，不能只比较 0.22.2 与开发快照

固定 [0.22.2→0.22.3 compare](https://github.com/LibRaw/LibRaw/compare/b93f6e45c194f5df9b02a43b1af9a54b4f41f33f...cf02d66eb5c19d4e46df7aee0582e47a4918509a) 为 **32 commits、19 文件**，完整 patch 保存在 `libraw-stable-compare.json`；[Changelog](https://github.com/LibRaw/LibRaw/blob/cf02d66eb5c19d4e46df7aee0582e47a4918509a/Changelog.txt#L1) 日期为 2026-10-24。

| 修复 | 对本项目判断 | 最低移植验证与分类 |
| --- | --- | --- |
| [CRX signed qParam 负索引](https://github.com/LibRaw/LibRaw/commit/4747dab46fb71d06fd1ff8122bd3b67d25c91f81)：legacy 路径 `q_step_tbl[qParam % 6]` 增加非负限制 | 本地 overlay 未见该修复；CR3 decoder 被生产编译，属于新补审项。只证实源码差异，没有损坏 CR3 运行复现/攻击结论 | 检验合法 CR3 不变，构造 qParam 负边界的 decoder 局部测试和 sanitizer；随后合法/损坏整文件。属于错误输入处理修复 |
| `pana8` 两处 shift 计数增加 `&63` | overlay 未见该修复。但后续左操作数会提升为 int，`&63` 不能一般性证明 >=32 的左移合法，必须核对 huff_coeff 实际值域 | UBSan、可达位域枚举、合法 RW2 回归；不能仅凭上游标题称全路径 UB 已消除 |
| `read_utils.cpp` 增加 float/double NaN/Inf 检查 | overlay 未见；当前 FM/WB validity 不覆盖所有 LibRaw 内部 metadata 运算 | 合法 metadata 不变；float/double 非有限 metadata 保持明确失败/确定政策。外部把无效值归零的政策也要审，不能当无条件等价 |
| [X-Trans 分离 source/destination](https://github.com/LibRaw/LibRaw/commit/b5b68d15cf9809a546f6a751866df17366fae7e5)、[copy 尺寸后续修正](https://github.com/LibRaw/LibRaw/commit/b55eef9ae5e4f3bd024c8d03349bf296940e6b0e) | 现有 0003 已明说选择串行、暂不采用额外 image。它解决调度不确定性，仍保留 tile 读到先前 tile 已改像素的结果；**这是已知取舍的残留，不计作本轮未知 bug** | 修正会改变现有图像，且增加约 8B/px image（含边界）；不能恢复多线程而不增预算。细纹理/跨 tile、镜像、线程数和峰值内存一起验收 |
| [X-Trans half 依据 CFA 找 green phase](https://github.com/LibRaw/LibRaw/commit/37ee39561d480845ae3351d3324fc27c14481080) | 新维护版修复；但当前 half_size 门限定 filters>1000，排除了 filters=9，不能当成本项目当前可触达故障 | 若将来扩展 X-Trans half，必须纳入暗角/噪声黑块/CFA phase 回归 |
| normalize_model static→stack、lossy DNG curve 初始化、wavelet OpenMP size 初始化 | **已分别由 0017、0018、0001 覆盖**；不能当新发现重复吸收 | 升级维护版时识别补丁重叠和上下文，保留已有回归 |

本项目还有独立的 bounded metadata、tile streams、LJPEG 与 ARW6 overlay；版本较新并不自动包含这些约束。没有在本轮升级 pin 或清理补丁。

官方 Changelog 对 CVE-2026-111272 有明确异议，认为该具体报告误解 LibRaw allocator。不能仅引用 CVE 名称宣称本项目有漏洞，也不能反向推导全部 decoder 已安全；上表依据实际 diff 而非该编号。

### 202611 快照提供新能力，同时带来 ABI 与内存成本

Knonoa 的 [setup_libraw.sh](https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/SpektraDroid/tools/setup_libraw.sh#L1) 固定官方 `0b87f5c907d7ee0e7641dd1b6a6c9140b9451922`、tree `fb93233a46ec9a96fb17f2dbab160f5fa27a0ab6`；[官方 Changelog](https://github.com/LibRaw/LibRaw/blob/0b87f5c907d7ee0e7641dd1b6a6c9140b9451922/Changelog.txt#L1) 日期 2026-11-04，明确是 `0.23.0-Snapshot202611`。

新增支持包括 Nikon HE/HE*、Canon R6 III/C50、Fuji X-E5/X-T30 III、Nikon Z5II/ZR、新 Sony。上游 [issue67](https://github.com/JamesQiu2005/SpektraLab/issues/67) 的 R6 III 品红、[issue93](https://github.com/JamesQiu2005/SpektraLab/issues/93) 的 X-E5 转换拒绝，与我们已有 DNG ForwardMatrix 修复不是同一层问题。固定 [canon.cpp](https://github.com/LibRaw/LibRaw/blob/0b87f5c907d7ee0e7641dd1b6a6c9140b9451922/src/metadata/canon.cpp#L1381) 确有 ColorDataSubVer=65/66 的独立偏移，而 0.22.2 没有；没有原片，不能断言只移植这几行就能修复 issue67。

官方特别列出 HE 解码 **8B/px 额外中间存储**，45MP 加压缩 RAW 约 400MB，且改了 LibRaw 构造器 ABI 校验、Panasonic multi-tile 内部字段。Linux/Android 默认 `LIBRAW_OWN_SWAB`，其说明来自系统 swab 输入输出同址错误报告。它们意味着“新机型支持”不能作为零成本相机列表更新。

Knonoa [RAW 验证记录](https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/SpektraDroid/docs/RAW_VALIDATION.md#L1) 给出 15 个真实样片、640/1024 解码、arm64 模拟器 10 测试无 skip，以及源索引/哈希；这是作者记录，未在本项目重跑。其明确不覆盖全尺寸、所有 ABI 执行、真机内存，也不含 R6 III/X-E5 定量显色。可复用 fixture **索引和方法**，不能复用“通过”状态。

决策门槛：优先按需求评估维护版遗漏补丁，再独立评估新增机型快照；无论小范围 backport 或完整快照，都要保留当前 ARW6、metadata 限额、FM/WB/half-size/失败原子性验证。HE、CR3、Fuji、Panasonic 分开建真实样片矩阵，测 unpack/demosaic 峰值，不能只测缩图后的 float 内存。

## 6. 可以吸收的方法，以及不能直接复制的结果

| 来源 | 适用性 | 类型与验证门槛 |
| --- | --- | --- |
| Windows_Linux PR94 DI encode/headroom probes | pointwise float64 参考、guard words、knee/negative/hue-wrap case 可以补本项目验证。作者报告 lavapipe DI 10 case、headroom 6 case；本轮未运行 | 测试增强，不改变图像。先确认本项目 kernel 参数/输出合同，再在真实生产 SPIR-V 运行；不能用作者 max delta 当本项目结果 |
| geek 输入精度描述与 RAW layout gate | 值得采用“发布精度必须来自实际路径”的原则，而非仅看结果容器是 float32 | 输入数学/政策修复；其 ACES→ProPhoto、CPU f64 oracle、GPU容差合同与本项目不同，整套复制会改变颜色 |
| zhaoye GPU unfused-FMA 可接受分支 | 固定 [vulkan_gpu.cpp#L277](https://github.com/Shawn-SU-1/zhaoye/blob/fc9802af8cb98560b61df75e04c0a5ed57583d7b/engine/src/gpu/vulkan_gpu.cpp#L277) 起允许 Android GPU 进入 Dekker 路线；JohnWick 的 Intel Arc 130T 包装则选择 CPU lavapipe。表明严格探针是现实兼容边界 | 算术后端选择。单个 math probe 通过不等于全 kernel parity；需 subnormal/overflow、two_prod范围、真实 Adreno/Mali/Intel 的中间结果、长时间运行。不能只放宽 gate 当“等价支持更多手机” |
| Knonoa CPU port 的 native 整链 | 可能作为另外的算法参考，但不是备用 Vulkan 实现；源代码使用独立光谱 LUT/blur/RNG 调度 | 效果/实现变化。逐阶段 oracle，材料、LUT域与分辨率、grain RNG和物理坐标必须分别比较，不能移植少量文件后声称同图 |
| Knonoa [tools/parity/compare.py](https://github.com/Knonoa/SpektraLabAndroid/blob/6d8dd5a098e83f03e6bb67b222dc55014184c08e/SpektraDroid/tools/parity/compare.py#L17) | 脚本只打印误差；缺中间 tap 直接 continue，最终没有误差阈值断言或非零退出 | **新确认的外部验证缺口**，固定源码可直接确认。不能把脚本 exit 0 说成 parity通过；如采用需强制tap/shape/finiteness/阈值及失败退出 |
| iOS Core Image decode/readback | 可以参考 UTI 恢复、识别 embedded-preview、线性 ProPhoto与输出sRGB标签的边界 | 平台专属；CIRAW 有 Apple默认显影，不是 LibRaw数学oracle。旧issue71并不能证明最新716f7e仍错误；本轮无Apple runtime |

已在其他报告覆盖的 CAM16 hoist/bake、DI 预拆、minilab、scene curve、halation 效果改变、Metal trim_pool 生命周期、IIR边界、全链SSBO分块、资源缓存所有权，本轮不重复计数；详见 [上游审查](UPSTREAM_REVIEW_2026-11-09.md)、[GPU审查](UPSTREAM_GPU_REVIEW_2026-11-09.md)、[深度审查](ENGINE_DEEP_REVIEW_2026-11-09.md)。

## 7. 许可、署名与移植边界

读取的 SpektraLab/Android/zhaoye/CPU port 文本分别保留应用/引擎 **GPL-3.0-or-later**，Andrea Volpato 的胶片/相纸配置和派生 LUT **CC BY-SA 4.0**。这些内容不是统一一种代码许可证：复制算法代码需保留原版权/代码许可；复制材料/校准数据还需保留数据出处、许可和修改标记。LibRaw 单独使用其 LGPL/CDDL 提供方式；本项目现有分发政策不能因借鉴某个移植 README 而自动改变。

`SpektraLab_library` 是上述归类之外的 **MIT** 目录/会话 SDK，其固定 README 明确将其与 GPL 应用及渲染职责分开；不能仅凭同名把 MIT 许可套到应用、引擎或材料数据上。

fixed source 与 author link 应随候选补丁记录，特别区分 Andrea 的材料/模型、Hanze Qiu 的 C++引擎/额外资料、各移植作者的 JNI/raster/CPU代码。仅借鉴测试方法不意味着可删原实现署名。Harmony 仓库没有可审应用源码；不能从其“基于GPL”自述推断已经具备可移植代码或完整许可交付。

这是对仓库声明与本次取证的记录，没有替所有分发制品做法律合规结论。README 性能数字、截图、版本名和作者自述均没有替代代码、独立样片或本项目测试。

本轮实际产物是研究记录与冻结清单；未更改上游 pin、产品算法、材料数据、版本号、发布包或远端仓库。
