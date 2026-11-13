# 上游更新核实与定点候选，2026-11-13

调研 A；共创：GPT（OpenAI）。基线 alpha-5/125：
`a94d584d2bb33ce721c27b68b28fcdbf598e9098`。

公开 API 最后核验时间：**2026-11-13 00:11:56 Asia/Shanghai**
（2026-11-12 16:11:56 UTC）。这是该时刻的快照，不代表 11 月 13 日全天结束状态。

## 结论

**00:11 快照中的两个 SpektraLab 分支及 Python main 均未超过前次记录。
08:33 再次核实时，Windows_Linux 已新增六个发布/更新检查提交，见第 7 节；
它们不修改引擎或材料数据。立即实施候选仍是 C1：LibRaw Sony YCbCr Huffman 缓存索引掩码。**
它未被现有 overlay 包含，作用于一个头文件；合法 13 位索引保持原值，无新增整图缓冲，
可独立验收。X-E5、R6 III 列为有明确验收条件的后备，不因上游有代码就直接宣布支持。

主线程随后告知 0055 已实现 view curve tangents 预计算：双云 CPU/ASan 通过，Vulkan 108 case、
23.4 MB 旧新输出逐字节一致，ARM64 完整 JNI 链接通过。这是主线程提供的结果，本轮未重复运行；
排除该方向，不重复 CPU 曲线缓存或执行路径研究。
三个候选均保留 RAW 线性默认，不切换既有胶片模型、profile 或插值默认。

## 1. 00:11 快照 SHA 与日期

日期使用 commit 的 **committer date**；release 发布时间另列。
本轮 web.run 搜索和直接打开均返回空结果，使用匿名 HTTPS 读取 GitHub 官方 API
与 raw.githubusercontent.com 固定 SHA 源码。

