# 阶段1设计：TIFF 多色彩空间导出

## 实现方案

### ICC 配置文件来源
- **sRGB**：从引擎现有 io/sRGB.icc 读取（已验证存在）
- **Display P3**：使用现有 displayP3Icc() 从系统提取
- **ProPhoto**：生成标准 ROMM RGB ICC v2 配置文件

### 代码修改

#### 1. Kotlin 层（ProcessingQueue.kt）
```kotlin
enum class TiffColorSpace(val displayName: String, val targetName: String) {
    SRGB("sRGB", "sRGB"),
    DISPLAY_P3("Display P3", "Display P3"),
    PROPHOTO("ProPhoto RGB", "ProPhoto RGB")
}

// 扩展 ExportFormat 或在 ProcessingJob 中增加 tiffColorSpace 字段
```

#### 2. Native 层（spektralab_jni.cpp）
- export_tiff 函数新增 target 参数传递色彩空间
- 当前硬编码检查 `xf->target() != "sRGB"` 需修改为支持三种目标

#### 3. OutputTransform 验证
- 确认 output_transform.cpp 已支持 "Display P3"
- 验证 ProPhoto → ProPhoto 是否为恒等变换
- 如需要，为 ProPhoto 目标实现特殊处理

### 向后兼容
- ExportFormat.TIFF 默认使用 TiffColorSpace.SRGB
- 现有代码路径不变，确保二进制兼容

### UI 修改
EditorScreen.kt 导出菜单扩展为：
- "16 位 TIFF · sRGB"
- "16 位 TIFF · Display P3"  
- "16 位 TIFF · ProPhoto RGB"

## ProPhoto ICC 生成方案

使用纯 Kotlin 生成 ICC v2 配置文件：
- 色度坐标：R[0.7347, 0.2653] G[0.1596, 0.8404] B[0.0366, 0.0001]
- 白点：D50 [0.9642, 1.0, 0.8249]
- TRC：Gamma 1.8（ProPhoto 标准）

