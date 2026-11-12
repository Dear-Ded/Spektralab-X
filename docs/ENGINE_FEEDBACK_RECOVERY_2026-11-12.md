# Issue 17：模拟器继续验证与导出完整性改进

日期：2026-11-12，Asia/Shanghai。GPT（OpenAI）实现与验证。

本轮延续 `ENGINE_FEEDBACK_ENVIRONMENT_2026-11-12.md` 的云端测试。反馈机为
Xiaomi 25098PN5AC、Android 17 / API 37、Adreno 840；实际故障原片
DSC02279 仍未取得。本轮使用公开程序生成的合成图，不上传私人 RAW 或其派生内容。

## 已落实的代码改进

`app/src/main/cpp/rgba16_frame_check.hpp` 提取原有整帧纯色抽样检查，并增加：

- 在读取前校验尺寸、行跨度与跨度乘法溢出。
- 检查每个有效像素的 alpha 是否为 65535；不检查行尾填充。
- 首个异常返回像素坐标和实际 alpha，额外空间为常量。

依据是引擎 RGBA16 打包约定：最终输出始终不透明。例如
`spk_view_pack.comp` 对每个像素写入 `v.b | 0xffff0000u`。
Android 的后续 RGBA8 打包会强制将 alpha 写为 255，因此原先只抽样 RGB 的
检查会放过“部分行/像素根本没有写完”的一类异常。

检查接在现有 `blank_result` 边界，覆盖 Bitmap 导出、胶片 JPEG 4:4:4 导出和
RGBA16 TIFF 导出。浮点无胶片路径继续使用原有 `FloatFrameCheck`。
`ProcessingQueue` 保留原始检查错误到诊断事件，向用户区分“像素不完整”和
“像素布局异常”，并沿用既有异常帧降档重试流程。

这项改进不能证明所有 RGB 都正确：如果上游错误仍写出合法 alpha，黑带、
偏色或错误画面仍可能通过检查。没有新增“大面积黑色即失败”的内容判断；
正常暗部、黑色边缘不会仅因其面积被拒绝。整张纯色的既有拒绝行为保留。

## 测试入口修正

`FeedbackAndroid.java` 原来在 `renderBitmap` 后调用 `repack`；前者不会保留
重打包缓存，实际返回 `nothing to repack`。现改为编辑器实际使用的
`renderReuse`，随后比对重打包像素哈希。此问题属于诊断程序，不是生产渲染缺陷。

`build_feedback_android.sh` 同时加入 `profiles-extra`，使诊断资源也包含应用
额外胶片别名，并记录胶片资源哈希。

诊断入口也捕获异常并输出到标准错误、返回 1。原先 Android 的
`RuntimeInit` 会仅向 logcat 报告未捕获异常，最后自己发送 SIGKILL，使外层
只看见 `Killed` / 137。这不能作为低内存杀进程的判据。

`feedback_session.cpp --warm-engine --first-pass` 新增受控生命周期：

1. 同一引擎先导出另一张 4096×3072 合成图，关闭会话并回收闲置内存。
2. 打开 Sony 工作尺寸对应的合成输入，保留编辑会话的 live / preview。
3. 按资源目录逐一打开胶片卡片会话；正片扫描和黑白相纸选择遵循应用逻辑。
4. 回收闲置内存，在编辑会话仍打开时创建独立导出会话。
5. 验证 live / preview / full，回收后再次验证原编辑预览及其负片缓存。

这验证共享引擎中的连续会话与回收次序；没有模拟用户触摸时序或 Adreno 私有驱动。

`run_rgba16_frame_check.sh` 包含有效带填充帧、原 RGB 抽样漏过的坏像素、
最后一个像素、只写完窄条的帧、合法不透明暗部、整帧纯色、错误跨度及溢出。
同一入口可用 `SANITIZE=1` 执行 ASan/UBSan，并测量 6709×4475 正常帧检查耗时。
现有 `engine-gpu` CI 增加这个检查步骤。

## 本轮环境边界

AOIDC 的 API 37 AVD 改用 `-gpu lavapipe -feature Vulkan,GLDirectMem`。
实际来宾设备为 Goldfish GFXStream / llvmpipe LLVM 21.0.0，Vulkan 1.3.0，
单个存储缓冲上限 128 MiB。此前 SwiftShader 组合在初始化时触发的
`Unsupported virtual memory feature` 未在这次小图渲染中重现。

AVD 使用 TCG，未提供 KVM 或 Adreno；`sys.boot_completed` 仍为空，系统服务有
重启。所有允许启动未完成状态的调用均记录为不稳定运行环境诊断，不能视作完整
Android 应用启动或真机兼容验收。Java 实际堆上限为 576 MiB，不是反馈机 512 MiB。

