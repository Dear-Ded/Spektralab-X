# 更新说明写作规则

适用于更新记录、应用商店短说明、发布页正文和应用内更新提示，包括历史条目的维护。
GPT（OpenAI）共创。

1. **说人话。** 使用用户熟悉的功能名称和日常表达。每条说明回答做了什么，以及使用时有什么改善。
2. **像产品介绍。** 优先写新增功能、体验优化和已修复的问题。用具体场景说明，例如“快速切换胶卷时，预览跟随最后一次选择”。
3. **保护私人信息。** 不写开发者姓名、账号、联系方式、本机路径、私人设备信息、云服务商、机器名称、测试环境、运行编号或内部日志。
4. **保留必要的使用信息。** 未发布版本明确标注；需要重新导入、格式限制、兼容性变化或仍会影响使用的问题要简洁说明，不能用宣传掩盖限制。
5. **技术细节归位。** 源码提交、测试数量、构建过程、哈希、性能测量条件和实现参数写入专门的工程记录。更新说明不复制这些材料。
6. **只写已实现的变化。** 不把研究计划写成已有功能，不把文件一致性写成色彩准确，不宣称缺少参照的“完全还原”。
7. **中英文保持一致。** 使用自然表达，核对同一版本的功能、限制与发布状态；遵守渠道长度要求。

例如，将“使用弹簧 .68/420 与 420 ms 潜影过渡，通过 450 项测试”改为：
“重绘胶卷包装与金属暗盒，选中时抽出胶片；连续切换时，预览平稳跟随最后一次选择。”

历史版本保留日期、版本顺序和真实功能事实，按以上规则重写措辞。已有 GitHub 发布页是外部发布内容，
修改前须有用户授权；仓库内更新说明的维护不自动改动已发布页面。

## English

Use plain, product-focused language for every release-note surface. Describe the
feature or fix and its effect on everyday use. Keep versions, release status and
material compatibility limits accurate. Exclude developer personal information,
private device details, local paths, cloud providers, machines, test environments,
workflow identifiers and internal logs. Put implementation and validation evidence
in engineering records. Never claim exact colour matching without an independent
reference. Keep Chinese and English notes consistent and within channel limits.
