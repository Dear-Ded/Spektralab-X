# 外部调色方案调研与 LUT 转换改进

GPT（OpenAI）共创。2026-11-13 北京时间查询作者仓库和官方文档。
本轮目标是提高外部风格在现有成像链路中的还原精度。沿用既有界面、
RAW 中性线性默认、标准显影 v1、胶片材料与引擎 pin；没有增加默认审美调整。

## 1. 阅读的原始资料

| 作者 / 项目 | 固定来源 | 实际结论及取舍 |
|---|---|---|
| Jed Smith / OpenDRT | `af683323e2a8a63501f02c0a724ec538e3228ad0`，DCTL 标注 v1.1.0 | 输入色域、输入传递函数、tonescale、purity、brilliance 和 hue shift 是分开的模块。颜色随亮度的变化和曲线相互关联，不能用一条 S 曲线或整体加饱和代替整套方案。[J][JP] |
| Troy Sobotka / AgX-S2O3 | `7f329dd10b276e87c302d0fe3d4f565da922fb43` | 作者说明强调基向量、inset/outset 和通道关系；`AgX.py` 展示了调整基色、曝光对数分配与可调 sigmoid。这里研究的是该实验仓库，不把它称为当前 Blender 实现，也不把其中参数塞进物理胶片模型。[A][AP] |
| ACES / Reference Gamut Compression | 官方 RGC specification，ACES 1.3 算法 | 保护一部分可信颜色，压缩远离无彩轴的 AP1 输入；要求关注曝光一致性和负值域。它明确不负责显示色域映射，压缩后极端值也不保证全部进入 AP1。不能照搬为 sRGB 导出限幅或替换现有 CAM16 输出。[R] |
| OpenColorIO / LUT baking | `22dd9c4faa0d1b7cfb3d64d1a6e9cb454acc6cfd` | LUT 烘焙有格式、整形域和采样精度限制，需在目标应用对照直接参考变换验证。由此选择检查本项目外部 LUT 的实际工作色域适配，而非直接加入另一套风格参数。[O] |
| Colour / ROMM transfer | `7ca0c87a85c10dd5e1fd51c09e46bda1f863dff3` | ROMM 的传递函数包含线性趾部，并非全域纯 gamma。实现中的 `16 ** (1.8 / (1 - 1.8))` 对应线性阈值 `1/512`，编码阈值 `1/32`。[C] |

已阅读 OpenDRT 参数说明、DCTL 的输入/比例/压缩处理，AgX README 与
`AgX.py` 的矩阵/对数分配，ACES RGC 规范正文及 LUT 附录、OCIO 烘焙教程，
以及 Colour 的 ROMM transfer 实现。没有购买、打包或复制作者 LUT / DCTL。
本轮没有加入外部库；生产改动是本项目独立实现的基础传递函数与烘焙调度修正。

外部方案的用途不同。OpenDRT/AgX 是画面形成方案，现有物理胶片模型本身也
形成画面；在它后面直接叠加完整 DRT 可能重复压高光。现有 `.cube` 默认按
sRGB 显示颜色解释，不能仅凭扩展名把 LogC、S-Log、ACEScct 或 HDR LUT 当作
普通照片风格。RGB 比例或色相排序不等于人的感知色相恒定，本轮不作此承诺。

## 2. 发现并改进的实际算法

### ProPhoto 趾部一致性

生产引擎、曝光适配和 TIFF ICC 已使用分段 ProPhoto，而 `LutBake` 原来
对所有输入执行 `pow(x, 1.8)`，返回也只执行反幂。外部表进出工作色域时，
暗部使用了另一条传递函数。

现在在 LUT 烘焙中同样使用：

```text
decode(E) = E / 16                E < 1/32
            E^1.8                otherwise
encode(L) = 16 L                 L < 1/512
            L^(1/1.8)            otherwise
```

