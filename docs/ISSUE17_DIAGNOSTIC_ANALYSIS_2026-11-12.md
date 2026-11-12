# Issue #17 手机诊断分析

日期：2026-11-12。本文整理用户提供的完整诊断及仓库只读核对结果，不包含私人影像、派生测量或交接文档内容。整理过程中未启动测试或云端任务。

## 结论与边界

已确认的现象是：重新打开 Sony RAW 后，应用返回了非空预览，并将异常内容保存为具有预期尺寸的 JPEG。现有证据不能认定手机硬件损坏、Adreno 驱动缺陷或纯粹内存不足，也不能认定问题已修复。

普通 JPEG 胶片路径已补齐调用器并在云端尝试：`exportBitmap → Bitmap.compress(JPEG, 95)` 的 sRGB/Display P3 分支尚无完整成功结果，模拟器在 Vulkan 初始化时中止。原生 4:4:4 或独立编码通过不能替代这条完整流程的验证。

以下“已确认”区分诊断原文所报告的事实与源码能确认的实现；手机安装包哈希、真实运行状态均未独立核验。

## 反馈环境

| 项目 | 诊断原文 |
| --- | --- |
| 应用 | Spektralab-X 0.2.00-alpha-3，versionCode 123 |
| 手机 | Xiaomi 25098PN5AC / pandora，SM8850，Android 17 / API 37 |
| GPU | Adreno 840，Vulkan 1.4.295，driver 2150932524 |
| 缓冲与调度 | maxStorageBufferRange 2147483647；verifiedStorageRange 672000000；maxComputeWorkGroupCountX 65535 |
| 输入与工作图 | Sony ILCE-7CM2；7028×4688 → 6709×4475；integer_compatible16；pixel cap 30030069 |
| 编辑 | Ultramax 400 / Ultra Endura；RAW neutral；balanced 自动曝光 +0.5 EV；36 mm；grain、halation、glare 开启；无裁切或片边 |
| 算术探针 | FMA residual 不符，选择已验证的 exact Dekker split；full shader parity pending |

`verifiedStorageRange` 是单缓冲探测结果，不是整个渲染的可用 GPU 内存预算；工作图有 30022775 像素，低于所报 pixel cap。不能由这些数字推断所有中间资源都可成功分配。

## 两次导出的时间线

| 时间 | 记录及可支持的结论 |
| --- | --- |
| 17:00:30～17:00:46 | 前一张图的 live 为 1072×748，preview 为 3562×2485；不能作为随后 Sony RAW 的同条件性能基线。 |
| 17:00:59～17:01:05 | 前一次排队后保存 4290×2995、6.86 MB JPEG，任务耗时 6 秒；具体源图及各时刻编辑参数未知。 |
| 17:01:05 | 前一次导出后 trim level 20，进程回调释放 259 MB，编辑器回调随后报告 0 MB。 |
| 17:02:19 | 重新打开 Sony RAW，7028×4688 → 6709×4475。 |
| 17:02:20～17:02:26 | live 1024×683 为 409 ms；preview 3400×2267 为 405 ms；随后 trim 释放 5 MB，并记录 film cards 26/26。 |
| 17:02:37～17:02:42 | 新图再次返回 live 788/309 ms、preview 1705/1600 ms；这些记录说明取得了 Bitmap，不证明像素正确。 |
| 17:02:47～17:03:03 | 新图排队并保存 6709×4475、0.51 MB JPEG，耗时 15 秒；这是应定位的异常导出。 |
| 17:03:03～17:03:42 | 导出后 trim 释放 351 MB，后续多次 level 20 报告 0 MB；日志没有显示该次任务被系统终止。 |

前一次保存成功只能说明同一进程此前完成过另一项导出，不能证明第二次使用的源数据、缓存、内存状态和渲染条件正确。

## 内存、退出与温度的语义

