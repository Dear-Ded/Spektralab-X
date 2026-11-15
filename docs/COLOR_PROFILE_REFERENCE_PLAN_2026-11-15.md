# 厂商色彩：配对参考与验收准备计划

日期：2026-11-15。GPT（OpenAI）共创。**未来真实配对样本全部 pending，尚未采集。** 本稿供适配前整合；采集数量和新验收门槛均为拟定工程预算，须在调参集上确认、冻结，不是已测结果或发布批准。

依据：[厂商研究](VENDOR_COLOR_RESEARCH_2026-11-15.md)、[工具说明](../tools/vendor_color/README.md)、[原创风格研究](COLOR_STYLE_RESEARCH_2026-11-15.md)、[既有 validation 说明](validation/vendor-color-2026-11-15/README.md)。直接引用既有云端证据，不重新开展研究。

## 1. 已有证据与声明边界

| 已校验 corpus / 证据 | 支持的结论 | 不支持的结论 |
|---|---|---|
| DCP：161 文件，2,411 标签、20,662,584 数值；aoidc/final/summary.json、dcp-inventory.json | 固定文件结构/数值解码；与 ExifTool 最大打印数值差约5.12e-13 | 完整RGB渲染、照片色差、原厂外观 |
| DCP组件：两机各40,960点C参考，另有8,192点解析检查；aoidc-c-reference.json / ibx-c-reference.json | HSV表参数插值；C参考最大分量差约1.35e-13 | WB/矩阵/双光源/曝光/曲线全链或官方成片一致 |
| NP3：13公开文件+96参考生成组合；aoidc/final/np3-comparison.json | 已观察0310变体逐字段一致 | 109张照片、基础Picture Control或滑杆数学已复现 |
| Canon：8官方PF2、109条TLV，出现的3个校验和有效；ibx/ibx-canon-tlv.json | 容器边界、观察字段和块定位 | 颜色/曲线块渲染；PF3已验证；缺失校验和算通过 |
| Lensfun：56 XML、1,059机身条目、1,580镜头条目；两机摘要一致 | 固定数据库几何/TCA/暗角盘点 | Android校正实现、去重型号覆盖、光谱镜头色彩 |
| 原创风格：validation/style-lab-2026-11-15/acceptance.json、output-final/PACKAGES.json / PUBLIC_IMAGES.json / metrics.json | 10款双语20包的数值、导入、生产插值检查；4张公开sRGB图有限预览 | 厂商参考、多肤色/植被/LED/RAW全面验收 |
| 公共RAW：[SAMPLES](SAMPLES.md)、tools/samples/manifest.tsv / expected.tsv；33文件；另见[RAW回归](RAW_COLOR_REGRESSION.md)、[输入审计](ENGINE_RAW_INPUT_AUDIT_2026-11-12.md) | 各自源码快照下解码、有限性、WB/矩阵/黑位/几何等回归 | 同曝光官方软件/相机JPEG配对；均值容差作为色彩准确度 |

厂商证据路径均相对 validation/vendor-color-2026-11-15/。已有结果只绑定归档源码/输入版本；旧报告已修复限制不直接当成当前缺陷。RawTherapee品牌文件名不是原厂外观授权。其他厂商公开视觉意图研究也不等于取得官方实现。

当前可表述“已验证固定文件结构/字段/插值组件”“原创finishing候选通过已有数值/导入检查”“Lensfun资料盘点”。不能表述原厂精准复刻/官方背书、全机型固件/光源覆盖、应用已完整导入渲染NP3/PF2/PF3/DCP、绝对色度准确、全面肤色保护、镜头色彩复现或手机显示/性能已验收。

## 2. 配对采集与元数据合同

一个capture_id对应一次快门：未改动RAW + 同快门相机JPEG + 从该RAW生成的官方软件导出。各资产记录SHA-256/字节数，导出绑定输入RAW哈希和配方。缺一方登记为明确的双配对并保持待补；邻近快门、RAW内嵌预览、网络示意图不能冒充同快门JPEG。分别评价RAW→官方软件、RAW→相机JPEG，并报告两种目标自身差异，不预设相等。

范围键固定：精确机型/模块、固件、RAW模式、基础外观、配方哈希/版本、官方软件完整build/处理版本、WB策略、光源集合、输出颜色合同。首批拟选各一个支持目标配方且有权使用官方工具的Nikon/Canon范围键，具体型号/版本pending；DCP完整执行参考另列，不等同原厂近似。

