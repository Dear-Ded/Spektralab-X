# 界面规范 / UI specification

> 状态：**v1，调研完成、尚未实现**。M3 界面重构以本文为准，改界面先改这里。
> 依据：获奖与标杆应用（Halide、Darkroom、Photomator、Lightroom、Snapseed、Kino、Not Boring Camera）、同类胶片应用（VSCO、RNI、Dehancer、Dazz、NOMO、Huji、FilmBox、Polarr、Spektrafilm-android）、国内热门应用（醒图、美图秀秀、一甜、黄油、剪映、小米相册）、Hark Pro 的设计取向，以及用户评价里的高频好评与差评。来源链接见文末。

## 0. 原则

1. **照片是主角。** 照片四周保持中性灰 `#121212`，不加圆角，切换工具时照片尺寸不变。
2. **新手一步出片，老手不设上限。** 三层递进：一条“风格”缩略图条点一下就出片（第 0 层）→ 再点一下当前项调强度、性格、冷暖（第 1 层）→ 按冲印链路排列的全部参数（第 2 层）。
3. **所见即所得。** 选胶片、选风格时缩略图用的是用户自己的照片（Dazz 被骂“选滤镜时看不到自己的照片”）。
4. **不做的事：** 不放广告、不设付费墙、不强加关不掉的默认效果、不连续弹权限、不连续弹评分、不改写照片的拍摄时间、导出入口不藏进菜单（Snapseed 改版的头号差评）。
5. **“开启”不只靠颜色表示。** 琥珀色之外再加形状变化（填充、圆点），这是 Halide 用户看错 RAW 开关后总结的教训。
6. **动效只用在面板和外框上，照片本身瞬间切换。** 前后对比是 0 ms，渐变会干扰判断。

## 1. 视觉基准

**间距**：基准 4 dp，常用 2 / 4 / 8 / 12 / 16 / 20 / 24 / 32 / 48。屏幕边距 16，滑杆行距 12，胶片条内间距 8。触控区不小于 48 × 48。

**固定高度**：顶栏 56、工具头 40、二级条 88（64 缩略图 + 名称）、底部分类栏 64。

**字号**（中文用系统字体，HyperOS 上是 MiSans；数字用 Inter 等宽数字 `tnum`，只用于纯数字，不和中文混排）：

| 角色 | 字号 / 行高 sp | 字重 | 用途 |
|---|---|---|---|
| title | 20/28 | SemiBold | 页面标题 |
| section | 16/24 | Medium | 面板、分组标题 |
| body | 14/22 | Regular | 说明文字 |
| label | 13/20 | Medium | 工具名、滑杆名 |
| caption | 12/18 | Regular | 缩略图名称；中文不小于 12 |
| readout | 13/16 | Medium | 滑杆数值 |
| bubble | 15/20 | SemiBold | 拖动时的数值气泡 |
| figure-L | 22/28 | Medium | 导出大小、预计时间 |

中文去掉字距（现有 `letterSpacing 0.2` 改为 0），文字样式设 `zh-CN` 语言，避免回退到日文字形。

**圆角**：照片 0、缩略图 6、标签 8、卡片 16、浮层 22、底部面板顶部 28、按钮全圆角。

**层级用色阶，不用阴影**：

| 层 | 颜色 | 用途 |
|---|---|---|
| L0 | `#0B0B0C` | 应用背景 |
| 照片四周 | `#121212` | 只用于照片周围，设置里可改 18% 灰 / 黑 / 白 |
| L1 | `#141416` | 顶栏、分类栏 |
| L2 | `#1B1B1E` | 工具面板 |
| L3 | `#232327` | 底部面板、选中项 |
| L4 | `#2C2C31` | 数值气泡、菜单 |

琥珀色 `#E8A24A` 只用于：选中项、偏离默认值的数值、主操作按钮。滑杆未填充轨道从 `#2C2C31` 提亮到约 `#6A6A72`，满足 3:1 对比度。

**图标**：Material Symbols Rounded 矢量图，未选中 300 字重、选中 400 并填充；栏内 24、密集行 20、标签 18。只打包用到的图标。

**动效**（沿用 `Theme.kt` 里的弹簧参数）：