另一台 IBX 云机本轮返回 `machine_cooldown`，服务商给出的恢复时间是
2026-11-12 15:50:10 UTC，即北京时间 23:50:10。收到后停止传输重试，继续使用
可用的 AOIDC。本轮不以本机执行代替云端测试。

## 实际结果

测试源码基于研究分支父提交 `89bf0ec719c260a845ecc955e5f57639f187e948`，
使用该分支的上游补丁和本轮改动。没有将测试结果等同于 alpha-3 APK 的逐字节验收。

| 项目 | 结果 | 验证范围 |
| --- | --- | --- |
| RGBA16 故障注入 | 通过 | 原抽样格之外的坏像素、末像素、窄条后未写入行都被拒绝；合法不透明黑行通过 |
| ASan / UBSan | 通过 | 同一测试入口，包含行填充、布局及溢出边界 |
| 3000 万像素检查耗时 | 57.9951 ms | 本轮最终普通构建；额外空间为常量；不是手机性能基准 |
| Kotlin Debug / Release | 通过 | `compileDebugKotlin`、`compileReleaseKotlin`，Gradle 总耗时 4m56s |
| Android x86_64 JNI | 通过 | 最终生产 JNI 完整链接及诊断 dex 编译 |
| Android ARM64 JNI | 通过 | 最终 `spektralab_jni.cpp.o` 编译；未声明完整 ARM64 APK 或链接通过 |
| API 37 小图链路 | 通过 | 1281×855，13 条帧记录，预览/重打包及三种 JPEG 解码均通过 |
| API 37 3000 万像素链路 | 拒绝 | 引擎明确报告 `spk_sep_fir_acc: buffer exceeds maxStorageBufferRange`，没有导出照片 |
| 26 卡片共享引擎大图流程 | 通过 | 6709×4475，33 条 RGBA16 帧记录，编辑会话回读时 `cached=1` |

API 37 小图包括 live / preview / full、逐级重打包哈希一致、sRGB 与 Display P3
的 Bitmap JPEG（95）保存及解码、原生 JPEG 4:4:4 保存及解码。13 条记录的
全黑行、全白行和 alpha 错误计数均为 0；512 像素合成图样张也已目视核验。
该尺寸小于各渲染层级上限，因此不代表三个不同分辨率的验证。

API 37 大图在 `before=1600×1067` 之后，于 live 渲染触发上述缓冲范围错误。
2026-11-12 14:06:11 UTC 的崩溃记录含原始异常，随后有
`Process: Sending signal. PID: 3201 SIG: 9`，对应外层 137。不能将这次拒绝
归因为 OOM，也不能将 128 MiB 软件来宾的拒绝当成反馈机 2 GiB 广告上限的证据。
该调用发生在异常输出修正前；最终诊断入口以无效模式实测打印完整异常并返回 1。

共享引擎大图运行使用主机原生 Vulkan、Mesa 25.2.4 / LLVM 21.0.0，而不是 Android
来宾；保持强制 Dekker split。CPU 后端沿用既有的存储范围实测放宽机制，来宾
Goldfish 路径没有使用该机制。耗时 **3m40.54s**，进程峰值 RSS
**2,825,592 KiB**；包含诊断程序的读回副本和软件驱动，不能直接比较手机进程 RSS。
33 条记录的全黑行、全白行及 alpha 错误均为 0。回收前后 `persistent.bytes`
保持 562,720,460，传输 staging 降至 0；导出后重新读取编辑预览成功且复用负片。
`--first-pass` 未执行原有三组打印参数往返比较；编辑会话的缓存检查单独执行。

前期 LLVM 15 的 25 卡片探索运行也正常结束（15m28.54s）；其资源和卡片参数与最终
运行不同，单独归档，不作直接性能比较。最终验收采用补齐别名并对齐应用卡片参数的
26 卡片运行。
ARM64 初次隔离构建因缺少已存在的 LibRaw 补丁目录失败；补齐原有补丁后编译通过，
没有为解决构建而修改 LibRaw 行为。

原始文本、哈希与 CSV 保存在
`tools/native/validation/feedback-recovery-20261112/`。不将 f32、大图 JPEG、共享库、
dex、含 ADB 公钥的模拟器启动日志或私人交接文件放入该证据目录。

本轮证明这组云端受控路径正常，并补上了不完整 RGBA16 的导出防护；尚未复现
反馈机“窄条、黑带、紫块”的实际故障，也没有据此认定设备硬件损坏或根因已修复。