**已确认：两次 1332 MB 都标注为 process peak。** 应用尝试写 `/proc/self/clear_refs` 重置 VmHWM；失败时读取进程历史峰值并附加该标记。第一次导出前 RSS 为 768 MB，第二次为 568 MB；不能把两次相同的 1332 MB 当作两次独立导出的精确峰值，也不能计算第二次新增用量。

源码：`app/src/main/java/com/dearded/spektralab/EngineHost.kt:369`、`app/src/main/java/com/dearded/spektralab/ProcessingQueue.kt:457`、`app/src/main/java/com/dearded/spektralab/ProcessingQueue.kt:1150`。

**已确认：四类退出计数全 0 是历史退出统计。** 应用读取系统返回的最近最多 8 条退出记录，按时间去重并累计特定类别。它不是运行中 OOM、GPU 分配失败或错误像素的计数器；不能由此排除未杀进程的分配失败、内存压力或系统记录遗漏。

源码：`app/src/main/java/com/dearded/spektralab/EngineHost.kt:274`、`app/src/main/java/com/dearded/spektralab/EngineHost.kt:330`。

**已确认：RAM 15084/5118 MB、low=false，Java heap 512/22 MB、native heap 239 MB 是生成诊断时的快照。** 它们不能还原导出瞬间的资源状态。Java 堆上限不等于 native、GPU 和进程总内存上限；native heap 数值也不能替代总 RSS。

源码：`app/src/main/java/com/dearded/spektralab/EditorViewModel.kt:1096`。

**已确认：thermal headroom 0.81 低于所标 severe 阈值 1.0。** 该字段来自 10 秒预测，应用异步采样并允许返回不超过 30 秒的缓存值。它不是摄氏温度，也不是 17:00～17:03 全程的温度或降频记录，不能证明热状态与故障无关。

源码：`app/src/main/java/com/dearded/spektralab/DeviceProfile.kt:127`、`app/src/main/java/com/dearded/spektralab/ThermalHeadroomSampler.kt:10`、`app/src/main/java/com/dearded/spektralab/ThermalHeadroomSampler.kt:32`。

**已确认：trim level 20 是 TRIM_MEMORY_UI_HIDDEN，表示界面不可见。** 数值语义已只读核对 AOSP `frameworks/base/core/java/android/content/ComponentCallbacks2.java`；该事件本身不是低内存告警。仓库同时注册进程和 Activity/编辑器回调，因此 `(app)` 与编辑器的两条记录不等于两次独立低内存事件。

实现只释放空闲 GPU 池及部分界面资源，设计上保留会话负片；不能据此证明所有资源的生命周期实现均正确。原生 trim 入口为 `app/src/main/cpp/spektralab_jni.cpp:1653`。

源码：`app/src/main/java/com/dearded/spektralab/EngineHost.kt:147`、`app/src/main/java/com/dearded/spektralab/MainActivity.kt:72`、`app/src/main/java/com/dearded/spektralab/EditorViewModel.kt:1317`。

## 渲染、文件与算术探针

**已确认：非空和耗时不是图像正确性校验。** 首次 preview 405 ms 与 live 409 ms 接近，值得核对实际参数、执行阶段与负片缓存命中，但单凭“过快”不能认定跳过渲染。后续 preview 已为 1600～1705 ms，且日志未保留每次编辑参数。

源码：`app/src/main/java/com/dearded/spektralab/EditorViewModel.kt:941`；JNI 使用可复用负片的 `spk_reprint`，见 `app/src/main/cpp/spektralab_jni.cpp:520`。

**已确认：保存检查允许部分损坏图像通过。** 现有空白帧检查只拒绝整幅近乎单色；只剩窄条而仍有像素变化的图像可能通过。JPEG 检查只核对尺寸和结束标记，并不解码验证全部像素。0.51 MB 与所报大片黑色/紫块现象相符，但体积本身不能定位渲染、打包或编码哪一层出错。

