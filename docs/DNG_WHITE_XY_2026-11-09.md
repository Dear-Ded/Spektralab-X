# DNG AsShotWhiteXY 解码合同与验证

日期：2026-11-09。Co-created with GPT (OpenAI).

本次修复使只有合法 `AsShotWhiteXY`、没有 `AsShotNeutral` 的受支持三通道 DNG 可以使用文件记录的白点正常默认解码。实现保留真实源白点、按校准签名决定是否应用 CameraCalibration，并从已知白点构造矩阵和 camera neutral。它不增加人为饱和度，也不以自动白平衡替代缺失的源白平衡。

这是 RAW 数值合同中的一个独立切口，不能视为完整 DNG SDK、所有 RAW 精度问题或 Lightroom 视觉一致性已经完成。较早文档中 Pixel 8 Pro 默认解码失败的记录属于修复前基线，本文件记录本次实现和验证。

## 修复前问题与实现入口

LibRaw 固定基线 `b93f6e45c194f5df9b02a43b1af9a54b4f41f33f` 的 TIFF 解析器会读取 `AsShotWhiteXY`，但只用于局部 XYZ 计算，没有在 `libraw_dng_levels_t` 中保留原始 xy，也没有据此合成可供生产解码使用的 `cam_mul`。因此合法 WhiteXY-only 文件会在源白平衡校验处失败。

实现由两个生产补丁组成：

- `patches/libraw/0021-dng-as-shot-white-xy.patch`：保留源 xy、有界完整校准签名、解析标记和非法签名状态；扩展 selected raw IFD 优先、IFD0 回退的逐字段归档。
- `patches/0032-dng-as-shot-white-xy.patch`：增加 `engine/src/io/dng_white_xy.hpp`，在 `raw_camera.hpp` 增加明确的 WhiteXY-only 分支，并在 `raw_decoder` 中保留源 WB provenance。

新分支仅在有 WhiteXY 标记且没有 AsShotNeutral 标记时进入。已有 AsShotNeutral 默认和 custom 解码保留原算序。`RawMetadata::camera_whitebalance_from_xy` 与 `camera_white_xy` 记录来源；自定义 WB 不覆盖文件记录的源 WB。host 元数据探针也通过同一生产入口读取派生结果。

LibRaw 内部数据结构追加了字段，库与使用它的头文件必须同步全量重编。没有修改公开 C 解码 API。

## 数学合同

依据 Adobe SDK 的 `SetWhiteXY`、`FindXYZtoCamera` 和 ForwardMatrix 分支，源白点的处理顺序如下。`AB` 为 AnalogBalance 对角矩阵，`CC_i` 为有效 CameraCalibration，`CM_i` 为 ColorMatrix。

1. 验证源 xy 为有限值，且 `x > 0`、`y > 0`、`x + y < 1`。
2. 使用 Robertson 等温线计算源相关色温 `T`。表与项目现有 host 白平衡实现的 31 行数值一致，不使用 McCamy 近似替代。
3. 对每个有效端点先构造完整矩阵 `M_i = AB * CC_i * CM_i`，再按已知 `T` 使用现有双光源权重插值。不能把 CC 和 CM 分别插值后相乘来替代完整端点插值。
4. 令 `XYZ = [x/y, 1, (1-x-y)/y]`，计算 `n = M(T) * XYZ`，以最大分量归一化，并按 SDK 的 neutral 合同把各分量限制在 `[0.001, 1]`。源 WB 为 `1/n`，第二绿色通道复用绿色增益。
5. 再次准备矩阵时显式传入已知源 `T`，避免先丢掉 xy、再用 NeutralToXY 找根选择出不同的白点解。

有 ForwardMatrix 时，同一当前 profile 的 FM 与 CM 配套使用；从 individual camera 到 reference camera 使用 `inverse(AB * CC)`，再结合 reference neutral 和插值后的 FM 构造 camera-to-PCS。

无 ForwardMatrix 时，构造线性 Bradford 的 D50 到源白点适应，组成完整 PCS-to-camera 矩阵，以其对 PCS 白点响应的最大分量归一化，再对完整矩阵求逆。不能只求 CM 的逆并把曝光尺度隐含交给后续步骤。

