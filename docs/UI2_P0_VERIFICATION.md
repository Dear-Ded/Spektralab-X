# P0 级问题质量验证报告

> 验证时间：2026-11-08  
> 验证人：Side session (UI 问题诊断)  
> 方法：代码审查 + 逻辑连接检查

---

## P0-1: 预测性返回 ✅ 已实现

**代码位置：** `EditorScreen.kt:377-379`

**实现验证：**
```kotlin
val panelBack = rememberBackProgress()
PredictiveBack(enabled = showChrome && panelLevel == 2, progress = panelBack, resetOnCommit = true) {
    panelLevel = 1
}
```

**连接检查：**
- ✅ 触发条件正确：`showChrome && panelLevel == 2`（面板在 3/4 高度时）
- ✅ 跟手逻辑正确：line 385 使用 `panelBack.value` 实时控制面板高度
  ```kotlin
  val h = (full - (full - anchors[1]).coerceAtLeast(0f) * panelBack.value)
  ```
- ✅ 提交操作正确：`panelLevel = 1`（收到半屏）
- ✅ 取消操作正确：`PredictiveBack` 内部自动处理，弹簧回到 `panelLevel == 2`

**依赖组件：**
- ✅ `rememberBackProgress()` — `Interactions.kt:364`
- ✅ `PredictiveBack()` — `Interactions.kt:380-403`
- ✅ `BackProgress.value` — `Interactions.kt:357`

**验收标准（UI2.md 3.8）：**
- ✅ 面板跟手往下收：`panelBack.value` 从 0→1 时，面板高度从 `anchors[2]` 平滑过渡到 `anchors[1]`
- ✅ 最多收到少一档：逻辑限制在 `anchors[1]`（半屏）
- ✅ 松手吸附或取消：`PredictiveBackHandler` 内部处理

**状态：** ✅ **代码正确，需真机验证系统返回手势触发**

---

## P0-2: 面板三档高度吸附 ✅ 已实现

**代码位置：** `EditorScreen.kt:359-419`

**实现验证：**
```kotlin
val heights = listOf(96.dp, boxHeight * 0.55f, boxHeight * 0.75f)
val anchors = remember(heights, density) { with(density) { heights.map { it.toPx() } } }
```

**三档定义：**
- ✅ 第 0 档：`96.dp`（收起，只露工具栏）
- ✅ 第 1 档：`boxHeight * 0.55f`（半屏）
- ✅ **第 2 档：`boxHeight * 0.75f`（3/4 屏）** ← UI2.md 6.1 要求

**拖动逻辑检查：**
```kotlin
detectVerticalDragGestures(
    onDragStart = { ... },
    onDragEnd = {
        val v = -tracker.calculateVelocity().y  // 手指向上 = 负值
        val best = snapIndex(anchors, at, v, from = panelLevel)
        if (best != panelLevel) view.haptic(HapticEvent.Segment)
        releaseVelocity = v
        panelLevel = best
        ...
    },
    ...
) { change, dy ->
    at = (at - dy).coerceIn(anchors.first(), anchors.last())
    tracker.addPosition(change.uptimeMillis, change.position)
    panelScope.launch { panelPx.snapTo(at) }
}
```

**连接检查：**
- ✅ 1:1 跟手：`at = (at - dy)` 直接跟随手指位移
- ✅ 吸附逻辑：`snapIndex(anchors, at, v, from = panelLevel)` 找最近档位
- ✅ 快速甩动多走一档：`snapIndex` 内部逻辑（line 266-269）
  ```kotlin
  if (abs(velocity) > flingVelocity && best == from) {
      best = (from + if (velocity > 0f) 1 else -1).coerceIn(anchors.indices)
  }
  ```
- ✅ 弹簧动画：`panelPx.settle(anchors[panelLevel], v)` 使用 `defaultSpatial`
- ✅ 震动反馈：`view.haptic(HapticEvent.Segment)` 在档位切换时触发

**验收标准（UI2.md 3.7）：**
- ✅ 拖动 1:1 跟手
- ✅ 松手按位置和速度吸到最近一档
- ✅ 快速甩动可多走一档