源码：`app/src/main/cpp/spektralab_jni.cpp:538`、`app/src/main/cpp/spektralab_jni.cpp:1388`、`app/src/main/java/com/dearded/spektralab/MediaSaver.kt:160`。

**已确认：反馈报告探针回退成功，未报告完整 shader 一致性通过。** FMA residual 不符只表明该探针未满足应用要求的精确残差，随后 Dekker 探针通过。它既不是坏硬件证据，也不是全部 shader 正确的保证。已有公开双云记录也出现 FMA 探针拒绝而 split 流程通过，见 `docs/ENGINE_FEEDBACK_DISPATCH_2026-11-12.md:78`。

## 普通 JPEG 与 4:4:4 的复现差异

任务标签 `JPEG · 工作尺寸` 对应非 extreme 普通导出；生产代码调用 `exportBitmap`，随后 `Bitmap.compress(JPEG, 95)`。高质量导出可走原生 4:4:4，不能将两条路径的通过结果等同。

源码：`app/src/main/java/com/dearded/spektralab/ProcessingQueue.kt:109`、`app/src/main/java/com/dearded/spektralab/ProcessingQueue.kt:913`、`app/src/main/java/com/dearded/spektralab/ProcessingQueue.kt:947`、`app/src/main/java/com/dearded/spektralab/ProcessingQueue.kt:974`。

Android Java 调用器已补齐“胶片输出 → exportBitmap → Bitmap.compress(JPEG, 95)”的 sRGB 与 Display P3 分支；云端执行在 Vulkan 初始化失败，尚无完整成功结果。反馈未明确提供宽色域开关，两种输出空间均需保留验证。

调用器文件为 `tools/native/android_feedback/FeedbackAndroid.java`；工具仍在修改，本文不固定其行号。已补齐调用器不等于云端验证通过；独立 codec 或原生 4:4:4 结果不能填补普通 JPEG 胶片流程的执行证据。

## alpha-3 与 alpha-4 核对

| 版本 | Git tag | 完整提交 |
| --- | --- | --- |
| 反馈版本 123 | v0.2.00-alpha-3 | ed135c8c2bab72084ea529f66ec5e3539819a478 |
| 当前测试来源版本 124 | v0.2.00-alpha-4 | 1da3ab15f239e957949441e30fdd1606f0da9955 |

只读比较两个 tag：Kotlin 会话、卡片、生命周期、队列、导出代码，JNI 渲染/输出、缩放与原生构建配置没有改动；上游获取脚本及 `patches/libraw` 树也相同。`spektralab_jni.cpp`、`EditorViewModel.kt`、`EngineHost.kt`、`ProcessingQueue.kt` 的 blob 标识逐项相同。

相关变更限于：`float_dng_linear.hpp` 的浮点 LinearRaw DNG 条带/Deflate 支持；0050 的 DNG OpcodeList3 取消检查；0051 的整数三通道 LinearRaw DNG 黑点修正；0052/0053 默认关闭的 Vulkan IIR 实验。0051 有 DNG、非 Bayer 等显式条件；0052/0053 需显式环境开关才选择实验核。

源码：`patches/0051-linearraw-uniform-black-normalization.patch:26`、`patches/0052-iir-terminal-boundary-experiment.patch:43`、`patches/0053-iir-difference-state-experiment.patch:43`；变更索引为 `docs/PATCHES.md:58`。

**判断：未发现直接改变该 Sony Bayer ARW 默认渲染、会话缓存或普通 JPEG 路径的版本差异。** 这不等于两个构建产物完全相同；新增 kernel 注册及构建差异仍可能影响资源准备。through0053 的通过结果不能代替精确 alpha-3 构建或手机安装包的验证。

复核命令：`git diff --name-only v0.2.00-alpha-3 v0.2.00-alpha-4 -- app patches scripts/fetch-upstream.sh`；两个版本的源码行号以对应 tag 为准，诊断工具引用以本文整理时的工作树为准。