自定义 WB 独立准备 selected 矩阵，源 `cam_mul` 和 xy provenance 仍来自文件。源 WhiteXY 默认路径不能可靠准备时明确失败；有效的显式 user WB 仍可使用既有可用路径。真正没有源 WB 且未指定有效 custom WB 的文件继续失败。

host 显示的 as-shot temperature/tint 是现有 host 从相机矩阵逆变换所得的估计值，不是声称文件记录了该温度，也不是额外的测色真值。

## 校准签名与 IFD 合同

Adobe DNG 1.7.1.0 规范第 47-48 页规定：

| Tag | ID | 类型 | 规范位置与缺省 |
| --- | --- | --- | --- |
| CameraCalibrationSignature | 50931 / `0xC6F3` | ASCII 或 BYTE | IFD0；缺省空字符串 |
| ProfileCalibrationSignature | 50932 / `0xC6F4` | ASCII 或 BYTE | IFD0 或 Camera Profile IFD；缺省空字符串 |
| AsShotWhiteXY | 50729 / `0xC629` | RATIONAL，2 个值 | 本次保存实际源 xy，不从 `pre_mul` 猜测 |

签名是带终止 NUL 的 UTF-8 字节串，count 包含该 NUL。只有完整签名相等时应用 CC，不等时使用 identity CC。双方缺省均为空，因此匹配；一侧非空另一侧缺省则不匹配。不能将缺失一律解释为拒用非 identity CC，也不能将截断后相同的前缀解释为匹配。

本次 overlay 每个签名保留最多 256 字节，包含终止 NUL，即最多 255 个内容字节。类型不支持、超长、缺少终止 NUL 或含嵌入 NUL 时保留 invalid 状态，新 WhiteXY 默认路径保守拒绝。超过此界限的签名可能是合法 DNG，本实现将其视为当前不支持，不能据此认定文件损坏。

字段归档沿用 LibRaw 的 selected raw IFD 优先、IFD0 回退。合成测试覆盖子 IFD 缺省回退和显式 override。此次没有实现 ExtraCameraProfiles 及完整 Camera Profile IFD 解析、选择或切换；规范允许 profile signature 位于 Camera Profile IFD，但本次不能据此声称支持该完整情形。矩阵和 FM 使用的是 LibRaw 当前已选的主 profile。

## 已完成验证

以下结果来自本轮真实构建及生产解码入口；合成文件由 Python 标准库构造，没有依赖用户提供样片。

| 验证 | 结果与范围 |
| --- | --- |
| AOIDC GCC 11 生产静态库 | `spk_libraw`、`spk_image_io` 完整重编通过，覆盖新增 LibRaw 内部布局 |
| 标准库合成 DNG | 25 组、98 次 candidate 解码通过；包括预期成功和预期拒绝合同 |
| 独立数学参考 | Python Robertson、完整端点矩阵和 neutral 参考；每像素合成灰阶 `maxRGBdiff < 0.0003`；37 次 FM/noFM 输出矩阵比较，固定容差 `1e-5`，最大绝对误差 `8.9464e-8` |
| 原有 AsShotNeutral 回归 | 6 张真实 RAW：4 张 Xiaomi、Pixel 4a、iPhone 12 Pro；default/custom 共 12 对兼容模式 float 像素完整字节相同 |
| 真实 WhiteXY-only 回归 | Pixel 8 Pro 的兼容/headroom 两种模式，各 default/custom，共 4 个 case 成功，输出有限，source xy 与源 WB provenance 保持；旧版两种 default 均因源 WB 缺失失败 |
| Android ARM64 | NDK `externalNativeBuildRelease` 成功 |
| JVM | 本轮任务为 `UP-TO-DATE`，没有重新执行；既有 66 项报告保持不变，不能把它记为本轮新跑 66 项 |

合成矩阵覆盖暖光、D50、D65、带 tint 白点；单/双 ColorMatrix；FM/noFM；Bayer 和 LinearRaw；签名缺省、显式 empty、匹配、BYTE、mismatch、单侧、255 内容字节上界、过长和无 NUL；子 IFD 回退/override；无效 xy 和矩阵；以及真正缺少源 WB 时的失败原子性。预期拒绝样例验证的是明确失败及旧结果不被替换，不算作成功显色样例。