**状态：** ✅ **代码正确，需真机验证拖动手感**

---

## P0-3: 共享元素转场 ❌ **部分缺失**

**框架已实现：**
- ✅ `Transitions.kt:60-76` — `Modifier.sharedFrame(key, shape)`
- ✅ `LightTableNav.kt:185` — `SharedTransitionLayout`
- ✅ `LocalSharedScope` / `LocalNavScope` 环境已提供

**已使用位置：**
- ✅ `LightTableScreen.kt:376` — 观片台网格缩略图
  ```kotlin
  .sharedFrame(frame.id, shape)
  ```
- ✅ `LightTableLoupe.kt:238` — 1:1 查看的照片
  ```kotlin
  .sharedFrame(frame.id, enabled = shared)
  ```

**缺失位置（UI2.md 3.7 要求）：**

### ❌ **风格/胶卷卡片 → 强度面板**

**问题：**
`Styles.kt` 中的 `ThumbCard` 组件（line 390, 412）**未使用** `sharedBounds` 或 `sharedElement`。

**验证结果：**
```bash
$ grep -n "sharedBounds\|sharedElement" app/src/main/java/com/dearded/spektralab/Styles.kt
(无结果)
```

**UI2.md 3.7 要求：**
> 风格 / 胶卷卡片 → 强度面板：卡片的圆角容器长成面板（sharedBounds），文字淡入，defaultSpatial 弹簧

**当前行为（推断）：**
- 点击风格卡片后，强度面板直接淡入/滑入
- 卡片与面板之间**没有"长成"动画**
- 视觉连续性断裂

### ✅ **观片台网格 → 单张查看**

**已实现：**
- `LightTableScreen.kt:376` 的缩略图有 `sharedFrame(frame.id, shape)`
- `LightTableLoupe.kt:238` 的全屏照片有 `sharedFrame(frame.id, enabled = shared)`
- 两者使用相同的 `frame.id`，框架会自动生成飞行动画

**状态：** ✅ **已正确实现**

### ❓ **单张查看 → 编辑器**

**需进一步检查：**
- 编辑器的照片预览是否有 `sharedFrame`
- 观片台→编辑器的导航逻辑

**暂时状态：** ❓ **待检查**

---

## 总结

| P0 问题 | 代码状态 | 连接状态 | 需真机验证 | 缺失部分 |
|---|---|---|---|---|
| P0-1: 预测性返回 | ✅ 已实现 | ✅ 正确连接 | ⚠️ 系统手势触发 | 无 |
| P0-2: 面板 3/4 高度 | ✅ 已实现 | ✅ 正确连接 | ⚠️ 拖动手感 | 无 |
| P0-3: 共享元素转场 | ⚠️ 部分实现 | ❌ **风格卡片未连接** | — | **风格/胶卷卡片 → 强度面板** |

---

## 修复建议

### 优先级 1：修复 P0-3（风格卡片共享元素）

**位置：** `Styles.kt:390-420`（ThumbCard 使用处）

**需要做的：**
1. 在风格卡片的 `ThumbCard` 上添加 `Modifier.sharedBounds(...)`
2. 在强度面板（待找到位置）上添加对应的 `Modifier.sharedBounds(...)`
3. 使用相同的 `key`（例如 `style.id`）
4. 确保两者在同一个 `SharedTransitionLayout` 作用域内

**技术难点：**
- 强度面板的位置需要确认（grep 未找到"强度面板"相关代码）
- 可能需要重构面板结构以支持共享元素

### 优先级 2：真机验证 P0-1 和 P0-2

**验证项：**
- P0-1：在 Android 14+ 设备上测试返回手势是否跟手
- P0-2：拖动面板到 3/4 高度是否流畅，吸附是否准确

---

## 下一步

1. ❌ **修复 P0-3 缺失部分**（风格卡片共享元素）
2. ⚠️ 需真机验证 P0-1、P0-2
3. ❓ 检查"单张查看 → 编辑器"的共享元素

**建议向主会话报告：P0 级问题代码基本正确，但风格卡片的共享元素转场缺失，需要补充实现。**
