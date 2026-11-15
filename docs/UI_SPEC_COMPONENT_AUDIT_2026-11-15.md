# 高级参数组件规格审计，2026-11-15

## 结论与范围

最高价值的修订顺序是：补齐连续调节回调和取消收尾；修正曲线、调色盘的操作面积；明确调色盘轴语义；落实真实色轮；把边框名称列表改为可比较的版式选择器。只缩小共享胶囊、卡片和页面间距，无法解决这些问题。

初始审计阶段仅创建本文件；用户随后授权新增 ColorWheel.kt 与 ColorWheelUiTest.kt，具体实现及接入示例见末节。未修改其他应用或测试文件，未运行本地应用编译、测试、模拟器或截图，未访问私人照片。以下审计结论来自实现前源码快照，不是已通过真机验收的结论；主线程后续改动应按函数名重新核对。云端验证由主线程负责。

- 已完整阅读 [用户规格](C:/Users/80983/Downloads/spektralab_x_ui_research_and_design_spec.md:1)，共 122 行，重点对应第 21、27、89、103、121 行。
- 仓库：`D:/AGI/GPT/.codex/worktrees/engine-android-research/wt-gpt-engine`，读取时 HEAD 为 `7163fadeb9e610042305631abe94894083aa5fcb`。工作区有主线程并行改动，本文以未提交工作区为准；行号配合函数名查找。
- 主线程负责 Editor 骨架、ThumbCard、SelectPill、Segmented、CategoryBar。本文件不代做这些组件；共享组件部分只报告仍影响高级控件的接口和调用问题。
- 最后复核时，`Pill.kt:85` 的 RowGap 已为 8dp、GroupGap 为 16dp；`SelectPill:120` 和 `Segmented:262` 外层已为 48dp。旧版“分段实际仅 40dp 高”和“12dp 行间距”的描述不再作为当前缺陷。
- 证据等级：**确认**表示源码直接可见；**风险**表示存在明确代码路径但尚未实机复现；**建议**表示下一版尺寸或接口，不冒充现状。

本文登记 20 项：P1 10 项，P2 10 项，无已证实 P0。P1 表示明显操作障碍或核心规格缺口；P2 表示精度、一致性及边界问题。不对未观察到的帧率、视觉完成度或整应用可访问性给出分数。

## 先处理的六项

