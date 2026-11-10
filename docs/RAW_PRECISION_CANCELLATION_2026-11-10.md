# RAW 精度、取消与内存适配

共创：GPT（OpenAI），2026-11-10。仅源码、合成测试和验证记录；不更改版本、不打包或发布。
RAW 默认保持 neutral/场景线性，不默认启用自动曝光、显示曲线或饱和度增强。

## 浮点白平衡

受支持的 float32 LinearRaw 使用自定义白平衡时继续走 `float_linear_v1`。
原始 AsShotNeutral 的倒数留作拍摄白平衡，实际所选倍率另行保存并按最小 RGB 倍率归一化，
再与原始逐通道黑白点和准备好的相机矩阵组合。显式色温使用同一校准入口。
这避免了为修改白平衡而重新落回整数解码，也避免两次归一化。

支持边界没有放宽：单 IFD、小端 classic TIFF、单条未压缩 chunky RGB float32、完整 ActiveArea、
常量黑、明确逐通道白点和受支持单 CM/FM。压缩、float Bayer、复杂裁剪、opcode/profile 等仍由
原诊断明确标为量化兼容。支持路径内的无效数值、读失败或取消直接失败，不暗中走量化回退。
有效 RGB 白平衡须为有限正数，最大最小倍率比不超过 1000；显式色温为 1700–50000 K，0 表示推断。

首次白平衡专项中，AOIDC GCC 11 完整 helper/dispatcher 的正常及 ASan/UBSan 均通过；
IBX GCC 14 的 helper 正常及 sanitizer 通过。包括 14 组默认输出与修改前二进制逐字节一致、
56 组相对倍率/尺度/原拍摄白平衡、68 组独立 Robertson 温度/tint 数学对照，及非法参数和取消。
最大绝对误差为 `4.76837158203125e-7`，原绝对容差 `2e-6` 未放宽。
IBX 的该次专项不包含完整 dispatcher；不能把 helper 验证写成全链验证。

## 浮点输入按行读取

在同一严格格式子集内，保留验证元数据时打开的输入流和 strip 偏移，LibRaw 仍负责打开、
相机校准、decoder 名称与几何核对；随后按行读取小端 RGB float32，不再让 `unpack()`
额外分配整张 `float3_image`。原 WB、矩阵、归一化和方向运算保留。每行检查取消，
读到截短文件时原子失败；验证后替换路径不会改变已打开的描述符。不在失败后暗中量化重试。

原有 16,777,216 像素及输入加输出 384 MiB 的保守准入上限不变。输出 RGB float 仍为整图，
因此这只消除该子集的一份整图输入，不是通用 RAW 分块或整个进程的硬内存预算。

AOIDC GCC 11 和 IBX GCC 14 的正常及 ASan/UBSan 均通过 42 组像素逐字节比较；
14 组默认 WB 还比较全部公开解码元数据。4096×3072 合成输入的 150,994,944 字节输出
经实际 `cmp` 完全一致。AOIDC 三轮交替独立进程的旧峰值 RSS 为
300192/300176/300220 KiB，新值为 152900/152940/153012 KiB，约减少 144 MiB。
这是不含 Vulkan 会话的宿主解码实验，不能换算手机整体峰值或提速。

## 协作取消

保留原 `openRaw` ABI，新增版本化可取消入口。编辑器和导出队列各自持有独立的 native 原子令牌。
Kotlin 的取消监听器可以在 EngineThread/ExportThread 阻塞于 JNI 时发出信号。
先等待 native 返回，再结束并 join 监听器，最后释放令牌。候选会话仍服从原事务所有权；取消不替换已提交图像。

0037 将信号继续传到 LibRaw 进度回调、DNG GainMap 解析/逐行校正、浮点转换和发布边界。
外部回调串行执行，取消状态保持；回调异常也按取消处理。LibRaw/OpenMP worker 不抛出 C++ 异常，
发起线程在 worker 结束后的安全边界抛出明确的取消类型。半尺寸解码预算改用 RAII，异常和取消均复原。

