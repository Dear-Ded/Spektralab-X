# 色彩对照：Spektralab-X 与上游 Linux 宿主逐像素比较

结论先说：在相同输入像素下，本应用的导出路径与上游自己的 `spektralab-host`（`export_frame`）
在 43 个用例里全部一致到 16 位量化误差以内（平均 ΔE00 0.000，p99.9 ≤ 0.006，最大 ≤ 0.015）；
屏幕路径与上游 `render_view` 一致到 8 位取整（平均 0.000，最大 ≤ 1.41，即个别像素差 1 个码值）。
Layer 2（调整）修了两处后也一致。手机 JPEG / Display P3 用例原先平均 0.20–0.36，原因是照片进入引擎前的
色彩转换与宿主不同；`openBitmap` 改成照宿主 `image_io.cpp` 先转线性 ProPhoto 后（第 4.3 节），
JPEG 导出平均 ≤ 0.016，p99.9 ≤ 0.072，最大 ≤ 0.24。

测量日期 2026-11-03，云主机 Debian 13、lavapipe（CPU Vulkan），上游固定提交加本仓库 `patches/`。

本页只比较安卓版与上游 Linux 宿主。安卓版与 macOS 版、Windows / Linux 版在各个环节上的差别和原因，见 [PLATFORMS.md](PLATFORMS.md)。

## 1. 方法

- **参考**：上游 `engine/host` 原样编译出的 `spektralab-host`（与桌面版同一个进程），
  通过它自己的协议调用 `open` → `render_view`（屏幕：矩阵加裁切，`display` 为 srgb / display-p3）
  和 `export_frame`（`tiff16`，CAM16 压进文件色域，亮度肩部关闭）。客户端照抄上游
  `engine/tests/host_smoke.py` 的 `Host` 类。
- **本应用**：`tools/parity/spk_android.cpp` 用应用自己的打开参数（`Edit.openingParams`：
  编码 ProPhoto 输出、`product_defaults`、`preview_long_edge` 1024）打开引擎，渲染 `full` 档，
  再经应用自己的 `output_transform.cpp` / `layer2.cpp`（`pack_rgba16` 导出，`pack_rgba8` 屏幕）离开 ProPhoto。
  这些文件是直接从 `app/src/main/cpp` 拷过去编译的，不是重写。
- **同一份输入**：两边拿到的是同一批字节。
  - `synth`：上游 `engine/tests/parity_metal_vulkan.py` 的 `make_input()`（色卡、曝光楔、色相扫、暗红、硬边、点光源），
    线性 sRGB 转线性 ProPhoto 后，宿主读未标记的 32 位浮点 TIFF（宿主按线性 ProPhoto 读），应用读同样的浮点。
  - RAW 场景：CC0 RAW（`docs/SAMPLES.md`）由 LibRaw 解码，再按 `openRaw` 的方式面积缩小到长边 1536，然后同上。
  - `rawfull_*`：RAW 原文件全尺寸，两边各自跑 LibRaw（宿主 `decode_raw_compatible`，应用同一函数），覆盖整条解码链。
  - `jpg_*`：宿主自己的 `write_image` 写出的 8 位 JPEG（sRGB，或带 Display P3 配置文件），
    宿主按 ICC 配置文件转换，应用按 `openBitmap` 的方式（`input_transform.cpp`）转成线性 ProPhoto 再交给引擎。
- **设置**：产品默认值；Portra 400 / Supra Endura，Velvia 100（直扫），Vision3 250D / 2383，Tri-X / Multigrade IV（滤镜 2）；
  颗粒和眩光关闭（随机项，逐像素比较会只测到噪声，上游 AGENTS 陷阱 1）。合成图关自动曝光，其余用应用默认的 `balanced` 自动曝光。
- **Layer 2**：`layer2_*` 三个用例同时给两边一组非中性调整（色温、色调、曝光、对比、亮度、饱和、高光、阴影、
  黑白点、暗角、四个色轮），应用用 Look.kt 的 50 个值，宿主用桌面 sidecar 的同义字段，对照上游
  `spk_view_grade_body.glsl` / `view_process.cpp`。
- **评分**：每对图像把编码值按各自色域解码到 XYZ（D65）、CIELAB，逐像素 CIEDE2000，报平均、p95、p99.9、最大。

## 2. 合格线

| 比较 | 合格线 | 实测最差 | 结论 |
|---|---|---|---|
| 导出 vs 宿主 export_frame（浮点 / RAW 输入） | 平均 ≤ 0.1，p99.9 ≤ 0.5 | 平均 0.000，p99.9 0.006，最大 0.015 | 通过 |
| 屏幕 vs 宿主 render_view | 同上 | 平均 0.000，p99.9 0.000，最大 1.41（1 个 8 位码值） | 通过 |
| JPEG / P3 导出 vs 宿主 | 平均 ≤ 0.05（原定 0.5） | 平均 0.016，p99.9 0.072，最大 0.24（修前 0.360 / 3.18 / 9.57） | 通过 |

## 3. 结果（ΔE00，平均 / p95 / p99.9 / 最大）

最后一列是**上游自己**的屏幕与导出之差（宿主 render_view 对宿主 export_frame），
本应用对应的“屏幕对导出”与它逐用例相同，说明那部分差别是上游的设计，不是移植的误差。

