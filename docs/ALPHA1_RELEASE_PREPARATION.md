# 0.2.00-alpha-1 源码审查记录

共创：GPT（OpenAI），2026-11-09。

状态：**继续源码深审；用户已允许提交并推送确认通过的源码。仍不打 tag、不发布、不生成 APK。**
`VERSION=0.2.00-alpha-1`、`VERSION_CODE=121` 是本地工作树中的待发布目标，不是发布完成或可发布承诺。
本次确认源码推送不包含这两个版本文件及 fastlane 发布材料，功能分支保留公开版编号。
比较基线是 `6aff78e9d1b7ac0e137526b36781271561ef95f8`，即公开 0.1.19-beta。

## 相对公开 beta 的源码范围

- DNG ForwardMatrix、白平衡温度表、known-K 双光源插值和缺失 as-shot 的有效 custom WB 处理；不人为增加饱和度。
- RAW candidate 事务，失败/取消保留旧照片、URI 和历史；尚需覆盖双会话内存准入。
- Vulkan device-loss 准备阶段防护、CAM16 常量外提和 DI 通道预拆；保留 Android 原补丁及 pin。
- 可选 Vulkan 点核融合，默认关闭；胶片前 scene curve Vulkan、参数/UI/风格/队列接入。
- noFilm 内部 float 到完整 Layer2 后量化，修复可恢复 HDR/负通道与近黑亮度误差；保留公开 C ABI。
- 场景曲线 dispatch base、Double/Float 曲线传输、局部蒙版亮度/负通道的后续边界修复。
- 导出文案区分工作尺寸与受设备限制的高质量导出，JPEG/HEIF 完成记录显示实际尺寸和体积。
- 后续优化：CRX 四处 signed quantizer 索引维护、工作源面积独立预算、无纸框极致 JPEG 匿名外存行转换；详见 [六阶段路线](ENGINE_OPTIMIZATION_ROADMAP_2026-11-09.md)。
- 第二批优化：WhiteXY-only 三通道主 profile 解码、源白平衡 provenance，以及 view/读回/CPU 行转换/输出的取消边界；保留旧成功预览到新结果打包成功，详见 [第二批记录](validation/optimization-wave2-2026-11-09.json)。
- 第三批优化：整数/DI view 与 legacy look 取消、匿名 spool 空间预留、候选/导出生命周期互斥及峰值采样窗口修正。浮点 DNG 精度缺口与 LinearRaw 参考实验单列，默认解码尚未替换。见 [第三批记录](validation/optimization-wave3-2026-11-09.json)。
- 版本比较支持带序号的 alpha/beta/rc；更新记录保留 Claude 与 GPT 的各自来源，不把旧 beta 的 UI 工作重复称为本轮新开发。

详情见 [CHANGELOG](../CHANGELOG.md)、[补丁清单](PATCHES.md)、
[固定上游评估](UPSTREAM_REVIEW_2026-11-09.md) 和 [本轮深审](ENGINE_DEEP_REVIEW_2026-11-09.md)。
上游 main 未整体覆盖 Android；预烘焙表、minilab 和 halation 语义变更仍单独评估。

## 验证与尚缺条件

机器可读结果和源码证据见 [validation/alpha1-2026-11-09.json](validation/alpha1-2026-11-09.json)。
早期 native/输出对照、51/58 项 JVM 作为历史快照保留；最新三项优化的两云 JPEG/CRX、66 项 JVM 和 NDK 源码编译见 [优化记录](validation/optimization-wave1-2026-11-09.json)。不把旧 APK 构建冒充最新源码的打包验收。
第三批实际执行 release Kotlin 编译、73 项 JVM 测试及 NDK 原生构建，零测试失败；
IBX 独立复证 7 项生命周期测试。源码哈希、完整日志及手机运行边界见 [第三批记录](validation/optimization-wave3-2026-11-09.json)。
基线胶片字节对照只覆盖列出的固定输入和设置，不覆盖所有胶片、随机状态及 OEM GPU。

用户本人 RAW/UltraRAW 原片与 JPEG 实际尺寸缺失；Adreno/Mali 真机长时间渲染、完整 DNG 默认显色、
内存准入与取消链路、真正原分辨率有界管线仍未完成。已发现的结构缺口和可执行验收在深审文档中列明。
不得据目前 host 数字宣称“所有手机已还原色彩”“全分辨率导出已实现”或“全算法逐字节等价”。