| 交互 | 规格 |
|---|---|
| 滑块 | 不做动画，1:1 跟手 |
| 数值气泡出现 / 消失 | fastEffects，缩放 0.8→1 加淡入 |
| 重置 | 数值用 defaultSpatial 回弹 |
| 切换分类 | 面板内容 defaultEffects 交叉淡入，面板高度不变 |
| 强度面板展开 | fastSpatial |
| 底部面板 | defaultSpatial，支持预测性返回 |
| 前后对比 | 瞬间，0 ms |
| 按钮按下 | 缩小到 0.96，fastSpatial |
| 首次引导提示 | slowSpatial，同时只出现一个 |

系统“移除动画”开启时一律跳过动效。

**震动**（最低 API 29，新常量要有回退）：

| 事件 | 常量 | 回退 |
|---|---|---|
| 滑杆经过 / 吸附默认值 | CLOCK_TICK | — |
| 滑杆到头 | GESTURE_THRESHOLD_ACTIVATE (34) | CLOCK_TICK |
| 档位拨盘每一档 | SEGMENT_TICK (34) | CLOCK_TICK |
| 重置、导出完成 | CONFIRM (30) | VIRTUAL_KEY |
| 套用风格 | CONTEXT_CLICK | — |
| 打开强度面板 | GESTURE_START (30) | CONTEXT_CLICK |
| 长按对比 | LONG_PRESS | — |
| 开关 | TOGGLE_ON / OFF (34) | CLOCK_TICK |
| 出错 | REJECT (30) | LONG_PRESS |

拖动过程中不连续震动，滑过胶片条时也不逐个震动。

## 2. 页面

### 2.1 启动页 / 空白页

```
┌──────────────────────────────┐
│ Spektralab-X      [观片台][⚙] │
│      （淡淡的安全灯光晕）        │
│  最近编辑  ▢ ▢ ▢ ▢  全部 ›    │ → 观片台
│  示例：同一张照片的 3 种胶片     │ 用内置示例图预先渲染
│ [ 打开照片 ]   用示例照片试试    │ 琥珀色主按钮 + 文字按钮
└──────────────────────────────┘
```

- 不做多步欢迎页，不在启动时要权限。用户第一次选照片时才走系统选图器（无需权限）。
- 缩略图加载中显示纯色占位，不做闪烁骨架屏（每帧重绘费电）。

### 2.2 编辑器

```
┌──────────────────────────────┐
│ ←   ↶ ↷              ⋯ [保存]│ 顶栏：只留返回、撤销、重做、更多、保存
│      照片（适应屏幕）          │ 双指缩放、双击放大、单击隐藏全部界面
│                       [◐]    │ 对比按钮：按住看原图，单击切换
├──────────────────────────────┤
│ 曝光                 +0.70 ↺ │ 工具头（↺ 仅在改过时出现）
│ ─────────────●═══════        │
├──────────────────────────────┤
│ ○曝光 ○对比 ●高光 ○阴影 ○…    │ 二级条：改过的项带小圆点
├──────────────────────────────┤
│ 风格  胶片  调整  冲印  效果  构图 │ 一级：图标 + 两字标签
└──────────────────────────────┘
```

- **顶栏**：“任务”“设置”“打开”收进 ⋯ 菜单；“保存”保持琥珀色主按钮，位置不变。
- **一级分类栏**：图标加两字标签（美图秀秀删图标只留文字被骂）。点进二级时，二级面板原地替换，带“× / ✓”；按 × 恢复到进入前的状态。
- **分类顺序按冲印链路**（同 Dehancer 和引擎本身）：风格 → 胶片（曝光、显影）→ 调整 → 冲印（相纸、放大机）→ 效果（颗粒、光晕、暗角、边框）→ 构图。偏离默认值的分类在图标上显示琥珀小圆点，长按圆点重置整组。
- **风格**（新手入口，合并现有的“风格条”和“胶片条”，消除两条语义不同的缩略图条带来的困惑）：
  - 顶部分类标签：★收藏 · 最近 · 彩负 · 反转 · 黑白 · 电影 · 大师 · 厂商；再加情绪标签：清淡 · 浓郁 · 暖 · 冷 · 黑白。
  - 缩略图两行名称：上行是好记的名字，下行灰字写胶片和类型，如“Portra 400 · 彩负”。
  - 点一下套用；再点一下当前项，原地展开 **强度 / 性格 / 冷暖** 三根滑杆（参照 VSCO Film X）。强度按“每张照片、每个风格”分别记住，再次套用不会回到 100%（Darkroom 的差评）。
  - 长按缩略图：收藏、隐藏、详情（胶片、感光度、特点）。
  - 上滑或点网格图标，展开成三列网格，一次比较多个风格。
  - 改过参数的风格名字变斜体并加圆点，可“存为我的风格”。