| 用例 | 导出 sRGB | 导出 P3 | 屏幕 sRGB | 屏幕 P3 | 上游自身：屏幕 vs 导出 |
|---|---|---|---|---|---|
| chart_cine2383 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 1.05 | 0.000 / 0.000 / 0.000 / 1.21 | 0.323 / 0.688 / 3.961 / 20.90 |
| chart_portra | 0.000 / 0.001 / 0.004 / 0.01 | 0.000 / 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 0.000 / 1.10 | 0.000 / 0.000 / 0.000 / 1.32 | 0.345 / 0.714 / 4.250 / 24.20 |
| chart_trix | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 0.000 / 1.03 | 0.000 / 0.000 / 0.000 / 1.40 | 0.088 / 0.165 / 0.947 / 1.20 |
| chart_velvia | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 1.09 | 0.000 / 0.000 / 0.000 / 1.38 | 0.317 / 0.641 / 5.130 / 22.52 |
| dunes_cine2383 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 1.17 | 0.000 / 0.000 / 0.000 / 1.15 | 0.312 / 0.616 / 3.959 / 17.27 |
| dunes_portra | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 1.13 | 0.000 / 0.000 / 0.000 / 1.26 | 0.333 / 0.669 / 4.562 / 15.70 |
| dunes_trix | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 0.000 / 1.03 | 0.000 / 0.000 / 0.000 / 1.40 | 0.087 / 0.164 / 0.962 / 1.18 |
| dunes_velvia | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 1.02 | 0.000 / 0.000 / 0.000 / 1.20 | 0.303 / 0.620 / 4.202 / 20.43 |
| fruit_cine2383 | 0.000 / 0.000 / 0.003 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 0.84 | 0.000 / 0.000 / 0.000 / 0.97 | 0.743 / 2.569 / 3.369 / 24.69 |
| fruit_portra | 0.000 / 0.001 / 0.003 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 0.87 | 0.000 / 0.000 / 0.000 / 1.08 | 0.720 / 2.700 / 3.052 / 25.48 |
| fruit_trix | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 0.000 / 1.03 | 0.000 / 0.000 / 0.000 / 1.40 | 0.090 / 0.169 / 0.977 / 1.20 |
| fruit_velvia | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 0.89 | 0.000 / 0.000 / 0.000 / 1.14 | 0.757 / 2.651 / 7.760 / 24.39 |
| jpg_fruit_p3_portra | 0.006 / 0.017 / 0.041 / 0.15 | 0.007 / 0.017 / 0.042 / 0.21 | 0.010 / 0.000 / 0.867 / 1.83 | 0.011 / 0.000 / 1.000 / 1.54 | 0.723 / 2.709 / 3.460 / 25.88 |
| jpg_fruit_p3_velvia | 0.007 / 0.020 / 0.072 / 0.24 | 0.008 / 0.021 / 0.072 / 0.21 | 0.011 / 0.000 / 0.876 / 1.59 | 0.012 / 0.000 / 1.033 / 1.91 | 0.759 / 2.660 / 9.062 / 25.09 |
| jpg_fruit_srgb_portra | 0.009 / 0.018 / 0.023 / 0.04 | 0.009 / 0.018 / 0.023 / 0.03 | 0.016 / 0.000 / 0.789 / 1.35 | 0.016 / 0.000 / 0.861 / 1.71 | 0.719 / 2.702 / 3.362 / 26.46 |
| jpg_fruit_srgb_velvia | 0.010 / 0.019 / 0.024 / 0.09 | 0.010 / 0.019 / 0.025 / 0.09 | 0.014 / 0.000 / 0.650 / 1.58 | 0.014 / 0.000 / 0.715 / 1.88 | 0.768 / 2.657 / 9.235 / 24.69 |
| jpg_lake_srgb_portra | 0.009 / 0.018 / 0.032 / 0.13 | 0.010 / 0.018 / 0.033 / 0.11 | 0.015 / 0.000 / 0.702 / 1.44 | 0.016 / 0.000 / 0.825 / 1.77 | 0.670 / 2.553 / 5.037 / 25.19 |
| jpg_lake_srgb_velvia | 0.009 / 0.017 / 0.027 / 0.11 | 0.009 / 0.017 / 0.028 / 0.12 | 0.013 / 0.000 / 0.633 / 1.61 | 0.013 / 0.000 / 0.760 / 1.63 | 0.590 / 2.115 / 6.091 / 24.98 |
| jpg_synth_p3_portra | 0.002 / 0.007 / 0.012 / 0.06 | 0.003 / 0.007 / 0.012 / 0.08 | 0.006 / 0.000 / 0.964 / 1.54 | 0.005 / 0.000 / 1.139 / 1.67 | 0.730 / 2.870 / 15.210 / 30.62 |
| jpg_synth_p3_velvia | 0.002 / 0.008 / 0.012 / 0.10 | 0.002 / 0.007 / 0.014 / 0.08 | 0.005 / 0.000 / 0.991 / 1.62 | 0.006 / 0.000 / 1.166 / 1.38 | 0.789 / 3.423 / 20.433 / 30.45 |
| jpg_synth_srgb_portra | 0.015 / 0.025 / 0.034 / 0.07 | 0.016 / 0.026 / 0.034 / 0.09 | 0.023 / 0.000 / 0.981 / 1.55 | 0.021 / 0.000 / 1.149 / 1.89 | 0.729 / 2.871 / 14.555 / 30.62 |
| jpg_synth_srgb_velvia | 0.015 / 0.024 / 0.029 / 0.06 | 0.015 / 0.025 / 0.029 / 0.06 | 0.019 / 0.000 / 0.994 / 1.69 | 0.017 / 0.000 / 1.094 / 1.83 | 0.788 / 3.446 / 20.433 / 30.44 |
| lake_cine2383 | 0.000 / 0.000 / 0.003 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 0.86 | 0.000 / 0.000 / 0.000 / 1.15 | 0.575 / 2.071 / 4.574 / 23.41 |
| lake_portra | 0.000 / 0.000 / 0.003 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 0.97 | 0.000 / 0.000 / 0.000 / 1.15 | 0.660 / 2.519 / 4.982 / 25.96 |
| lake_trix | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 0.000 / 1.03 | 0.000 / 0.000 / 0.000 / 1.40 | 0.090 / 0.164 / 1.010 / 1.19 |
| lake_velvia | 0.000 / 0.000 / 0.003 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 0.85 | 0.000 / 0.000 / 0.000 / 1.23 | 0.570 / 2.052 / 5.858 / 24.08 |
| layer2_dunes_portra | 0.000 / 0.001 / 0.005 / 0.01 | 0.000 / 0.001 / 0.006 / 0.01 | 0.000 / 0.000 / 0.000 / 0.96 | 0.000 / 0.000 / 0.000 / 1.18 | 1.395 / 4.927 / 17.079 / 24.48 |
| layer2_fruit_velvia | 0.000 / 0.001 / 0.005 / 0.01 | 0.000 / 0.001 / 0.005 / 0.01 | 0.000 / 0.000 / 0.000 / 0.95 | 0.000 / 0.000 / 0.000 / 1.13 | 4.246 / 14.606 / 24.466 / 25.38 |
| layer2_synth_portra | 0.000 / 0.001 / 0.006 / 0.01 | 0.000 / 0.001 / 0.006 / 0.01 | 0.000 / 0.000 / 0.000 / 0.90 | 0.000 / 0.000 / 0.000 / 1.04 | 1.678 / 8.971 / 20.016 / 30.53 |
| rawfull_iphone_portra | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 0.000 / 1.19 | 0.000 / 0.000 / 0.000 / 1.41 | 0.313 / 0.622 / 1.309 / 3.10 |
| rawfull_sl1_portra | 0.000 / 0.000 / 0.003 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 1.14 | 0.000 / 0.000 / 0.000 / 1.28 | 0.555 / 1.650 / 2.349 / 5.82 |
| skyline_cine2383 | 0.000 / 0.001 / 0.003 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 1.05 | 0.000 / 0.000 / 0.000 / 1.13 | 0.605 / 1.848 / 4.868 / 19.18 |
| skyline_portra | 0.000 / 0.001 / 0.003 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 0.95 | 0.000 / 0.000 / 0.000 / 1.24 | 0.679 / 2.118 / 4.159 / 19.72 |
| skyline_trix | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 0.000 / 1.03 | 0.000 / 0.000 / 0.000 / 1.40 | 0.086 / 0.161 / 0.940 / 1.18 |
| skyline_velvia | 0.000 / 0.001 / 0.003 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 0.96 | 0.000 / 0.000 / 0.000 / 1.14 | 0.651 / 2.072 / 9.547 / 21.50 |
| synth_cine2383 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 0.000 / 1.00 | 0.000 / 0.000 / 0.000 / 1.12 | 0.783 / 2.944 / 14.463 / 27.61 |
| synth_portra | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 0.000 / 1.00 | 0.000 / 0.000 / 0.000 / 1.18 | 0.734 / 2.877 / 8.846 / 28.36 |
| synth_trix | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 0.000 / 1.03 | 0.000 / 0.000 / 0.000 / 1.40 | 0.092 / 0.169 / 0.992 / 1.20 |
| synth_velvia | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 0.000 / 1.19 | 0.000 / 0.000 / 0.000 / 1.33 | 0.886 / 3.763 / 14.181 / 28.66 |
| temple_cine2383 | 0.000 / 0.000 / 0.003 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 0.85 | 0.000 / 0.000 / 0.000 / 1.24 | 0.393 / 1.226 / 4.320 / 26.53 |
| temple_portra | 0.000 / 0.000 / 0.003 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 1.05 | 0.000 / 0.000 / 0.000 / 1.14 | 0.412 / 1.256 / 4.962 / 27.06 |
| temple_trix | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 0.000 / 1.05 | 0.000 / 0.000 / 0.000 / 1.40 | 0.089 / 0.158 / 1.010 / 1.20 |
| temple_velvia | 0.000 / 0.000 / 0.005 / 0.01 | 0.000 / 0.000 / 0.004 / 0.01 | 0.000 / 0.000 / 0.000 / 1.06 | 0.000 / 0.000 / 0.000 / 1.08 | 0.442 / 1.332 / 4.662 / 26.76 |