独立常量例：输出恒为 sRGB 编码 `0.01` 的表，转换后的 ProPhoto 中性值应为
`16 * 0.01 / 12.92`。测试直接检查该值，避免用互相抵消的解码/编码回环掩盖问题。

### 在最终网格上烘焙组合变换

旧算法继承源表边长。即使原始 sRGB 表为简单的 2³，ProPhoto → sRGB →
源表 → ProPhoto 仍有矩阵、非线性传递与色域边界，不能只靠八个角点表达。
新的 sRGB 适配在最终 65³ 网格上直接计算整条组合变换，保留四面体插值。
直接传给 `LutBake` 的大表不再先缩到 65³ 再做跨色域烘焙。

ProPhoto 原生表仍保留原分辨率与样本；大于 native 上限时沿用原来的重采样。
输入 sRGB 色域以外的颜色仍按原合同裁切，这次没有偷偷换成新色域压缩算法。

需要区分层次：`CubeFile.toLut` 对复合 1D/3D、非单位 domain 或大表仍有
自己的展开/重采样；Hald 大表也有原来的归一化步骤。本轮没有消除这些先前
阶段的近似，65³ 也不保证任意陡峭 LUT 无误差。

### 已存风格的版本边界

新缓存标记为 `SPK2`，读取同时接受旧 `SPKL`。旧缓存按已存样本逐值读取，
不会在升级后悄悄重烘焙；重新导入同一外部风格时才得到新算法。
原 `.cube` 和色域标签仍可导出，不把烘焙后的 ProPhoto 表误当原 sRGB 表分享。
旧版应用不能读取新 `SPK2` 私有缓存；跨版本交换仍使用 `.spklook` / `.cube`。

## 3. 数值验证方法与边界

`LutBakeTest` 使用独立 Double XYZ / Bradford 矩阵组合，并用排序后的重心
坐标插值源表，不读取生产的组合 Float 矩阵，也不复用六分支插值实现。
旧对照保留 alpha-6 的 Float 算法和缓冲复用方式。合成暖调表由项目自行生成，
含跨通道混合，不能当作某位作者的外观复制或真实照片主观评测。

源边长为 2、17、33、65、129；每种检查立方体内部、暗部、中性轴、高饱和边界
四组，各 4096 个非网格样本，共 81920 色。记录编码 ProPhoto 绝对误差，
另以独立矩阵/裁切/sRGB 编码统计 8-bit 显示码值误差。后者是数学 sRGB 预览
参考，不是 Android 实机显示测量、CAM16 文件意图或 CIEDE2000。

常规网格顶点容差为 `2e-6`。129³ 交替窄带用于检查重复重采样造成的信息损失，
对 Float 坐标误差高度敏感，采用一个 16-bit 工作色域码值 `1/65535` 的压力界限。
最初窄带测试在 `2e-6` 门限下出现约 `2.4e-6` 差异，原始失败日志保留；
没有因此放宽常规顶点或 Kotlin/native 的一致性要求。

`lut_bake_parity.cpp` 把 Kotlin 实际烘焙的 65³ 表和 16384 个输入/期望输出
交给生产 `Lut3d`，检查 native 插值及 16-bit 量化。CI 的 Release JVM 测试生成
夹具，随后用 ASan/UBSan 跑该生产头文件；声明生成目录为 Gradle 输出，防止
测试缓存命中后缺少夹具。测试二进制与合成大表不入 Git。

## 4. 最终云端结果

以下为 33³ 合成暖调源表；编码误差是每通道平均绝对误差，非感知色差评分。

| 样本组（各 4096 色） | 旧 ProPhoto 平均误差 | 新 ProPhoto 平均误差 | 旧 sRGB8 平均码差 | 新 sRGB8 平均码差 |
|---|---:|---:|---:|---:|
| 立方体内部 | 0.000249655 | 0.000067979 | 0.17261 | 0.05282 |
| 暗部 | 0.001814561 | 0.000415782 | 0.59229 | 0.16227 |
| 中性轴 | 0.000095496 | 0.000017961 | 0.02726 | 0.00822 |
| 高饱和边界 | 0.000184971 | 0.000041327 | 0.12476 | 0.03060 |

