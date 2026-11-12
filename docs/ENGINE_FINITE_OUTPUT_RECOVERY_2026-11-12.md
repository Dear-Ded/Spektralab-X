# Issue 17：异常 RGB 的保存漏洞与引擎恢复修复

日期：2026-11-12，Asia/Shanghai。GPT（OpenAI）实现与云端验证。

## 定位到的代码问题

旧路径在浮点 RGB 转成整数照片时丢失错误信息：

- `spk_to_rgba16.comp` 对输入直接 clamp、转换整数，并固定写入 65535 alpha。
- `spk_view_pack.comp` 显式将 NaN 编码为 0，也固定写入不透明 alpha。
- 局部坏通道或大部分区域的 NaN 可以变成黑色、偏色像素；剩余正常区域使整帧纯色检查通过。
- 原 `BlankFrameException` 的降档重试继续使用同一个引擎，保留其设备、池和其他会话缓存状态。

本轮用真实 Vulkan 打包和真实 `view::process` 注入故障。在修改前的生产实现中，
18 个异常组合全部返回成功，且全部通过此前的 JNI alpha/纯色检查。故障包括
单点 NaN、正负无穷、最后一个样本 NaN、前 15 行之外全部 NaN，以及矩形区域绿色通道 NaN。
每种故障分别在正常派发、每块 1 个工作组和每块 7 个工作组下运行。

## 已实现的修复

补丁 `0054-vulkan-finite-output-boundary.patch` 在量化前检查完整 RGB 平面：

- `spk_validate_finite` 用浮点指数位检查 NaN/无穷，不改变有限负值、HDR 或正常像素。
- 首个坏样本用 `atomicMin` 记录；每工作组一次计数，检查扫描是否完整派发。
- CPU 只读回 8 字节摘要，返回坏像素坐标和通道，或实际/预期工作组数。
- 普通胶片结果、打印视图缓存、双画面打印层、源图显示和最终视图输出均接入检查。
- 失败发生在异常 RGB 被转换为正常整数、进入打印缓存或交付照片之前。

`ProcessingQueue` 将 `render integrity:` 原始错误记入诊断，携带出错引擎句柄进入恢复。
异常帧先重建 GPU 引擎，再按既有面积/边长规则缩小重算。重试再次失败时停止保存，
并替换该次异常引擎，供后续编辑使用。`EngineHost` 复用既有会话退役及代际更新机制。

原 RGBA16 全像素 alpha/布局检查继续负责检查量化后的不完整输出。
胶片、相纸、RAW 默认开发、物理参数、IIR 模式及 GPU 广告容量规则保持现有定义。
本轮版本仍为 alpha-4 / 124，改动提交在研究分支。

## 云端结果

所有编译、故障注入和运行测试均在 AOIDC 云电脑执行。输入为程序生成的图像。

| 验证项 | 结果 |
| --- | --- |
| 旧实现反例 | 18 个异常组合全部被接受，alpha/纯色检查同样放行 |
| 新实现：胶片打包 | 18/18 拒绝，坐标和通道均正确；失败不交付结果 |
| 新实现：视图处理 | 同一 18/18 拒绝；正常 3 组及 3 组 proof 通过 |
| 正常输出对照 | 三种派发上限下，胶片、view、proof 共 9 条完整像素哈希一致 |
| 3000 万像素尾部故障 | 精确报告 `(6708, 4474), channel 2` |
| 漏派发负对照 | 派生着色器跳过一工作组，报告 `3113/3114 groups`，返回失败 |
| API 37 来宾 | 原生胶片/视图同一矩阵通过：18 个异常拒绝，正常及 proof 通过 |
| API 37 JPEG 导出 | 13 条记录通过；sRGB、Display P3、原生 4:4:4 保存及解码正常 |
| ARM64 / x86_64 JNI | 两个架构均完整链接通过 |
| Kotlin / 导出降档 | Debug、Release 编译和 `ExportRetryTest` 通过 |
| 26 卡片及双会话大图 | 33 帧通过；所有像素哈希与上一轮一致；最终编辑负片缓存命中 |

完整暖引擎运行使用 6709×4475 工作图，耗时 4m07.68s，进程峰值 RSS
2,778,880 KiB。33 帧的全黑行、全白行和 alpha 错误均为 0。
这些是云端软件 Vulkan、包含测试副本的运行数据。单独的最终普通扫描为
157.839 ms；多任务运行中为 399.709 ms，摘要始终为 8 字节。

ASan/UBSan 使用同一个检查实现。云端附带 LLVM 21 Vulkan 驱动在全部用例通过后，
于驱动内部销毁阶段报 `operator new vs free` 不匹配，原始堆栈归档。
改用系统 LLVM 15 Vulkan 驱动运行同一受检二进制，矩阵及 3000 万像素检查均以 0 退出，
未关闭 sanitizer 检查。生产数学代码没有为这项云端驱动错误作修改。

API 37 使用 Android 17 CE2A.260420.051.A3、Goldfish/GFXStream LLVM 21、
128 MiB 单缓冲上限、TCG。原生测试经 `adb shell` 执行；诊断 JNI 经 `app_process`
执行，实际 Java 最大堆为 576 MiB，`sys.boot_completed` 为空。3000 万像素整帧在主机
原生 Vulkan 执行；来宾的故障矩阵尺寸为 773×1031。

`run_feedback_android.sh` 增加独立 `smoke` 模式，并让任何失败用例向外返回非零，
修正此前“用例失败但外层返回成功”的测试报告问题。
最终 smoke 的 13 条记录均无黑行、白行或 alpha 错误；三张 JPEG 分别为
432,137、416,857、657,849 字节。测试结束正常关闭云端 AVD。
资源 staging 编译并打包 56 个内核，新增检查内核的 SPIR-V 与实际受检版本一致，
并纳入 `EngineAssets` 使用的资源内容戳。

## 调研依据与复现

查阅的 Khronos 一手资料：

- [GLSL.std.450](https://registry.khronos.org/SPIR-V/specs/unified1/GLSL.std.450.html)：
  FClamp 通过 FMin/FMax 定义；NaN 操作数的选择存在实现差异，clamp 不能充当有限性检查。
- [Vulkan SPIR-V 精度规则](https://docs.vulkan.org/spec/latest/appendices/spirvenv.html)：
  特殊浮点值不会自动给应用抛出算术异常。错误信息应在整数转换前保留。
- [Vulkan 同步示例](https://docs.vulkan.org/guide/latest/synchronization_examples.html)：
  GPU 到 CPU 回读需要可见性屏障、完成等待及非 coherent 内存的 invalidate。
  已核对现有后端对应路径，保留原有同步机制。

云端工作目录：`/data/work/gpt-finite-output-20261112`。生产源码来自通过 0053 的
冻结树加 0054；零 fuzz 重放后，六个补丁输出文件与实际构建源码逐个一致。

```bash
UP=/path/to/54-patched-upstream B=/path/to/54-build \
BASELINE_UP=/path/to/53-patched-upstream BASELINE_B=/path/to/53-build \
BUILD_DIR=/outside-repo/finite-results SPEKTRAFILM_VULKAN_FORCE_SPLIT=1 \
bash tools/native/run_finite_output.sh
```

不传 `BASELINE_UP/BASELINE_B` 时执行候选矩阵和漏派发负对照，供 `engine-gpu` CI 使用。
新增 CI 步骤已在云端按相同调用方式执行通过；GitHub 工作流本身按既有触发规则运行。
原始日志、CSV、构建/资源哈希见 `tools/native/validation/finite-output-20261112/`。