## 4. 发现与处理

### 4.1 Layer 2 两处与上游不一致（已修，`layer2.cpp`、`output_transform.cpp`）

1. 色轮“明亮度”系数：本应用乘 0.3，上游 `view_process.cpp`（`u[28..31]`）和 `Adjustments.swift`（`cbLum`）都是 0.25。改为 0.25。
2. 上游的调整在进入输出转换前把结果夹到 0..1 并量化到 16 位（`spk_view_grade_body.glsl` 末尾），
   本应用把超出范围的值直接交给 CAM16，导致高光、饱和处走到不同的压缩位置。
   现在 `convert()` 在调用输出转换前同样夹取并按 `floor(x*65535+0.5)` 量化，再走 `apply16`。

修前后（`layer2_synth_portra`）：

| | 导出 sRGB | 导出 P3 | 屏幕 sRGB | 屏幕 P3 |
|---|---|---|---|---|
| 修前 | 0.200 / 0.827 / 3.695 / 13.03 | 0.213 / 0.907 / 4.628 / 14.77 | 0.253 / 0.737 / 0.976 / 1.36 | 0.296 / 0.883 / 1.103 / 1.82 |
| 修后 | 0.000 / 0.001 / 0.006 / 0.01 | 0.000 / 0.001 / 0.006 / 0.01 | 0.000 / 0.000 / 0.000 / 0.90 | 0.000 / 0.000 / 0.000 / 1.04 |

没有调整时（Layer 2 为中性）走的代码与修前完全相同，所以其余用例不受影响。

### 4.2 路线图里“最大约 22”的离群点：上游自己的行为，不是本应用的错误

`docs/ROADMAP.md` M1/M2 记录的最大 ~22 是“屏幕路径对导出路径”。逐像素看（`grade --where`）：
全部是打印结果里几乎纯蓝的像素，ProPhoto 编码约 (0, 0, 0.39)，在 sRGB 之外很远。
屏幕路径矩阵加裁切得到 sRGB (0, 2, 127)，导出路径的 CAM16 色度压缩把它压到接近黑 (0, 0.1, 0.2)，
两者 ΔE00 28.4（synth）、24.2（chart，同样是蓝色块）。上游宿主自己的 `render_view` 与 `export_frame`
在同一批像素上差得一模一样（上表最后一列），本应用的屏幕与上游屏幕、导出与上游导出各自一致。
这是上游“屏幕不做色域压缩、导出做”的设计（`HOST-PROTOCOL.md` §3：render_view 不是导出软打样），
不改。

### 4.3 手机 JPEG / Display P3：进入引擎前的色彩转换（已修，`openBitmap`、`openBitmapHdr`）

修前 JPEG 用例平均 0.20–0.36，p99.9 最高 3.18，最大 9.57。原因：应用的 `openBitmap` 把 sRGB / Display P3
码值连同 `input_color_space: "sRGB"`（或 `"Display P3"`）交给引擎，引擎用 CAT02 把 D65 适应到 ProPhoto 的 D50；
上游宿主（和桌面版）在解码时就按 ICC 配置文件的 Bradford D50 色度点转成线性 ProPhoto
（`image_io.cpp` 的 `encoded_to_linear_prophoto`），再以 `input_color_space: "ProPhoto RGB"` 打开。
两个 sRGB→ProPhoto 矩阵每项差约 0.003–0.005，饱和色上就是修前的差别。

修法：
- 新文件 `app/src/main/cpp/input_transform.{hpp,cpp}`：宿主的 `Trc`、`RgbSpace`、`srgb_space`、
  `display_p3_space`、`linear_prophoto_space`、`invert3`、`mul3` 在配置时从上游 `engine/host/image_io.{hpp,cpp}`
  原样截取（`CMakeLists.txt` 生成 `upstream_input_math.inc`，按标记截取，上游改动时配置直接失败），
  矩阵与 256 级曲线表的算法与宿主相同。
