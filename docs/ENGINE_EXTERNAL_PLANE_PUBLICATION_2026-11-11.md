# 外存分块：完成状态、取消与故障恢复

GPT（OpenAI）共创，2026-11-11。接续 [引擎复核](ENGINE_ANDROID_REASSESSMENT_2026-11-11.md) 的 P0；补丁 `0042-external-plane-publication.patch` 依赖 0001–0041。版本不变，只改源码，没有 APK、安装或发布。

## 发现与修复

外存原型先预分配整幅文件，但没有记录哪些行真正写过。旧代码能成功读取尚未渲染的区域，返回全零。文件大小正确、读取成功，都不能证明渲染完成。基线探针实际复现 `FAIL unwritten preallocated pixels were readable`。目前它还在生产 Pipeline 之外，不能把该问题说成现有手机 RAW 灰片的已证实原因。

0042 将 plane 的生命周期分为 Filling、Producing、Sealed、Failed：

- 每行一个字节记录覆盖，创建文件前分配。互不重叠的行可以并发写，重复或重叠写明确拒绝；写入中的行也不能重复领取。
- 所有行完成且没有在途写入，才允许 seal。消费者只能读取 Sealed 数据；发布后不可修改。覆盖检查不靠像素值，因此黑色、负数、HDR、NaN payload 不会被误判。
- 渲染独占一个 Producer。失败、异常、取消或未提交即离开作用域，都会作废输出。即使所有行已经写完，失败的任务也不能随后被手动封存。
- 共享 point/FIR 的 worker 执行器，保留原 kernel、运算顺序和全帧行坐标。线程创建失败时由现有 worker 接管，增加实际 EAGAIN 注入验证；全部线程汇合后才返回。
- positional I/O 正确推进短读短写、重试 EINTR，拒绝意外 EOF/零进度。每次最多传输 1 MiB，在 I/O 和 GPU 阶段边界检查取消。取消读取不会破坏已发布的输入。
- 统计只在成功发布后填写；执行失败返回空统计和错误。调用者须使用线程安全的取消回调，并独占所用 Gpu 实例；执行器内的锁不保护其他调用者直接使用同一 Gpu。

取消是协作式的，不能打断已经进入的系统调用或正在执行的 GPU dispatch。1 MiB 是单次 I/O 的字节上限，不是最坏延迟保证。封存只提供进程内完成合同，不是断电恢复或持久化承诺。

## 对应外部调研

遇到预分配、并发写和预算解释的问题后，直接阅读以下一手规范。下载内容的 SHA-256 保存在 [证据目录](../tools/native/validation/external-plane-20261111/)。

| 一手资料 | 约束与采用方式 |
| --- | --- |
| [POSIX.1-2024 read/pread](https://pubs.opengroup.org/onlinepubs/9799919799/functions/read.html) | 未写过的普通文件区域可读出零；允许短读；pread 不改变共享文件偏移。显式覆盖与 seal、独立文件偏移、短读循环据此实现 |
| [POSIX.1-2024 write/pwrite](https://pubs.opengroup.org/onlinepubs/9799919799/functions/write.html) | 允许短写；EINTR 与已写入字节要分别处理；成功写入后的读取可见性不等于持久化。本路径无需每带 fsync 来证明进程内完整性 |
| [POSIX.1-2024 posix_fallocate](https://pubs.opengroup.org/onlinepubs/9799919799/functions/posix_fallocate.html) | 预留存储空间不能证明渲染覆盖；错误码由返回值给出。保留预留及重试。ENOSPC 注入是强制失败测试，不能据此声称成功预留的同一区间仍应因空间不足失败 |
| [Vulkan memory budget](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceMemoryBudgetPropertiesEXT.html) | heapUsage 是估计，budget/usage 可变；不能保证整个 Android 进程不会超限。没有把 GPU 估计值冒充全进程硬预算 |

glibc 加固编译会把部分 pread 重定向为 `__pread_chk`。AOIDC 的 sanitizer 运行揭示探针漏拦截该入口，现同时注入两者；这只影响测试，不削弱产品的加固设置。

## 两云验证

全部为合成像素；没有公开用户 DNG、JPEG、渲染照片或私人样片指标。重现入口见 [验证目录](../tools/native/validation/external-plane-20261111/) 与 [native README](../tools/native/README.md)。

| 检查 | AOIDC | IBX |
| --- | --- | --- |
| 完成状态、I/O、并发、取消、资源释放、线程创建失败 | 94 项通过 | 94 项通过 |
| 同一合同 ASan/UBSan | 94 项通过 | 94 项通过 |
| point：独立整幅参考与分块比字节 | 901 场景，102,764,160 float32 | 相同覆盖通过 |
| FIR print：独立整幅参考与分块比字节 | 2251 场景，75,470,400 float32 | 相同覆盖通过 |
| GPU 取消与重新渲染 | point 8 / print 10 个位置，各 3 个整段位置；恢复字节相同 | 相同覆盖通过 |
| 严格 128 MiB buffer range | 4097×3073 整幅拒绝，分块通过 | 同样通过 |
| Android NDK r27.2，ARM64 API 29 | 两执行器头文件 `-Wall -Wextra -Werror` 语法编译通过 | 不替代手机运行 |

AOIDC 使用 GCC 11.4 / llvmpipe LLVM 15.0.7，复用此前当前引擎 archive，重编 header-only 执行器和探针。IBX 使用 GCC 14.2 / llvmpipe LLVM 19.1.7，从固定上游 `a46627d250545a394ca21522f8a28b0fb89e4c42` 重新应用全部 42 个补丁并重编当前引擎。比较发生在各主机内部，不宣称跨驱动字节一致。

合同探针直接编译 external plane 与共享执行器，没有链接未插桩的引擎 archive；ASan/UBSan 覆盖这些实现与测试。真实 GPU 数值、取消和恢复由另两个探针覆盖。没有重跑未改动的 Kotlin/JNI/TIFF 测试；上一批证据单独保留。

## 尚未完成与接入条件

0042 完成了 P0 中的覆盖、提交、取消和失败不发布部分。它尚未接入实际 Pipeline，未补齐整个手机渲染的内存账本，不能据此宣称任意大图都能在手机渲染。

下一步必须在分配前统一计入每 worker 输入窗口与输出、spans/coverage、GPU 常量与空闲池、RAW 解码、codec 和页缓存；按实际阶段需求规划并发数和带高。GPU live 上限与 Vulkan buffer range 只是其中两项。

生产接入还要去掉 `run_stages_striped` 的整幅 GPU 输入/输出及其他跨阶段整幅 plane。保留它们再套一层分块不能解决峰值。先接已验证的默认 point/FIR 配置，再分别处理全局统计、IIR 递归状态、颗粒全帧坐标和 JPEG/TIFF 行消费者。当前 IIR 仍明确拒绝，不能拿大 halo 近似。

仍需独立完成 float Bayer、更多相机 profile、手机 Adreno/Mali、热状态及实际外观对照。新 RAW 的线性默认显示保持不变。