| 对象 | 最新完整 SHA | UTC 日期 | Asia/Shanghai 日期 | 官方 API / 固定源码 |
| --- | --- | --- | --- | --- |
| SpektraLab Windows_Linux | `223205fddbbc01c583977497d9f27c79a49ca8d0` | 2026-11-10 00:47:12 | 2026-11-10 08:47:12 | [branch API](https://api.github.com/repos/JamesQiu2005/SpektraLab/branches/Windows_Linux)、[commit](https://github.com/JamesQiu2005/SpektraLab/commit/223205fddbbc01c583977497d9f27c79a49ca8d0) |
| SpektraLab main，2.3.2 | `1952c63537ec576d24422f1065b12495631346a5` | 2026-11-10 11:49:21 | 2026-11-10 19:49:21 | [branch API](https://api.github.com/repos/JamesQiu2005/SpektraLab/branches/main)、[commit](https://github.com/JamesQiu2005/SpektraLab/commit/1952c63537ec576d24422f1065b12495631346a5) |
| spektrafilm main | `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc` | 2026-06-06 23:40:37 | 2026-06-07 07:40:37 | [branch API](https://api.github.com/repos/andreavolpato/spektrafilm/branches/main)、[commit](https://github.com/andreavolpato/spektrafilm/commit/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc) |
| LibRaw master | `a9a14b1970d188c25dade81f8326ff00a6e33649` | 2026-11-08 09:07:09 | 2026-11-08 17:07:09 | [branch API](https://api.github.com/repos/LibRaw/LibRaw/branches/master)、[commit](https://github.com/LibRaw/LibRaw/commit/a9a14b1970d188c25dade81f8326ff00a6e33649) |
| LibRaw 最新正式版 0.22.3 | `cf02d66eb5c19d4e46df7aee0582e47a4918509a` | 提交：2026-10-24 09:24:46；发布：2026-10-26 11:27:10 | 提交：2026-10-24 17:24:46；发布：2026-10-26 19:27:10 | [tags API](https://api.github.com/repos/LibRaw/LibRaw/tags?per_page=6)、[latest release API](https://api.github.com/repos/LibRaw/LibRaw/releases/latest)、[commit](https://github.com/LibRaw/LibRaw/commit/cf02d66eb5c19d4e46df7aee0582e47a4918509a) |

对“前次记录 SHA → 当前同名分支”再次调用 compare，Windows_Linux、main、Python main
均返回 `identical`、`total_commits=0`；LibRaw `a9a14b1 → master` 也相同。
上述结果证明截至核验时刻没有新增提交，不根据 release 标签或抓取时间推测。

Python 其他公开分支也未变化，依据 [branches API](https://api.github.com/repos/andreavolpato/spektrafilm/branches?per_page=100)
及各 commit API：

| 分支 | SHA | committer date，Asia/Shanghai | tip 范围 |
| --- | --- | --- | --- |
| couplers-tuning-iterations | `a273e49b4243d19d7447df39c2b9880e9ec35a66` | 2026-05-13 21:07:41 | [couplers.py 迭代耦合器](https://github.com/andreavolpato/spektrafilm/commit/a273e49b4243d19d7447df39c2b9880e9ec35a66) |
| dev | `6cd00c8d4f30b5b550f50f4bbd3753c9f2a48507` | 2026-06-10 13:22:34 | [GUI gamma 范围](https://github.com/andreavolpato/spektrafilm/commit/6cd00c8d4f30b5b550f50f4bbd3753c9f2a48507) |
| experimental | `28bf883e1672e884307edc75852549376e13644e` | 2026-07-08 15:25:58 | [带参数 single-LUT bake](https://github.com/andreavolpato/spektrafilm/commit/28bf883e1672e884307edc75852549376e13644e) |

## 2. 相对项目 pin 的差异与去重

先读取 [前次比较](ENGINE_FORK_COMPARISON_2026-11-12.md)、
[抓取脚本](../scripts/fetch-upstream.sh)、[补丁索引](PATCHES.md) 并检查实际补丁内容。
起始基线有 54 个引擎补丁、17 个 LibRaw overlay；主线程新落地的 0055 另按上述进度排除。
抓取脚本固定引擎 `a46627d250545a394ca21522f8a28b0fb89e4c42`、LibRaw `0.22.2`，
LibRaw archive SHA-256 为 `02275a04cf0d1477ab9fd7ee231ddea59e7b14497458da6e3ba957517d6655c7`。

### Windows_Linux

[官方 compare](https://api.github.com/repos/JamesQiu2005/SpektraLab/compare/a46627d250545a394ca21522f8a28b0fb89e4c42...223205fddbbc01c583977497d9f27c79a49ca8d0)
仍返回 ahead 41、behind 0；其中含桌面 UI、构建和下列功能，不等于有 41 项待回移植优化。

| 上游变化 | 本项目状态 | 决策 |
| --- | --- | --- |
| Sony ARW6，`b5467e8` | LibRaw `0019-sony-arw6` 已有 | 不重复；C1 是另一套 Sony YCbCr 解码器 |
| 失效设备准备保护，`53299f7` | 引擎 `0008`、`0025` 已有 | 不重复，执行路径由主线程负责 |
| X-E5，`0799cf7` | GFX100RF fallback 在，X-E5 分支不在 | C2 后备，仅扩展精确型号入口 |
| R6 III，`d978ba9` | `0019` 没有后续 Canon parser/矩阵 | C3 后备，不能把新 overlay 当成已经吸收 |
| DI/headroom Vulkan 测试，`0cc2598` | 属于主线程执行路径范围 | 本轮不重复展开 |
| Windows/MSVC、缓存目录、桌面导出 UI | 宿主平台差异 | 不作为 RAW 像素/效率候选 |

### main

最后的非 release 提交是 [`8c42ae0`](https://github.com/JamesQiu2005/SpektraLab/commit/8c42ae0faadc20633af321d6014c6a6be3774f4f)，
2026-11-10 17:50:04 Asia/Shanghai，涉及 Intel Mac、Swift、Metal；不是新 RAW 线性模型。
HDR、scan_hdr、minilab 等语义已有 [V2 研究](UPSTREAM_V2_RESEARCH_2026-11-11.md)，
固定 main 的 [windows_unported.hpp](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/src/pipeline/windows_unported.hpp)
仍列有显式拒绝的未实现能力。
[raw_decoder.cpp](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/src/io/raw_decoder.cpp#L50)
仍包含 `output_color=4`、`output_bps=16`、关闭 auto-bright 的 compatible 设置及独立 headroom 路径。
不构成本项目 DNG 校准/浮点保留合同的整树替代。
CAM16 常量外提、DI 通道曲线预拆已有 `0026`、`0027`，本轮不重复提出。

### Python

最新 [`3bb2c2d`](https://github.com/andreavolpato/spektrafilm/commit/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc)
修改 V-Log LUT 的自动 headroom/gain 及诊断，不能由此推断本项目 RAW 有相同曝光错误。
具体可借鉴的文件/函数是：

- [color_spaces.py / native_stops_above_midgray](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm_lut_creator/color_spaces.py)：去除 V-Log 特设 auto-stops，按原生编码 headroom 计算。
- [params_builder.py / digest_params](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm/runtime/params_builder.py)：LUT 模式显式关闭曝光补偿、依赖全图最大值的 highlight boost、空间及随机效果。
- [test_lut_mode.py](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/tests/test_lut_mode.py)：重复运行、像素置乱后还原、仅提升单像素亮度，分别检验确定性、邻域独立性与全局统计独立性。
- [model_diagnostic.py / dynamic_range_usage](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm_lut_creator/qa/tests/model_diagnostic.py)：使用输入编码自身中灰作曝光阶梯锚点，避免待测 gain 抵消自己的曝光偏差。

这些方法适用于既有离线 LUT 研究。完整胶片本来含空间、随机和测光行为，不能强加逐像素独立性，
也不能借此替换完整生产模型。本轮不新增这个方向的实施候选。

## 3. LibRaw：维护版与开发快照的实际交集

[0.22.2 → 0.22.3 官方 compare](https://api.github.com/repos/LibRaw/LibRaw/compare/b93f6e45c194f5df9b02a43b1af9a54b4f41f33f...cf02d66eb5c19d4e46df7aee0582e47a4918509a)
仍为 32 commits、19 文件。不能因为 release 标为 bugfix-only 就整体升级。

| 位置 / 修复 | 本地覆盖与决策 |
| --- | --- |
| wavelet size、normalize_model 静态变量、lossy DNG 默认曲线、CRX signed quantizer 索引 | 已分别有 LibRaw `0001`、`0017`、`0018`、`0020`；不重复 |
| `internal/losslessjpeg.h / HuffTable::decode` | 17 个 overlay 均未修改该文件，C1 |
| `read_utils.cpp / libraw_int_to_float`、`getreal` | 上游 NaN/Inf 归零与本地无效元数据标记冲突，暂不直接回移植，详见下文 |
| X-Trans source/destination 分离 | 改变既有 RAF 输出并增加一份 image；本地 `0003` 保留串行旧算法，不能当无损加速或整包升级 |
| `crx.cpp / crxConvertPlaneLine` 两处 64 位 offset | `0020` 仅四处索引保护，没有这项；收益取决于可达尺寸和内存，低于本轮候选，未声称普通 CR3 已复现坏图 |
| `open.cpp`、`libraw_datastream.cpp` | 维护版含 progress reset、stream validity、`_fsize` 初始化；未来需在最终叠加树上审，不能用整文件覆盖现有 ARW6 改动 |

**直接合入非有限元数据归零会破坏本项目的一条错误输入合同。**
固定 [read_utils.cpp](https://github.com/LibRaw/LibRaw/blob/cf02d66eb5c19d4e46df7aee0582e47a4918509a/src/utils/read_utils.cpp)
在 `libraw_int_to_float` 及 `getreal(type=12)` 将 NaN/Inf 替换为零。
本地 [LibRaw 0023](../patches/libraw/0023-dng-profile-dynamic-range.patch)
通过 `getreal(11)` 读取 ProfileDynamicRange float hint，随后用 `!std::isfinite(hint)`
设置 `render_metadata_invalid`。若预先归零，`version=1, range=0, hint=NaN/Inf`
可能被当成合法 SDR，丢失原损坏标志。这是两处源码控制流的静态推论，本轮未运行复现。
今后考虑该维护修复，必须同时保留读取有效性，并冻结 DNG 无效标志及标准显色拒绝行为。

开发快照的后续只有两项，依据
[202611 快照 0b87f5c → master compare](https://api.github.com/repos/LibRaw/LibRaw/compare/0b87f5c907d7ee0e7641dd1b6a6c9140b9451922...a9a14b1970d188c25dade81f8326ff00a6e33649)：

- [`211ec934`](https://github.com/LibRaw/LibRaw/commit/211ec9340888b66f430e23ec9ec5afaad01887ca)，2026-11-08 01:01:25 Asia/Shanghai：`open.cpp` 恢复受 `USE_X3FTOOLS` 保护的 `foveon_count`，是快照内构建修复。
- [`a9a14b1`](https://github.com/LibRaw/LibRaw/commit/a9a14b1970d188c25dade81f8326ff00a6e33649)，2026-11-08 17:07:09 Asia/Shanghai：`nikonhe_init_image_data` 将递推赋值的目标从 `skip_decomp[...]` 修正为 `odd_ofs[comp][v_lvl][h_lvl-1]`。这是 Nikon HE 偏移，不是本地 RAW 黑点修复。

当前固定 0.22.2 的 overlay 未引入 Nikon HE，因此这两项不是当前直接可修的生产缺陷。
未来若支持 HE，至少要包含后一修复，不能只采用 11 月 4 日快照。
[官方 Changelog](https://github.com/LibRaw/LibRaw/blob/a9a14b1970d188c25dade81f8326ff00a6e33649/Changelog.txt#L1)
明确列出 HE 额外 8 B/px 中间数据、45MP 时约 400 MB 相关缓冲，并有构造器 ABI 变化。
这不满足当前保持像素、预算和默认稳定的立即升级条件。

## 4. C1，唯一立即实施候选：Sony YCbCr Huffman 缓存索引掩码

**固定来源。** 官方
[`d7e09495734c527c9fdcd154e6435e06d20bb942`](https://github.com/LibRaw/LibRaw/commit/d7e09495734c527c9fdcd154e6435e06d20bb942)，
2026-10-24 15:58:59 Asia/Shanghai，已进入 0.22.3。
只修改 `internal/losslessjpeg.h / HuffTable::decode(BitPump&)`：增加
`LIBRAW_DECODE_CACHE_MASK=(1<<13)-1`，在缓存寻址前按位与。

**确实未包含的证据。** 初始 17 个 LibRaw overlay 的路径索引及实际内容没有
`internal/losslessjpeg.h`、`LIBRAW_DECODE_CACHE_MASK` 或该 `decodecache` 修改。
现有 LJPEG `0013`、`0015`、`0016` 约束的是 dcraw decoder 工作量/段/IDCT，未修改此头文件；
`0019` 的新 Sony ARW6 decoder 也不是该 Sony YCbCr decoder。
主线程的 0055 只处理 view curve tangents，不重叠。

[0.22.2 的缓存初始化](https://github.com/LibRaw/LibRaw/blob/b93f6e45c194f5df9b02a43b1af9a54b4f41f33f/src/decompressors/losslessjpeg.cpp#L388)
实际分配 `1<<13`、即 8192 项；
[未修复 decode](https://github.com/LibRaw/LibRaw/blob/b93f6e45c194f5df9b02a43b1af9a54b4f41f33f/internal/losslessjpeg.h#L152)
直接用 `pump.peek(13)` 寻址。
[sonycc.cpp / decode_sony_ljpeg_420](https://github.com/LibRaw/LibRaw/blob/b93f6e45c194f5df9b02a43b1af9a54b4f41f33f/src/decoders/sonycc.cpp#L67)
调用该函数；不需要改变胶片、RAW 颜色或 GPU 执行代码。

**收益与风险。** 对合法的 0..8191 索引，掩码保持原值，后续计算不变；新增一次整数 AND，
无新表或图像缓冲。它只限制这一处缓存寻址，不能概括为所有损坏 JPEG 都已安全。
异常高位被掩码后的错误处理仍须验证，且不宣称已经实测提速。

**独立验收条件。** 以下均是后续门槛，本次未实施或运行：

1. 对真实 `HuffTable::decode` 覆盖全部 8192 个合法缓存索引，旧/新返回值及消费 bit 数相同；同时覆盖 cache off、命中与 slow path。
2. 受控 `BitPump` 注入 `0x2000`、`0x80000000`、`0xffffffff` 等高位值，修复后该缓存访问不越界；真实 `BitPumpJpeg` 另测截断、marker、byte-stuffing 小流并跑 ASan/UBSan。mock 只能证明局部防护；只有真实流的旧版失败见证才能记录为实际解码修复证据。
3. 授权公开 Sony YCbCr RAW 及 ARW6、Bayer DNG、X-Trans 控制样本的原始解码值、默认线性 float 输出、源 WB/矩阵/黑白点均与冻结基线逐字节相同；失败输入后重试正常输入也须一致。
4. 同机、同编译选项交错至少 20 对解码，记录中位耗时和峰值 RSS；无随像素增长的新增分配，中位耗时回退不超过 2%。超限须查明噪声或原因，不能以理论 O(1) 代替测量。

优先级理由：它比新机型校准更容易给出合法输入恒等证明，单文件、无新图像分配，
且与主线程正在实施的曲线/GPU 工作完全独立。

## 5. 有样片再推进的两个后备

两项均为前次已指出但本地尚未回移植的缺口，本轮补齐实施边界，不与 C1 同时扩大实施范围。

### C2：精确 X-E5 型号复用 X-H2 校准

来源 [`0799cf7320b4c2bdf0b09cdf84f79619fd7f86cf`](https://github.com/JamesQiu2005/SpektraLab/commit/0799cf7320b4c2bdf0b09cdf84f79619fd7f86cf)，
2026-11-10 00:12:24 Asia/Shanghai。
借鉴 [raw_camera.hpp / CameraLibRaw::adobe_coeff](https://github.com/JamesQiu2005/SpektraLab/blob/223205fddbbc01c583977497d9f27c79a49ca8d0/engine/src/io/raw_camera.hpp#L11)
及 [raw_camera.cpp 的边界测试](https://github.com/JamesQiu2005/SpektraLab/blob/223205fddbbc01c583977497d9f27c79a49ca8d0/engine/tests/raw_camera.cpp#L30)。
仅 Fujifilm、`colors==3`、非 DNG、型号恰为 `X-E5` 时查询 X-H2 的系数，保持原始型号等识别结果。

本轮实际对照 [官方 master 的 X-E5/X-H2](https://github.com/LibRaw/LibRaw/blob/a9a14b1970d188c25dade81f8326ff00a6e33649/src/tables/colordata.cpp#L546)
与 [0.22.2 的 X-H2](https://github.com/LibRaw/LibRaw/blob/b93f6e45c194f5df9b02a43b1af9a54b4f41f33f/src/tables/colordata.cpp#L537)：
矩阵同为 `11809,-5358,-1141; -4248,12164,2343; -514,1097,5848`，黑白点 override 均为 0。
当前补丁没有 X-E5 分支。只向现有类加分支，不覆盖已经承载 DNG 双光源、ForwardMatrix、WhiteXY、
校准签名、OpcodeList3 和取消的头文件；维持 X-Trans 串行策略。

验收条件：

1. X-E5 的全部 `cam_xyz` 元素与上述整数除以 10000 的差不超过 `1e-6`，且不处于未校准状态；原始型号、decoder、尺寸、WB、黑白点不被改写为 X-H2。
2. `X-E5-unknown`、其他 maker、DNG、非三通道不进入新增 fallback；GFX100RF 两个旧别名、X-H2 的状态、元数据和线性输出保持冻结结果。
3. 至少一份授权公开未压缩和一份无损压缩 X-E5 RAF，固定官方参考构建、相同参数，比较 RAW 数值及完整校准元数据；非目标 RAF/ARW/DNG 输出逐字节相同，无新整图分配，自定义 WB/取消重试通过。缺少某压缩模式样本时不宣布该模式已验收。

风险：矩阵相同只能支持该定点映射，不等于所有 X-E5 布局、模式或实拍显色均已验证。

### C3：R6 III subversion 66 与矩阵成对修复

来源 [`d978ba9b0f1764dbce27542802cdd709bc83271f`](https://github.com/JamesQiu2005/SpektraLab/commit/d978ba9b0f1764dbce27542802cdd709bc83271f)，
2026-11-10 00:12:25 Asia/Shanghai。具体取两个局部修改：

- [canon.cpp / LibRaw::parseCanonMakernotes](https://github.com/JamesQiu2005/SpektraLab/blob/223205fddbbc01c583977497d9f27c79a49ca8d0/engine/third_party/libraw-arw6/files/src/metadata/canon.cpp#L1310)：ColorData 长度 3973/3778 分支中，subversion 66 使用独立 preset、WBCT、两组 black 和 white offsets。
- [colordata.cpp / LibRaw::adobe_coeff](https://github.com/JamesQiu2005/SpektraLab/blob/223205fddbbc01c583977497d9f27c79a49ca8d0/engine/third_party/libraw-arw6/files/src/tables/colordata.cpp#L160)：R6 III 条目排在 R6 II 前，防止前缀匹配误用矩阵。

忽略行尾空格后，上游 overlay 的 canon.cpp 对官方 0.22.2 只增加该 subversion 分支。
新分支 `Canon_WBpresets(2,2)`；WBCT、第二黑位、主黑位、白点分别使用
`save1+2*(0x0069+0x00d7)`、`save1+2*(0x0069+0x0116)`、
`save1+2*(0x0069+0x0227)`、`save1+2*(0x0069+0x022b)`。
当前 `0019` 只有 Sony 表项及 ARW6，未带入这些 Canon 修改。

R6 III 矩阵为 `8309,-1786,-1095; -4851,12736,2353; -956,1911,5534`；
[LibRaw 官方表](https://github.com/LibRaw/LibRaw/blob/a9a14b1970d188c25dade81f8326ff00a6e33649/src/tables/colordata.cpp#L162)
仍标注 preliminary、与 C50 相同，不能表述为本项目已验证的最终相机标定。
只改矩阵会留下 WB/黑白点问题，只改 parser 又可能选到 R6 II 矩阵，必须成对处理。

验收条件：

1. 各偏移放不同值的合成 ColorData 块进入真实 parser，覆盖长度 3778/3973、subversion 66/48/34、截断及错误 type。66 必须读对 presets、WBCT、两组黑位及白点，非 66 不变；ASan/UBSan 无越界。
2. R6 III、R6 II 的 `cam_xyz[0][0]` 分别为 `0.8309`、`0.9539`，误差不超过 `1e-6`，另比较全矩阵；新增排序测试必须能使旧表失败。
3. 授权公开 R6 III RAW 和 CRAW 至少各一份，与固定官方参考构建先比较 RAW 和元数据，再比较线性输出。R6 II、R6、R5、Sony A7 V 控制样本须字节相同；不能以不再品红代替数据，也不自动计入 C50 支持。
4. 维持默认线性/浮点边界、现有 overlay 及取消恢复，不增加整图缓冲。相同模式交错至少 20 对解码，非目标中位耗时回退不超过 2%，RSS 无随像素增长的新项。

风险：本项有意修正目标机型的入片数值，目标样片不要求与旧错误输出相同；非目标机型必须冻结。
不重新复制上游整文件 overlay，不整体升级 LibRaw，也不替换现有 X-Trans 行为。

## 6. 本轮交付

只新增本文件；候选尚未回移植、编译或完成新运行验收。研究依据为公开上游源码，
未访问认证文件、私有样片或云包装脚本。Android/GPU 本地执行路径及云优化由主线程继续。

## 7. 08:33 接续核实与实施结果

2026-11-13 08:32:14 至 08:33:59 Asia/Shanghai，接手主线程重新请求官方 commit API
和 [Windows_Linux compare](https://api.github.com/repos/JamesQiu2005/SpektraLab/compare/223205fddbbc01c583977497d9f27c79a49ca8d0...894915fb7f93656d2951eaf11a8be35e4ff60246)。
Windows_Linux HEAD 为 `894915fb7f93656d2951eaf11a8be35e4ff60246`，比本报告凌晨快照
领先六个提交、落后零个；tip 的 committer date 为 2026-11-13 00:10:42 UTC，
即北京时间 08:10:42。

六个提交为 `b9a4dfa6`、`b00de13c`、`6e686bc9`、`8e094777`、`9ceca338`、`894915fb`。
变化包括独立 Windows beta.3 发布、便携包许可证、发布资产校验、预发布版本比较和
按 release ID 处理草稿。官方 compare 列出 18 个文件；`engine/` 和材料源码均无变化。
最后的发行说明包含此前已知的 X-E5/R6 III 支持，不能把说明汇总当成新增引擎提交。
本项目继续保留引擎 pin 和已验证 overlay，没有执行外部发布脚本。

同次 API 刷新确认 SpektraLab main、Python main、LibRaw master，以及 SAINT、
thetechgeekko、Knonoa 三个主要同类项目默认 HEAD 仍与凌晨表格相同。
vkdt 仍使用同类报告注明的凌晨查询时间，不外推为本次刷新结果。

主线程已完成 C1 的 `patches/libraw/0024-sony-ycbcr-cache-index-mask.patch`，
实际回移植与固定官方 commit 的两行修改相同。81,920 个合法组合逐字节/状态对照、
24,576 个受控高位组合、ASan/UBSan、两份合成 DNG 文件对照和完整 ARM64 JNI 链接通过；
18 个 LibRaw overlay 零 fuzz 重放并与编译源码一致。
具体覆盖和未取得真实 Sony YCbCr 故障文件的边界见
[实施报告](ENGINE_SONY_HUFFMAN_CACHE_2026-11-13.md)。第 4 节的完整相机/性能候选门槛
仍是后续扩展条件，不应将局部测试描述为已满足全部相机验收。