- `spektralab_jni.cpp`：`openBitmap` 与 `openBitmapHdr` 多一个 `space` 参数，像素先按配置文件曲线解码，
  高光恢复（`HighlightExpand`）或 Ultra HDR 增益图在照片自己的原色里做，最后乘矩阵到线性 ProPhoto；
  `make_before` 因此按 RAW 的方式（线性 ProPhoto → sRGB）生成前后对比图，胶片卡片（`small_rgb`）也是线性 ProPhoto。
- Kotlin：`openPhotoSession` 总是以 (`"ProPhoto RGB"`, linear) 请求打开参数，`EditorViewModel` 记下同样的输入空间，
  胶片卡片因此与正式会话同一输入空间。

修后（第 3 节表内 `jpg_*` 各行即修后数字）：JPEG 导出平均 0.002–0.016，p99.9 ≤ 0.072，最大 ≤ 0.24；
屏幕平均 ≤ 0.023，最大 ≤ 1.91（8 位取整）。RAW、合成和 Layer 2 用例与修前相同。
修前的路径保留为 `spk_android` 的 `srgb_old` / `p3_old` 输入，可用来复现修前数字。
高光恢复（`headroom_stops` > 0）与 Ultra HDR 增益图路径只做了代码上的同样处理，宿主没有对应功能，未做像素对照。

### 4.4 fma 探测：GPU 的 fma 不精确时，引擎会怎样（不需要补丁）

问题：有报告说 Adreno / Mali 会把 GLSL 的 `fma()` 拆成普通的乘法加加法，引擎的启动探测因此报告 “unfused”。
要确认这会不会让引擎拒绝启动，或者悄悄降低精度。

机制（上游 `engine/src/gpu/vulkan_gpu.cpp` 第 313–410 行）：`spk_math_probe` 把 GPU 上 `fma(a, b, -a*b)` 的残差
和 binary64 的精确残差逐个比较，不留容差。不一致时，再测 Dekker 拆分乘积（`spk_split_probe`，高低两个字都要精确），
通过就设 `fused_fma_ = 0` 并正常启动。这个值通过特化常量 `SPK_FUSED_FMA` 传给仅有的两个用到 `two_prod` 的
着色器：`spk_iir_df_acc`（光晕模糊）和 `spk_geometry_resample_df`（旋转、重采样），两者都改用 `spk_split_product`。
只有拆分乘积也不精确、拆分探测文件缺失或设置了 `SPEKTRAFILM_VULKAN_STRICT_FMA=1` 时才拒绝启动。

验证（`tools/parity/fma_check.sh`，云主机 lavapipe，2026-11-03）：

1. **启动探测**：用上游自己的故障注入测试（`engine/tests/gpu_vulkan_math_gate.cpp` 与 `math_gate_fault.comp`，
   即上游 `spk_math_gate_*` 测试的同一套夹具）链接打过本仓库补丁的引擎，7 种情况全部符合预期：
   原样、残差全 0、残差混杂三种都以拆分乘积启动；拆分乘积错误、拆分探测缺失、STRICT 三种都拒绝启动，且第二次调用仍然拒绝。
   **意外发现**：原样的 lavapipe（LLVM 19.1.7）本身就报告 fma 不精确（第 0 个残差得 0，应为 7.45e-11），
   走的是拆分乘积。也就是说，本页第 3 节和此前全部对照数字，走的本来就是 Adreno 将会走的那条路。
2. **画面**：4 个场景（合成图 Portra、水果 CineStill 800T + 2383、湖景 Velvia 旋转 7.5°、沙丘 Tri-X），打开光晕，
   关闭颗粒和眩光。原样与换入“残差全 0”探测各渲染一次，印片和导出、屏幕全部逐位相同（16 位码值最大差 0）。
3. **探测防住了什么**：换入一个“说谎”的探测（返回精确残差，让引擎以为 fma 精确），两个着色器就在不精确的
   `fma()` 上做 `two_prod`，丢掉低位字。与拆分乘积路径相比：

| 场景 | 印片 16 位码值最大差 / 平均差 | 导出 sRGB ΔE00 平均 / p99.9 / 最大 | 屏幕 sRGB ΔE00 最大 |
|---|---|---|---|
| synth_portra | 1 / 0.022 | 0.0003 / 0.010 / 0.025 | 1.04 |
| fruit_cine800t | 5 / 0.060 | 0.0006 / 0.010 / 0.023 | 1.22 |
| lake_velvia_rot | 7 / 0.099 | 0.0007 / 0.010 / 0.019 | 1.50 |
| dunes_trix | 1 / 0.023 | 0.0003 / 0.011 / 0.024 | 1.67 |

   即便探测失效，代价也只有导出最大 ΔE00 约 0.025，屏幕偶尔差 1 个 8 位码值，远低于可察觉的 1。
   探测是对的，而且它保护的精度在这几个场景里本来就很小。

结论：不需要引擎补丁。fma 不精确的 GPU 上，引擎会正常启动，并走 lavapipe 上已逐像素验证过的拆分乘积路径。
还没做的：真机上从设备信息页读 `math_mode`，确认 Adreno 报告 “exact Dekker split product”（或 “fused fma”）。

### 4.5 颗粒、眩光、光晕全开：按统计量比较

前面的用例都关了颗粒和眩光（随机项，逐像素比较只会测到噪声）；光晕是确定的，一直开着（引擎默认）。
`stoch_*` 用例把三者全打开，按上游 `engine/tests/parity_metal_vulkan.py` 的 `grain_*` 用例的办法比较统计量：
32 px 方块的均值差中位数、标准差相对差中位数、全图均值差（8 位码值），再加一项我们自己的：
细节（亮度减 9 px 方框均值）径向功率谱的 12 个频段，每段功率与宿主的相对差。

合格线（`host_parity.py` 的 `STATS_BARS`）：方块均值差中位数 ≤ 1.0、方块标准差相对差中位数 ≤ 0.15、
全图均值差 ≤ 0.5（这三条是上游的），功率谱每段相对差 ≤ 0.15。
`stoch_ctrl_synth_portra` 是反向对照：本应用一侧把 `grain_amount` 改成 1.3，**必须**不过线，用来证明这些线能抓住颗粒强度的变化。

结果（run3，云主机 lavapipe，2026-11-04，导出 sRGB）：

