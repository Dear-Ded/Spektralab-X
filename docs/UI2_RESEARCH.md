# UI 2.0 设计研究

**目标对标**：Hark Pro、Gemini Android  
**研究日期**：2026-11-10  
**用户反馈来源**：2026-11-04 真机测试（小米，0.1.19-alpha）

---

## 一、核心设计方向

### 1.1 用户要求总结

用户在真机上使用 0.1.19-alpha 后明确要求：

1. **对标 Hark（Hark Pro）和 Gemini 安卓应用**的交互与视觉质量
2. **停用纯黑 + 琥珀橙（#E8A24A）方案**，照片周围保持中性环境（颜色干扰判断）
3. **选中态用形状/标记表达**，不只靠颜色
4. 两条路径：**一键预设**（滤镜式）+ **暗房顺序**（胶片→相纸→显影→效果→后期→边框）
5. **底部面板一次一个主控件**，高级项折叠
6. 每个参数和风格有清晰归类，**用户始终知道东西在哪、选中了什么**
7. **手势冲突算重大 bug**（例如曲线吞掉纵向滚动）
8. 产品原则：**高天花板低地板** — 爱好者能摸到每个参数，普通用户点一个风格就能出片
9. **体验优先于包体积**：字体/素材该打包就打包（许可证限 OFL/Apache-2.0）

---

## 二、Gemini Android 设计分析

### 2.1 视觉语言

