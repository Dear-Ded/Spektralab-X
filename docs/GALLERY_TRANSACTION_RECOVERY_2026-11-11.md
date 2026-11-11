# 相册提交与恢复

GPT（OpenAI）共创，2026-11-11。仅源码与主机验证，未构建 APK、安装或发布。

## 修了什么

同步写入相册不会随协程取消立即停止。旧逻辑可能已成功发布照片，却因 IO
返回时的取消检查把任务记为取消并丢失 URI；用户重试会再生成一张。临时查询
异常也曾被当作“文件不存在”，内部内存不足降档重试可能覆盖未决票据。

现在以相册实际状态归并结果：

| 核对结果 | 行为 |
| --- | --- |
| 已发布 | 优先记为完成，即使同时取消或收到了失败回执 |
| 确认仍为 pending | 仅以 IS_PENDING=1 条件删除；删除成功才可清票据 |
| 删除零行 | 再查一次，区分已发布、已移除和未知 |
| 查询抛错、null cursor、删除失败 | 保留 URI，停止该项并提示稍后重试核对 |
| 非 null cursor 正常返回零行 | 确认不存在，可清票据 |

用户重试先核对同一票据；启动恢复先核对再判断尝试次数；内部降档重试也受
同一门禁约束。启动清扫、历史裁剪和清空历史保留未决记录。它们可以在核对
完成后正常清除，本轮没有加入强制丢弃票据的入口。

取消意图写入 ProcessingJob.cancelledByUser，旧 JSON 默认 false。进程死亡
后不再自动重做用户已经取消的任务，已发布照片仍优先归并为完成；明确重试
会清除取消意图。TIFF 创建和记录 URI 在同一个 IO 段执行；markPending
检查同步持久化是否成功，失败则不继续发布。

独立终审另复现了“排队取消后立即重试”的竞态：旧异步清理会把新排队状态
覆盖为取消。最终回调绑定原 URI，并在写取消终态时再次检查取消意图；旧
回调不能处理重试建立的新票据。

## 验证

本机 Temurin 21.0.13、Kotlin 2.0.21、协程 1.9.0 执行 34 项 JVM 检查：
12 项纯状态单元测试，22 项真实协程时序合同。测试编译整个生产 MediaSaver、
GalleryTransaction，并逐字提取 14 个队列方法及持久化声明，使用真实 IO
线程和 barrier。新增立即重试的两项回归修前均失败，修后全部通过。

覆盖发布后返回前取消、复制中取消、提交后回执异常、未知状态重试不重渲染、
删除失败、尝试次数上限、内部降档重试、同步记录失败、历史/启动清扫，以及
取消后序列化、load、恢复的 PENDING/PUBLISHED/UNKNOWN 三种结果。

复现：

```powershell
./tools/jvm/run_gallery_transaction.ps1 `
  -BuildDir build/gallery-contract `
  -ToolDir build/host-jvm/host-tools
```

ToolDir 使用独立 JRE、Kotlin、JUnit、JSON 和 Hamcrest 缓存；可先用
tools/jvm/run_tiff_targets.ps1 准备公开主机依赖。Gallery 脚本只额外获取固定
1.9.0 协程依赖，不触发 Gradle、NDK 或 APK 构建。

源码、修前/修后日志及完整应用编译结果保存在
[gallery-transaction-20261111](../tools/native/validation/gallery-transaction-20261111/README.md)。

## 边界

主机 provider、Android framework、preferences、引擎与服务使用替身；它验证
生产状态逻辑和协程时序，不代表 Android 系统、OEM MediaStore 或真实断电
持久性验收。同步 copy 仍不保证立即取消，已成功发布则准确承认完成，不回滚
成品。存储介质拒绝持久化仍是外部失败，未知票据不会自动反复重导。