- **胶片**：左端是当前胶片的小卡片（参照 Halide 的胶片窗）。新手默认只看到曝光补偿和测光方式。
- **调整**：横向滚动的参数图标，一次只显示一根滑杆；“全部”打开带所有滑杆的面板。加 **调色盘**（见 2.8）。
- **冲印**：相纸档位用带档位感的拨盘；放大机滤镜用滑杆。
- **效果**：颗粒、光晕、暗角、边框各是独立开关；边框、漏光、日期戳默认关闭。日期取 EXIF 拍摄时间，跟随旋转方向，格式可改。随机漏光用“种子 + 换一个”，既有惊喜又可控。
- **构图**：裁剪手柄直接在照片上，比例用标签，拉直和透视用带档位的刻度拨盘，并可直接输入数值（Not Boring 的拨盘被批调得慢）。

### 2.3 滑杆

- 轨道 3 dp，默认值处一个 2 dp 刻度，填充从默认值开始（双向滑杆从中间开始），滑块 20 dp、拖动时 24 dp。
- 拖动时滑块上方显示数值气泡。
- 双击滑块重置；去掉每行的“重置”文字按钮，改为改过才出现的 ↺ 图标。
- 点击滑块两侧每次步进一格（Lightroom）。
- 手指上下偏离轨道时减速到 1/4，精细调整。
- 点数值可直接输入。
- **按住滑杆名称，只隐藏这一项调整**，松手恢复（Darkroom）；**按住分类图标，隐藏整组**（Lightroom）。
- 拖动色调类滑杆时可开启高光 / 暗部溢出提示。
- 白平衡滑杆的轨道画成色温、色调的渐变，不填充。

### 2.4 导出面板

```
┌──────────────────────────────┐
│ ─── 导出                      │
│ 格式    [JPEG][HEIF][TIFF]   │
│ 尺寸    [原尺寸 ▾]            │
│ 质量    ────────●── 92        │
│ 边框    [关 ◯]                │
│ 元数据  [保留][去除位置]        │
│ 同时保存原图 [◯]               │
│  预计 18.4 MB · 约 6 秒        │ 按预览实际编码后估算
│ [        导出到相册        ]   │
│        在后台继续 →            │
└──────────────────────────────┘
```

导出中按钮本身显示进度；完成后 CONFIRM 震动加一条可点“查看”的提示。

### 2.5 正在处理

- 每个任务显示正在冲洗的低分辨率预览，不只是一根进度条（参照 Hark 让用户“看到它在干活”）。
- 读屏只在每 25% 播报一次。

### 2.6 设置

分组：输出（默认格式、色域）、编辑（照片四周颜色、直方图、在照片上滑动调参）、触感（关 / 轻 / 全部）、外观（减少动效）、隐私（明文写清楚哪些东西会离开手机）、关于。

### 2.7 首次使用引导

- 不做开场轮播。启动页提供示例照片，打开后逐个出现最多四条提示，每条只在用户第一次来到对应位置时出现：
  1. 点风格出片，再点一下调强度
  2. 按住照片看原图
  3. 双击滑杆归零
  4. 右上角保存
- 同一时间只出现一条，点任意处关闭，“跳过全部”后不再出现；设置里可以“重新查看引导”。
- 提示锚定在真实控件上（`TooltipBox` / `RichTooltip`，需要聚光遮罩时用 svenjacobs/reveal），绝不写死坐标。上次“指引位置不对”就是写死位置的典型问题。

### 2.8 调色盘（仿 iPhone 摄影风格）

