# 输入精度接入与验证，2026-11-10

共创：GPT（OpenAI）。本批仅修改源码、测试和文档，不改变版本，不生成、安装或发布 APK。
RAW 默认继续使用 neutral，胶片输入保持场景线性；不通过增加饱和度或自动曝光掩盖还原问题。

## 已修复的三个入口问题

| 问题 | 本批行为 | 支持边界 |
| --- | --- | --- |
| 解码器已提供 F16，应用仍转成 8 位 | 合规 SDR 的 RGBA_F16 直接按实际 stride、alpha 读取为 native float，复用原有 sRGB/P3 解码和 ProPhoto 转换 | 仅保留解码器已有精度，不强迫所有格式输出 F16；有限且去预乘后的编码值必须在 `[0,1]`。不接收 PQ/HLG、线性标签或超域值 |
| float LinearRaw 在 LibRaw unpack 阶段先整数化 | 对严格子集保留浮点，读取原始有理数黑电平和 ASN，单次归一化后准备矩阵、应用方向 | 单 IFD、小端 classic TIFF、单条未压缩 chunky RGB float32、完整 ActiveArea、常量黑、明确逐通道白点和受支持单 CM/FM。不是通用 DNG 解码器 |
| 用色域面积判断是否超出 sRGB | Bradford 适配至 D65 后，从适配后的 XYZ 矩阵列重建基色，再检查是否越过 sRGB 三条边 | 边界容差为 `1e-4 xy` 垂距；P3 是可用的较宽目标，不能承诺 Adobe RGB/BT.2020/任意 ICC 都无损 |

F16 入口显式处理 opaque、premultiplied、straight alpha，拒绝非法布局、非有限值和超域编码，
不静默裁剪。worker 只设置失败标志，全部 join 后解锁位图再返回失败；不在 worker 抛异常。
已有 gain map 的照片继续走原兼容路径，包括确有 gain map 但元数据提取失败的情况，避免新入口误收 HDR。
默认 sRGB 8 位路径继续使用原入口和像素处理。

浮点 RAW 归一化不能直接复用 LibRaw 已按通道黑白范围修正的白平衡，否则会重复校正。
本批恢复原始 AsShotNeutral 的倒数再准备变换；不把小数 BlackLevel 塞回旧整数字段。
有限负值与高于白点的场景线性值在此阶段保留，不加显示曲线、裁剪或自动曝光。

## 兼容与诊断

`sourceInfo` 与已有开发诊断记录实际精度模式：

| 模式 | 含义 |
| --- | --- |
| `rgba8_sdr` | 原有 8 位 SDR 输入 |
| `f16_sdr_v1` | 新的有界 F16 SDR 输入 |
| `rgba8_gainmap` | 原有 gain-map 重建入口 |
| `integer_compatible16` | 原有整数 RAW 兼容解码 |
| `float_linear_v1` | 严格子集浮点 LinearRaw；另记录原始黑、白和 ASN |
| `float_legacy_quantized` | 不支持的浮点布局继续使用旧量化解码，并记录原因 |

已经判定支持的浮点文件若解码失败，直接返回失败，不暗中改走整数路径。
不能打开的普通 RAW 仍返回原解码错误，不误报为空的浮点计划。
压缩 float、float Bayer、WhiteXY、复杂裁剪/opcode/profile 等仍可能走量化兼容；
这不是已解决的高精度范围。既有原型的失败记录和旧数值证据继续保留。

后续源码更新已让受支持子集的自定义 WB 保留 `float_linear_v1`，并接通 Android 到
LibRaw 的协作取消。上面的首批验证数字是历史快照；后续行为和最终验证见
[RAW 精度、取消与内存适配](RAW_PRECISION_CANCELLATION_2026-11-10.md)。

浮点子集上限为 16,777,216 像素，保留输入加输出浮点 payload 共 402,653,184 B（384 MiB）
的保守准入值。后续已改为按行直接读取已验证的未压缩 strip，消除 LibRaw 的整图浮点输入分配，
但没有扩大该准入值。这不是进程内存总预算：LibRaw、解码 scratch、engine/GPU 和旧编辑会话仍需单独记账。
F16 位图与 RGB float 也仍是整图驻留，不能描述为完整分块渲染或保证手机不 OOM。

## 已完成的验证

公共合成证据和最终源码 SHA-256 位于
[input-precision-20261110](../tools/native/validation/input-precision-20261110/README.md)。
未纳入用户照片、私人文件名或私人图片测量。

- AOIDC 与 IBX 分别使用 GCC 11 / GCC 14 验证全部 65,536 种 half 位模式、SDR 数域、透明度、奇数尺寸和非对齐带 padding 行。IBX 还通过 ASan/UBSan；其原始输出在会话工具记录中，本目录不伪造对应日志。sRGB/P3 渐变的 257 个邻接值保留，8 位对照少于 40 个编码值。
- AOIDC 浮点 helper：26 个历史案例中支持 17 个，另外 18 个非法/越界案例明确拒绝、4 个预算边界拒绝、89 个取消边界通过。独立数学参考容差固定 `2e-6`，最大误差 `4.76837158203125e-7`；远处最大值改变不会改变其他 8064 个输出分量，邻接浮点与负值保留。
- AOIDC 正式 dispatcher：17 组精确浮点对照、8 组旧浮点兼容对照、4 组整数 Bayer/LinearRaw 与默认/custom WB 对照，以及 77 个取消边界通过。兼容像素与元数据逐项相同；八种方向对应 LibRaw 实际 flip；支持文件中的非法像素失败且不回退。helper 和 dispatcher 均通过正常构建与 ASan/UBSan；复用的 LibRaw/IO archive 没有整体插桩。
- 真实 `spektralab_jni.cpp` 合成 harness 通过 F16 sRGB/P3 的 source/render/repack、原始尺寸与诊断；非法数值与 1024×1024 末像素 NaN 触发单/多 worker 失败后解锁并保留输入。浮点 RAW 正式入口保留完整源值、neutral 与 unbinned 状态，并通过 render/repack/provenance。只替换 Android 位图分配和 JNI 字符串平台 shim，不替换生产入口。
- API 34 Robolectric native graphics 通过 47 项 Debug 回归，包含 F16 8 项、色域路由 9 项及原有照片颜色、胶片缩略图、导入权限、尺寸和输出色彩标签。Release JVM 全部 92 项通过，零失败和错误；release Kotlin 编译通过。
- NDK r27.2 / Clang 18 ARM64 原生构建通过，最终在干净重放至 0036 的上游树上复核，新 `openBitmapF16V1` 与现有 `openRaw` 导出均存在。初次 Gradle 原生配置因没有设置 `SPK_UPSTREAM` 失败，修正环境后通过；失败日志保留，不把整次初跑描述为成功。

## 仍未完成

真实手机的 OEM 高位深解码、Adreno/Mali 驱动、内存峰值和热状态没有本批运行验收。
Android 已接通协作取消；阻塞 I/O 和没有进度回调的单次操作仍需返回后响应，详见后续记录。
通用 float Bayer/压缩与复杂 DNG 元数据、完整字节预算和外存分块仍需继续实现。
相机 JPEG 包含相机的显影选择，本批不宣称与相机 JPEG 或 Lightroom 完全一致，
也不把源代码和云端合成验证称为手机灰片问题已经全部验收。
