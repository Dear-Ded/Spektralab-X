# 上游、衍生引擎与算法取舍，2026-11-12

共创：GPT（OpenAI）。用户要求派子 agent 比较上游及各类二创，判断现有路线是否需要改进。
本轮两名只读子 agent 分别研究谱系与成像算法，主线程检查安卓源码并完成一个受限 RAW 修复。
固定下列公开提交作为比较对象，保留一手来源，未下载或运行所有项目的发行包。
研究起点为本分支 `8e48cbb`；本轮 RAW 修复与测试提交为 `7ec9f7a`，增加第 51 张引擎补丁。

## 判断

**当前路线适合继续推进，但没有证据称为最优解，也不能把另一个版本整体替换视作升级。**
对于保留现有 SpektraLab 输出、完整光谱胶片过程和安卓运行的目标，固定 C++/Vulkan 引擎并定点修复
仍有较低迁移成本。我们在 RAW 元数据、浮点保留和失败恢复上已有实际验证，在跨实现独立参考、
IIR 数学、完整大图预算与真机验证上仍有明确缺口。其他实现的测试结构、调度和预算值得吸收。

判断“最优”至少需要同时限定颜色参考、胶片/相纸版本、曝光、空间效果、速度、内存和目标机型。
目前没有一套受控实拍胶片及扫描/相纸参考，也没有各引擎同机同输入实测，因此不提供虚构的真实性排名。
更高浮点精度、更新版本号、更多滤镜或更好看的示例，均不能独自证明更接近指定胶片。
原作者 [README](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/README.md)
也说明部分 CMY diffuse-density 数据使用通用资料，因为材料数据往往不公开；复现参考代码不能消除材料信息的不确定性。

## 谱系和实际差异