| 必填组 | 采集内容 |
|---|---|
| 身份/血缘 | sample/capture/scene/session ID、同源变体组、人物匿名ID、split；原件/导出/配方哈希、官方导出输入RAW哈希 |
| 来源/权利 | 公共原始URL或授权记录编号、取得日期、作者署名、许可文本/版本/证据；逐资产明确研究、衍生输出、云处理、再分发、应用嵌入权限及肖像使用范围。公开下载不等于打包许可 |
| 相机/镜头/几何 | make/model/模块/固件；RAW位深/压缩/CFA或LinearRaw、多帧/HDR、有效区/尺寸/方向/裁切；镜头/卡口/焦距/光圈/距离及已应用修正 |
| 曝光/WB | 快门/ISO/光圈/补偿/包围档/基准测光、RAW黑白位与剪切；软件曝光/基线曝光；WB模式/K/厂商tint坐标定义、AsShotNeutral/WhiteXY或乘数、软件继承/覆盖设置。相同K不能视为相同WB |
| 光源/对象 | 实例ID、类型/型号、布局/混光/闪光/频闪、实测CCT/xy/SPD及仪器/测量状态；色卡版次/批次。未测记null/pending，不能用K/CRI或标称色卡值冒充光谱/反射率实测 |
| 外观/软件 | 基础Picture Control/Picture Style/profile ID、全部滑杆/曲线、配方原件；NX Studio/DPP等完整build、处理版本（可得时）、兼容模式、OS、设置快照；未暴露参数注明并限制声明 |
| 自动/空间处理 | 自动曝光/色调、降噪、锐化/清晰度、局部对比、高光/动态范围、HDR/AI、镜头畸变/TCA/暗角、缩放/输出锐化；可关闭的锁定，不能关闭的列入目标 |
| 输出/工程 | 每资产尺寸/位深/范围、原色/白点/TRC、ICC哈希、JPEG质量/抽样、SDR/HDR/增益图；CMM/版本/intent/黑点补偿/CAT/色域政策；引擎源码/patch/参数、解码模式/线程/half-size budget及WB/矩阵/曝光/曲线已应用账本 |

无ICC须有可核对的明确空间依据，否则阻塞色差验收。未知许可、关键版本/设置缺失均保持pending。官方软件不能在授权云环境执行时，由有权使用方另行提供参考和参数快照；不得换用别的显影器冒名。

机器模板：[paired_reference_manifest.template.json](../tools/vendor_color/reference_contract/paired_reference_manifest.template.json)。samples为空，模板记录不计样本；null不是默认/通过，not_applicable必须有理由，空缺失字段列表也不表示合格。只存公共URL或授权资料区匿名引用，不记录账号/序列号/私人定位/联系方式，不读取凭据或私人交接，不上传私人照片/授权原文；云处理须有明确权限。

## 3. 采集覆盖与训练/验收隔离

每范围键起始计划18训练场景组+6调参组+12独立验收组，各拍-2/0/+2 EV，至少108次快门；这是未来预算，当前数量为零。补低/较高ISO、as-shot/固定WB变体；重导出不算独立场景。严重剪切另作压力样本，另补未剪切有效曝光。宣称支持的组合须逐格覆盖，张数不能替代缺格。

| 覆盖 | 计划要求 |
|---|---|
| 灰阶/色卡 | 至少20级灰、黑白端点、渐变及标准色卡，各光源独立拍摄 |
| 多肤色 | 至少3个皮肤外观明度/色度层次、每层2位授权人物；日光/暖光；验收人物不用于训练/调参，不从肤色推断种族 |
| 植被/天空 | 多叶材/透射反射/阴影，蓝天/薄云/逆光/反射；验收每类至少2个实景组 |
| 织物/LED | 红蓝紫青饱和织物及纹理/荧光标识；高显色白光、窄带/霓虹、混光；验收每类至少2组，LED留出独立光源实例 |
| 高光/暗部 | 镜面/彩色近剪切/回拉、暗纹理与噪声，验收至少2组；区分传感器、软件及输出剪切 |

光源至少日光、阴天/开放阴影、钨丝、高显色LED、窄带LED、混合光六类；验收每类至少2个场景组。对象可共现，但同图多个ROI不算多个独立场景；12组不足覆盖则补采。无光谱/反射率实测只能声明指定成片一致性，不能声明绝对物体色准确。

拟合前冻结分组：同RAW、连拍、包围曝光、裁切/缩图、WB重导出、配方变体同split；按session/场景/人物/光源实例防泄漏。验收保留新场景/人物，不复用同拍摄session；调参集可选参数/门槛，验收不得用于拟合、阈值选择或逐图WB/曝光优化。看过验收再调参，该批降为调参并另采独立验收。解封前冻结源码/参数、清单哈希、ROI/掩膜和阈值；逐图及光源/肤色层次/曝光/WB/模式分别汇总最差格。