真实 Pixel 8 Pro 样例沿用既有公开研究样例：

```text
Google_-_Pixel_8_Pro_-_16bit_(4:3).dng
SHA-256: 45420c595401547cca950ae58d552bc82b32d31ce1d58c082df33004016197f5
AsShotWhiteXY: [0.322046, 0.326646]
```

该样例具有非 identity CameraCalibration，验证涉及真实校准处理，不是仅让 identity 矩阵 fixture 通过。探针和合成参考入口为 `tools/native/dng_whitexy.cpp`、`tools/native/dng_whitexy_fixtures.py`、`tools/native/run_dng_whitexy.sh`。

固定 pin 上 32 个引擎补丁和 15 个 LibRaw overlay 已通过 GNU `patch --fuzz=0` 全量重放；92 个引擎文件、22 个 LibRaw 文件与生产候选源码逐字节哈希一致，Android 的 LibRaw 副本也一致。0031 标准上下文及旧 LibRaw 0016 的上下文重排不改变已测试源码。没有完成物理手机运行、显示链路测色或 Lightroom 并排视觉验收。机器记录见 [第二批证据](validation/optimization-wave2-2026-11-09.json)。

## 支持边界与未解决项

- 仅覆盖当前 LibRaw 主 profile 的受支持三通道矩阵路径，不等同于完整 DNG profile、四通道相机或所有合法 xy 的支持。
- 对 `x + y >= 1` 等极端 xy 保守拒绝；SDK 有其约束策略，本次没有声称逐项复现所有边界处理。
- 有界 signature 不能覆盖任意长度合法签名，且没有新增 Camera Profile IFD 选择。不能把已测 IFD0/selected raw IFD 路径外推为完整 profile 支持。
- 未改变 BaselineExposure 的应用政策、profile tone/lookup 或自动测光合同，也没有以增饱和度掩盖色彩校准问题。
- 未修复浮点 DNG 在 LibRaw unpack 中的早期整数化，也未修复小数或非均匀空间 BlackLevel 的全部精度问题。后端 RGB 使用 float 容器不代表 RAW 输入浮点信息得到保留。
- 已有均匀 Samsung `2x2x3` 黑电平按通道处理不属于本次新修复，不能重复列为发现或成果。
- 数值参考、旧路径字节回归和 ARM64 编译支持当前实现合同；它们不代替真实设备驱动、显示效果或完整 Adobe DNG SDK 一致性验证。

## 一手研究依据

已直接核对以下固定版本一手资料，本文为算法及限制的归纳，没有复制大段规范正文。本轮文档整理复用已核实记录，没有重复联网下载。

| 资料 | 用途 |
| --- | --- |
| Adobe DNG Specification 1.7.1.0，2023-09 | tag 类型、签名完整匹配及缺省、IFD 范围 |
| Adobe SDK，Android 固定修订 `de700ad461e35af50b28b861943a0b0753b10929`，`dng_color_spec.cpp` | `SetWhiteXY`、`FindXYZtoCamera`、ForwardMatrix 与无 FM 的适应及尺度合同 |
| 同修订 `dng_shared.cpp` | 校准签名解析和缺省兼容行为 |
| 同修订 `dng_linearization_info.cpp` | 与本次区分的小数/空间黑电平及归一化研究 |
| LibRaw `b93f6e45c194f5df9b02a43b1af9a54b4f41f33f` | 原 WhiteXY 丢失位置、TIFF/identify 的字段归档路径 |

固定来源：

```text
https://helpx.adobe.com/content/dam/help/en/photoshop/pdf/DNG_Spec_1_7_1_0.pdf
https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/source/dng_color_spec.cpp
https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/source/dng_shared.cpp
https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/source/dng_linearization_info.cpp
https://github.com/LibRaw/LibRaw/tree/b93f6e45c194f5df9b02a43b1af9a54b4f41f33f
```

本地研究证据：`D:/AGI/GPT/other/spektralab-backend-research/wave2/WHITE_XY_PRIMARY_EVIDENCE.md`。原 SDK/PDF 在研究时下载并于内存中检查，未保存为本地完整 SDK 副本；该记录保留固定来源与结论，不能误称存在本地完整原文归档。