**核心特征**（[来源](https://www.androidsage.com/2026/05/04/gemini-ai-android-app-gets-a-bold-redesign-gemini-ui-2-0/)）：

- **纯黑背景**（pure black）+ **底部深蓝渐变光晕**（subtle deep blue gradient glow）
- **圆角药丸形状**贯穿整个界面（输入栏、按钮、导航）
- Material Design 3 Expressive 设计，**清洁、扁平的深度感**（forgo animated particle effects，favor cleaner, flatter depth）
- 导航抽屉使用深炭灰背景保持一致性

**配色哲学**（[来源](https://design.google/library/gemini-ai-visual-design)）：

- **多色渐变**是核心：锐利的前缘 + 扩散的尾部，引导注意力
- **圆形**传达简洁、和谐、舒适
- 暖色调、空间感、圆润质感 — **乐观、愉悦、精致**
- **柔软性**让复杂系统变得易接近
- 动画有明确起止点，创造方向流动感

**中性环境原则**（[来源](https://m3.material.io/styles/color/system/overview)）：

- Material Design 3 的 **Neutral palette**（近饱和的白与黑）用于表面、背景、高重要性文本/图标
- 动态颜色从用户内容（壁纸）提取，但**照片编辑类应用需保持背景中性**，避免干扰对成片的判断

### 2.2 交互模式

- **预测性返回手势**（[来源](https://developer.android.com/design/ui/mobile/guides/patterns/predictive-back)）：Android 14+ 的系统动画，滑动返回时预览目标
- **底部导航**而非顶栏
- **幻灯片式汉堡菜单**（传统 Android 模式）

---

## 三、Hark Pro 设计分析

### 3.1 视觉氛围

**布局结构**（[来源](https://gizmodo.com/hark-pro-ai-ui-2000822253)）：

- 主屏中央是"作曲器"（提示框），左侧面板系统
- **卡片堆叠 + 分层通知** — "看起来几乎像 Apple 会设计的东西"
- 卡片显示 AI 主动建议的操作；下方面板作为个性化小部件

**平静的个性化背景**：

- 动态背景（云层、日落等），**唤起平静感并反映用户空间**
- 避免纯白/纯黑的冷漠感

**交互节奏**：

- **主动卡片系统**：上下文操作（批准请求、上传收据、办理登机）
- **"交接"可视化**：显示 AI 的云计算机正在工作
- **节奏化通知**："不会一次性堆积...会在恰当时机分步呈现"

### 3.2 设计哲学

- 前 Apple 设计师主导，目标是为 AI 构建**图形界面**（GUI），超越命令行式聊天机器人
- 感觉"更像操作系统"而非简单的聊天界面
- 分层、卡片式 UI，优先考虑平静、个性化氛围而非功利性背景

---

## 四、Material Design 3 设计系统

### 4.1 形状令牌（Shape Tokens）

**圆角尺度**（[来源](https://m3.material.io/styles/shape/corner-radius-scale)）：

- **十种尺寸的形状尺度**，决定圆角程度
- 三类组件（Small、Medium、Large）可通过主题属性覆盖
- **连续曲率圆角**（Figma smoothing 0.6）：xs 4 / s 8 / m 12 / l 16 / xl 24
- 嵌套圆角 = 外层圆角 - 内边距

**应用原则**：

- 工具栏、主要操作按钮使用完全圆角
- 药丸、分段控件、按钮、卡片、设置行使用 m（12）
- 选中态**不再改变形状** — 形状保持一致，通过填充/标记表达选中

### 4.2 选中态设计

**可访问性与反馈**（[来源](https://m3.material.io/components/navigation-drawer/accessibility)）：

- **形状匹配悬停效果**：悬停效果遵循元素形状（圆角矩形、圆形或自定义路径）
- **填充图标 vs 轮廓图标**：选中目标使用填充图标，未选中使用轮廓图标
- **模式、位置、颜色共同表达**：清晰区分元素类别
- **状态转换流畅**：颜色偏移、色调效果、活动指示器确认用户交互

**关键原则**：

- 低视力用户受益于**明确的语言描述 + 高对比度的大视觉元素**
- 视觉和触觉反馈改善导航体验
- 从悬停到选中的视觉变化应**流畅而非突兀**

### 4.3 基于色调的表面

**Tone-based Surfaces**（[来源](https://m3.material.io/blog/tone-based-surface-color-m3)）：

- MD3 的标志性特征，取代基于海拔的着色方法
- 允许直接分配色调表面颜色，而不是在抬升表面上使用不透明层
- 动态颜色、色调表面、圆润形状、基于弹簧的运动创造**有生命力和个性化**的 UI

---

## 五、关键设计决策

### 5.1 配色方向

**停用方案**：

- ❌ 纯黑（#000000）+ 琥珀橙（#E8A24A）

**采用方向**：

1. **中性背景**：
   - 照片周围使用 MD3 Neutral palette（近饱和的深灰/浅灰）
   - 避免彩色背景干扰对成片色彩的判断
   - 参考 Gemini 的"纯黑 + 深蓝光晕"：深色模式下深灰底 + 微妙渐变点缀

2. **强调色**：
   - 不再用琥珀橙作为主强调色
   - 考虑**深蓝/青色渐变**（Gemini 风格）或**动态提取色**（MD3）
   - 保持胶片感，但避免琥珀橙的单调与过强对比

3. **选中态**：
   - **形状标记** + 填充图标（Material Design 3 标准）
   - 轻微背景色调 + 边框/标记（不只靠颜色）
   - 确保无障碍访问（WCAG 对比度）

### 5.2 选中态设计

**核心原则**：

- 选中态**保持形状一致**（不改变圆角或尺寸）
- 使用**填充图标**（选中）vs **轮廓图标**（未选中）
- 添加**形状内标记**（如勾选、高亮边框）
- 微妙的背景色调变化（符合中性环境）

**示例方案**：

- 胶片选择：未选中为轮廓卡片，选中为填充卡片 + 左上角勾选标记
- 参数步骤：未选中为轮廓药丸，选中为填充药丸 + 轻微阴影

### 5.3 两条路径设计

**路径 1：一键预设**（滤镜式，低地板）

- 顶层入口：编辑器顶部"风格"按钮
- 卡片网格：展示预设缩略图 + 名称
- 点击即应用：Portra 400、Tri-X、Ektar 等
- 应用后可进入暗房路径微调

**路径 2：暗房顺序**（高天花板）

- 底部展开式导航：
  1. 胶片
  2. 相纸
  3. 显影/打印（曝光、效果）
  4. 胶片效果
  5. 后期调整
  6. **边框**（独立成组）
- 每个步骤点击后展开对应控件
- **一次只显示一个主控件**，高级参数折叠在"更多"后

### 5.4 底部面板优化

**现状问题**：面板太挤

**改进方案**：

1. **主控件 + 折叠高级项**：
   - 显影步骤默认显示"曝光"和"效果"主控
   - "更多参数"折叠：时间、温度、搅拌等
   - 双向链接：调整连续值时，主控显示"自定义"

2. **底部导航 Tab**：
   - 平铺 6 个主步骤（胶片、相纸、曝光、效果、后期、边框）
   - 点击 Tab 后展开对应面板
   - 当前 Tab 用填充图标 + 形状标记

3. **手势冲突优先级**：
   - 曲线等手势控件**不吞掉纵向滚动**
   - 使用 `detectTransformGestures` 时限制方向
   - 或增加明确的"拖动区域"与"滚动区域"分离

### 5.5 清晰归类与导航

**用户需求**："始终知道东西在哪、当前选中什么"

**解决方案**：

1. **面包屑导航**：
   - 编辑器顶部显示：胶片 > Portra 400 > 显影 > 曝光
   - 点击任意层级快速跳转

2. **当前选中高亮**：
   - 底部 Tab 的当前步骤用填充图标 + 背景标记
   - 参数面板中当前调整的项用微妙边框

3. **默认值指示**：
   - 参数偏离默认值时显示"重置"按钮
   - 风格选中时显示"已应用"标记

---

## 六、动效与交互

### 6.1 流畅动效

**Gemini 的动效特点**：

- **明确的起止点**，创造方向流动感
- **内部活动**传达思考、分析、智能
- **速度变化**创造期待与释放感

**应用到 Spektralab-X**：

- 参数调整时**实时预览**（已有 FrameRateBoost）
- 切换胶片/相纸时**淡入淡出 + 微妙滑动**
- 底部面板展开/收起使用**预测性返回手势**

### 6.2 预测性返回（Predictive Back）

- Android 14+ 系统特性
- 滑动返回时预览目标（减少意外跳转）
- 适用于：面板收起、退出编辑器

---

## 七、Material Design 3 技术实现

### 7.1 形状令牌（Corners.kt）

**已有基础**（来自 main 分支）：

```kotlin
// ui_corners.md 5
object Corners {
    val xs = 4.dp  // 连续曲率，Figma smoothing 0.6
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    // full: 完全圆角，仅用于工具栏和主要操作
}
```

**应用规则**：

- 嵌套圆角 = 外层 - 内边距
- 选中态不改变圆角

### 7.2 中性色板

**参考 MD3 Neutral Tones**：

- `surface`: 深灰（非纯黑）
- `surfaceVariant`: 轻微色调变化
- `onSurface`: 高对比度文本/图标
- `outline`: 边框/分隔线

**具体色值**（需在代码中定义）：

```kotlin
// 深色模式示例（照片编辑中性环境）
val surfaceDark = Color(0xFF1C1B1E)       // 深灰底
val surfaceVariantDark = Color(0xFF2B2930) // 卡片/面板
val onSurfaceDark = Color(0xFFE6E1E5)     // 主文本
val outlineDark = Color(0xFF938F99)        // 边框
val accentBlue = Color(0xFF4A90E2)         // 深蓝强调色（替代琥珀橙）
```

---

## 八、实施优先级

### Phase 1：最小可见改动（0.2.0 起点）

**目标**：验证中性配色 + 形状选中态

1. **替换纯黑 + 琥珀橙**：
   - 定义中性色板（深灰底 + 深蓝强调）
   - 全局应用到背景、卡片、按钮

2. **选中态加形状标记**：
   - 胶片选择：填充卡片 + 勾选标记
   - 底部 Tab：填充图标 + 微妙背景

3. **验证手势冲突**：
   - 曲线控件不吞掉纵向滚动
   - 测试预测性返回手势

**代码位置**：

- 独立分支：`ui-neutral-colors`（不合并 main）
- 文件：`app/src/main/java/com/dearded/spektralab/theme/`

### Phase 2：底部面板重构

1. Tab 式导航（6 个主步骤）
2. 一次一个主控件展开
3. 高级参数折叠

### Phase 3：双路径入口

1. 顶部"风格"按钮 → 一键预设
2. 底部 Tab → 暗房顺序

---

## 九、参考资料

### 设计系统

- [Gemini AI Visual Design](https://design.google/library/gemini-ai-visual-design) — 视觉原则、渐变、动效
- [Material Design 3 Shape](https://m3.material.io/styles/shape) — 形状令牌、圆角尺度
- [Material Design 3 Color System](https://m3.material.io/styles/color/system/overview) — 动态颜色、中性色板
- [Predictive Back Design](https://developer.android.com/design/ui/mobile/guides/patterns/predictive-back) — Android 14+ 返回手势

### 案例研究

- [Gemini Android UI 2.0 Redesign](https://www.androidsage.com/2026/05/04/gemini-ai-android-app-gets-a-bold-redesign-gemini-ui-2-0/) — 纯黑底 + 深蓝光晕
- [Hark Pro AI UI](https://gizmodo.com/hark-pro-ai-ui-2000822253) — 卡片式、平静背景、节奏化交互

### 技术实现

- [Material Design 3 in Compose](https://m3.material.io/blog/how-to-gemini-app-compose-material-design-3)
- [Selection State Accessibility](https://m3.material.io/components/navigation-drawer/accessibility) — 形状、填充图标、高对比度

---

## 十、总结：三条最重要结论

1. **中性背景是照片编辑应用的核心原则**：
   - Gemini 用纯黑 + 深蓝微光，避免彩色干扰
   - Material Design 3 的 Neutral palette 提供饱和度极低的深灰/浅灰
   - **Spektralab-X 应弃用纯黑+琥珀橙，改用深灰底 + 深蓝/青色强调**

2. **选中态必须用形状+标记，不只靠颜色**：
   - Material Design 3 标准：填充图标（选中）vs 轮廓图标（未选中）
   - 形状保持一致，通过背景、边框、勾选标记表达状态
   - 确保无障碍访问（色盲友好、高对比度）

3. **底部面板"一次一个主控件"是简化复杂度的关键**：
   - Hark Pro 的节奏化交互避免一次性堆积
   - Tab 式导航 + 折叠高级参数
   - 手势冲突优先级：曲线等控件不吞掉纵向滚动

---

## 十一、开屏动画优化（用户反馈）

### 11.1 当前问题

**用户反馈**：开屏动画不够丝滑，而且现在一闪而过。

**现有实现**（LaunchIntro.kt）：

- 时长：0–900 ms（SplitFlap 名称落定）
- 组成：
  1. Gemini 光晕从底部升起（0–500 ms）
  2. 光谱扫过完成（0–450 ms）
  3. 齿孔拉动一帧（60–600 ms）
  4. "Spektralab-X" 翻牌落地（200–900 ms）
- 退出条件：引擎就绪后至少 600 ms、最多 1.6 s
- 可点击跳过（150 ms 后）

**问题诊断**：

1. **一闪而过**：引擎预热过快时，900 ms 的动画被 600 ms 最小停留时间截断，名称还未完全落定
2. **不够丝滑**：弹簧动画参数（defaultSpatial 0.8/380）可能在小米高刷屏上显得过快

### 11.2 优化方向

**时长调整**：

- 最小停留时间从 600 ms 延长到 1000 ms，确保名称完全落定
- 名称启动延迟从 200 ms 提前到 100 ms，给更多时间展开
- 总时长：1000–1600 ms（保持上限）

**弹簧参数调整**：

- 齿孔拉动：从 defaultSpatial (0.8/380) 改为 slowSpatial (0.8/200)，更舒缓
- 光晕升起：保持 slowEffects (1.0/800)，无回弹
- 名称翻牌：保持线性 tween，不改动（已是机械感）

**渐进式退出**：

- 引擎就绪后不立即退出，等名称完全落定（最后一片翻完 + 100 ms）
- 再淡出 200 ms（defaultEffects），避免突然消失

### 11.3 参考标准

**Gemini 安卓启动动画**（推断）：

- 光晕缓慢展开（约 800–1000 ms）
- 文字渐显（约 400 ms）
- 总时长约 1.2–1.5 s，不被引擎截断

**Material Design Motion 指引**：

- 全屏转场用 slowSpatial (0.8/200)
- 启动画面至少停留 1 s，给足够时间建立品牌印象

---

## 十二、React Bits 动效研究（用户反馈）

### 12.1 概述

**React Bits** ([reactbits.dev](https://reactbits.dev/)) 是开源的 110+ 动画组件库，MIT + Commons Clause 许可证（已用于 SplitFlap 和 Aura）。

**适用场景**：

- HTML/CSS 动画，无 WebGL 依赖（性能友好）
- 确定性动画，适合视频编辑工作流
- 可定制、开发者友好

### 12.2 适合胶片模拟应用的组件

#### **过渡动效**

1. **Pixel Transition** ([来源](https://reactbits.dev/get-started/index))
   - 复古像素溶解过渡（覆盖卡片 → 交换内容 → 清除）
   - **适用**：胶片风格切换时的过渡（Portra → Tri-X）

2. **Halftone Reveal** 
   - 半色调网点膨胀，光标下显现真实照片
   - **适用**：观片台缩略图悬停预览

3. **Dither Veil** 
   - 1-bit 抖动照片，光标穿透显现全彩
   - **适用**：黑白胶片预览 → 彩色底片对比

4. **Fade Content** 
   - 通过颗粒、雾气、像素块淡入淡出
   - **适用**：编辑器面板展开/收起，呼应胶片颗粒

#### **加载指示器**

5. **Lattice Loader** ([来源](https://reactbits.dev/get-started/index))
   - 格子波浪亮起 + 实时秒表
   - **适用**：冲洗队列的"正在处理"行

6. **Status Mark** 
   - 空闲环 → 进度弧 → 勾选标记的形态变化
   - **适用**：观片台批量处理状态

7. **Counter** 
   - 里程表式数字滚动
   - **适用**：帧数、胶卷张数显示

#### **反馈动画**

8. **Click Spark** ([来源](https://reactbits.dev/get-started/index))
   - 点击爆发火花（虚线、彗星、点、星星）
   - **适用**：快门式点击反馈（拍照感）

9. **Fuse Button** 
   - 操作确认 + 撤销计时器
   - **适用**："重置全部"的二次确认

10. **Slide Commit** 
    - 滑动确认手柄，旋转执行，展开为完成药丸
    - **适用**："保存"主操作的明确反馈

#### **文字效果**

11. **Blur Text** ([来源](https://reactbits.dev/get-started/index))
    - 从模糊到清晰的柔焦显现
    - **适用**：观片台标题渐显

12. **Shuffle** 
    - 字符随机洗牌后落定
    - **适用**：胶片型号切换时的动态感

13. **Gradient Text** 
    - 文字内渐变动画
    - **适用**：编辑器顶栏当前步骤高亮

### 12.3 已采用的组件

**SplitFlap Text** (ui2_reactbits.md 8.1)：

- 启动画面和"关于"页的品牌名
- 机械翻牌效果，JetBrains Mono 等宽字体
- 三次翻转，35 ms 错开，100 ms/翻

**Aurora** (Aura.kt, ui2_reactbits.md 8.1)：

- 启动、等待、空白页的底部光晕
- 蓝 → 青 → 绿 → 黄渐变，无红/紫
- 30 fps，动画关闭时静止

### 12.4 推荐新增组件

| 组件 | 用途 | 优先级 |
|---|---|---|
| Pixel Transition | 胶片/相纸风格切换过渡 | 高 |
| Halftone Reveal | 观片台缩略图悬停预览 | 中 |
| Lattice Loader | 冲洗队列处理指示 | 高 |
| Click Spark | 快门式点击反馈 | 低 |
| Blur Text | 观片台标题渐显 | 低 |

**实施注意**：

- React Bits 是 React 组件，需转译为 Compose（如 SplitFlap 和 Aura）
- MIT + Commons Clause 许可证与 GPL-3.0 不兼容，只能"重建行为、不看源码"
- 每个组件转译后写 `ui2_reactbits.md` 文档记录

---

## 十三、总结：三条最重要结论（更新）

1. **中性背景是照片编辑核心原则**：
   - Gemini 用纯黑 + 深蓝微光，避免彩色干扰
   - Material Design 3 的 Neutral palette 提供饱和度极低的深灰/浅灰
   - **Spektralab-X 应弃用纯黑+琥珀橙，改用深灰底 + 深蓝/青色强调**（main 已实现）

2. **选中态必须用形状+标记，不只靠颜色**：
   - Material Design 3 标准：填充图标（选中）vs 轮廓图标（未选中）
   - 形状保持一致，通过背景、边框、勾选标记表达状态
   - 确保无障碍访问（色盲友好、高对比度）（main 已实现）

3. **底部面板"一次一个主控件"+ 动效提升沉浸感**：
   - Hark Pro 的节奏化交互避免一次性堆积
   - Tab 式导航 + 折叠高级参数
   - React Bits 动效（Pixel Transition、Lattice Loader）增强胶片感和专业感
   - 开屏动画延长至 1–1.6 s，弹簧参数调慢（slowSpatial）

---

**文档版本**：1.1（新增第十一、十二节）  
**下一步**：
1. 优化开屏动画时长和弹簧参数（LaunchIntro.kt）
2. 评估 React Bits 高优先级组件（Pixel Transition、Lattice Loader）的转译可行性