| 用例 | 方块均值差中位数 | 方块标准差相对差 | 全图均值差 | 功率谱最大相对差 | 标准差（本应用 / 宿主） | 结论 |
|---|---|---|---|---|---|---|
| stoch_synth_portra | 0.000 | 0.000 | 0.000 | 0.000 | 15.51 / 15.51 | 通过 |
| stoch_dunes_portra | 0.000 | 0.000 | 0.000 | 0.000 | 15.45 / 15.45 | 通过 |
| stoch_fruit_cine2383 | 0.000 | 0.000 | 0.000 | 0.000 | 14.40 / 14.40 | 通过 |
| stoch_lake_trix | 0.000 | 0.000 | 0.000 | 0.000 | 27.69 / 27.69 | 通过 |
| stoch_jpg_fruit_srgb_portra | 0.010 | 0.001 | 0.025 | 0.000 | 14.87 / 14.87 | 通过 |
| stoch_ctrl_synth_portra（反向对照） | 0.120 | **0.234** | 0.071 | **0.621** | 15.57 / 15.51 | 按预期不过线 |

**发现**：四个浮点 / RAW 输入的用例，颗粒和眩光开着也逐像素一致（导出 ΔE00 平均 0.000，最大 ≤ 0.007，和关闭时一样）。
原因：引擎的随机种子每个会话从同一个常数起步（上游 `pipeline/pipeline.hpp:839` `rng_state_` 初值，
`pipeline.cpp:84` 的 splitmix64 `fresh_seed`），新会话第一次渲染得到的就是同一份颗粒和眩光。所以统计量全为 0，
这比统计量接近更强。同一会话里再渲染一次会换一份颗粒，那时只有统计量还能比，所以统计比较留着，
并由反向对照证明它管用（标准差差 23 %、功率谱差 62 %，都远超 15 % 的线）。
JPEG 用例的逐像素差（导出平均 0.065，p99.9 2.6）比颗粒关闭时（0.009）大：输入上 0.01 级的差别经过颗粒放大，
但颗粒的统计量与宿主一致（标准差相对差 0.001）。

### 4.6 色调曲线与第一次完整运行（main `cc26be2`，2026-11-04）

`main` 加入了色调曲线（`Curves.kt`，Layer 2 的 `kCurves` 尾部，`layer2.cpp` 照上游 `view_process.cpp` 的
`curve()` 采样成 5 × 256 表）、Look 格式 2、引擎补丁 0005（打印 LUT 缓存加锁）和导出线程之后，
`scripts/parity.sh` 第一次从零完整运行：重新拉取上游并打 0001–0005 补丁，构建宿主和应用一侧，渲染并评分。

- 新增 `layer2curve_synth_portra`、`layer2curve_dunes_portra`：第 1 节那组非中性 Layer 2 调整，再叠加一组明显的曲线
  （RGB、明度、红、蓝四条）。应用一侧按 `kCurves` 尾部传入，宿主一侧按桌面 sidecar 的 `curves.<通道>.points` 传入。
  屏幕与导出各自和宿主比较，所以预览和导出用的是同一条曲线，而且和上游的曲线一致。
- 结果：51 个用例全部通过，0 个不合格（`run/grade.json`）。

| 组 | 用例数 | 导出 sRGB 平均 / p99.9 / 最大 | 导出 P3 | 屏幕 sRGB 最大 |
|---|---|---|---|---|
| 合成图 | 4 | 0.000 / 0.004 / 0.007 | 0.000 / 0.005 / 0.008 | 1.19 |
| RAW（缩小） | 24 | 0.000 / 0.005 / 0.007 | 0.000 / 0.005 / 0.009 | 1.17 |
| RAW（全尺寸，含解码） | 2 | 0.000 / 0.004 / 0.007 | 0.000 / 0.005 / 0.008 | 1.19 |
| JPEG / P3 | 10 | 0.015 / 0.072 / 0.24 | 0.016 / 0.072 / 0.21 | 1.83 |
| Layer 2 | 3 | 0.000 / 0.006 / 0.015 | 0.000 / 0.006 / 0.015 | 0.96 |
| Layer 2 + 色调曲线 | 2 | 0.000 / 0.006 / 0.015 | 0.000 / 0.006 / 0.016 | 1.04 |
| 颗粒 / 眩光 / 光晕（统计量） | 5 + 1 对照 | 第 4.5 节的线全部通过；对照按预期不过线 | | |

与第 3、4.5 节的数字逐组相同：默认值下补丁 0005 和曲线阶段都不改变像素（曲线全为恒等时 `curve_table` 返回空表，
这一阶段被跳过）。fma 探测检查（第 4.4 节）7 种情况也与之前相同。

### 4.7 DNG 增益图、LUT 接口与补丁 0001 改为 256 行（main `063a03f`，2026-11-04）

`main` 又加入了 Layer 2 的 LUT 接口（`ColourTable` / `set_lut`，`Look.lut`）、引擎补丁 0006（DNG OpcodeList2 增益图）
和 0007（颗粒大小与扫描锐化上线）。在 `063a03f` 上用 `scripts/parity.sh` 完整运行：52 个用例，0 个不合格。

- **增益图 DNG**：样片里只有 Pixel 2 XL 的 DNG 带 OpcodeList2 GainMap（4 个 opcode 9；Leica Q、Leica M10、Ricoh GR III
  的 OpcodeList2 为空）。新增 `rawfull_pixel_portra`，原文件全尺寸，两边各自用 LibRaw 解码，所以两边都走 0006：
  导出 sRGB 平均 0.0001 / p99.9 0.004 / 最大 0.0075，16 位码值最大差 0.004；屏幕最大 1.21（1 个 8 位码值）。
  宿主一侧确实打了 0006：补丁标记从 `+393763879b61225c` 变为 `+1a7707473ac1c2f2`，`engine/src/io/dng_gain_map.hpp`
  存在，`spektralab-host` 的构建时间晚于该文件。
- **LUT 关闭时 Layer 2 不变**：`spk_android` 不调用 `set_lut`，`lut_` 为空，`apply()` 走原来的路径。
  3 个 `layer2_*` 与 2 个 `layer2curve_*` 用例的数字与第 4.6 节逐项相同（导出最大 0.015 / 0.016）。
- 其余各组也与第 4.6 节相同。

**补丁 0001 从每批 512 行改为 256 行。** 衍生项目 SAINTTaiYi/SpektraLab-Android 的 fa199b6 改成 256 后，44.7 MP 从设备丢失
变成能跑完。验证方法：同一份代码各跑一次 512 和 256，每个用例的每个输出记下 SHA-256（`hashes.json`），
用 `host_parity.py same` 比较。第一次比较 468 个输出中 432 个相同，不同的 36 个全部属于 4 个 `temple_*` 用例
（Fujifilm X100F，X-Trans）。原因在输入：两次运行各自重新解码了 `temple.f32`，两份在 4 723 200 个值中有 465 个不同，
最大差 0.00099。用同一份 `temple.f32` 以 512 行重跑这 4 个用例，结果与 256 行逐字节相同。
所以 52 个用例的 468 个输出在 512 与 256 之间全部逐字节相同，补丁已改为 256。

