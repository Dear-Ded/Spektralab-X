# targetSdk 37（Android 17）预排查

更新：2026-11-05 · 对应 targetSdk 36（0.1.20）。升到 37 之前按这张表逐项复核。

来源：
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/about/versions/17/behavior-changes-all
- https://developer.android.com/about/versions/17/changes/messagequeue

| 变化 | 本应用 | 结论 |
|---|---|---|
| 反射或 JNI 修改 `static final` 字段（37 起反射抛 `IllegalAccessException`，JNI `SetStatic*Field` 直接崩溃） | Kotlin 代码没有 `getDeclaredField` / `setAccessible`；JNI 只用 `GetStaticFieldID(Bitmap$Config, ARGB_8888)` **读取**（`spektralab_jni.cpp`），没有 `SetStatic*Field` | 无问题 |
| 新的无锁 `MessageQueue`（反射私有字段会失效） | 没有反射 `MessageQueue` / `Looper`；只用公开的 Handler 和协程 API | 无问题 |
| 分享时的 URI 授权 | 唯一的对外分享是 `ImportSheet.kt` 分享 .spklook：FileProvider URI + `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION` + `createChooser`，已是显式授权 | 无问题。可加固：同时 `setClipData(ClipData.newRawUri(...))` |
| 本地网络权限强制（`ACCESS_LOCAL_NETWORK`，NEARBY_DEVICES 组） | 远程处理（`RemoteApi.kt`）连局域网服务器时会用到 | 远程处理上线、或升 37 时处理：声明并请求权限 |
| Safer native DCL（`System.load` 的文件必须只读） | 只用 `System.loadLibrary`，库在 APK 里 | 无问题 |
| 默认开启证书透明度 | 更新只连 GitHub（公开 CA） | 无问题（未实测） |
| BAL 加固扩展到 `IntentSender`；弃用 `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` | `UpdateReceiver` 收到 `STATUS_PENDING_USER_ACTION` 后从广播里 `startActivity(confirm)`。用户切到后台时可能弹不出来 | **风险**：升 37 前真机测「下载更新后切到后台，安装确认能否弹出」；弹不出时改成发通知，由用户点开 |
| 小组件内存上限、后台音频、蓝牙 RFCOMM、联系人 CP2、OTP 短信、`setContentCaptureEnabled`、大屏方向 opt-out | 都没有用到 | 无影响 |
| 应用内存上限（所有应用，与 target 无关） | `ExitDiagnostics` 已识别 `MemoryLimiter:AnonSwap`，队列据此降档 | 已处理 |

结论：本次没有需要改代码的问题。升 37 时要处理两条：安装确认的后台启动（BAL），以及远程处理的本地网络权限。