## 合理假设与尚未覆盖条件

- 合理假设：换图、卡片小会话、空闲池清理和导出会话的交错暴露共享资源或缓存生命周期问题；目前没有证据证明该假设成立。
- 卡片 26/26 只证明各卡片调用取得结果。卡片使用约 384 px 的独立会话，不改主会话负片，但共享同一个引擎；小会话成功不能验证主图完整性。源码：`app/src/main/java/com/dearded/spektralab/EditorViewModel.kt:735`、`app/src/main/cpp/spektralab_jni.cpp:1183`。
- 合理假设：损坏可能发生在胶片中间阶段、GPU 读回、行跨度/Bitmap 打包或 JPEG 编码。现有反馈没有逐阶段像素证据，不能在这些位置中作出归因。
- 未覆盖：实际故障原图、专有 Adreno 驱动、ARM64 与 HyperOS、真实热状态、完整 APK/Kotlin/Compose 生命周期及 MediaStore 行为。通用软件 Vulkan 与 AVD 无法逐项等价替代。

## 现有云端结果的解释边界

公开 native 记录中，5 GiB 限制和传输分块条件通过；2 GiB 条件被 cgroup OOM 杀死；严格容量条件明确拒绝。后两者没有产生“成功保存损坏图像”的相同症状。这能区分这些受控失败，不能排除 Android 上的内存或驱动因素。来源：`docs/ENGINE_FEEDBACK_ENVIRONMENT_2026-11-12.md:47`。

主线程归档结果中，云端 API 37 的 6709×4475 独立 codec 已通过；普通 JPEG 与 4:4:4 均能完整解码。AVD 系统服务反复重启、未完成 boot，实际 Java 大堆为 576 MiB，不能冒充手机的 512 MiB。默认配置的 1281×855 胶片 smoke 在 `spk_engine_create` 分配 64 KiB inline ring 时被 GFXStream 中止，日志为 `Unsupported virtual memory feature`，full 因先决条件失败跳过。关闭 ring 并使用 1 MiB staging 的对照也在初始化算术探针分配时以相同错误中止。二者均发生在打开图像前，与手机成功保存损坏文件的症状不同，不计为完整 Android 胶片复现或手机验收。原始证据见云端报告。

## 优先实验

1. **云端：在支持所需 Vulkan 分配的 Android 环境执行已补齐调用器。** full 渲染经 `exportBitmap → Bitmap.compress(95)`，同时覆盖 sRGB/Display P3，核对引擎、Bitmap 和 JPEG 解码后的逐行内容；以原生 4:4:4 为对照。当前 AVD 初始化中止，未执行完整胶片输出，本文不重复执行。
2. **云端：严格 alpha-3 对照。** 冻结准确 alpha-3 及 through0053 构建，同一合成图、参数、Dekker 模式下比较各阶段像素与普通 JPEG；记录构建和资源版本，消除版本混用因素。
3. **云端：复刻暖引擎操作顺序。** 先完成约 12.8 MP 图导出，再换入 7028×4688 合成图并缩至 6709×4475，运行 26 个卡片会话，随后编辑、预览和普通导出；以冷启动、无卡片为对照，记录缓存命中和逐行覆盖。
4. **云端：受控 trim 与双会话交错。** 在打开后、卡片期间、导出前及导出中清理空闲池，覆盖编辑器与导出各自会话；JNI 级测试通过后仍需完整 APK 的前后台生命周期验证。先校准 AVD 稳定性和真实 Java 堆，再解释大图结果。
5. **必须真机：定位实际故障原图的首个损坏阶段。** 在该 Xiaomi/驱动上比较 no-film 和逐个关闭 grain、halation、glare，分别核对引擎结果、Bitmap 与 JPEG；核验安装包版本及驱动，保留逐阶段证据。其他图像或通用云 AVD 通过不能替代此项。