暗部平均工作色域误差约降 77%，立方体内部约降 73%。高饱和边界 sRGB8
最大码差仍为 18（旧 29）；65³/129³ 的某些最大误差基本不变，不能以均值
推导每个像素都改善。全部边长、分组的均值和最大值保留在 `quality.csv`。

AOIDC 云端 GCC 11.4 / Clang 14，各自普通、ASan/UBSan 四种组合均通过
16384 个 Kotlin → 生产 C++ 样本，`max_float=0`、`max16=0`，开启 leak 检查。
这是生产插值头文件的检查，不含整个 Android JNI / GPU 成像链路的重新插桩。
最终 Release JVM 为 23 类、179 项，零失败、错误、跳过；其中新增七项包括
数值、缓存兼容与可选云端成本记录。两个新数值测试在 alpha-6 原文件上均失败，
以此确认测试能检出原问题。完整 x86_64 Debug APK 构建成功，未发布或安装手机。

新表的单份 float 数据为 3295500 字节，约 3.14 MiB；旧 33³ 为 431244 字节，
约 0.41 MiB。Java 和 native 可各持有表，不能把单份容量当整个进程增量。
AOIDC/JDK21 预热一次、取后三次最好值，33³ 源表的单次烘焙由 13.19 ms
变为 104.37 ms；129³ 源表由 114.68 ms 变为 121.80 ms。这是 `LutBake`
微测量，不含导入解析、文件 I/O 或手机性能，也不说明更大表的缓存访问没有成本。
烘焙只在保存/导入时做，渲染读取已存表。

本轮源码输入十个路径的 SHA-256 在本机、云端测试快照和 Gradle 工作目录一致。
Debug 测试 APK SHA-256 为
`e7169c13994a22061dc5a20f520d30a30f7dc5519437ed99a1490bb0acbc6a6d`。
原始结果、负对照、最初窄带门限失败和工具链记录见
[公开验证目录](../tools/native/validation/expert-color-20261113/README.md)。
规范云端归档 15850 字节、49 个唯一条目，SHA-256
`c70ca13c00245563258701b58007e28d7901ec2a7e1778bd62c533496ed88ddc`。
源清单最初为 CRLF 导致 Linux 校验将回车读入文件名，改为 LF 后十项全部通过；
一次 SSH 传输重置也已重试并校验，没有为这些环境问题改算法或降低数值要求。

尚无真实照片审美评分、手机内存/帧率或新 GitHub Actions 运行结果。
私人 RAW、照片、派生图和两份交接文档始终未上传或提交。

## 原始链接

[J]: https://github.com/jedypod/open-display-transform/blob/af683323e2a8a63501f02c0a724ec538e3228ad0/display-transforms/opendrt/OpenDRT.dctl
[JP]: https://github.com/jedypod/open-display-transform/blob/af683323e2a8a63501f02c0a724ec538e3228ad0/display-transforms/opendrt/docs/opendrt-parameters.md
[A]: https://github.com/sobotka/AgX-S2O3/blob/7f329dd10b276e87c302d0fe3d4f565da922fb43/README.md
[AP]: https://github.com/sobotka/AgX-S2O3/blob/7f329dd10b276e87c302d0fe3d4f565da922fb43/AgX.py
[R]: https://docs.acescentral.com/rgc/specification/
[O]: https://github.com/AcademySoftwareFoundation/OpenColorIO/blob/22dd9c4faa0d1b7cfb3d64d1a6e9cb454acc6cfd/docs/tutorials/baking_luts.rst
[C]: https://github.com/colour-science/colour/blob/7ca0c87a85c10dd5e1fd51c09e46bda1f863dff3/colour/models/rgb/transfer_functions/rimm_romm_rgb.py