| 优先级 / ID | 路径 / 行号 | 已确认问题 | 最小可执行修订 | 验收标准 |
| --- | --- | --- | --- | --- |
| P1 R01 | [AdjustPanel.kt](../app/src/main/java/com/dearded/spektralab/AdjustPanel.kt#L86)，同文件 140、151、161、221；[Sliders.kt](../app/src/main/java/com/dearded/spektralab/Sliders.kt#L78) | 普通调整、HSL、色彩平衡、暗角滑杆未传 onDrag，使用默认空回调。只有曲线、调色盘明确传入 vm::setInteracting。 | Adj 显式要求交互回调；直接调用 LabeledSlider 的分支同时接入。随后迁移为开始、变更、完成、取消接口。 | 一次拖动对应一次 start、若干 change、一次 finish；松手确实到达渲染协调层。 |
| P1 R02 | [Sliders.kt](../app/src/main/java/com/dearded/spektralab/Sliders.kt#L182)、[Curves.kt](../app/src/main/java/com/dearded/spektralab/Curves.kt#L248)、[PalettePad.kt](../app/src/main/java/com/dearded/spektralab/PalettePad.kt#L131) | 三种自定义手势均在循环后手动复位，没有 finally。 | 把已启动手势的状态清理放入 finally，结束与取消分别报告，保证幂等。 | 拖动中切模块、禁用、旋屏或移除组件后，pressed、active、fine、LocalSliderDrag 与 interacting 全部清理。 |
| P1 C01 | [Curves.kt](../app/src/main/java/com/dearded/spektralab/Curves.kt#L225)，同文件 237 | 曲线外高 124dp，内部上下 padding 后图面约 112dp；通道与重置在不可滚动 Row。 | 紧凑图面 192dp，320dp 窄屏 176dp；展开为 288/320dp 方图。通道独立横滚，重置固定 48dp。 | 四通道、五通道与 English 标签均可达；图面不挤压，端点可抓取；读数和操作不遮图。 |
| P1 P01 | [PalettePad.kt](../app/src/main/java/com/dearded/spektralab/PalettePad.kt#L195)、[Look.kt](../app/src/main/java/com/dearded/spektralab/Look.kt#L94) | 规格 X 轴要求冷暖，实际模型控制饱和度与自然饱和度，背景却使用偏暖色渐变。 | 短期保留模型并准确标“浓淡”；若严格实现冷暖，新增明确映射和兼容版本，不能仅换文案。 | 横轴两端标签、渐变、数值与照片变化含义一致；旧风格不被静默重解释。 |
| P1 W01 | [AdjustPanel.kt](../app/src/main/java/com/dearded/spektralab/AdjustPanel.kt#L143) | 四个 Zone 存在，但 UI 只有分区切换加色相、强度、明亮度滑杆，没有色轮。 | 一个可编辑轮切换四区；完整提供 H/S/L 数值操作。紧凑轮直径 176dp，展开 224dp。 | 角度调 hue、半径调 sat、中心 sat=0 保留 hue；四区状态独立，全部接入 R01/R02。 |
| P1 B01 | [BorderGroups.kt](../app/src/main/java/com/dearded/spektralab/BorderGroups.kt#L65) | 50 个目录项以分组后的 FlowRow 文字胶囊呈现，不能直接比较留白、片边和印样比例。 | 紧凑态单行版式预览，完整浏览在展开内容；提供 preview slot，不重写主线程 ThumbCard。 | 使用合成样片能一眼区分白边、黑边、拍立得、数据条；所选项和无边框始终可找。 |

## 1. 连续调节、渲染与撤销

### R01 / P1：普通滑杆没有调节生命周期

**确认。** `AdjustPanel.kt:221-223` 的 Adj 只转发 onChange。曝光 `:86`、色轮 hue `:151`、暗角范围 `:161` 也未传 onDrag。HSL 的 `:140-142` 通过 Adj 继承问题。`Sliders.kt:78` 把 onDrag 默认为空，`:200` / `:210` 即使调用，也不会通知 ViewModel。

影响必须准确区分：`EditorViewModel.kt:854-859` 以 interacting 管理 settle，`:911-925` 用它协调 live/preview；但 `:595-596` 的 Look-only 修改可能直接走 repack。不能据此声称所有高级调色拖动都在反复跑 3400px 或一定卡顿。已确认的是回调契约缺失，松手没有明确提交信号，和用户规格第 21 行不符。

**建议。** Adj 必须接收非默认的交互接口；曝光、HSL、Zone、暗角也全部经过同一入口。开始回调先于首个修改，最终值先于 finish。不要用 LocalSliderDrag 的“界面淡出”代替引擎交互回调。

**验收。** 每类参数记录 start/change/finish/cancel 序列；拖动、点击步进、输入、TalkBack、重置都有明确提交。UI 只提交生命周期；是否重显影、repack、以及最终分辨率由引擎协调，不能硬编码“3400px 已完成”。

### R02 / P1：取消或移除组件缺少确定性收尾

**确认代码结构，运行后果为风险。** `Sliders.kt:189-219`、`Curves.kt:263-310`、`PalettePad.kt:144-190` 在正常循环后结束手势，没有 try/finally。pointerInput 的 key 改变、Composable 被移除等会取消协程，普通尾部语句并非可靠清理点。

**建议。** 使用局部 started / ended 标志。只有成功接管手势才 start；finally 清理按压、高亮及 LocalSliderDrag；finish 和 cancel 分别报告。建议取消采用“保留已显示的最后值并提交一次”策略，返回起始值只能作为明确的取消编辑行为。通道/分区改变前结束上一手势。

**验收。** 移除组件、enabled=false、切通道、切工具、旋屏、多指抢占、系统返回后，没有滞留淡出或拖动态；已开始的事务恰好结束一次；普通点击不伪造长拖动。

### R03 / P1：一次拖动一条历史记录的注释没有实际保证

**确认。** `PalettePad.kt:58` 称 onDrag 将一次手势作为一个历史步骤。实际 `EditorViewModel.kt:580` 每次修改调用 history.record，`EditHistory.kt:8、19-26` 只按 700ms 间隔合并，setInteracting 没有打开/关闭历史事务。

**影响。** 拖动途中停住超过 700ms 后继续移动，可拆为两步；两个不同控件快速连续操作又可能被合并。属于源码可推导路径，本次未执行复现。

**建议。** 回调带稳定 parameterId 和 interactionId，在协调层提供 begin/commit/cancel edit transaction；同次手势不论停顿多久只记录一个 before，结束记录 after。离散操作各自成步；组件不维护第二份整张照片历史。

**验收。** 拖动 2 秒、停顿 1 秒再拖动并松手，撤销一次回到手势起始值；两根不同滑杆在 200ms 内分别操作仍能分两步撤销。

## 2. Sliders.kt：密度、精度与输入

### S01 / P1：整体行偏高，真正的拖动区却偏低

**确认。** `Sliders.kt:139、150` 的数值/复位使标题行至少 48dp，`:164` 的轨道 Canvas 高 36dp，单参数最少约 84dp，不含 ends、原因和父级间隔。三个参数至少 252dp；按当前 RowGap=8dp 再加两个间隔是 268dp。HSL 的工具切换和色带选择尚未计入。仅缩小外层行距不能把它变成完整的 260-300dp 操控台。

轨道 pointerInput 所在布局仅高 36dp；标题行的 48dp 点击区不能替轨道证明触控面积。系统可能扩展命中范围，故不宣称“36dp 外一定点不到”，但不能依赖相邻扩展区域来满足本项目 48dp 目标。

**建议。** 紧凑态只编辑一个选定参数，使用 48dp 参数选择行 + 单个 96dp 精密控件（48dp 标题/数值/重置行 + 48dp 拨盘操作区，视觉轨道仍仅 2dp）。完整多参数态保留三行时，每行 96dp、间隔 8dp，明确作为可滚动展开内容。通过减少同时出现的主控件降低密度。

**验收。** 每个独立数值入口、复位、轨道均有不互相抢占的至少 48x48dp 操作域；紧凑态一个完整参数可操作；展开态底行可达。fontScale=1.3/2.0 允许行高增长，不缩小字号。

### S02 / P2：仍为滑杆，步长从显示格式推导

**确认。** `Sliders.kt:222-250` 绘制默认点、填充轨道、空心拇指；没有规格 PrecisionDial 的固定中心指针、移动长短刻度。`stepFor:295-298` 从格式和总范围推导步长：曝光 -3..3、两位小数得 0.03EV；0..360 的色相得 2 度。显示精度与操作步长被绑在一起。

**建议。** 用显式 `ParameterSpec(step, fineStep, defaultValue, unit, scale)`。曝光普通/微调均可到 0.01EV；Zone hue 普通 1 度、微调 0.1 度；HSL 普通 1、微调 0.1。拨盘操作区高 48dp，中心针宽 2dp、高 24dp；小刻度 6dp、大刻度 12dp；常用整步间距 8dp，微调增益 0.25，主刻度周期由参数定义。

`Look.kt:26` 的 temperature 是 -100..100 偏移量，没有 Kelvin 模型时不能标 K；`Band.hue` 同样是 -100..100 偏移量，不能直接加度符号。`Zone.hue` 才是角度。

**验收。** 点按、拖动、输入、TalkBack 都遵守显式步长；可调到 +0.01EV；Zone hue 的 359/0 按圆周处理；单位与模型一致。

### S03 / P2：命中和绘制使用不同的轨道几何

**确认。** `Sliders.kt:177、181` 手势按左右各 12dp 计算，`:225、228-230` 绘制按左右各 14dp 计算。端点相差 2dp，归一化比例也不同。

**建议。** 共享 `TrackGeometry(contentRect, valueToOffset, offsetToValue)`；统一左右 16dp 内容内缩，视觉和命中使用同一坐标。保留相对拖动，不在首帧跳到手指正下方。

**验收。** 最小、默认、最大值的绘制中心和触控映射一致；仅允许不超过 1 个物理像素的取整误差；往返拖到端点无跳变。

### S04 / P2：气泡绘制在 Canvas 顶部之外

**确认。** `Sliders.kt:248` 的 top = cy - thumb - 6dp - h。在 36dp Canvas 中 cy=18dp、拖动 thumb=14dp，top 必为负。可能画到标题行，是否被裁剪取决于宿主，不能静态断言一定消失。`:162` 的 bubbleStyle 未继承 UiMono，仅设 tnum。

**建议。** 紧凑态使用固定顶部等宽读数，取消重复气泡；保留气泡时显式预留至少 28dp，按实际字体测量，不靠越界绘制。数值使用 `Readout.copy(fontSize=12.sp, lineHeight=18.sp)`，单位使用 UiSans。

**验收。** 端点、负号、+3.00 EV、360 度及 fontScale=2.0 均不遮标题、不裁字；读数列不因位数变化而晃动。

### S05 / P2：过滤输入可能悄悄改变正负与小数

**确认。** `Sliders.kt:266` 删除非数字/ASCII 负号/点再解析，`1,5` 变为 `15`，Unicode 减号 `−1` 变为 `1`；`:285` 超范围直接 clamp。

**建议。** 只明确规范化 Unicode 减号、全角符号，之后整串解析，不剥除未知字符。范围外显示具体错误并禁止确认，或清楚显示将采用的值。输入界面提供单位、范围、IME Done；负值参数验证键盘有负号。

**验收。** `−1` 为 -1；`1,5` 要么明确支持为 1.5，要么报错，绝不变成 15；超范围不静默改变；取消不改模型。

## 3. Curves.kt：比例、端点与非触摸操作

### C01 / P1：112dp 图面与不可滚动通道行不匹配精细编辑

**确认。** `Curves.kt:237` 为 `height(124.dp).padding(vertical=6.dp)`，有效绘图区约 112dp；`:225-233` 的通道、Spacer、重置同处普通 Row。五通道模式由 `includeLuma=true` 启用，现有胶片前曲线正在使用它。窄屏/English 的实际溢出程度需截图测量，结构上没有横滚或换行策略。

**建议。** 通道行高 48dp，通道最小宽 48dp、左右内容间距 8dp，独立横滚；固定重置 48x48dp，二者间距 8dp。紧凑图面：常规手机高 192dp、320dp 窄屏高 176dp；展开图面：宽度足够时 320x320dp，320dp 手机为 288x288dp，居中。图内四周留 24dp 端点命中带，曲线坐标和直方图都映射到同一个内图 Rect。控点视觉直径 10dp、活动点 14dp、捕获直径 48dp；主线 2dp、其他通道 1dp。

**验收。** RGB、R/G/B 和额外 Luma 均可达；最左下/右上端点不因位于 Canvas 边界而只剩四分之一捕获区；选中通道滚入视口；展开后横纵单位物理尺度一致。

### C02 / P1：曲线有描述和重置，没有可访问的点编辑能力

**确认。** `Curves.kt:238-242` 只提供描述和重置 customAction；`:248-309` 的添加、移动、删除均靠触摸。TalkBack 用户不能完成相同的曲线编辑任务。

**建议。** 在“数值”模式暴露选点、输入、输出、删除/添加点；选点前后按钮各 48x48dp，X/Y 数值入口各至少 72x48dp。端点 X 锁定；中间点 X 按现有邻点约束限制。常规显示采用 0-100%，胶片前曲线采用 -8..+8EV 的坐标格式器，UI 单位与模型分离。语义包含通道、点序号、X/Y、可否删除、disabled 状态。

**验收。** 仅用 TalkBack 或键盘即可创建、中移、删除一个中间点，并回退到恒等曲线；触摸与数值路线输出相同 CurveSet；禁用时没有可执行修改动作。

### C03 / P2：采样曲线提示与本组件重置状态不一致

**确认。** `Curves.kt:232` 在 editable=false 时禁用内部重置，`:356` 却写“重置后可编辑”。`EditorScreen.kt` 的胶片前曲线调用点另有外部“重置采样曲线并开始编辑”按钮，所以当前页面并非完全无法恢复；问题是复用组件依赖隐含宿主出口。

**建议。** 明确传入 `onResetImported: (() -> Unit)?`；有动作才显示明确入口，没有则只显示“导入采样曲线，只读”。按钮最小高 48dp，长英文允许两行。保留 SceneCurve.kt:19-24 的采样数据与插值行为，不为追求视觉贝塞尔而改变数值。

**验收。** 所有只读模式的提示都对应屏内可达动作；未重置前不改进口数据；重置范围明确到当前通道或全部通道。

## 4. PalettePad.kt：轴语义、手势与读数

### P01 / P1：规格“冷暖”与实际“浓淡”不一致

**确认。** 用户规格第 109 行写 X 冷暖、Y 反差。`Look.kt:94-106` 中 colour 改 saturation/vibrance；tone 改阴影、高光、对比和黑场，不等于单独曝光或 brightness。`PalettePad.kt:195-199` 用灰、暖棕、橙色横渐变，与冷暖轴也不符；`AdjustPanel.kt:210` 和 EnglishCatalog.kt:82 的说明又把纵轴近似为明暗/brightness。

**建议。** 当前兼容实现标“浓淡 / 影调”，端点用“低饱和 / 高饱和”“浓郁 / 通透”，并以中性网格避免虚假色温暗示。若主线程按新规格改成冷暖，新增显式 axis model / recipe version，将 X 映射到真实 temperature；既有 colour/tone 配方保持原意。默认点可能是 style home 而非 0,0，“回到风格默认”与“归零”必须区分。

**验收。** 只动横轴时照片变化与标签一致；回到非零 style home 恢复风格原貌；归零和回风格默认有分别明确的动作；旧风格数据不变义。

### P02 / P1：两个方向灵敏度不同，捕获半径过大

**确认。** `PalettePad.kt:118` 图面仅 116dp 高；`:142-143` 分别用 200/width、200/height 计算拖动单位，因此相同物理位移纵向变化明显大于横向。`:140` 把 48dp 当作半径，形成直径 96dp 的捕获圆；`:152` 在此范围优先接管纵向手势，放大了与页面滚动的冲突范围。`:129、174-175` 的吸附用参数单位距离，非屏幕距离。

**建议。** 紧凑盘面高 192dp，窄屏 176dp；展开为 288/320dp 方盘。相对拖动使用同一个单位/物理像素增益，默认 gain=0.6、微调=0.2；控点捕获半径改为 24dp。吸附在投影坐标中按 6dp 进入、10dp 离开形成滞回；风格默认点与中心分别显示。空白纵滑交给父滚动，按住控点后才接管双轴。

**验收。** 同样 40dp 的横/纵拖动，参数绝对增量一致；盘面控点 24dp 范围外纵滑能滚页；进入吸附不来回震动；到盘面四角仍可抓取返回。

### P03 / P2：英文行拥挤，TalkBack 状态只读取一次

**确认。** `PalettePad.kt:77-87` 在同一行放合并读数、“数值/调色盘”、复位；`:215-219` 把两端词与长手势说明塞进 SpaceBetween Row，没有换行策略。EnglishCatalog.kt:841 的说明明显长于中文。`:67-69` 在 remember(context) 中读取触摸探索状态，没有状态监听；同一 context 下改变系统触摸探索不能由该代码自行更新。双击只比较时间 `:185-188`，没有位置约束。

**建议。** 头行高 48dp，左侧短标题，右侧两个固定 48dp 操作入口；两个读数移到盘面后的 24dp 行，长说明独占可换行区域。持续暴露“数值”入口；动态监听触摸探索变化，不只在初次组合时判断。双击回默认采用平台手势识别或补明确空间限制，避免相距很远的两次点击误复位。长按微调用超时驱动反馈，而非只等下一次 move 才改变状态。

**验收。** 320dp English、fontScale=1.3 下没有互相遮挡；启动后开关 TalkBack 可即时使用数值路线；两次远距离点按不复位；静止长按到阈值即可看到微调反馈。

## 5. 色轮与 HSL：真实控件和独立状态

### W01 / P1：四区数值模型完整，实际没有四色轮组件

**确认。** `Look.kt:7、40-43` 有 master、shadowZone、midZone、highlightZone；`AdjustPanel.kt:143-153` 只切换区并绘制三根滑杆。仓库现有这条路径未绘制 SweepGradient/RadialGradient 色轮。此为用户规格第 110 行的功能表现缺口，不能把“四个区域可选”认定为“四色轮已完成”。

**建议。** 常规手机显示一个活动色轮，在 48dp 四区导航中切换，不能将四个可操作轮挤成一排。轮直径 176dp，内容容器至少 224dp 宽；展开轮直径 224dp。色轮外留 24dp 命中边带，拇指视觉直径 14dp、捕获直径 48dp。中心中性，圆周以 hue 映射，径向以 sat 映射；明亮度独立。0 度朝右并顺时针增加（屏幕 Y 向下），盘面、读数、数值输入都用同一约定。中心 sat=0 时保留最后 hue，避免下次拉开时色相跳回红色。色轮属于有意义的色彩数据显示，不能和编辑器装饰渐变混为一谈。

**触控建议。** 空白纵滑传递父级；从已选控点或色轮主动选择动作开始编辑；越过半径 sat clamp 到 100，hue 连续环绕。单指换 hue/sat，完整提供 H/S/L 数值模式；双指操作不作为唯一能力。四区导航分别保存 edited 状态。重置当前区与全部色轮在名称和范围上明确区分。

**验收。** 四区切换不串值；hue 在 359/0 处无大幅反向跳变；中心不抖动、不丢 hue；disabled 无修改；手势/数值/TalkBack 的 Zone 相同；全部接入 R01-R03。具体色轮渲染性能留给主线程云端/真机测量，不在本审计中预判。

### H01 / P2：色带有触控面积，但缺少完整的选择与编辑反馈

**确认。** `AdjustPanel.kt:115-142` 有 8 色、48dp 外容器、28dp 色块、40dp 选中环及 contentDescription，这些应保留。edited 点只在 `!on` 时出现，因此正在编辑的色带反而不显示 changed 标记。整条最少 8x48+7x8=440dp，必须横滚；当前没有选中色带自动进入视口或“第几色”的可视辅助。`band/tool/zone` 使用 remember，移除该分支或重建界面可能回到第一项。

**建议。** 每色保持 48x48dp，视觉色块 24-28dp、选中环 36dp；已修改点 4dp，位于右上，与选中环同时存在。色带下显示当前完整色名“蓝色 / Blue”和 edited 状态，12sp/18sp，不能仅靠颜色辨识。外露一个 48dp H/S/L 参数导航和一个 96dp 选定参数拨盘，展开数值模式再显示全部三行。状态提升到高级工具 state（toolId/bandId/zoneId），用稳定 ID 保存并恢复；模型切换不清除导航选择。

**验收。** 色觉受限用户可读色名选中同一色带；第八色在 320dp 可发现并可滚入；选中且已改时两个状态同时存在；旋屏、切曲线再返回保留色带及 H/S/L 参数。无默认字号低于 12sp。

## 6. 边框选择与品牌标识

### B01 / P1：以名称猜版式，多行文字挤占参数空间

**确认。** `BorderGroups.kt:58-72` 分组胶囊后直接 FlowRow 所有名称，未提供图示；50 项目录包含 none，见 `Frames.kt:559-656`。参考规格称 50 边框，本实现实际为 49 个非 none 项加 1 个无边框，本轮不据此扩展新增款式任务。

**建议。** `BorderPicker` 接收通用 preview slot。紧凑态展示一条版式预览：默认 tile 宽 88dp，窄屏 80dp，图示区高 64dp，文本区至少 36dp（两行，每行 18sp 行高），内部间隔 8dp，合计至少 108dp；长名称在完整浏览中全显。图示使用合成中性样片和真实 frame geometry，以 Fit 完整显示印样边缘，不能用 Crop 抹掉白边。选中用 2dp 琥珀边 + 对勾/语义 selected，非选中不包重描边。无边框也是同尺寸 tile，避免长文本按钮打断节奏。

完整浏览可用单一展开网格：320dp 布局在 16dp 边距内用 3 列（(288-2x8)/3=90.7dp），411dp 布局用 4 列（(379-3x8)/4=88.75dp）。这类有真实图片/版式差异的选择网格有用途，不等于装饰卡片墙。滚动由宿主统一负责，避免无约束 LazyVerticalGrid 嵌套到已有 verticalScroll。

**验收。** 无需点进每项，即可区分细白边、宽黑卡纸、拍立得、片边与数据条；完整预览不裁外边；当前项自动进入可见区域；参数设置紧随选择区，不能被十几行文本名称推到远处。审计和验证只用合成样片。

### B02 / P2：分类归属、外部变更同步和无边框范围存在具体缺口

**确认。** `BorderGroups.kt:27-43` 的 FILM 集合不含 contact、mount，而 `Frames.kt:564、579` 两项都有 EdgeSpec；它们被默认分到“留白”。`BorderGroups.kt:57` 的 rememberSaveable 初始化只用一次 current，外部 undo/粘贴把 current 改到另一组时，group 不随之更新。`:61` 的分类选择未传 enabled，禁用期间仍可切分类；这是浏览是否可用的未明确契约，不必武断地把浏览动作认定为数据修改错误。

另外 `Model.kt:460` 应用 none 时会保留原 perf，preset.date=null 又不关闭原 dateImprint（`:462-465`）；因此“无边框”可能仍有独立齿孔/日期。不能为了“无”而未经确认把所有独立装饰都清掉，也不能让文案宣称完全无装饰。

**建议。** 明确分类 metadata：contact、mount 入“胶片片边”，带 EdgeSpec 的未知项优先落该类，其余再 fallback。由外部 current 的实际变更驱动选中组同步，手动预览其他组时不强制跳回。API 区分 browseEnabled 和 applyEnabled。无边框文案/语义明确为“移除当前边框款式”，独立日期、齿孔用单独状态摘要；若产品要一键全部清除，提供明确 action scope，不暗改。

**验收。** undo/粘贴跨组后当前款式可见；contact/mount 不再藏在留白类；无胶卷的禁用 tile 有原因且不可应用；点击无边框后摘要准确反映仍开的齿孔/日期，或显式全清动作按约定清除。

## 7. Pill.kt 衔接、字体数字与中英文

### T01 / P2：共享结构已变紧凑，调用侧仍需约束长标题和展开说明

**确认。** 当前 `Pill.kt:176-197` 的 PanelHeader 把未加 weight 的单行 title、可选 summary、reset 放在一行；title 只 maxLines=1，没有明确宽度上限/省略策略。`:206-214` 的 FoldHint 让 12sp/18sp 的 Text 自身成为 clickable，没有显式 48dp 操作域，也没有展开/收起 stateDescription。QuietButton `:232-240` 已有 minHeight=48dp，但未显式约束最小宽度。SettingRow `:318-346` 的整行 toggle、56dp 最小高是已有正面实现。

**建议，仅供主线程接口衔接。** Header 主标题区 weight(1f)、summary 可折到第二行，右侧动作 min 48x48dp，左右间距 8dp；动作不因 English 长文挤没标题。FoldHint 改由完整 48dp 行承接展开，或单独 48dp 信息按钮，正文不充当难点的小目标；提供 expanded state。共享选择 API 接受 modifier、role、stateDescription，工具导航用 Tab，边框互斥选项用 RadioButton，组容器给出分组语义。SelectPill/Segmented/ThumbCard 的最终尺寸由主线程继续统一，本文不重复重构。

**验收。** 320dp、English、fontScale=2.0 下标题与“重置此项”同时可达；展开说明可用 TalkBack 改变并读出状态；图标按钮实际最小宽高均 48dp；不靠每处手写 padding 弥补共享接口。

### T02 / P2：Logo 未继承选中墨色，文字色 token 不能照抄到所有角色

**确认。** `Brands.kt:684-685` 的 LogoGlyph 固定取 MaterialTheme.colorScheme.onSurface，不使用 SelectPill 提供的 LocalContentColor。主线程调整共享选中 fill/ink 后，Logo 与文字仍可能不一致。`BrandPanel:628-639` 将 Logo 和 Text 并排，横向胶囊名称长度差异大；`:674-679` 又单独实现 ToggleRow，仅 Switch 可点，未复用整行 SettingRow。字体方面 `Theme.kt:144-173` 已有 UiSans、UiMono、tnum 与中文字形回退，是应保留的基础；但 Readout 没显式 lineHeight，气泡见 S04。

**建议。** LogoGlyph 接受 `tint: Color = LocalContentColor.current`，图示高度 16dp、最大宽 64dp、固定 slot 保持名称起点；特殊联名在更宽的品牌 tile/完整浏览中展示，不能强行缩成不可辨的字标。BrandPanel 开关复用 SettingRow 的整行 toggle，提供 disabled reason。主标题 16/24sp 半粗、标签 13/20sp、参数 14/20sp、辅助 12/18sp；纯数字 UiMono+tnum，中文标签/单位 UiSans，不用整段中英读数都套等宽。

静态 sRGB 对比计算（非设备色彩测量）：用户规格的 InkSecondary #8E8E98 对 Panel #131316 约 5.72:1，可作为普通辅助字；InkMuted #56565F 仅约 2.55:1，不应用于需要阅读的手势说明或范围。Hairline #24242A 对 Panel 约 1.20:1，只适合作非必要分割线，不能成为唯一控件边界/焦点指示；FilmAmber 对 Panel 约 8.89:1，深墨 #111113 对 FilmAmber 约 9.05:1。新增组件遵循主线程主题 token，不逐组件硬编码另一套。

**验收。** Logo 与同级文字在默认、选中、禁用态使用同一墨色；必读文字达到本次设计目标 4.5:1，必要控制边界/焦点达到 3:1；数字列固定宽度且基线一致；低对比 token 不承载唯一操作信息。中文和 English 切换不改变内部参数 ID、品牌 ID、色带 ID。

## 8. 三种视口的明确尺寸建议

设备数据按 Android densityDpi 计算：560/160=3.5，1440x3200px 对应 411.43x914.29dp；48dp 对应 168px。411x914 是全窗口近似值，布局必须先扣当前系统栏、导航和显示缺口 inset。以下尺寸均为 dp；文字用 sp；不把物理 px 直接当布局尺寸。

| 项目 | 411x914 常规竖屏 | 320x640 窄竖屏 | 873x393 横屏 |
| --- | --- | --- | --- |
| 高级组件左右边距 | 16，组件可用宽约 379 | 16，约 288 | 从宿主 contentWidth 决定；建议高级参数侧栏 336，左右 16 后约 304 |
| 参数行/触控区 | 标题 48 + 拨盘 48 = 96 | 同 96，不按屏宽缩小触控 | 同 96，侧栏滚动 |
| 紧凑曲线图面（外尺寸，含端点边带） | 高 192；四周命中留白 24 | 高 176；四周 24 | 高 176；四周 24 |
| 展开曲线图面 | 最大 320x320 | 288x288 | 普通侧栏宽 304 时为 304x304，需滚动；扩大高级区域后方可显示 320 方图 |
| 紧凑调色盘 | 高 192，共同增益 | 高 176，共同增益 | 高 176，共同增益 |
| 展开调色盘 | 最大 320x320 | 288x288 | 按侧栏可用宽做方盘，不能越过可用高强塞 |
| 色轮视觉直径 | 紧凑 176，展开 224 | 紧凑 176，展开 224，命中容器至少 224/272 | 紧凑 160，命中容器至少 208；完整数值向下滚动 |
| HSL 色带 | 每项 48，间隔 8，横滚 | 每项 48，横滚且选中项入视口 | 每项 48，不能缩到一排 8 个 |
| 边框预览 | tile 88x至少108，间隔 8 | 80x至少108，间隔 8 | 80x至少108，或宿主展开网格 |
| 圆角/线条 | 图面 8dp；轨道 2dp；必要焦点 2dp | 相同 | 相同 |

**预算约束。** 曲线自己的 48dp 通道行 + 8dp 间隔 + 192dp 图面 + 8dp + 24dp 读数行 = 280dp，尚未包含主线程的工具导航、面板头和系统 inset。若 260-300dp 指整个操控台，必须允许内容滚动、让部分说明进入展开态，或由主线程提供展开入口；不能宣称所有元素无滚动完整显示。320dp 设备单图已需 176dp；三根合法触控参数行则需 3x96+2x8=304dp，只能作为展开内容。

横屏总高仅 393dp。高级组件应消费宿主明确提供的 contentHeight，使用独立可滚动内容，不在自身硬编码 300dp 底部面板。侧栏 336dp 是给主线程的候选接口预算，不是要求本任务改 Editor 骨架；可用高不足时保持触控尺寸并滚动。fontScale=1.3/2.0 下以上文本相关高度均为 min，不允许 scale 整个组件或减小到 12sp 以下。

## 9. 建议接口与职责

以下仅为设计接口草案，未写入应用、未编译；辅助类型名称可按仓库约定调整。重点是显式参数语义、布局约束和生命周期，避免继续传一串位置参数。

```kotlin
data class ParameterSpec(
    val id: String,                 // stable, language independent
    val label: String,
    val range: ClosedFloatingPointRange<Double>,
    val defaultValue: Double,
    val step: Double,
    val fineStep: Double,
    val unit: String,
    val formatValue: (Double) -> String,
    val cyclic: Boolean = false,
)

// phase=Drag only while an actual gesture owns the pointer.
enum class ChangePhase { Drag, Discrete }

interface ParameterInteraction<T> {
    fun onStarted(interactionId: Long)
    fun onValueChange(value: T, phase: ChangePhase)
    fun onValueChangeFinished(value: T)
    fun onCancelled(lastValue: T)
}

enum class AdvancedControlLayout { Compact, Expanded }

@Composable
fun PrecisionParameter(
    spec: ParameterSpec,
    value: Double,
    enabled: Boolean,
    interaction: ParameterInteraction<Double>, // required, no empty default
    modifier: Modifier = Modifier,
    disabledReason: String? = null,
)

@Composable
fun RgbCurveEditor(
    curves: CurveSet,
    channel: CurveChannel,           // stable ID, not a localized list index
    selectedPoint: Int?,
    onChannelChange: (CurveChannel) -> Unit,
    onPointSelect: (Int?) -> Unit,
    interaction: ParameterInteraction<CurveSet>,
    layout: AdvancedControlLayout,
    modifier: Modifier = Modifier,
    includeLuma: Boolean = false,
    editable: Boolean = true,
    axisFormatter: CurveAxisFormatter,
    onResetImported: (() -> Unit)? = null,
)

@Composable
fun ToneColor2DPad(
    value: PadValue,
    home: PadValue,
    axes: PadAxes,                   // explicit Tone/Chroma vs Tone/Warmth
    interaction: ParameterInteraction<PadValue>,
    layout: AdvancedControlLayout,
    enabled: Boolean,
    modifier: Modifier = Modifier,
)

@Composable
fun ColorWheels(
    zones: Map<ZoneId, Zone>,
    selectedZone: ZoneId,
    onZoneSelect: (ZoneId) -> Unit,
    interaction: ParameterInteraction<ZoneEdit>,
    layout: AdvancedControlLayout,
    enabled: Boolean,
    modifier: Modifier = Modifier,
)

@Composable
fun HslDeck(
    bands: List<Band>,
    selectedBand: BandId,
    selectedParameter: HslParameter,
    onBandSelect: (BandId) -> Unit,
    onParameterSelect: (HslParameter) -> Unit,
    interaction: ParameterInteraction<BandEdit>,
    layout: AdvancedControlLayout,
    enabled: Boolean,
    modifier: Modifier = Modifier,
)

@Composable
fun BorderPicker(
    currentId: String,
    groupId: BorderGroupId,
    onGroupChange: (BorderGroupId) -> Unit,
    canApply: (BorderPreset) -> Boolean,
    unavailableReason: (BorderPreset) -> String?,
    preview: @Composable (BorderPreset) -> Unit,
    onPick: (BorderPreset) -> Unit,
    modifier: Modifier = Modifier,
    browseEnabled: Boolean = true,
)
```

生命周期契约：成功取得手势后 start；拖动中持续 change(Drag)；正常抬起报告最后值并 finish；取消只发 cancel，不再 finish；两者都在 finally 释放 UI 拖动态。数值输入、重置、步进和 TalkBack 可用一次 start/change(Discrete)/finish 的原子事务；打开输入框本身不开始编辑。取消后是否保留最后值由统一协调器按 R02 决定。事件中值域由 ParameterSpec 校验。

接入最小路径：先让 Adj 接收 required onDrag，所有直接调用补 vm::setInteracting；再用适配器承接新接口，在 ViewModel/历史层实现事务。不能先删旧回调再留下未接通的引擎。LocalSliderDrag 仅负责 UI 视觉状态。组件接收 modifier/约束并保存稳定工具选择，照片/渲染/历史仍由现有层负责。

## 10. 交给云端及真机的验收清单

以下未在本地执行，不代表现有云端测试已经覆盖。

| 场景 | 具体操作 | 通过标准 |
| --- | --- | --- |
| 三个视口 | 411x914、320x640、873x393，各开曲线、调色盘、HSL、色轮、边框 | 内容可达；图面符合第 8 节；操作区不因压缩跌破 48dp；无不可恢复的裁剪 |
| 中英/字体 | 中文、English；fontScale=1、1.3、2.0 | 必要数值和重置可见；长说明允许换行；不用缩字解决溢出 |
| 调节事件 | 每种控件拖动一次，随后步进、输入、重置 | 事件 begin/change/end 数量正确；松手提交明确；离散操作原子提交 |
| 取消恢复 | 拖动中切模块、切通道、禁用、旋屏、多指和返回 | 恰好一次结束；无 stuck pressed/fine/interacting；数据策略一致 |
| 撤销边界 | 同次拖动中停 1 秒再继续；两参数快速分别操作 | 前者一条历史，后者两条；不再只由 700ms 猜测 |
| 曲线 | 四/五通道，端点，中间点，数值路线，导入只读曲线 | 通道可达；端点可抓；数值可完成编辑；采样保留；重置范围准确 |
| 调色盘 | 同 40dp 横/纵拖动，吸附，非零 home，空白纵滑 | 共同增益；不误抢滚动；home/zero 不混淆；视觉和模型轴含义一致 |
| 色轮/HSL | 0/359 度、中心、八色首尾、跨工具返回 | 无环绕跳变；中心保留 hue；状态和 edited 标志正确；单位不伪装 |
| 边框 | 合成样片比较四类；跨组撤销；无胶卷；none+齿孔/日期 | 完整版式预览；所选项可见；原因清楚；none 范围和摘要一致 |
| TalkBack/键盘 | 无触摸完成一个参数、一个曲线点、一次色轮/HSL 修改 | 调节动作等价；焦点可见；语义含标签/值/状态；禁用操作不可执行 |

## 保留与取舍

保留现有相对拖动、默认点提示、直接数值输入、HSL 48dp 色块容器、整行 SettingRow、UiSans/UiMono 回退，以及与引擎一致的单调曲线插值。用户规格提到贝塞尔是视觉/实现参考，不是授权替换已与引擎一致的 Curve.evaluate 算法。

新规格的近黑、琥珀和紧凑比例与 PRODUCT.md / UI2.md 的旧白色选中、旧层级存在差异；本轮按用户新规格和主线程最新共享组件落地继续，不能恢复旧比例。InkMuted 和 Hairline 的低对比角色按 T02 限制。高级图面可显示有语义的色谱，照片周围不新增装饰极光。

当前没有基于私人照片的对照，没有帧率测量，也没有应用运行证据；不把“现代、精细”虚构成可量化评分。后续集中按上述尺寸、生命周期、语义和验收场景闭环，避免继续添加未经证实的装饰或框架改造。

## 任务升级后的实现交付

以上为实现前审计快照。用户随后明确授权新增一个色轮生产文件和一个测试文件，本次已通过 apply_patch 创建：

- [ColorWheel.kt](../app/src/main/java/com/dearded/spektralab/ColorWheel.kt)：`internal fun ColorWheel(label: String, value: Zone, enabled: Boolean, onDrag: (Boolean) -> Unit, onChange: (Zone) -> Unit)`。192dp 视觉直径，外含 12dp 控点安全边带，容器上限 216dp 且不超过可用宽。SweepGradient 顺时针排列红、黄、绿、青、蓝、品红、红，RadialGradient 从中性灰向外透明叠加；0/90/180/270 度分别为右红/下黄绿/左青/上紫。半径映射饱和度并 clamp，亮度保留。
- [ColorWheelUiTest.kt](../app/src/testDebug/java/com/dearded/spektralab/ColorWheelUiTest.kt)：沿用仓库 Robolectric/Compose 测试依赖，包含纯坐标方向、实际绘制色谱、拖出夹紧、纵滑退让、控点纵拖、圆外起手、双击、按钮/无障碍复位、无障碍调节、取消/禁用/移除/尺寸变化清理、默认尺寸及窄屏/横屏场景。只编写源码，未在本机执行或编译。

新组件只在取得拖动意图后发送 onDrag(true)，所有结束/取消路径在 finally 中发送 false 并恢复 LocalSliderDrag；圆外起手不编辑，圆内空白纵滑退让父级，控点捕获半径 24dp。单击不跳值，附近双击只归零 sat；按钮和 customAction 使用相同复位语义，保留 hue/lum。无障碍 setProgress 调饱和度，额外 customAction 调色相。数值 H/S 读数内置，完整数值输入可继续保留调用侧滑杆。

接入由主线程在现有 Zone 选择分支完成，不改本任务禁止的文件：

```kotlin
ColorWheel(
    label = listOf(tr("全局"), tr("阴影"), tr("中间调"), tr("高光"))[zone],
    value = z,
    enabled = enabled,
    onDrag = vm::setInteracting,
    onChange = ::setZone,
)
```

此交付实现 W01 的轮盘主体及新组件内部 R01/R02 契约；未声称已接入 AdjustPage、已修旧滑杆/曲线/调色盘回调、或已修全局历史 R03。PrecisionDial 和 AdjustPage 接入由主线程负责，未修改 AdjustPanel.kt、Sliders.kt、EnglishCatalog.kt。

### 0.200-alpha-1 集成前静态自查

取消、禁用、移除和尺寸变化均核对至已启动拖动的 finally 清理；双击要求两次圆内起手、各自不超过 touchSlop、两次位置接近且间隔在系统双击时限内，单击不改值。绘制、触控和控点使用同一圆心以及 `minDimension / 2 - 12dp` 半径，渐变绘制区域同步 inset 12dp；中心归零 sat 时保留 hue，全部操作保留 lum。

测试文件共 17 个 @Test。已读取仓库 BOM 2026.01.00 对应的 Compose UI Test 1.10.1 发布源声明，核对 `captureToImage()`、`TouchInjectionScope.cancel()`、`advanceEventTime()`、`fetchSemanticsNode()` 以及 width/height 的类型；上述调用不需要额外的 ExperimentalTestApi 标注。此次为静态 API 核对，未执行 Kotlin 编译、单元测试、应用构建或设备操作，不能替代主线程云端编译及运行结果。
