# 架构

SpektraLab for Android 分三层：Compose 界面、Kotlin 编辑器状态、C++ 引擎。引擎来自上游，不在本仓库里改动源码，只通过 [`patches/`](../patches/) 打补丁。

```
┌─ 进程 ──────────────────────────────────────────────────────────────┐
│ SpektraLabApp ── EngineHost（进程级，只启动一次）                     │
│                   ├─ 引擎句柄、资源目录、胶片目录、GPU 探测结果          │
│                   ├─ scope：导出等需要比编辑器活得久的任务              │
│                   ├─ EventLog：最近 40 条事件，供诊断信息使用           │
│                   └─ ThermalMonitor：发热余量（每 1.5 秒最多查询一次）   │
│                                                                    │
│ MainActivity ── EditorScreen（Compose）                             │
│                   │ UiState（StateFlow）                            │
│                   ▼                                                │
│                 EditorViewModel（每个编辑器一个）                     │
│                   ├─ SourceLoader：识别 RAW/照片、解码、缩小           │
│                   ├─ EditHistory：撤销/重做，0.7 秒内的改动合为一步     │
│                   ├─ 渲染循环：live → preview，旧任务可取消             │
│                   ├─ Bitmap 池：每档两张轮换，不再每帧新建             │
│                   └─ 导出：在 EngineHost.scope 里运行 + 前台服务       │
│                   │ JNI（NativeEngine，只在 EngineThread 上调用）      │
│                   ▼                                                │
│ spektralab_jni.cpp ── spk_engine.h（上游 C 接口）                    │
│                   ├─ LibRaw 解码、半尺寸解码、白平衡                   │
│                   └─ SpektraLab 引擎（C++20）── Vulkan 计算着色器       │
└────────────────────────────────────────────────────────────────────┘
```

## 线程

- **EngineThread**：唯一调用引擎的线程（单线程执行器，64 MB 栈）。引擎的错误信息是线程局部变量，同一会话也不能被两个线程同时驱动。
- **主线程**：界面和 `UiState`。唯一的例外是 `NativeEngine.cancel`，它可以从主线程调用，用 `cancelLock` 保证不会碰到已释放的会话。
- **Dispatchers.IO**：读文件、写 MediaStore。

## 渲染档位

| 档位 | 长边 | 何时渲染 |
|---|---|---|
| `live` | 1024 px | 每次改动，拖动滑块时连续渲染 |
| `preview` | 3400 px | 松手后立即渲染；按住不动 1.2 秒也会渲染 |
| `full` | 工作尺寸 | 导出、1:1 查看 |

新改动到来时，正在跑的 `preview` 会在下一个节点处取消；`live` 不取消，否则拖动时永远看不到画面。

## 内存

单张图能处理多大，取下面三者中最小的：

1. **GPU 单缓冲上限**：一个平面占一个存储缓冲。小米 12–14（Adreno 7xx）只有 128 MiB，约 8 MP。
2. **可用内存**：每像素按约 100 字节估算，并给系统留 1.5 GB。
3. **画布上限** 4200 万像素：在 Adreno 上超过这个尺寸，曾经导致 GPU 设备丢失。

其他内存措施：

- 解码前先释放上一张照片。
- 传感器像素超过预算 4 倍时，LibRaw 用半尺寸解码。
- 预览 Bitmap 复用，不再每帧新建。
- `onTrimMemory` 时释放 GPU 缓冲池里空闲的平面和 1:1 视图，当前照片保留。

各 GPU 的具体数字见 [DEVICES.md](DEVICES.md)。

## 引擎补丁

| 补丁 | 作用 |
|---|---|
| `0001-iir-chunked-launches` | 模糊计算每 512 行提交一次，避免触发高通驱动 2 秒的 GPU 超时 |
| `0002-dispatch-chunks-under-2-24` | 单次计算不超过 2²⁴ 个线程，Adreno 在这个数以上会写坏图像 |
| `0003-raw-half-size-budget` | 传感器远大于工作尺寸时，按半尺寸解码 |
| `0004-dng-dual-illuminant-matrix` | DNG 按拍摄白平衡在两组色彩矩阵之间插值 |

补丁在 [`scripts/fetch-upstream.sh`](../scripts/fetch-upstream.sh) 下载上游源码后按文件名顺序应用。补丁内容一变，CI 缓存键也跟着变。

## 生命周期

- 引擎随进程存在。退出编辑器再进来，复用已经预热的引擎，不用重新编译 GPU 管线。
- 会话（一张打开的照片）属于编辑器。编辑器销毁时，会等正在进行的导出结束，再释放会话。
- 导出期间运行一个前台服务，只用来提高进程优先级，避免切到后台后被 HyperOS 杀掉。
