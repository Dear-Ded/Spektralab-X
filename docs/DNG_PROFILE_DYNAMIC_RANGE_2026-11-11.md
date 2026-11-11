# DNG 相机配置动态范围合同，2026-11-11

共创：GPT（OpenAI）。本轮修复可选标准显色对 HDR 相机配置的错误接纳，不改变新 RAW 的线性默认，不增加自动曝光或饱和度。未打包、安装、修改版本或发布。

## 发现及修复

现有标准显色读取主配置的 ProfileToneCurve，却没有检查 DNG 1.7 引入的 ProfileDynamicRange。HDR 配置的曲线及相关查找表使用规定的非线性编码；直接按当前 SDR 路径使用会解释错误。旧实现配合新增回归能复现 `HDR camera profile interpreted as SDR`。这是新确认的配置兼容缺口，没有证据表明它是此前私人手机样片发灰的原因。

LibRaw 补丁 0023 保留标记、有效性与 range，随后引擎补丁 0049 和 JNI 将它传给 RawDevelopment。仅当用户显式启用标准显色时，HDR 或损坏标记被明确拒绝；不能静默套 SDR 曲线、忽略坏标记或用标准曲线代替。缺失标记按原有 SDR 合同处理，有效显式 SDR 不改变计算。关闭标准显色时仍先旁路，保持中性线性解码及后续胶片输入。

ProfileDynamicRange 的 TIFF tag 为 52551/0xCD47，类型 UNDEFINED，长度 8 字节：SHORT Version=1、SHORT DynamicRange=0/1、FLOAT Hint。多字节字段沿 TIFF 字节序读取，不因 UNDEFINED 类型改成固定大端。Hint<=0 表示未知，SDR 的有效正 hint 不能大于 1。解析器同时检查类型、长度、文件范围、版本、range 和非有限 hint；新增字段复用原有有界 IFD 存储，不申请由 tag 决定大小的内存、不追踪额外 profile 偏移。配置来源沿用 selected RAW IFD/IFD0 与 CM/FM 的匹配合同，混合来源仍明确不支持。

内部 LibRaw 和 RawDngRenderMetadata 布局改变，头文件、完整 LibRaw archive、引擎和所有 JNI 消费者必须同步重编。公开 C ABI、已有编辑序列化和应用版本均未改变。

## 实际验证

合成证据位于 [dng-dynamic-range-20261111](../tools/native/validation/dng-dynamic-range-20261111/README.md)，不含私人照片、场景测量或原始文件名。

- 45 个 DNG fixture 通过：保留此前 26 个 rendering metadata 案例，新增 19 个 SDR/HDR、未知 hint、坏版本/range/NaN/Inf/长度/类型/巨大 count、大端字节序和 SubIFD 来源案例。全部线性解码像素的 FNV64 与修复前的独立旧 archive/旧 image-I/O 构建一致。
- 显色回归在旧实现失败；新实现 9 组通过，ASan/UBSan 同样 9 组通过。覆盖 HDR/坏标记的显式拒绝、中性旁路，以及缺失/有效 SDR 的相同输出字节。
- 全部 49 个引擎补丁在干净 pin 上以 `--fuzz=0` 重放；97 个受影响文件与实际构建树逐字节相同。LibRaw 17 个 overlay 从 pristine 0.22.2 顺序零 fuzz 应用，host 与 Android 使用的三个修改后 LibRaw 文件逐字节相同。
- Android NDK 27.2，ARM64/API29 完整原生库重新编译 186/186。38 个 JNI 导出仍在，所有 ELF LOAD 段为 16 KiB 对齐。四 worker 构建墙钟 80.98 秒、CPU 383%，无 swap；这是构建资源统计，不是渲染耗时或整个构建进程组的峰值内存保证。

测试项目也补齐与应用一致的 USE_ZLIB 和元数据上限，避免用错误的 host 解码配置验证。没有在 Android guest 或 Adreno 上运行此次原生库，不能从编译推断手机显色、驱动精度和温控行为。

## 后续 HDR 范围

本次没有宣称完整支持 HDR 相机配置。DNG 规定的 HDR 编码 `f(x)=x*(256+x)/(256*(1+x))` 把线性 [0,16] 映射到 [0,1]，而曲线超出最后节点还需按末端斜率延伸；相关 HueSat/Look/RGB table 及超范围中间值也须按各自阶段处理。后续实现必须对照独立 DNG SDK 参考及真实 HDR profile，不能仅更换 BT.2020 名称、ICC 或显示标签。

当前受限浮点 DNG 路径仍有独立 tag allowlist，不能据通用 LibRaw 路径的本次保护，宣称所有 HDR/浮点 DNG 都可打开。相机 JPEG 的厂商 tone/local processing、Lightroom profile 及全 HDR 输出仍为独立验收项。

## 一手依据

实际读取 Adobe DNG 1.7.1.0 specification 的 ProfileDynamicRange 和 HDR 处理条款（PDF 页 94、95、103；SHA-256 `abdecfd8e104e8b86cc054d3a40b677bb2ebe87131bfde080a1df3ee16eb2f8d`），并实际 HTTP 获取固定 [Adobe DNG SDK dng_shared.cpp](https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/source/dng_shared.cpp)：`tcProfileDynamicRange` 检查 UNDEFINED/8、读取 version/range/hint。网页搜索空返回未被当作证据。没有复制 SDK 曲线表或添加新的运行依赖。