## 4. RGB与感知比较规则、分级门槛

完整DCP数学参考使用同一已准备camera RGB及独立完整处理链，覆盖WB/矩阵/双光源/HueSat/曝光/Look/Tone/输出；真值不得调用待验实现。真实RAW全链另外评价。锁定有效区/方向/镜头校正/尺寸，记录几何映射/插值核；建议配准中位残差≤0.5原始px、P95≤1px、重叠≥预声明比较区90%，使用独立留出控制点检查。失败记不可比较并补采；ROI/遮挡反光掩膜预定义，边缘侵蚀≥2px，不能按误差筛除。

共同有效区全像素逐R/G/B报告有符号均值、MAE/RMSE、绝对误差P50/P95/P99/max，分别给归一化编码RGB/码值尺度及同一线性输出RGB；并列灰/肤色/材质ROI、平滑/纹理区、有效/排除数量与原因。缩图/抽样必须标preview/sampled。主结果不做逐张曝光/WB拟合，归一化诊断另列且不参与通过判断。场景线性负值/>1不隐藏，分桶报告；剪切率、新增剪切、灰阶单调/端点另列，有意染色不强制R=G=B。

SDR按各自明确profile/TRC转共同XYZ/CIELAB D50，记录CMM/intent/CAT/黑点补偿/色域政策，避免重复适应；ΔE00用kL=kC=kH=1，逐图/ROI/必需格报mean/P50/P95/P99/max、超过3/5比例及L*/色度/环形色相差，低色度不解释色相角。100×Oklab另列，不能称ΔE00；SDR Lab不证明HDR。光晕/降噪纹理/断层/局部映射/预览导出须另审联系图，记录显示profile/亮度/环境/缩放/审阅者；PSNR/SSIM仅辅助。

**单位不得混淆：** 5.12e-13是解码数值差、1.35e-13是HSV表参数分量差，均不是RGB或照片色差。原创包1.19209e-7是同一离散表插值差；LUT对连续算法的100×Oklab又是另一层。

| 等级 | 准入与拟定通过规则 | 当前状态/可声明范围 |
|---|---|---|
| R0 来源 | 配对/输入哈希正确，必填元数据与相应用途权限齐全 | 新真实配对pending；许可未知阻塞相应用途 |
| R1 文件/组件 | 固定字节/源码、完整数值/字段/独立函数对照，未知/不支持项明确 | 仅既有归档范围成立，不表示应用已支持导入 |
| R2 完整DCP RGB | 独立完整链/步骤账本；正常域漫反射白=1，各通道MAE≤1e-5、P99≤1e-4、max≤1e-3；负值/高光预算另冻结。非有限数、漏步骤、静默忽略不支持字段失败 | pending；通过仅称执行指定版本/子集DCP，不等于官方直出 |
| R3 限定厂商近似 | 独立矩阵/配准合格；每图及必需格ΔE00 mean≤2、P95≤4、P99≤6；灰/肤色ROI mean≤1.5、P95≤3；各通道编码RGB MAE≤2/255、P99≤8/255，线性RGB MAE≤0.01、P99≤0.04；同SDR合同新增剪切≤0.1%；max/尾部/空间缺陷另审 | pending；官方软件/相机JPEG分别判定，仅称列明机型/版本/光源/模式下近似 |
| R4 应用发布条件 | 权利及对应R2/R3齐备，实际接入版本预览/导出/强度/输入分支、设备颜色管理/资源性能有证据 | pending；云端数值或主线程UI审阅不代替设备/厂商成片对照 |
| S 原创风格支线 | 复用已有候选门槛：公开照片采样LUT对连续算法max<0.75、均匀ProPhoto max<5，单位100×Oklab；再补实拍/设备审阅 | 数值候选证据已有，广泛实拍pending，不称厂商复刻 |
| L 镜头空间支线 | 指定机身/镜头/焦距/光圈/距离/裁切的网格畸变、TCA位移、均匀场通道暗角残差及重复修正检查；阈值待基线/分辨率冻结 | 仅数据库盘点已有，实现/残差/预算pending |

新阈值须在调参阶段冻结，不能验收后追认放宽；R3不是视觉不可区分/行业认证/准确度百分比。全有效区与ROI各自满足对应预算，失败光源/模式须补齐或明确缩小声明范围，不靠总体均值掩盖。

## 5. 三类资产与既有工具复用