这是协作取消：provider 描述符/复制 I/O、`open_file`、没有回调的单次解码操作、统计/resize/缩略图
和同步 GPU 会话创建可能先完成当前调用。不会在 GPU 仍使用资源时强行释放。

实际兼容 decoder 对整数 Bayer/LinearRaw、全尺寸/半尺寸、默认/自定义白平衡的八种组合，
与冻结的前一版 archive 比较完整浮点输出和全部非计时元数据，均逐字节相同。
逐个进度位置取消、回调异常、重试、OpenMP 调用方设置复原，以及 GainMap worker 和八线程回调串行测试通过。
正式 JNI 三条 RAW 路由共覆盖 23 个取消位置，另含公开令牌跨线程取消、旧缓存保留、候选释放与重试。
六项纯协程测试验证阻塞 native 的即时信号和令牌释放顺序。

另外修复 `open_pixels` 的分配失败所有权：先建立 C++ 会话包装对象，再打开引擎会话，
引擎句柄在注册完成前由 RAII 持有。正式 JNI harness 定向注入包装对象 `bad_alloc`，
验证 OOM 分类、旧缓存和会话计数不变、随后可以成功重试。最终正常及 ASan/UBSan 均通过。

## GainMap 完整性

0038 修复了合成 DNG 能重现的两个处理问题：

- 列表声明超过 1024 项时，旧循环只解析前 1024 项并应用其中的校正，可能跳过后续内容校验。现在在处理像素前整表拒绝超限列表，不应用不完整前缀。
- 极大行列采样步长会使 32 位迭代/行数运算回绕，导致额外校正区域外的一列，或漏掉第一行。现在用 64 位步长运算，保留有效区域和第一采样点。

还将重复黑电平尺寸的乘法提升到 64 位，超过数组容量时整表不应用。该项的异常 API 状态在旧实现会越界，
但 pinned LibRaw 从普通 DNG 读取 SHORT RepeatDim 并限制容量，未证明这种 API 状态可由正常 DNG 文件产生。
不将其描述为已发现的普通照片问题。

正常单地图、1024 项完整列表、合法重复黑电平和逐像素参考保持一致。
同时核对合成 DNG 经实际 LibRaw open/unpack 后的行为；两台云主机正常和 ASan/UBSan 均通过。
本补丁没有调整原有 Bottom/Right 边界兼容策略，也没有增加未知 opcode 的实现。

## 验证范围

合成验证记录在 [RAW 取消](../tools/native/validation/raw-open-cancellation-20261110/README.md)
及 [精度与 GainMap](../tools/native/validation/raw-adaptation-20261110/README.md)。
最终行读取、JNI 分配失败和 Android 集成记录见
[行读取与最终验证](../tools/native/validation/float-strip-streaming-20261110/README.md)。
测试只使用公共生成的合成像素，没有用户照片、私人文件名、照片散列或私人照片测量。
Sanitizer 只覆盖各 runner 明确编译的单元，复用的引擎/LibRaw/codec archive 不因此变成整体插桩。

最终 NDK r27.2 / Clang 18 ARM64 原生构建通过，旧 RAW、新取消入口及 F16 的 JNI 符号存在。
保存的 Debug XML 为 8 suites/53 tests，Release 为 14 suites/98 tests，均零失败、错误及跳过；
最后一轮 Gradle 的 JVM 任务为 `UP-TO-DATE`，不将保留报告说成该次重新执行。
固定 pin 的 38 个引擎补丁、16 个 LibRaw 补丁零 fuzz 重放，93/22 个源文件核对一致。
本轮未调用 APK assembly、安装或发布任务。

尚不能据此宣称通用 DNG 浮点解码、完整外存分块、整个进程的硬内存预算、手机驱动/热状态验收，
或与相机 JPEG/Lightroom 完全一致。按行读回、编码、解码与全流程外存调度是不同层次的适配。
