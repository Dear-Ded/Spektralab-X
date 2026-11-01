# 引擎补丁 / Engine patches

本仓库不改动上游源码本身。上游 [SpektraLab](https://github.com/JamesQiu2005/SpektraLab) 在 CI 里按固定提交下载（见 [`scripts/fetch-upstream.sh`](../scripts/fetch-upstream.sh)），然后按文件名顺序打上 [`patches/`](../patches/) 里的补丁。补丁集合的哈希写进上游目录的标记文件和 CI 缓存键，改动补丁会自动重新解压。

All changes to the upstream engine live here as unified diffs, applied in name order on top of the pinned upstream commit.

| 补丁 | 改动文件 | 解决的问题 |
|---|---|---|
| `0001-iir-chunked-launches.patch` | `pipeline/blur.cpp`、`spk_iir_df_acc.comp` | 上游把一整个平面的 IIR 高斯模糊作为一次 GPU 提交。在大图或降频状态下单次提交可能超过 2 秒，高通 kgsl 驱动会重置 GPU（`VK_ERROR_DEVICE_LOST`）。补丁把每个方向按每 512 行一批提交。每个线程负责完整的一行，分批不改变任何运算，输出逐位相同。 |
| `0002-dispatch-chunks-under-2-24.patch` | `gpu/vulkan_gpu.cpp`、全部计算着色器 | Adreno 驱动在单次计算超过约 2²⁴ 个线程时会写坏图像（顶部出现乱码条），而上游正好在这个位置把工作组折到第二个维度。补丁把每次 dispatch 拆成不超过 2²⁴ 线程的一维分块，起始下标通过 push constant 传给着色器，着色器的一维下标统一加上它。 |
| `0003-raw-half-size-budget.patch` | `io/raw_decoder.cpp`、`io/raw_decoder.hpp` | 拜耳传感器的像素超过工作尺寸 4 倍时，用 LibRaw 半尺寸模式（2×2 合并，不做去马赛克）解码，而不是先建一张全尺寸浮点图再缩小。5000 万像素 DNG 的解码内存约降到四分之一。预算按线程设置，默认 0 表示全尺寸，桌面版行为不变。 |

## 写一个新补丁

```bash
bash scripts/fetch-upstream.sh upstream     # 上游 + 已有补丁
cd upstream && git init -q && git add -A && git commit -qm base
# 修改 engine/ 下的文件
git diff > ../patches/0004-short-name.patch
```

- 一个补丁只解决一个问题，文件名以序号开头。
- 在本文件的表格里写清楚动机和是否改变输出。
- 补丁必须用 LF 换行（`.gitattributes` 已设置）。
- 能提交给上游的修复，也请到上游提一份。