- 一个二维面板加一根滑杆：横轴 **色彩**（左淡右浓），纵轴 **色调**（上亮下深），滑杆是 **风格强度**。
- 色调轴不是曝光：往上提亮暗部、压高光、降对比，往下压暗部、提对比、加深黑场。色彩轴控制饱和与自然饱和度。具体映射写在 `Look` 层，每个风格保存自己的默认位置，双击回到风格默认点而不是原点。
- 拖动是相对移动（增益约 0.6），手指不遮挡圆点；长按进入精细模式（增益 0.2）。靠近中心和风格默认点时吸附并震动。
- 只改冲印后调整层，拖动时只重新打包，不重新冲洗。整个拖动记一步撤销。
- 读屏把它当作两根滑杆（色调、色彩），并提供“数值”切换成普通滑杆。
- 后续加“肤色保护”：按色相识别肤色区域，饱和度变化在肤色上减弱；在说明里写明这是按颜色判断，不是识别人脸。

### 2.9 观片台

- 入口：启动页图标、“最近编辑 › 全部”、编辑器 ⋯ 菜单里的“在观片台查看”。
- 网格：双指缩放切换 2–6 列，缩略图可切换“原图 / 当前胶片”；长按进入多选，拖动连选。
- 单张查看：左右滑切换，上滑选中、下滑淘汰（可自动跳下一张），星级 0–5，点一下进入编辑。
- 批量：复制调整（按组勾选：胶片与相纸、曝光、白平衡、胶片效果、冲印效果……）、粘贴、批量导出（复用现有后台任务队列）。粘贴的是设置，不是算好的数值：每张照片自己测光。
- 打开观片台不自动冲洗任何照片（上游曾因此打开文件夹就耗 7 秒、363 MB）。缩略图用系统缩略图或 RAW 内嵌预览，按格子像素尺寸请求，滚出屏幕即取消；胶片缩略图走低分辨率专用通道，排在编辑器和导出之后。
- 编辑记录存在应用私有目录的 JSON 里，不往用户相册文件夹里写文件；格式向上游桌面版的 sidecar 看齐。照片身份按“文件名 + 大小 + 修改时间”识别，不单凭内容哈希（上游踩过坑：两张相同字节的文件继承了彼此的调色）。
- 后续：胶卷条视图、印样（contact sheet）导出、双图对比且同步缩放。

## 3. 流畅度

- 120 Hz 每帧 8.33 ms。目标：滑杆拖动 3 秒，CPU 帧耗时 P90 < 6 ms、P99 < 8 ms；胶片条快速滑动 P95 不超时；切换分类最长帧 < 16 ms；冷启动首屏 < 500 ms。
- 工具：Macrobenchmark（有无 Baseline Profile 各跑一遍）、JankStats（按“正在拖哪根滑杆”打标签，写入事件日志）、Perfetto（原生渲染加 trace 段）。
- 规则：交互时照片布局不变；变化的值只在 `graphicsLayer {}` / `drawBehind {}` 里读；滑杆事件经合并通道送引擎，拖动时低分辨率、松手全分辨率；传给界面的数据类标 `@Immutable`；列表给 `key` 和 `contentType`；绘制中不分配对象；拖动时关模糊；只在拖动期间请求高刷新率。

## 4. 无障碍与中文排版

- 读屏下双击被系统占用：重置、对比都要提供自定义操作或切换按钮。滑杆要有名称和带单位的状态描述（“正 0.7 档”“5600 开尔文”）。
- 不只用颜色表示“已修改”，同时加圆点。
- 系统字体放大到 200% 时，滑杆行允许折行。
- “5600 K”“6×7”用不换行空格；中文句子用全角标点；中文与数字之间的空格规则全局统一。
- 预留英文界面：两字分类名在英文下会变长 3–4 倍，分类栏格子要能容纳约 10 个拉丁字母。

## 5. 可用的开源库（均为 Apache-2.0，与 GPL-3.0 兼容）

- **Telephoto**：缩放、平移、双击放大，大图分块加载。
- **Haze**：半透明毛玻璃（仅照片放大时使用）。
- **colorpicker-compose**：中性灰取色、色轮。
- **Coil 3**：观片台缩略图缓存。
- **svenjacobs/reveal**：首次引导的聚光遮罩（MIT）。

滑杆、曲线、拨盘、调色盘和本项目的引擎与震动强相关，自己写。

## 来源

