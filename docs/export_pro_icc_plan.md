# ICC 配置文件获取方案

## 现状
引擎资源目录 assets/engine/io/*.icc 在运行时复制到内部存储，但构建产物未找到实体文件。

## 方案选择：生成标准 ICC

### 理由
1. Display P3：已有 displayP3Icc() 从系统提取
2. sRGB：可从系统或生成标准配置文件
3. ProPhoto：Android 无内置，必须生成或嵌入

### 实现方案
使用 Little CMS (lcms2) 纯 Java 移植或直接嵌入预编译 ICC：

#### 选项1：嵌入标准 ICC 二进制（推荐）
- sRGB：IEC 61966-2-1 参考配置文件（公开可用）
- ProPhoto：ICC 官方 ROMM RGB 配置文件
- 许可：ICC 配置文件格式是开放标准，参考实现可自由使用

#### 选项2：运行时生成
```kotlin
// 生成 sRGB ICC v2
fun generateSrgbIcc(): ByteArray {
    // 使用标准参数构建 ICC 二进制结构
    // - 白点 D65: [0.9505, 1.0000, 1.0890]
    // - 色度: R[0.64, 0.33] G[0.30, 0.60] B[0.15, 0.06]
    // - Gamma 2.2 + 线性段
}
```

### 下一步
1. 从可信源获取或生成三个 ICC 文件
2. 验证与 output_transform 色彩空间定义一致
3. 嵌入到 res/raw 或 assets
