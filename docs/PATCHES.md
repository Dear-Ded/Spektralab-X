# 引擎补丁 / Engine patches

本仓库不改动上游源码本身。上游 [SpektraLab](https://github.com/JamesQiu2005/SpektraLab) 在 CI 里按固定提交下载（见 [`scripts/fetch-upstream.sh`](../scripts/fetch-upstream.sh)），然后按文件名顺序打上 [`patches/`](../patches/) 里的补丁。补丁集合的哈希写进上游目录的标记文件和 CI 缓存键，改动补丁会自动重新解压。

All changes to the upstream engine live here as unified diffs, applied in name order on top of the pinned upstream commit.

| 补丁 | 改动文件 | 解决的问题 |
|---|---|---|
| `0001-iir-chunked-launches.patch` | `pipeline/blur.cpp`、`spk_iir_df_acc.comp` | 上游把一整个平面的 IIR 高斯模糊作为一次 GPU 提交。在大图或降频状态下单次提交可能超过 2 秒，高通 kgsl 驱动会重置 GPU（`VK_ERROR_DEVICE_LOST`）。补丁把每个方向按每 512 行一批提交。每个线程负责完整的一行，分批不改变任何运算，输出逐位相同。 |
| `0002-dispatch-chunks-under-2-24.patch` | `gpu/vulkan_gpu.cpp`、全部计算着色器 | Adreno 驱动在单次计算超过约 2²⁴ 个线程时会写坏图像（顶部出现乱码条），而上游正好在这个位置把工作组折到第二个维度。补丁把每次 dispatch 拆成不超过 2²⁴ 线程的一维分块，起始下标通过 push constant 传给着色器，着色器的一维下标统一加上它。 |
| `0003-raw-half-size-budget.patch` | `io/raw_decoder.cpp`、`io/raw_decoder.hpp` | 拜耳传感器的像素超过工作尺寸 4 倍时，用 LibRaw 半尺寸模式（2×2 合并，不做去马赛克）解码，而不是先建一张全尺寸浮点图再缩小。5000 万像素 DNG 的解码内存约降到四分之一。预算按线程设置，默认 0 表示全尺寸，桌面版行为不变。 |
| `0004-dng-dual-illuminant-matrix.patch` | `io/raw_camera.hpp`、`io/raw_decoder.cpp` | DNG 通常带两组色彩矩阵（手机一般是标准光源 A 和 D65），LibRaw 只取日光那一组。补丁按 DNG 规范（Adobe DNG SDK 的 `dng_color_spec`）用实际白平衡迭代求出色温，在两组 ColorMatrix（含 CameraCalibration、AnalogBalance）之间按色温倒数插值，再交给 LibRaw 原有的转换流程。只改相机矩阵，不改白平衡倍率；白点不暖于日光那一组的标定光源时，用的就是 LibRaw 原来那组矩阵（仅有浮点舍入差异）。样片实测：Pixel 2 XL（4605 K）红通道平均变化 2.6/255，Leica Q（8530 K）不变。 |
| `0005-lock-print-lut-caches.patch` | `core/print_lut.hpp`、`core/print_lut.cpp`、`pipeline/engine.cpp` | 编辑器和导出各用一条引擎线程后，两个会话可能同时第一次用到同一种相纸。上游的 `PrintLutLibrary::get` 缓存和 `engine->print_lut_buffers`（相纸查找表、DI 取景表的 GPU 副本）在懒加载时都不加锁，并发写 `std::map`/`unordered_map` 是数据竞争。补丁给两处各加一把 mutex，只包住查表和首次填充，其他逻辑不变；表只上传一次。锁的顺序固定为先查表锁、后 GPU 的 dispatch 锁，不会反过来。单线程时行为和输出逐位不变。 |
| `0006-dng-opcode-gain-map.patch` | `io/dng_gain_map.hpp`（新增）、`io/raw_decoder.cpp` | LibRaw 0.22.2 会解析 DNG 的 OpcodeList2，但没有 DNG SDK 时一项都不执行，小米、Pixel 的 DNG 因此缺了镜头阴影校正，四角暗 3–4 倍。补丁在 unpack 之后、dcraw_process 之前把 GainMap 乘到拜耳数据上（先减黑电平、乘增益、再加回），opcode 列表出错时不改任何像素。没有 GainMap 的文件输出逐字节不变。OpcodeList3（WarpRectilinear 等）尚未处理。 |
| `0007-grain-size-and-unsharp-on-the-wire.patch` | `core/params.cpp`、`tests/parity_schema.py` | 上游 a46627d 的线上参数里没有颗粒尺寸和扫描锐化，应用无法调。补丁加三个仅原生字段：`grain_particle_area_um2`（默认 0.2，0.02–2）、`scanner_unsharp_sigma`（默认 0.7，0–5）、`scanner_unsharp_amount`（默认 0.7，0–3），超范围拒绝。不传或传默认值时输出与未打补丁逐字节相同。注意 preview_mode 档会把两者置零，快速预览里看不到。 |
| `0008-latch-device-lost.patch` | `gpu/vulkan_gpu.cpp` | 上游 PR #64 的思路：第一次提交返回 `VK_ERROR_DEVICE_LOST` 后用原子标志锁存，此后 `submit_sync` 和 `flush` 都立即失败，不再碰已丢失的 VkDevice（原来 `flush` 在丢失后仍返回成功，之后的分配、上传、读回会接着打到死设备上）。错误文案保留 `(VkResult -4)`，应用据此替换引擎一次（`EngineHost.recover`）。正常渲染不受影响，只多一次原子读。 |

## LibRaw 补丁

LibRaw 固定在 0.22.2（与上游宿主一致）。`app/src/main/cpp/CMakeLists.txt` 在构建目录里复制一份 0.22.2，按文件名顺序打上 [`patches/libraw/`](../patches/libraw/) 的补丁再编译；补丁的哈希写进标记文件，改动补丁会重新复制。不整体升到 0.22.3：它改了 X-Trans 去马赛克，富士 RAF 的输出会和上游不同，所以只回移植需要的修复。

| 补丁 | 来源 | 解决的问题 |
|---|---|---|
| `0001-openmp-wavelet-denoise-size` | LibRaw #842（a6268d4） | OpenMP 分支的 `wavelet_denoise` 用未初始化的 `size` 分配内存。0.22.3 已正式修复；我们仍在 0.22.2 上，暂时保留，升级时删除。 |
| `0002-ljpeg-idct-table-once` | 本项目 | `ljpeg_idct` 的余弦表第一次调用时才填，多线程有竞争；改为只初始化一次。0.22.3 和 master 仍未修。 |
| `0003-xtrans-serial-tiles` | LibRaw #845 | X-Trans 去马赛克的瓦片重叠，多线程输出随调度变化；保持串行，与单线程输出一致。 |
| `0010`–`0016` | thetechgeekko/Spektrafilm-android（GPL-3.0） | 损坏或恶意 RAW 的分配与工作量上限（TIFF 元数据、LJPEG、DNG tile）；补丁头注明原文件和 sha256。 |
| `0017-normalize-model-orig-on-stack` | LibRaw #876（1a8fd32，0.22.3） | `GetNormalizedModel()` 的 `orig` 是函数静态变量，编辑和导出两个线程同时打开 RAW 时可能串用对方的机型名、色彩矩阵和白电平。改成局部变量。单线程输出不变。 |
| `0018-lossy-dng-curve-init` | LibRaw #862（62f7098，0.22.3） | 有损（JPEG）DNG 带 OpcodeList2 但没有 PolynomialMap 时，`lossy_dng_load_raw` 的色调表没初始化，栈里的内容会进像素。改成先填默认曲线，再由 opcode 覆盖。只影响有损 DNG（LinearRaw）；补丁 0006 的 GainMap 只处理拜耳 DNG，两者不重叠。 |

## 写一个新补丁

```bash
bash scripts/fetch-upstream.sh upstream     # 上游 + 已有补丁
cd upstream && git init -q && git add -A && git commit -qm base
# 修改 engine/ 下的文件
git diff > ../patches/0005-short-name.patch
```

- 一个补丁只解决一个问题，文件名以序号开头。
- 在本文件的表格里写清楚动机和是否改变输出。
- 补丁必须用 LF 换行（`.gitattributes` 已设置）。
- 能提交给上游的修复，也请到上游提一份。