**附带发现**：云主机构建（LibRaw 打开 OpenMP、未打 `patches/libraw/`）解码同一个 X-Trans RAF 两次，结果不完全相同
〔推断：X-Trans 去马赛克的多线程分块；安卓一侧打了 `patches/libraw/0003-xtrans-serial-tiles`，未在真机上验证是否确定〕。
差值最大 0.00099（线性），远低于 16 位一个码值的可见量级，但它会让逐字节比较在 X-Trans 用例上误报，
比较这类用例时要固定同一份输入。

## 5. 复现

**一键脚本**：`scripts/parity.sh [工作目录]`（默认 `build/parity`）。发版前在云主机手动跑一次，不进常规 CI（约 40–60 分钟、1.5 GB）。
本机已有 CC0 样片时用 `SAMPLES_DIR=<目录>`，校验一致的文件直接链接，不再下载（raw.pixls.us 偶尔会无报错地提前断开，脚本会续传重试）。在 Linux x64 上从零开始：按固定提交下载上游并打补丁，
用上游自己的脚本装三组编解码器，编译 `spektralab-host` 和本应用一侧（`tools/parity/build.sh`），按 `docs/SAMPLES.md`
下载 CC0 RAW 并校验 SHA-256，渲染全部用例（每个用例渲染完立即评分并删掉像素文件），按第 2 节和第 4.5 节的线检查，
有不过线的就以非零状态退出。`THREADS=2` 控制线程数，`ONLY="stoch_ jpg_"` 只跑部分用例，`FMA=1` 另跑第 4.4 节的检查。
约需 1.5 GB 磁盘、4 核上 40–60 分钟。要放进 CI 的可选 job，调用这一个脚本即可（需要 lavapipe：`mesa-vulkan-drivers libvulkan1`）。

`host_parity.py render --prune` 边渲染边评分，只留每个用例的 `grade.json`；`grade --check` 按合格线检查，不过线时退出码为 1。

下面是逐步的手动做法：

云主机上（目录可改）：

云主机上（目录可改）：

```bash
# 1. 依赖：NASM，Meson 1.9.2 与 Ninja 1.13（venv），上游的三组编解码器
U=/data/home/spk/up; P=/data/home/spk/parity2
python3 -m venv $P/venv && $P/venv/bin/pip install meson==1.9.2 'ninja>=1.13,<1.14'
export PATH=$P/venv/bin:$PATH
bash $U/engine/setup-codecs.sh $P/deps/export-codecs
python $U/engine/setup-input-codecs.py --sources $P/deps/input-codecs --build $P/b-input --install $P/deps/input-codecs/install-linux --generator Ninja --jobs 2
python $U/engine/setup-lens-codecs.py --sources $P/deps/lens-codecs --build $P/b-lens --install $P/deps/lens-codecs/install-linux --generator Ninja --jobs 2
# 2. 上游宿主
cmake -S $U -B $P/build-host -G Ninja -DCMAKE_BUILD_TYPE=Release -DSPEKTRALAB_BUILD_NATIVE_RAW=ON -DSPEKTRALAB_BUILD_HOST=ON   -DSPEKTRALAB_REQUIRE_VULKAN=ON -DSPEKTRALAB_STATIC_RUNTIME=ON -DSPEKTRALAB_DEPS_DIR=$U/build/deps   -DSPEKTRALAB_CODECS_DIR=$P/deps/export-codecs -DSPEKTRALAB_INPUT_CODEC_PREFIX=$P/deps/input-codecs/install-linux   -DSPEKTRALAB_LENS_PREFIX=$P/deps/lens-codecs/install-linux -DBUILD_TESTING=OFF
ninja -C $P/build-host spektralab-host
# 3. 本应用一侧：把 tools/parity/spk_android.cpp、host_parity.py 和 app/src/main/cpp 的
#    output_transform.*、input_transform.*、layer2.*、resize.* 放进 $P/src（两个 .inc 由 build.sh 从上游生成）
UP=$U B=$P/build-host SRC=$P/src bash tools/parity/build.sh
# 4. 渲染（约 5 分钟，45 个用例）与评分
cd $P && python3 src/host_parity.py render --host build-host/engine/host/spektralab-host   --resources build-host/engine/resources --android src/spk_android --up $U --raw /data/home/spk/samples/raw --out run1 --threads 2
python3 src/host_parity.py grade run1 --table run1/table.md
python3 src/host_parity.py grade run1 --where synth_portra/host_view_srgb   # 某一对的最差像素
```

`--only <名字片段>` 只跑部分用例。JPEG 输入转换的诊断：对某个 `jpg_*` 目录，用
`spk_android <resources> srgb_old <jpg> <dir> '<params>'`（P3 用 `p3_old`，修前的应用路径）重写应用一侧，再 `grade`。
旧的 `run.sh` / `grade.py` / `spk_parity.cpp` 只比较本应用自己的几条路径，保留作历史对照。

磁盘有限时可按 `--only` 分批渲染、评分后删除用例目录（一次完整渲染约 4 GB）。
`build.sh` 从上游 `output.cpp`、`image_io.*` 生成 `upstream_output_math.inc` 和 `upstream_input_math.inc`，标记与 `CMakeLists.txt` 相同。

fma 探测检查（第 4.4 节，约 2 分钟）：

```bash
cd $P && UP=$U B=$P/build-host A=src/spk_android R=build-host/engine/resources IN=run1 OUT=fma \
    LP_NUM_THREADS=2 bash src/fma_check.sh
```

## 6. 分条执行（striped）与整幅渲染

目的：确认上游的分条执行（`settings.striped`、`strip_rows`）出图和整幅渲染逐位一致，作为“极致渲染”的前提。

方法：`tools/stability/striped.cpp`，链接打了 0001–0005 补丁的上游引擎（云主机 lavapipe）。RAW 用上游 `decode_raw_compatible` 解一次；每次渲染都新建引擎和会话，渲染 `full` 档，对可见的 RGBA16 像素取 FNV-1a 哈希。颗粒和光晕关闭（两者是随机的）。参数：Portra 400 / Supra Endura，自动曝光 balanced。

