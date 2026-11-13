# 视图曲线预计算与云端回归

日期：2026-11-13，Asia/Shanghai。GPT（OpenAI）实现与双云验证。

## 改动

补丁 `0055-prepare-view-curve-tangents.patch` 优化 `view::process` 的五行曲线表生成。
原实现每求一个内部采样点都会分配斜率和切线数组，并重算整条曲线的
Fritsch-Carlson 切线。每行只有一组控制点，却重复执行最多 254 次相同准备工作。

新增内部 `detail::PreparedCurve`，每行移动接收已验证的控制点，计算一次斜率和切线，
随后生成原有 256 个 float 表项。控制点校验、区间查找、端点值、`1e-6` 最小跨度、
切线限制、double 表达式顺序和 float 量化保持一致。Shader 及曲线表格式无需改变。

本轮保留上游 pin、RAW 线性默认值、成像参数和现有 IIR 选择。
修改所在研究分支继续使用 alpha-5 / 125 版本号。

## 结果

所有编译和执行均在云电脑进行，测试只使用程序生成的数据。

| 检查 | 结果 |
| --- | --- |
| AOIDC、IBX 独立 CPU 对照 | 各 286 条曲线、130,611 次采样，double 结果与 float 表项均逐位一致 |
| 边界覆盖 | 2–256 控制点、反向、常量、平台、振荡、非完整端点、近重合点，以及每个结点两侧相邻浮点值 |
| AOIDC、IBX ASan/UBSan | 同一矩阵均通过，无检查关闭或错误抑制 |
| 五行测试批次动态分配 | 2,032 → 15；新测试计入五份控制点复制，生产路径移动已有控制点 |
| 100 次批次：AOIDC | 291.743 → 14.849 ms |
| 100 次批次：IBX | 85.462 → 5.337 ms |
| 实际 Vulkan 旧新对照 | 108 组全部通过，23,387,688 字节输出完整相同 |
| ARM64 Android | NDK 27.2.12479018，android-29，完整 `libspektralab_jni.so` 编译链接成功 |
| 异常输出回归 | 18 个异常拒绝，21 组 view、3 组 proof、30 MP 尾部定位、漏工作组负对照通过 |
| Vulkan 资源 | 56 个 SPIR-V 与上一版完整一致 |

耗时针对曲线表生成这一 CPU 阶段，来自各云电脑当次非 sanitizer 运行。
实际 Vulkan 对照使用云端软件 Vulkan，固定 split 数学路径，并分别设置正常、
每块 1 个和每块 7 个工作组。矩阵包含浮点／RGBA16 输入、六种曲线、
RGBA8 显示、旋转缩放后的 RGB16 输出及 proof 缩放。测试以 `cmp` 检查完整字节流，
CSV 哈希供复核。

## 持续验证

`tools/native/view_curve.cpp` 冻结旧曲线求值器，与生产头文件对照；
`tools/native/view_curve_gpu.cpp` 直接调用实际 `view::process`。
两者已加入 `engine-gpu` CI。CPU 位级比较失败、分配计数回退，或 Vulkan 用例失败，
均返回非零。

```bash
UP=/path/to/55-upstream BUILD_DIR=/outside-repo/curve-results \
  bash tools/native/run_view_curve.sh

UP=/path/to/55-upstream BUILD_DIR=/outside-repo/curve-asan SANITIZE=1 \
  bash tools/native/run_view_curve.sh

UP=/path/to/55-upstream B=/path/to/55-build \
BASELINE_UP=/path/to/54-upstream BASELINE_B=/path/to/54-build \
BUILD_DIR=/outside-repo/curve-gpu-results SPEKTRAFILM_VULKAN_FORCE_SPLIT=1 \
  bash tools/native/run_view_curve_gpu.sh
```

原始 CSV、sanitizer 日志及构建摘要见
[`tools/native/validation/view-curve-20261113`](../tools/native/validation/view-curve-20261113)。
生成的二进制、完整像素载荷与 JNI 库留在云端工作目录，未加入仓库。