| 类型与固定源码 | 实际关系与可能影响 | 对我们的取舍 |
| --- | --- | --- |
| [原 Python spektrafilm main 3bb2c2d](https://github.com/andreavolpato/spektrafilm/tree/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc)，2026-06-06 UTC | Andrea Volpato 的胶片、印放和扫描参考模型；RGB→光谱方法还引用 Hanika/Hanatos 与 Mallett–Yuksel。Python 不等于手机实现，也不等于真实胶片的测量真值 | 补充固定版本的分阶段数学参考；明确我们当前烘焙数据的来源版本，不能直接换最新 profile |
| [SpektraLab Windows_Linux 223205f](https://github.com/JamesQiu2005/SpektraLab/tree/223205fddbbc01c583977497d9f27c79a49ca8d0)，2026-11-10 UTC | 相对我方 a46627d 基础 ahead 41、behind 0。已有 device-loss/ARW6 等部分内容被我方定点吸收；新机型校准仍有缺口 | 保持 pin，按功能与样本回移植；不要重复计算已经吸收的改进 |
| [SpektraLab main 1952c63 / 2.3.2](https://github.com/JamesQiu2005/SpektraLab/tree/1952c63537ec576d24422f1065b12495631346a5)，2026-11-10 UTC | C++/Metal 原生移植与新算法分支。新增 HDR、scan_hdr、minilab 等会改变密度、肩部和印放行为；非 Apple 路径仍显式拒绝未移植能力 | 作为显式新模式逐项评估，不能整树替换 Android Vulkan 或默认改变旧编辑 |
| [SAINTTaiYi/SpektraLab-Android b17f6d2](https://github.com/SAINTTaiYi/SpektraLab-Android/tree/b17f6d2bdcd8024bb62abf212d10819115ca2d87)，2026-11-10 UTC | 同源 C++/Vulkan 路线，基础 445e314。窗口化 descriptor、绝对坐标播种、FIR halo、IIR 分批 dispatch、映射读回和 film 分带已有源码；另有主动色相校正 | 大图执行值得优先研究。其色相修正属于外观政策，不能当成不改变原色的优化。README 性能/设备统计是作者报告，本轮未复现 |
| [thetechgeekko/Spektrafilm-android e70771c](https://github.com/thetechgeekko/Spektrafilm-android/tree/e70771cf99e61481feb471d5c4ac827a488c73f6)，2026-11-11 UTC | 直接重写 Python，独立 CPU/Vulkan 路径。固定旧 Python oracle c1d0e44，较新的资料和若干效果显式 default-OFF。颗粒是统计对照，非逐像素同一随机场 | 借鉴阶段 tap、容差门禁、能力/缺失清单、全局内存准入与 CPU 回退。不能把它的旧默认 profile 或 RNG 当作当前 SpektraLab 的替代 |
| [spektrafilm-ofx 86476af](https://github.com/chaert-s/spektrafilm-ofx/tree/86476afc5b077de77e2278e3658d1ba9309892a1)，2026-07-06 UTC | 直接以 Python 为基础的 C++/Metal/Vulkan OFX 插件，有逐阶段及空间/随机对照组织；本轮未追定其严格 Python pin | 可借鉴用例组织；其非空间阈值和脚本退出行为不适合原样做我们的 CI 门禁 |
| [spektrafilm-rs 9dd59b0](https://github.com/turbasvin/spektrafilm-rs/tree/9dd59b0380194b93686aaa230a8bb9680aa270a4)，2026-06-15 UTC | Rust 重写，f64 CPU 与 f32 GPU 分开，含 WGSL/实验 CUDA；公开对照指向 Python v0.3.2，承认阶段误差和随机差异 | 预计算 PCHIP 是离线 LUT 实验候选；不能推断它能消除当前 signed-domain 投影突变 |
| [vkdt filmsim d240f06](https://github.com/hanatos/vkdt/tree/d240f06994740f3b968117f6d941abcbf6e9023f)，2026-11-11 UTC | 独立 Vulkan 模块使用 Andrea 数据，但明确更换颗粒模型和参数尺度 | 可比较吞吐和统计外观，不能声称与原颗粒逐位相同或更真实 |
| [ART 接口 6a24de2](https://github.com/agriggio/ART/tree/6a24de2c70b83f6cfa044dd9289dd09c52fed2e7)，2026-11-11 UTC | LUT 桥接 Python/Rust，生成 LUT 时关闭空间效果及颗粒等 | 适合确定性色彩外观交换，不替代完整光晕、扩散和颗粒的最终引擎 |

原 Python 的 `couplers-tuning-iterations@a273e49b4243d19d7447df39c2b9880e9ec35a66`、
`dev@6cd00c8d4f30b5b550f50f4bbd3753c9f2a48507` 和
`experimental@28bf883e1672e884307edc75852549376e13644e` 也已核实 tip。
指定源码分别涉及 DIR 迭代、GUI gamma 范围和带参数 single-LUT bake；本轮不是整条实验分支审计，
不能把这三条的日期较新解释成已验证的新物理默认模型。

## 安卓执行差异：最值得借鉴，也最容易误判

主线程直接检查 SAINT 的固定
[engine-android.patch](https://github.com/SAINTTaiYi/SpektraLab-Android/blob/b17f6d2bdcd8024bb62abf212d10819115ca2d87/patches/engine-android.patch)
及 [tiling 记录](https://github.com/SAINTTaiYi/SpektraLab-Android/blob/b17f6d2bdcd8024bb62abf212d10819115ca2d87/docs/tiling.md)。
窗口索引与全帧索引分开、参数明确声明 stride/halo，比猜测 buffer 容量更可靠；前者用于寻址，后者用于
颗粒种子和物理坐标。带间 FIR halo 相加、log-curves 与 couplers 的双平面依赖，都需要保留。

但 `run_film_banded` 每个可带化 run 仍 `gpu_->alloc(plane.bytes())` 分配完整输出平面；
`j == i` 时 materialise 并整幅运行不可分阶段，IIR/overscan 仍在这类边界内。
`SPK_BAND_ROWS` 环境开关选择该 film executor，不能等同于所有手机默认全链执行。
因此窗口绑定解决单 SSBO range，分批 dispatch 缓解长提交，而严格内存上限还需要跨阶段外存与预算。
我们现有 0048 受限印放尾段也没有完成这件事。不能把任何一方的“分带”一词当成完整大图验收。

thetechgeekko 的
[memory_budget.h](https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/engine/spektra-core/src/main/cpp/runtime/memory_budget.h)
按 JNI/native scratch/cache/GPU host/device/writer/JVM 等域及处理阶段记账，`try_reserve` 失败时可回收空闲缓存并重试。
这是比单看 Java heap 或一个 SSBO 更合理的准入结构；仍须检查各处分配覆盖率与进程实测，不能只凭一个头文件
证明所有依赖都受预算控制。其
[覆盖清单](https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/docs/UPSTREAM_PARITY.md)
明确区分 matched/adapted/missing，颗粒按统计验收、GPU 按容差验收，值得保留这种诚实边界。

## 哪些差异会真正改变照片

| 项目 | 对照片的影响与当前证据 | 决策 |
| --- | --- | --- |
| RAW 黑白点、WB、矩阵、profile 选择 | 改变进入胶片的曝光刻度与色度；后续非线性曲线可能放大误差。本轮均匀整数 LinearRaw 缩放缺陷已修复并验证 | 优先修可证明的错误，保留源元数据与线性默认 |
| IIR 末端条件 | 当前启发式可让画边光晕、扩散、锐化响应依扫描方向变化。已有 sigma 24 诊断阶跃翻转差约 0.598；不是照片色差百分比 | 高优先级算法修正。CPU 正确边界候选已有参考，GPU 尚未移植；必须版本化并实测 |
| FIR/IIR 切换 | sigma 2.999→3 时实测核宽 2.9746→3.2995，空间效果可能随尺寸或滑杆阈值跳变 | 测 PSF/频响和时间内存后比较 FIR/FFT/递归候选，不能只改阈值 |
| RGB→光谱负通道投影、LUT | 现有 signed-domain 突变不能靠统一加密 33/65 网格解决；改变投影会改变饱和色和负通道行为 | 先独立参考及域分类。更高阶插值只做离线实验，最终全光谱不被替换 |
| profile/refit、色适应、minilab/HDR | 材料曲线、白点、肩部和印放节点改变，会影响旧图外观；它们不是无损加速 | 独立模式与版本，指定材料对和参考后再决定采用 |
| 颗粒、光晕和扩散 | RNG、物理像素尺度、层数、相关性与 PSF 都影响观感；同均值不代表同纹理，同数据不代表同随机场 | 固定种子和坐标检查执行等价；跨 RNG 用统计/功率谱及参考外观，分开验收 |
| 窗口绑定、并发、缓存、融合 | 算序、量化点和全帧统计不变时可做到执行优化；错误播种或分块统计会造成接缝与颜色漂移 | 优先吸收可证明等价的结构，覆盖中断、失败、全幅/分块和跨设备 |

IIR 数值及 CPU 候选来源见 [已有算法实验](ENGINE_ALGORITHM_RESEARCH.md)；
负域/LUT 见 [signed-domain](LUT_SIGNED_DOMAIN_2026-11-11.md) 和
[离线实验](LUT_OFFLINE_EXPERIMENT_2026-11-11.md)。这些是已有证据，本轮没有把 CPU 数学候选冒充新的产品 GPU 修复。

### 光晕、颗粒与 LUT 不能混为一种误差

本轮子 agent 发现并由主线程复核：固定 Python
[diffusion.py](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm/model/diffusion.py)
的 halation amount 改变总量，半径仍由 spatial scale 控制；我方继承的
[pipeline.cpp](https://github.com/JamesQiu2005/SpektraLab/blob/a46627d250545a394ca21522f8a28b0fb89e4c42/engine/src/pipeline/pipeline.cpp)
还用 `sqrt(amount)` 乘半径。amount=4 时，后者半径翻倍。这是参数语义与物理模型的变化，
不是移植性能差异。原生 `antihalation == "no"` 才启用背反射模型，其他胶片仅关闭防光晕层不自动切到同一路径。
[halation_psf.hpp](https://github.com/JamesQiu2005/SpektraLab/blob/a46627d250545a394ca21522f8a28b0fb89e4c42/engine/src/core/halation_psf.hpp)
四高斯拟合相对的是其假设积分，拟合误差不能当作真实胶片测量误差。
下一步须测实际 GPU 混合 PSF 的能量、非负性、边缘响应和分层半径，尤其是带符号权重与 FIR/IIR 混用，
再与点光源及曝光序列参照比较。当前没有普遍优于原 Python 的实拍结论。

颗粒方面，主线程另核实了最新所列 e70771c 的
[stats.cpp](https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/engine/spektra-core/src/main/cpp/kernels/stats.cpp)
和 [grain.comp](https://github.com/thetechgeekko/Spektrafilm-android/blob/e70771cf99e61481feb471d5c4ac827a488c73f6/engine/spektra-core/src/main/cpp/gpu/grain.comp)：
大均值 Poisson 和部分 Binomial 使用正态近似，GPU 支持正态分支并统计其他分支以供 host 拒绝。
我方 [spk_rng.glsl](https://github.com/JamesQiu2005/SpektraLab/blob/a46627d250545a394ca21522f8a28b0fb89e4c42/engine/src/shaders/vulkan/spk_rng.glsl)
是小均值顺序、大均值 PTRS 的 Poisson 目标，但 float 参数、最多 64 次拒绝后回退 `floor(mu)` 仍是实现边界。
以 Poisson 均值 36 为例，其理论偏度为 1/6，正态为 0；同均值方差不足以证明同纹理。
目前没有证明这些边界在我方默认参数下造成缺陷，不能据此宣布某个采样器真实感获胜。

子 agent 检查的历史 Knonoa
[6d8dd5a 密度 LUT](https://github.com/Knonoa/SpektraLabAndroid/tree/6d8dd5a098e83f03e6bb67b222dc55014184c08e)
是 41 cubed 的光谱密度积分表、三线性采样并夹紧密度域；不是整条 RGB 外观 LUT，不能套用我方后者的色差数值。
同样，minilab LUT 替换的是印放校准节点。三类表必须各自测域边界和阶段误差。

已有 signed-domain 合成见证中，相邻 float32 的 Y 仅差 `1.4901161e-8`，最终 P3 UNORM16 相差
Delta E 00 `51.79945`；触发域为 B>0、Z<0、x>1 附近，并非普通照片误差或发生率。
保留分母符号的反事实消除了该见证，但其他危险域及 65 cubed LUT 仍有大误差，不能直接作为完整修复。
应覆盖 B=0、Y=0、Y+Z=0 的相邻浮点邻域，增加非负光谱生成的物理样本并明确支持域，不能把负 RGB 一律裁零。

## 继续开发顺序与验收

1. **完成已证明的 RAW 修正。** 本轮 0051 已执行，详见 [均匀黑点验证](ENGINE_LINEAR_RAW_BLACK_2026-11-12.md)。
   两云与 Windows 各通过 12 个基础、48 个独立参考、20 个冻结对照；取消/恢复和 ARM64 对象通过。
   更复杂 profile、McCamy/Robertson 温度差异、非均匀黑点仍单独评估，不扩大这张补丁。
2. **补独立 Python 分阶段参考，推进 signed 映射支持域与正确 IIR 边界候选。** 锁定代码、profile、光谱表和参数；
   分别看 film log/raw、film CMY、paper CMY、scan XYZ/最终 RGB。跨阶段误差要能定位。
   IIR 覆盖短线、常量、斜坡、边缘脉冲、HDR、方向翻转、混合通道、累加与两遍；保留旧模型回放。
3. **推进生产大图与总内存准入。** 吸收显式窗口、全帧索引、域/阶段账本；先保留真正等价的 point/FIR run，
   IIR 要续传正反向状态。通过全幅/多带高/不同 worker 对照，并记录解码、CPU、GPU、池、codec 和临时盘峰值。
4. **有样本再回移植新相机，有参考再引入新模型。** X-E5 / R6 III 是具体缺口；小米 DNG 优先级仍按用户输入。
   minilab/HDR/新 profile 单独研究；最后用真实 Adreno/Mali、热状态与受控胶片参照评估速度、内存和真实外观。

可吸收方法，不照搬测试结论。例如 OFX 的
[run_parity_harness.py](https://github.com/chaert-s/spektrafilm-ofx/blob/86476afc5b077de77e2278e3658d1ba9309892a1/tools/run_parity_harness.py)
非空间门为 max_abs 0.015、p99 0.006、RMSE 0.003，末尾统计 failed/infra 后仍 `return 0`。
主线程复核了这两段。因此“脚本成功退出”本身不是图像一致证明；我们的新增门禁要失败非零退出。

## 新版本与候选的边界

V2 与 pin 的基础 constants Git blob 同为 `147b6d3a99c9b213a1e6adab590740084e488217`，9,450,196 字节；
本轮子 agent 核对的 colour/curves/hanatos/numeric/printing/profile/spectral/print_lut.cpp 也相同。
这只说明所列资源/文件相同，不代表 V2 全管线相同。新增路由和节点确实可以改变成像。
[minilab index](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/resources/minilab/index.json)
只有 Portra 400→Portra Endura 一个材料对、两个校准空间，不能外推为所有胶片更准确。
[非 Apple 能力拒绝](https://github.com/JamesQiu2005/SpektraLab/blob/1952c63537ec576d24422f1065b12495631346a5/engine/src/pipeline/windows_unported.hpp)
仍存在；main 与 Windows_Linux 也不是可以直接顺序升版的同一线。

CAM16 预烘焙表可继续测冷建表时间与 ARM64 数值；已有 0026 invariant hoist 不重复实现。
Rust 的 [PCHIP](https://github.com/turbasvin/spektrafilm-rs/blob/9dd59b0380194b93686aaa230a8bb9680aa270a4/crates/spektrafilm-math/src/pchip3d.rs)
预计算斜率和 cell min/max，会增加内存并改变插值，尚不能解决我们已有负域缺口。

机型候选源码：[X-E5](https://github.com/JamesQiu2005/SpektraLab/commit/0799cf7320b4c2bdf0b09cdf84f79619fd7f86cf)、
[R6 III](https://github.com/JamesQiu2005/SpektraLab/commit/d978ba9b0f1764dbce27542802cdd709bc83271f)。
前者借用 X-H2 校准并限制型号/非 DNG/三通道；后者含 ColorData subversion 66 与独立矩阵，
III 必须在 II 之前匹配。尚无本项目实际机型文件验收，不据提交标题宣布准确。

本轮没有全生态穷举、受控实拍比较、所有衍生版运行测试或手机验收，也没有新 APK。
引用源码与数据仍分别遵守其来源声明；未复制其他项目私有二进制素材或默认色相政策。