- [Darkroom 编辑界面](https://darkroom.co/help/edit/edit-view)、[Darkroom 曲线与白平衡](https://darkroom.co/blog/2026-09-15-curves-white-balance)、[Darkroom 选片](https://darkroom.co/help/manage/flag-reject)、[Darkroom 批量操作](https://darkroom.co/help/manage/batch-actions)
- [Halide Mark III](https://www.lux.camera/mark-iii-looks/)、[Lux：界面的实体感](https://www.lux.camera/physicality-the-new-age-of-ui/)
- [Lightroom 手势](https://helpx.adobe.com/uk/lightroom/mobile/get-started/gesture-controls-in-lightroom-for-mobile.html)
- [Snapseed 3/4 改版评价](https://rottenwifi.com/snapseed-is-back-what-googles-3-0-to-4-1-revival-changed/)
- [VSCO 预设使用](https://support.vsco.co/hc/en-us/articles/360043272091-Using-Managing-Presets)、[VSCO Film X](https://support.vsco.co/en/articles/12698496-vsco-membership-how-to-use-film-x)
- [Dazz 用户评价](https://justuseapp.com/en/app/1422471180/dazz-cam-vintage-camera/reviews)、[NOMO 用户评价](https://justuseapp.com/en/app/1362548649/nomo-point-and-shoot/reviews)
- [醒图 App Store](https://apps.apple.com/cn/app/%E9%86%92%E5%9B%BE-%E5%85%A8%E8%83%BD%E4%BF%AE%E5%9B%BE%E7%A5%9E%E5%99%A8/id1500526240)、[Hypic 界面拆解](https://screensdesign.com/showcase/hypic-photo-editor-ai-art)
- [iPhone 摄影风格](https://www.macrumors.com/guide/iphone-16-photographic-styles/)、[摄影风格逆向分析](https://hypersphere-flute-ssn6.squarespace.com/reverse-engineering-apples-photographic-styles)、[DXOMARK 肤色测试](https://www.dxomark.com/feature-focus-diving-into-the-apple-iphone-16-series-undertones-functionality/)
- [Hark Pro（TechCrunch）](https://techcrunch.com/2026/10/06/hark-releases-an-ai-personal-assistant-with-a-focus-on-privacy/)
- [darktable 选片模式](https://docs.darktable.org/usermanual/5.8/en/lighttable/lighttable-modes/culling/)
- [NN/g：新手教程与情境帮助](https://www.nngroup.com/articles/onboarding-tutorials/)
- [Android 震动原则](https://developer.android.com/develop/ui/views/haptics/haptics-principles)、[JankStats](https://developer.android.com/topic/performance/jankstats)、[Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [Telephoto](https://github.com/saket/telephoto)、[Haze](https://github.com/chrisbanes/haze)、[colorpicker-compose](https://github.com/skydoves/colorpicker-compose)、[reveal](https://github.com/svenjacobs/reveal)、[Coil](https://coil-kt.github.io/coil/)

## 附录 A：可复用的开源组件（2026-11-03 调研）

总原则：**参照改写，不加依赖**。原因两条：
1. 仍在维护的候选库大多已用 Kotlin 2.3–2.4、Compose BOM 2026.09 编译，本项目是 Kotlin 2.0.21，直接依赖很可能编译失败。要依赖须先升级 Kotlin（单列一项，评估后再定）。
2. 现成的裁剪、对比库大多直接产出裁好的 Bitmap；本项目是非破坏式编辑，界面只产出参数，处理交给引擎。

| 组件 | 做法 | 参照 | 许可 |
|---|---|---|---|
| 修图滑杆（双向填充、数值气泡、纵向精调、点两侧步进） | 自己写 | ImageToolbox `HyperOSSlider.kt`、Compose-Colorful-Sliders | Apache-2.0 |
| 色调曲线 + 直方图 | 自己写 Compose 版 | ImageToolbox `ImageCurvesEditor.kt`；Telegram `PhotoFilterCurvesControl.java` | GPL-2.0+（ImageToolbox 该文件实为 Telegram 移植，署名两家） |
| 三向色轮 | 参照改写 | ImageToolbox `ColorWheelsEditor.kt` | Apache-2.0 |
| 二维调色盘 | 自己写 | 无成熟开源实现 | — |
| 拉直刻度拨盘 | 自己写 | Telegram `CropRotationWheel.java`、uCrop `HorizontalProgressWheelView` | GPL-2.0+ / Apache-2.0 |
| 裁剪层（只输出裁剪框和角度） | 参照改写 | ImageToolbox `lib/cropper`、Cropify、Krop | MIT / Apache-2.0 |
| 前后对比分割 | 自己写（约 100 行） | ImageToolbox `BeforeAfterLayoutImpl.kt` | Apache-2.0 |
| 底部工具栏、胶片条吸附 | 自己写 | Compose `AnimatedContent`、`rememberSnapFlingBehavior` | — |
| 观片台网格（捏合改列数、拖动多选） | 参照改写 | IacobIonut01/Gallery `GridPinchZoomState.kt`、`MultiSelectExt.kt` | Apache-2.0 |
| 首次引导提示 | 自己写（遮罩 + 挖孔 + 文字） | svenjacobs/reveal（需新版 Kotlin） | MIT |
| 新版本提示 | 直接搬代码并署名 | JunkFood02/Seal `UpdateUtil.kt`（含 alpha/rc 版本比较） | GPL-3.0 |

注意事项：
- 曲线插值算法必须和引擎查表一致，否则所见不等于所得；Catmull-Rom 会过冲，需截断或改用单调三次插值。
- 更新检查：GitHub `releases/latest` 不返回预发布版本，alpha 期间要请求 `/releases` 自行筛选；未登录每 IP 每小时 60 次，带 ETag 缓存；不做应用内安装，直接跳 Release 页面。
- 所有参照来源在 NOTICE 和关于页保留原版权声明。

## 附录 B：视觉风格与胶片元素（2026-11-03 调研）

- **胶片味来自工艺信息，不来自品牌。** 不用任何厂商 Logo、字体、包装配色（Kodak 黄红配色作为商业外观受保护；CineStill 持有 "800T" 商标）。型号只以纯文本出现，写作“Portra 400 风格”，“关于”页加不隶属声明。颜色按冲洗工艺编码，只用作 6 dp 色点和片边字颜色：C-41 `#B07A4E`、E-6 `#8FA0B2`、B&W `#8E8E8E`、ECN-2 `#4A4A4A`、纤维相纸 `#E6DDCC`、RC 相纸 `#EDEFF0`。
- **界面外框不加颗粒、纸纹或静态材质贴图**，颗粒只在照片里。层级仍用色阶，只把最高一层（数值气泡、菜单）微调暖到约 `#2E2C2A`（Lux“抬起偏暖”）。琥珀色保留，按下态 `#F2B565`；安全灯光晕只出现在启动页。
- **字体**：中文和正文用系统字体（HyperOS 为 MiSans，非 OFL，不打包）。只打包一款 Barlow Condensed Medium 子集（OFL），用于片边字、帧号、工艺代号（如 “C–41 · 400 · 12A”）和导出页大号数字，不与中文混排。原 1.2 节“数字用 Inter”作废：Inter 与 MiSans 拉丁字形太像，857 KB 没有区分度。
- **图标**：继续用 Material Symbols（Apache-2.0），评估 Sharp 风格；自绘 8 个领域图标（胶卷暗盒、相纸、放大机、颗粒、光晕、片框、安全灯、片边条码），24 格、对齐 Symbols 300 字重，未选中描边、选中填充。不混用第二套图标库。
- **胶片条卡片**：缩略图仍用用户照片；上方 10 dp 片边条（底色 `#0E0D0C`，9 sp Barlow Condensed 写型号缩写和帧号，帧号按 00 → 00A → 1 → 1A 排），左侧工艺色点；只有选中格底边显示一段装饰性 DX 风格条码（随机，不编码真实厂商号）；选中态 2 dp 琥珀描边加片边实心点。不画齿孔，齿孔只在胶片详情抬头出现一次。
- **待真机核实**：MiSans 是否支持 tnum、Barlow 子集化后的大小与 tnum、Sharp 风格接入方式、工艺色与琥珀色的可辨度。

## 附录 C：沉浸式（2026-11-03 调研）

**做沉浸的位置（按收益）**

1. 编辑器：轻点照片隐藏全部界面和系统栏，再点、返回或开始编辑时恢复。照片框尺寸不变，界面只改 `graphicsLayer` 透明度，隐藏时同时拦截点击、移出无障碍树。沉浸状态跨照片保留（Lightroom 翻页退出全屏被投诉）。
2. 1:1 细看：改为同窗口全屏覆盖层（不再用 Dialog），系统栏隐藏，标签避开 `safeDrawing`。
3. 观片台单张翻页：默认沉浸，轻点显示顶部和底部操作。
4. 构图：半沉浸，只隐藏分类栏和二级条，系统栏保留；手柄只在左右边缘做手势排除，底部手柄放在 `mandatorySystemGestures` 之上。
5. 横屏编辑：可选只隐藏状态栏。
6. 启动页：只做边到边，不隐藏系统栏。
7. 导出中：编辑器可见时屏幕常亮，不算沉浸。

**不做沉浸**：拖动滑杆和拨盘时、按住对比时、所有面板和弹窗（它们是独立窗口，会把系统栏带回来）、数值输入、TalkBack 开启时。

**先修的两个问题**

- `enableEdgeToEdge()` 改为 `SystemBarStyle.dark(TRANSPARENT)`：应用始终深色，系统浅色模式下状态栏图标会看不见。
- 照片区域不能依赖随可见性变化的插图，否则隐藏系统栏时照片会跳。改用 `*IgnoringVisibility` 插图，或顶栏和面板改为叠在照片上的覆盖层。

**API**：`WindowInsetsControllerCompat`，`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，`hide/show(systemBars())`，`ON_RESUME` 时重新应用；`BackHandler` 先恢复界面。

**HyperOS**：部分用户开了"手势确认"，需要上滑两次才能退出，所以轻点和返回必须能恢复。换第三方桌面会退回三键导航，要测遮罩。滑动临时显示系统栏的行为需在 13/14/15 真机实测。曲面屏左右 16 dp 内不放可点元素。

## 附录 D：观片台的"逐卷查看"（参照 ObscuraFilm，2026-11-03 调研）

[Tudor1713/ObscuraFilm](https://github.com/Tudor1713/ObscuraFilm)（暗盒，GPL-3.0）是基于同一上游引擎的桌面相册，以"胶卷柜 → 抽片 → 放大箱显影"为核心交互。借鉴其结构和算法，不照搬外观；移植的算法在 NOTICE 注明"参照改写自 Tudor1713/ObscuraFilm（GPL-3.0）"。

**分卷**
- 第一期自动卷：按"胶片 + 编辑日期"自动分卷，每卷满 36 张续为"卷 2"，用户零操作（参照 Wanderoll）。
- 第二期手动卷：自建卷，上限 36 张，在网格里多选后"装入"。
- 挑片（上滑选中、星级）只作为卷内筛选，不单独成卷。

**卷列表**：竖向卡片，不做 3D 胶卷柜。每卡一行工艺色点 + "Portra 400 风格"纯文本 + 片边字（`C–41 · 400 · 11.03 · 24/36`，Barlow Condensed）+ 一排用户自己的缩略图。

**卷内**：两种视图可切换。
- 片条：横向 35mm 片条，当前格居中吸附；横图占全格（36×24），竖图占半格（18×24），画面永远正向；片边条 10 dp 写型号和帧号（只写奇数格），**片条上画齿孔**（附录 B 的"齿孔只出现一次"规则对卷内片条例外，因为片条本身就是这个视图的主体）；底部刮擦条每格一刻度，点刻度跳转，显示"当前格 / 总张数"。
- 印样：6 列网格（§2.9 的印样）。
- 底部操作：复制调整、导出本卷、导出印样。

**动效**（只用 Theme.kt 已有弹簧）
- 打开一卷：卡片共享元素过渡到片条，defaultSpatial，≤ 400 ms（不用 ObscuraFilm 3 s 以上的开卷动画）。
- 拉片条：跟手，惯性吸附到格；每过一格一次 `SEGMENT_TICK`，不连续震动。
- "显影"：卷内某格第一次被放大查看时，从点击点向外圆形由黑白显影成胶片色（1100 ms，`cubic(.35,.1,.2,1)`），每格只演一次；设置里可关，系统"移除动画"时跳过。编辑器内一律不用（原则 6 不变）。

**性能**
- 卷内默认用系统缩略图；"未冲洗"的黑白效果用 Compose `ColorMatrix`，不占引擎线程。
- 胶片缩略图走 §2.9 的低分辨率通道，排在编辑器和导出之后；渲染队列按离屏幕中心的距离排序，最近 6 格优先，关卷时整组取消。
- 只保留可见范围前后各一屏的位图，磁盘缓存设上限。
- 每种胶片可用本项目引擎烘焙 33³ 色表做即时预览（`RuntimeShader` 需 API 33，以下回退为缩略图）；不使用 ObscuraFilm 的 `luts/*.png`。

**不照搬**：品牌暗盒外观（商业外观风险）、每卷换强调色（只用琥珀色）、Archivo / Instrument Serif 字体、Unsplash 示例图、3 s 以上的开关卷动画。

## 附录 E：边框（2026-11-03 调研）

原则：**片边、齿孔、日期戳一律交给引擎**（上游原样），app 只画相纸、卡纸和参数条。

**E.1 上游已有、安卓还没接上的（先补，保真度收益最大）**

| 项 | 现状 | 做法 |
|---|---|---|
| `overscan_edge_text` 片名边码 | 不发送，片边上没有片名 | 按胶片与片幅查表（上游 `Session+FilmEdge.swift`），设置里加"使用真实片名"开关 |
| `overscan_f_number` | 从不赋值，引擎一律按 f/5.6 算片门半影 | 从 EXIF FNumber 读取（一行） |
| `overscan_frame_number` | 不发送 | 0 = 随机；批量导出时按序号 |
| 日期后背：`placement`、`data_text`、`size`、`ev`、`inset_x/y`、`style`、`corner`、美式日期顺序 | 只有一个开关 | 做成日期后背二级面板；照搬上游限制（全景不印、120 需片边、半格 data 时 size ≤ 1） |
| `overscan_gate`、`overscan_carrier`、两个 seed | 只有预设能改 | 片边面板加片门形状、片夹、"重掷"按钮 |
| `overscan_pair`（半格双拼） | 不支持 | 等 app 支持两图拼合再做 |

**E.2 新增预设（30 款，名称不出现品牌）**

- 极简：细白边、细黑边、1:1 / 3:4 / 9:16 留白补齐
- 画廊：美术馆卡纸（下边加宽）、黑卡纸、细线卡纸、签名边
- 引擎片边：135 带片名、135 黑框扫描、135 带编号、6×7、6×8 肩形片门、6×9、6×17、半格、暗房放大相纸
- 日期后背：橙色 LCD、数据后背（`F2.8 1/125 ISO 200 45mm`）
- 拍立得（按官方尺寸换算）：600 / i-Type、Instax Square、Instax Wide、Go、Mini 黑边；现有 `instant_sq` 侧边偏窄，按官方比例修正
- 参数条（纯文字、无 logo）：双栏白条、相框参数、单行极简、胶片信息条；模糊背景卡最后做（最耗内存）
- 现有 `mount` 改成真实幻灯片框比例

**E.3 兼容规则**

1. 开片边时裁切必须等于片门比例（偏 5% 引擎拒绝）：带 `crop` 的纸框不能叠 135 片边，只配同形状片幅。
2. FrameSpec 新字段（`keyline`、`captionLayout`、`radius`、`blur`）在 `fromJson` 里都有默认值，旧存档和排队任务照常读；预设 id 只增不改，删掉的落回 `none`。
3. 内存：导出前按"原图 + 卡片"两张 ARGB 预算，超 Android 17 上限就降一档；预览位图不超过 100 MB（RecordingCanvas 上限）。
4. 卡片沿用印片色彩空间，P3 不降成 sRGB。
5. 先旋转像素再画框，EXIF 方向写 NORMAL，参数条永远在视觉底部。
6. 文字：字号按短边比例，设最小像素字号；宽幅和窄竖图先量宽度，放不下就缩字或改上下排。
7. EXIF 缺失：焦距回退到实际焦距（不写"等效"），加镜头型号，缺的项整项省略，不印 `-` 或 `ISO0`。
8. 商标：不画任何品牌 logo、不模仿品牌字体；预设名用"白条参数""相框参数"，不写"徕卡水印"。
9. 字体：拉丁与数字可内置 Noto（OFL），中文用系统字体。

**E.4 可参考的开源实现**：semi-utils（Apache-2.0，布局模板；不取其 logo 与字体）、auto-shotframe / frame-tool / photoborder / exifmark / EasyWatermark（MIT）。framestamp、magick-frames 无许可证，只看思路不复制。
