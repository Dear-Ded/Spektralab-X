# Sony Huffman 缓存维护修复

日期：2026-11-13，Asia/Shanghai。GPT（OpenAI）接续实现与云端验证。
起始源码为 alpha-5 / 125，`a94d584d2bb33ce721c27b68b28fcdbf598e9098`。

## 修改与范围

`patches/libraw/0024-sony-ycbcr-cache-index-mask.patch` 回移植 LibRaw 官方
`d7e09495734c527c9fdcd154e6435e06d20bb942` 的两行修改：定义 13 位 mask，
并在 `HuffTable::decode` 的 8192 项缓存寻址前取低 13 位。官方 commit API 已实际
读取，补丁相同。LibRaw 仍为 0.22.2 加局部 overlay，没有整体升级依赖。

所有 0..8191 合法索引保持原值，缓存值、慢路径及 bit 消费逻辑不变。
本项没有新增图像缓冲，也不改变 RAW 默认显色、相机矩阵、材料数据或 IIR 模式。
它是独立依赖维护；未将该解码器与 Issue 17 的手机花屏原因关联。

原会话新增的测试入口把 `after` 同时用作源码目录和程序名，导致链接失败。
接续时修为 `.bin` 程序名，并支持 pristine / 已打 0024 的两种源码输入。
原失败目录保留，新最终测试使用独立目录。

## 实际验证

执行位置为 AOIDC Ubuntu x86_64，GCC 11.4；编译及测试均在云端完成。

| 项目 | 实测结果 |
| --- | --- |
| 合法组合 | 81,920 例；旧/新解码返回值、bit 消费、真实 BitPumpJpeg 状态均一致 |
| 组成 | 三种 cache/slow 组合 24,576；DNG 消费组合 49,152；真实 byte-stuffing 位流 8,192 |
| 受控高位组合 | 24,576 例，修复后返回低 13 位对应结果、消费数量正确 |
| ASan/UBSan | CI 形式调用退出 0，无错误恢复或 sanitizer 抑制；旧实现的受控负对照由 ASan 拒绝 |
| 已打补丁的输入 | 反向生成旧对照的独立调用通过，确认 runner 不会重复应用补丁 |
| 文件对照 | 两份公开合成 DNG，解码器信息、解包 RAW 和 half-size camera-WB RGB16 全部逐字节相同 |
| LibRaw 重放 | pristine 0.22.2 依次应用 18 个 active overlay，全部 `--fuzz=0`；源码与 ARM64 构建一致 |
| Android 编译 | NDK 27.2.12479018、API 29、arm64-v8a，189 步完整 JNI 构建/链接成功 |

文件对照使用 `integer-bayer.dng` 和 `bayer-max2-black64.dng`，为既有公共程序生成的夹具，
实际 decoder 分别为 `packed_dng_load_raw()`、`uncompressed_fp_dng_load_raw()`。
客户端链接原有完整 host archive，仅以相同生产 flags 重编受改动头文件影响的
`losslessjpeg.cpp` 与 `sonycc.cpp` 两个对象。host 文件对照不能称为全依赖干净重编；
本轮完整重编证据来自独立 ARM64 JNI 构建。

旧/新 host LibRaw 的 `src/`、`internal/`、`libraw/` 比较仅 `losslessjpeg.h` 不同。
原始证据在 [validation](../tools/native/validation/sony-huffman-20261113/README.md)，
补丁哈希、JNI 哈希和 archive 来源都已留存。

## 覆盖边界

受控 BitPump 的高位测试证明局部缓存访问约束，不是从真实故障 Sony RAW 解码得到的
失败见证。真实 BitPumpJpeg 测试使用合法的小字节流和 byte stuffing，未开展完整
Sony YCbCr/ARW6/X-Trans 相机文件矩阵、截断文件专项或相机解码性能测量。
因此没有给出真实相机故障修复率、解码提速或 RSS 降幅。

IBX 本轮冷却至 2026-11-13 10:09:00 Asia/Shanghai，本项没有第二台云机执行结果。
此前曲线优化的双云结果单独保留，不混用为本项证据。没有手机安装、GPU 实机运行、
新 APK 或版本发布。CI 已接入相同 helper，尚未把未触发的线上 CI 写为通过。