| 输入 | 条高（行） | 哈希 | 耗时 | GPU 池峰值 |
|---|---|---|---|---|
| Leica Q，缩到 1024×683 | 整幅 | `8b966d8d34223f17` | 509 ms | 43 MB |
| | 256 / 97 / 7 | 相同 | 540 / 555 / 670 ms | 43 / 41 / 40 MB |
| Leica Q，原始 6016×4016（24.2 MP） | 整幅 | `895819ad50c20496` | 12.8 s | 1106 MB |
| | 1024 / 509 | 相同 | 13.8 / 13.8 s | 1126 / 1126 MB |

结论：
- 所有条高（含除不尽的 97、509 和极窄的 7 行）都和整幅逐位一致。
- **峰值内存没有下降。** 这个版本的上游里，`choose_strip_height` 不看内存预算，每段可分条的计算结束后结果仍拷回整幅大小的平面（`band_into`），所以整幅平面照样要分配。分条只把一次长提交拆成多次短提交，能缓解 GPU 看门狗，不能突破单缓冲上限（Adreno 7xx 为 128 MiB，24 MP 一个 RGB float 平面就要 290 MB）。
- 因此手机上的“原始分辨率”不能只靠打开 `striped` 实现，需要应用层分块（见路线图 M5 的衍生项目借鉴）。

复现：

```bash
c++ -std=c++20 -O2 -fopenmp -I$UP/engine/include -I$UP/engine/src -I$SRC tools/stability/striped.cpp $SRC/resize.cpp \
    -Wl,--start-group $B/engine/lib*.a -Wl,--end-group -lpthread -ldl -o striped
./striped $B/engine/resources '<RAW>' 0 '{"film_stock":"kodak_portra_400","print_stock":"kodak_supra_endura","grain_active":false,"glare_active":false,"auto_exposure":true,"auto_exposure_method":"balanced"}' 1024 509
```

## 7. 极致渲染的 4:4:4 JPEG（libjpeg-turbo 3.2.0）

极致渲染的 JPEG 改由应用自带的 libjpeg-turbo 编码（`jpeg444.cpp`：质量 98、4:4:4、优化霍夫曼表、嵌入 ICC），普通导出仍用 `Bitmap.compress`（4:2:0）。在云主机上用同一张渲染图对比（2026-11-04）：

- 输入：Leica Q DNG，原始 6016×4016（24.2 MP），Portra 400 / Supra Endura，颗粒开，自动曝光，引擎直接输出 sRGB，8 位。
- 应用编码器：主机上同一份 `jpeg444.cpp` 加同版本 libjpeg-turbo（主机无 NASM，关了 SIMD；手机上是 NEON，输出按 libjpeg-turbo 的设计应逐位相同，未在手机上核对）。

| 编码 | 文件大小 | 与源像素的差（8 位，平均 / 最大） |
|---|---|---|
| 应用 4:4:4 q98 | 32314 KiB | 1.324 / 12 |
| PIL 4:4:4 q98 优化 | 32314 KiB | 1.324 / 12 |
| PIL 4:2:0 q98（相当于 `Bitmap.compress` 的采样） | 18070 KiB | 4.428 / 77 |

- 应用编码器与 PIL 4:4:4 解码后逐像素完全相同（100% 像素差为 0），采样因子都是 1×1，ICC 588 字节已嵌入。
- 4:4:4 比 4:2:0 大约 79%，与源的平均误差降到约 30%，最大误差从 77 降到 12（4:2:0 的误差集中在颗粒和色彩边缘）。

复现（主机上，`J` 为 libjpeg-turbo 安装目录）：

```bash
c++ -std=c++20 -O2 -I$J/include -Iapp/src/main/cpp tools/stability/jpeg444_main.cpp app/src/main/cpp/jpeg444.cpp $J/lib/libjpeg.a -o jpeg444
./render8 <resources> '<RAW>' '<params>' lake.rgb     # tools/stability/render8.cpp
./jpeg444 lake.rgb app444.jpg 98 <resources>/io/sRGB.icc
python3 tools/stability/jpeg444_compare.py lake.rgb app444.jpg .
```

## 8. 「无」（分支 no-film，2026-11-05）

测量在 aoidc 云主机（Ubuntu 22.04，llvmpipe / lavapipe），上游固定提交 a46627d 加 `patches/`，`tools/parity/spk_android` 用 `passthrough.hpp`（与 JNI 同一份代码）。

### 8.1 选了胶片时输出不变

main 的 `spk_android` 用旧参数，本分支的 `spk_android` 用新参数（多出 `x_no_film:false`、`digital_intermediate:false`）。
4 个输入（dunes、fruit 线性 ProPhoto；fruit sRGB JPEG；synth P3 JPEG）× 4 组胶片相纸（Portra 400 + Supra Endura、Velvia 100 直接扫描、Tri-X 400 + Multigrade IV、Vision3 250D + 2383）× 颗粒眩光开 / 关，
每例 5 个文件（引擎 16 位印相、sRGB / P3 导出、sRGB / P3 屏幕）：**160 个文件全部逐字节相同，0 个不同**。

### 8.2 「无」与原图（ΔE00，平均 / p99.9 / 最大）

源图是 JPEG 本身（PIL 解码），在文件自己的色彩空间里比较。

| 输入 | 屏幕 | 屏幕 8 位码值完全相同 | 导出 |
|---|---|---|---|
| fruit sRGB | 0.058 / 1.01 / 2.49 | 86.0 %，最大差 5 | 0.262 / 2.01 / 2.53 |
| synth sRGB（色卡） | 0.071 / 0.86 / 2.12 | 81.0 %，最大差 29 | 0.491 / 5.04 / 5.41 |
| lake sRGB | 0.075 / 0.97 / 2.59 | 78.5 %，最大差 14 | 0.230 / 2.57 / 7.20 |
| fruit P3 | 0.061 / 1.18 / 2.77 | 86.0 %，最大差 3 | 0.197 / 1.30 / 2.75 |
| synth P3（色卡） | 0.069 / 0.83 / 2.28 | 83.6 %，最大差 15 | 0.329 / 4.98 / 5.17 |

RAW 解码（线性 ProPhoto）的「无」对照 numpy 里直接转 sRGB（CAT02、截断）：dunes 0.290 / 0.90 / 1.16，fruit 0.284 / 0.94 / 1.14。