| 资产支线 | 复用清单与待补 |
|---|---|
| 相机输入/厂商外观 | aoidc/corpus-files.json、DCP机型/签名/光源/编码/版权/Embed Policy、NP3/PF2报告；profile/基础模式/用户配方分ID。逐资产许可、完整RGB/官方参考pending；DCP不装后置CUBE，无明确许可的研究解析器不转运行时依赖，PF3仍pending |
| 原创照片风格 | tools/style_lab/candidates.json为唯一参数源，PACKAGES.json绑定双语20包；65³ encoded ProPhoto、v2 .spklook、scope=filter，保留LICENSE/NOTICE。双语表相同不等于包哈希相同，sRGB预览不是宽色域参考。优先补clear_portrait多肤色、airy_daylight高光、powder_coast海天、silver_document灰阶，mineral_greens植物、night_cobalt LED；其余补招牌/暖肤/黄绿材质/双色灰阶 |
| 镜头几何/TCA/暗角 | Lensfun固定XML/哈希，数据库CC BY-SA 3.0与代码LGPL分开；匹配/插值/已修正状态/残差/许可义务pending。不装全局LUT，不推断镀膜透射、耀光、散景 |

| 已核查工具 | 可复用输出及限制 |
|---|---|
| vendor_color的fetch_sources.py、inspect_profiles.py --full、validate_profiles.py及现有NP3/C/PF2参考入口 | 复用来源/字段/偏移/哈希/组件报告；不生成官方照片或完整RGB参考 |
| tools/native/raw_color_audit.cpp；float_dng_deflate_host的SPK_BUILD_RAW_COLOR_AUDIT目标 | 生产decode_raw_precision导出PREFIX.f32（小端RGB Float32、linear ProPhoto D50）和元数据JSON；budget=0全尺寸。当前CLI为camera WB、无显示显影，不是外部profile/配方渲染器 |
| tools/native/raw_color_compare.py | 复用ICC/配准/曝光诊断、comparison.json和三联图；实际缩至1536、relative intent转8位sRGB、要求RGB ICC、拟合中间调曝光。现门槛≥30内点/比例0.3/覆盖0.15/中位2px，不等同本稿严格全幅；其色度距离不是ΔE |
| run_raw_camera.sh / spk_decode / regress.sh；spk_render / samples.sh / compose.py | 复用WB/有限性/解码指纹和联系图，不重录expected.tsv。spk_render的none路中位曝光增益、RAW路auto exposure、8位PNG/stb sRGB限制，使其不能直接作锁曝光精密参考 |
| style_lab/build.py、既有StyleLabCandidateTest / run_lut_bake_parity.sh | 复用20包、PUBLIC_IMAGES/PACKAGES/metrics、联系图和.bin fixture；4公开图缩至512、每13像素采样，不是全幅RAW标定 |
| ciede2000_reference.py、lut_metrics.py、gate_report.py及ICC typed/ProfileCmm工具 | 复用独立ΔE公式/报告模式/输出TRC与ICC检查；公式不做RGB转换，现LUT报告固定linear P3/D65 SDR且部分裁剪，不能直接输入ProPhoto/sRGB/D50或套用其mean≤3。ICC证据不是任意ICC输入/设备显示验收 |

后续仅在授权云环境执行：补权利/采集→冻结范围/分组/设置→取得官方参考并核对RAW哈希→输入审计保留float/元数据→诊断对齐→完整全像素/ROI/感知报告→独立验收。尚无通用“官方RAW全链+严格配准+全幅D50 ΔE00”批处理入口；保留为后续缺口，不在本任务新增实现/依赖。

## 6. 当前待补数据与阻塞

| 阻塞 | 必须补齐 |
|---|---|
| B1 范围/权利 | 精确机型/固件/RAW模式/官方软件build/基础模式及逐资产研究、云处理、衍生、再分发/嵌入权限 |
| B2 真配对 | 未改动RAW、同快门JPEG、同RAW官方导出、设置快照/来源链；双配对明确缺方，全部pending |
| B3 覆盖/测量 | 灰阶、肤色、植被、天空、织物、LED、高光逐格拍摄及曝光/WB/光源记录；无光谱/反射率则限缩声明 |
| B4 独立验收 | 无同源泄漏的train/tuning/acceptance、冻结门槛/ROI/掩膜/控制点/CMM和完整报告 |
| B5 完整参考 | DCP独立RGB及步骤账本、Nikon基础/滑杆响应、Canon块语义；PF3官方样本/算法/许可 |
| B6 支线/应用 | 原创自然场景/审阅；镜头网格/均匀场和残差预算；接入后云端验证与授权设备预览/导出/显示/资源证据 |

交付仅本文和既有空JSON模板；未采集或编造标定值，未执行构建/测试。未改另一agent的集成稿或既有未跟踪文件；未提交、推送、发布或创建PR。未来所有可执行测试仅云端。