解读：
- 屏幕路径平均 ΔE00 低于 0.08，人眼看不出。差异来自进出两个方向的色适应不同：进引擎时用 ICC 的 Bradford D50 色度坐标（与上游宿主 `image_io.cpp` 一致），离开时用引擎的 CAT02 矩阵（与上游 `render_view` 一致）。白点精确保持，差别集中在高饱和色上，色卡的最大码值差 29 就出在这里。
- 导出路径多了 CAM16 色度压缩（上游 `export_frame` 的文件边界，亮度肩部关闭），它在色域边界前就开始收拢饱和色，所以平均 0.2–0.5、最大 5–7。有胶片的照片走的是同一步，这一步是上游设计，没有为「无」改动。
- 想要导出逐码值等于原 JPEG，需要在「无」时跳过 CAM16 压缩并改用 Bradford 回程。这会让「无」的导出和上游桌面版 no_film 不一致，本次没有做，记为待定。

### 8.3 裁剪与旋转

裁剪 x 0.1、y 0.2、w 0.5、h 0.6 加一个四分之一转：输出 614×768，与预期尺寸一致；对照 numpy 里先裁后转的源图 ΔE00 0.71 / 5.0 / 15.2，反方向转为 21.5（方向正确）。残差来自 `spk_view_geometry` 的双线性重采样在像素中心上与 numpy 整像素裁剪差半个像素，集中在边缘和细节处。

### 8.4 数字中间片（相纸「无」）

Portra 400 和 Vision3 250D 的 DI 出图正常（1536×1023，均值约 0.38 / 0.33 / 0.23，标准差 0.19，经 DI 观看表后是中性正像）。
**Tri-X 400 的 DI 整幅是同一个码值 75**（Cineon 黑位 95 附近），即黑白负片在这个引擎版本上不能做数字中间片。
因此黑白负片的相纸「无」不可选（`Catalog.normalize` 清掉，相纸卡变灰并写明原因），黑白片仍需选一款相纸。

## 9. 预览与导出一致（`tools/parity/view_export.py`）

起因：Snapseed 4.x 用户大量反映“导出比预览更饱和”。这一节只比本应用自己的两条路径，不涉及上游宿主：同一张图、同一组参数，编辑器屏幕上显示的结果和导出文件，换算到同一色彩空间后算 ΔE00。

- 屏幕：`live` 档（`preview_long_edge` 1024，只在拖动滑块时）和 `preview` 档（松手后的清晰帧），经 `pack_rgba8` 和显示变换（矩阵加裁切），8 位。
- 导出：`full` 档，经 `pack_rgba16` 和导出变换（CAM16 色域压缩），16 位。
- 场景：synth、lake、skyline、fruit；胶片 Portra 400、Velvia 100、Tri-X 400；sRGB 和 Display P3。几何用例（lake、skyline，固定曝光）：转 90°、居中裁剪一半。
- `spk_android` 读环境变量 `SPK_TIER` 选档（默认 `full`）。比较前导出在线性光下按面积缩到屏幕尺寸。

### 9.1 门槛

| 比较 | 门槛 |
|---|---|
| 同一次 full 渲染：屏幕打包 vs 导出打包 | 平均 < 1 |
| 屏幕档 vs 导出，128 px（长边） | 平均 < 1。这一项防的就是颜色或饱和度偏移：缩到 128 px 后锐化和重采样的差别消失，颜色差别留下 |
| 转 90° vs 原图转 90° | 平均 < 1 |
| 居中裁剪 vs 原图同一区域，128 px | 平均 < 1 |
| 原尺寸的屏幕档 vs 导出、原尺寸裁剪 | 平均 < 1；基线本身 ≥ 1 的那几对（下表）是已知的细节差别，不卡 1，但比基线变差超过 0.3 判失败。基线在 `tools/parity/view_export_baseline.json`，`--record` 重写 |

### 9.2 结果（aoidc，lavapipe，2026-11-05，main + raw-m6）

126 对，0 失败。按比较类型的平均 ΔE00 范围：

| 比较 | 对数 | 最小 | 最大 |
|---|---|---|---|
| full：屏幕打包 vs 导出 | 24 | 0.085 | 0.789（synth Velvia sRGB） |
| live vs 导出，128 px | 24 | 0.034 | 0.797（lake Portra sRGB） |
| preview vs 导出，128 px | 24 | 0.009 | 0.613（fruit Velvia sRGB） |
| live vs 导出，原尺寸 | 24 | 0.181 | 2.229（lake Portra sRGB） |
| preview vs 导出，原尺寸 | 24 | 0.085 | 0.789 |
| 转 90° | 2 | 0.002 | 0.003 |
| 居中裁剪，128 px | 2 | 0.014 | 0.553（lake） |
| 居中裁剪，原尺寸 | 2 | 0.015 | 1.467（lake） |

结论：缩到 128 px 后所有屏幕档与导出的平均差都在 0.8 以下，没有“导出比预览更饱和”的现象。

### 9.3 原尺寸超过 1 的几对：差在细节，不在颜色

基线 ≥ 1 的 9 对：live vs 导出 synth Portra sRGB 1.105、synth Velvia sRGB 1.069、lake Portra sRGB 2.229 / P3 2.099、lake Velvia sRGB 1.896 / P3 1.774、lake Tri-X sRGB 1.219 / P3 1.222；lake 居中裁剪原尺寸 1.467。

- live 档是 1024 px 的独立渲染，扫描锐化和重采样与 full 档不同；导出缩到 1024 px 后保留的边缘细节和 live 档不一样。这些对的 p99 在 9–13.5，平均只有 1.1–2.2，同一对缩到 128 px 后降到 0.30–0.80：差别集中在边缘，不是整片颜色。
- lake 裁剪：原尺寸 1.467、p99 9.9，128 px 降到 0.553，skyline 裁剪两种都很小（0.015 / 0.014）。推测是与画幅成比例的模糊类效果在裁剪后相对像素变大，lake 有水面强光，所以明显；未逐项验证。
- 松手后的 preview 档：在这组 1536 px 的对照输入上，preview 档（长边 3400）与 full 档渲染出完全相同的像素（24 对全部一致），所以“preview vs 导出”就等于“full：屏幕打包 vs 导出”，比 live 档接近得多（128 px 最大 0.613 对 0.797，原尺寸最大 0.789 对 2.229）。这只说明输入小于 3400 px 时两档相同；50 MP 照片上 preview 档与 full 档仍会不同，这组用例没有覆盖。

### 9.4 复现

在 `scripts/parity.sh` 跑完的工作目录里（需要 `run/inputs` 和编好的 `spk_android`）：

```bash
python3 tools/parity/view_export.py --android <work>/src/spk_android \
  --resources <work>/build-host/engine/resources --inputs <work>/run/inputs --out ve --check
```
