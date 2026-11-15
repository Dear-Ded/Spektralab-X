# 下一版色彩与照片风格研究

日期：2026-11-15。GPT（OpenAI）共创。目标：从公开方法中提炼可验证的改进，制作原创、可导入的候选；不宣称复刻相机品牌，不复制商业 LUT、付费预设或创作者配方参数。本轮不发布新应用版本。

## 1. 管线定位与结论

当前已有 87 款内置风格，以及 RAW → 场景线性 ProPhoto → 物理胶片 / 相纸 → 输出编码 → Layer 2 的管线。继续堆叠品牌名称不能解决输入色彩合同、肤色附近的调整、饱和色渐变和风格适用性问题。

本轮改进放在独立离线生成器 `tools/style_lab/`，通过现有 v2 导入格式供下一版筛选：

- 使用原生编码 ProPhoto 65³ LUT，避免先把宽色域输入夹入 sRGB 再调色；现有外部 sRGB LUT 合同保持。
- 在 Oklab 中分别控制明度、色度、色相与色彩密度，周期权重穿过 0°/360° 时连续。密度指有色区域的明度变化，不等于整张降低曝光。
- 使用宽泛肤色近似颜色权重，减弱部分调色；不识别人脸，不等于经过多肤色标定。木材、土壤和皮肤可能处于同一区域。
- 原创色域压缩沿线性 ProPhoto RGB 中性轴进行，在立方体边界的 85% 位置启动柔和膝点，保持相对于中性锚点的 RGB 比例。它不保证 Oklab 明度和色相完全不变；实际偏移与硬裁剪对照记录在证据中。
- 接近黑色的第一层 65³ 网格保留原色，至编码 RGB 最大通道 0.08 平滑进入完整风格，降低极暗高彩度像素的插值误差与染色。
- 彩色风格对 Oklab LMS 为负的极端 ProPhoto 颜色保留输入；在 min(LMS)/max(LMS)=0..0.12 内平滑增加风格影响。这是数值适用范围控制，不是色彩还原。两款单色风格改用线性 Rec.709 / D65 亮度的立方根映射，避免保留彩色原色；最深暗部仍遵守上述原色保留规则。
- 导出仅滤镜范围，不设置曝光、白平衡、胶片、相纸或照片局部信息。风格强度继续使用现有 RGB 混合，未改成感知混合。

算法参数是本项目原创设计，不是厂商数学实现。65³ LUT 的离散误差与 sRGB 预览色域限制单独计量。独立风格实验不能替代真机性能、相机色彩标定、真实 HDR、照明分析或空间处理。

## 2. 厂商方法研究

以下是各厂商公开资料中的设计取向，适用特定产品、模式与固件，不能当成可移植的官方配方。

| 厂商 | 公开方法与资料 | 可借鉴方向 | 在本项目中的边界 |
|---|---|---|---|
| Fujifilm | [Film Simulation](https://www.fujifilm-x.com/en-us/products/film-simulation/)、[Reala Ace 方法说明](https://www.fujifilm-x.com/en-us/exposure-center/simulation-clues-part-nine/)：自然色彩与独立层次设计 | 人像不靠全局染黄，分别处理亮暗区域与色彩强弱 | 不复制模拟模式、Color Chrome 或传感器专有处理 |
| Sony | [Creative Look 官方说明](https://www.sony.com/electronics/support/articles/00261294)：FL、IN 及部分机型 FL2/FL3 的不同色彩意图 | 让低彩度、浓彩度与反差成为独立轴 | 型号支持不同，不用 Sony 模式名作为候选名称 |
| Nikon | [NX Studio](https://imaging.nikon.com/imaging/lineup/software/nx_studio/)、[Imaging Cloud](https://www.nikonusa.com/learn-and-explore/c/tips-and-techniques/imagine-create-inspire-nikon-imaging-cloud)：Color Blender、Color Grading、曲线及创作者配方 | 分区调整与连贯渐变；配方附适用场景 | 不导入其相机配置文件或宣称 NCP/NP3 精确还原 |
| Canon | [Picture Style 原理说明](https://global.canon/en/imaging/picturestyle/editor/understanding.html)、[Faithful](https://global.canon/en/imaging/picturestyle/style/faithful.html)：模式的对象与拍摄条件有明确限制，Faithful 针对约 5200 K 光照描述 | 把自然还原与创意外观分开评估，避免“万能真实色” | 忠实模式具有输入条件，不能推断任意照片都色度准确 |
| Hasselblad | [HNCS 说明](https://www.hasselblad.com/about/history/heritage/what-makes-hasselblad-unique/)：关注自然物体与肤色的连贯表现 | 保护肤色附近与中性物体，少做全局高饱和 | HNCS 属于系统色彩与相机校准，通用 LUT 不能等同 |
| Leica | [Leica Looks](https://leica-camera.com/en-int/photography/leica-apps/leica-looks)：标准、复古、单色及色彩强调等方向 | 暗部冷色与亮部暖色要克制，保留黑白锚点 | Looks 文件和商业实现不在本轮采集范围 |
| Ricoh GR | [GR IV Image Control](https://www.ricoh-imaging.co.jp/english/products/gr-4/feature/04.html)：Cinematic Yellow/Green 与独立亮暗反差控制 | 黄绿色相、色彩密度、亮暗反差分别设计 | 原有 Negative Film 灵感已有覆盖，新增候选不重复贴牌 |
| Pentax | [Custom Image SATOBI](https://www.ricoh-imaging.co.jp/english/products/custom_image/)：低彩度、特定色偏与时代感的组合 | 赭石与克制植物色，避免把“复古”简化成抬黑和泛黄 | 仅研究公开视觉意图，不套用厂家参数名或商标 |
| Panasonic | [LUMIX 官方 Photo Style 手册](https://av.jpn.support.panasonic.com/support/global/cs/dsc/manual/html/DC-S1M2_S1M2ES_en/18750784.html)：Natural、L.ClassicNeo、Like709 等合同不同 | 浅淡但保留清晰层次，区分照片外观和视频输入 | 不将 V-Log、Like709 当作普通 JPEG 的替换滤镜 |
| Sigma | [fp 官方手册 p.93](https://www.sigma-global.com/en/support/download/fp_Manual_FW_Ver.5.0_EN.pdf)：Powder Blue、Forest Green 针对不同对象设计 | 蓝色提亮与降低彩度分开，植物色改变需有方向 | 本轮粉蓝和植物候选为原创设计，不能承诺厂商一致 |
| OM System | [Color Profile Control 官方手册](https://learning.omsystem.com/PEN/zz_html_manual/en/picture_mode_130.html)：多色分区和影调控制 | 以少量局部色彩变化维持整体协调 | 不采集厂家资产；全局 LUT 不理解主体或背景 |

## 3. 摄影师与调色创作者

| 创作者 / 机构 | 原始公开资料 | 本轮提炼 | 无法靠一个 LUT 完成的部分 |
|---|---|---|---|
| Cullen Kelly | [本人 Contour 方法介绍](https://cullenkellycolor.com/contour)：Curve、Split、Saturation、Density、Hue 分开组织 | 明度与色彩浓度分开思考，选择有色区域的密度变化 | 只研究公开设计意图，不访问其专有数学实现；拍摄光线和局部光比不能靠整体压暗模拟 |
| Juan Melara | [Print Film Emulation 文章](https://juanmelara.com.au/blog/print-film-emulation-luts-for-download)、[FilmUnlimited](https://juanmelara.com.au/blog/filmunlimited-flexible-film-emulation) | 先明确输入编码、曝光与转换阶段，再谈印片外观 | 早期文章已标注过时；电影 Log / Cineon 与本应用 SDR 输入不同；不下载 LUT 或商业工程 |
| Reggie Ballesteros | [本人 Reggie's Portra 文章](https://reggiebphotography.com/blog/the-most-versatile-fujifilm-x-trans-iv-film-simulation-recipe-reggies-portra) | 自动白平衡与逐张曝光是工作流的一部分，不把固定偏移当成通用还原 | 相机内处理和曝光决定成片；不复制文章配方数值 |
| Ritchie Roesch / Fuji X Weekly | [跨 Sony 使用配方的说明](https://fujixweekly.com/2023/06/26/using-a-fujifilm-x100v-film-simulation-recipe-on-a-sony-camera/)、[不存在万能配方](https://fujixweekly.com/2022/04/07/is-there-one-fujifilm-film-simulation-recipe-to-rule-them-all-3-reasons-why-rules-are-meant-to-be-broken/) | 配方要附带光线、对象、输入与失败条件 | 不同相机与白平衡偏移不保证可直接移植 |
| Alex Webb / Magnum | [Into the Light](https://www.magnumphotos.com/theory-and-practice/into-the-light-with-alex-webb/) | 浓彩街景保留阴影结构与彩色主体对比 | 构图、等待、光影分割不能由颜色滤镜生成 |
| Saul Leiter Foundation | [基金会资料](https://www.saulleiterfoundation.org/)、[White](https://www.saulleiterfoundation.org/white) | 赭色、暗部与有限色彩的组织关系，减少无方向的全画面饱和 | 反射、玻璃、遮挡和画面层次来自拍摄，不是预设 |
| Sean Tucker | [Balancing Colours](https://www.seantucker.photography/blog/2014/11/12/balancing-colours) | 主体与环境的色彩关系，精细且克制的颜色整理 | 背景局部修正需要蒙版，肤色近似颜色不能承担语义选择 |

## 4. 十个原创候选

中文与英文文件色表相同，显示名称不同；当前导入格式不会随应用语言动态翻译用户风格名称。任选一种语言导入，避免重复。

| 中文 / English | 设计 | 建议先评估的照片 | 注意 |
|---|---|---|---|
| 素净人像 / Clear Portrait | 温和层次、降低背景绿干扰、较强肤色颜色权重 | 日光人像、浅色织物 | 不去除混合光色偏，不美白皮肤 |
| 晴日留白 / Airy Daylight | 减反差与轻降彩度，蓝色稍清透 | 建筑、明亮日常 | 不恢复已经剪切的高光，不固定加曝光 |
| 岩青绿影 / Mineral Greens | 植物绿稍偏青、增加有色区域密度 | 园林、石材、山野 | 还缺足够植物实拍验证，优先补测 |
| 浓彩街光 / Dense Street | 增反差、增彩度和有色区域密度 | 阳光街景、彩色招牌 | 阴影易显得更深；不改变真实布光 |
| 琥珀青灰 / Amber Slate | 冷影暖亮的轻双色调 | 窗边、暖光室内 | 有意给中性色着色，检查肤色 |
| 粉蓝海岸 / Powder Coast | 蓝色降低彩度并适度提亮 | 海天、浅蓝织物 | 需要原片蓝色细节，不能凭空制造天空 |
| 赭石旧日 / Muted Ochre | 黄绿轻转色、低彩度与温和反差 | 秋色、砖墙、静物 | 没有添加物理颗粒、晕光或旧镜头效果 |
| 夜航钴蓝 / Night Cobalt | 深蓝密度、略偏钴蓝、微量暗部色调 | 蓝调时刻、混合灯光 | 没有降噪，极端 LED 需要额外实拍 |
| 银灰纪实 / Silver Document | 感知明度单色、较深层次 | 建筑、光影、人像 | 不是黑白胶片乳剂模型 |
| 墨暖双色 / Warm Ink | 去彩度后叠加微量暖亮冷影 | 雨天、静物、纪实 | 黑白锚点保持，但中间调有意染色 |

这些候选补的是一致的感知调色方法和可验证资源合同，不表示十种审美主题在原 87 款中完全不存在。暂不自动增加内置卡片，先用样张筛选，避免同类风格膨胀。

## 5. 色彩科学、试验与适用边界

- [Oklab 原始说明](https://bottosson.github.io/posts/oklab/)：使用发布的矩阵与有符号立方根，近似感知均匀，不等于标准 CIEDE2000。
- [Oklab Gamut Clipping](https://bottosson.github.io/posts/gamutclipping/)：研究固定明度 / 色相的色域边界思路。试验发现 ProPhoto 的该路径可能有不相连区间；首交点虽能控制单点越界，在跨色相时仍会跳变，65³ 插值的极端压力误差可达 61.55（100×Oklab 距离），因此没有采用该候选算法。最终改成凸 RGB 立方体内的中性轴压缩，用可量化的感知偏移换取连续性，不宣称采用作者完整算法。
- [ACES Reference Gamut Compression specification](https://docs.acescentral.com/rgc/specification)：借鉴保护正常范围、压缩边缘异常值的原则。本轮作用域为输出 SDR ProPhoto，既不是 AP1 场景参照，也不称 ACES RGC。
- 公开样张来自 [scikit-image v0.25.2 数据与许可说明](https://github.com/scikit-image/scikit-image/blob/v0.25.2/skimage/data/_fetchers.py)：NASA astronaut（公共领域）、Rachel Michetti coffee（CC0）、Stefan van der Walt chelsea（CC0）、SpaceX rocket（公共领域）。记录下载哈希；只按 sRGB 解释这些公开 JPEG/PNG，不证明相机 RAW 准确性。
- 附带合成灰阶、色相和彩度渐变。统计 Oklab 距离、越界比例、65³ 与 33³ 的插值误差、与硬裁剪的色相 / 明度偏移。视觉预览使用实际打包色表与独立四面体采样器，再转 sRGB 显示。
- 数值测试涵盖转移函数脚趾、宽色域有符号往返、公开 Oklab 参考值、周期色相、单调中性灰、黑白锚点、色度响应、柔和膝点、保护权重以及四面体坐标顺序。真实 Kotlin 导入测试读取中英文 20 包，验证范围、原生颜色空间、LUT 有效性和照片参数保留；C++ 使用生产插值器校验十份独立参考数据。

云端结果与交付清单在本节后附。当前公开样张没有覆盖多种肤色、绿色植被实景、霓虹、各相机标准 RAW 与设备屏幕；不得写成全场景验收通过。手机未连接，本轮没有新增真机测试。

## 6. 云端结果与交付

AOIDC：Python 3.10、NumPy 2.2.4、Pillow 12.3.0；IBX：Python 3.13、NumPy 2.2.4、Pillow 13.0.0。两机独立执行数值测试与完整生成，中英文 20 个 `.spklook` 包的 SHA-256 全部一致（包括表、元数据和缩略图）；总计 54,759,160 字节。最终受测代码哈希见 `validation/style-lab-2026-11-15/tested-source.json`，不包括后续仅文字更新。

- 数值回归：两机各 14 项全部通过，涵盖暗部原色保留、负 LMS 极值退让、色域包含性、RGB 比例、软膝点值与导数连续、单调灰阶、端点与独立参考值。
- 应用完整 JVM 回归：Debug 379 项、Release 231 项，均 0 失败、0 错误、0 跳过。新增测试真实读取 20 包，通过现有 `.spklook` / Cube 解析器及 `LutBake`，逐项验证仅滤镜范围、双语表字节一致、65³ Float32 表精确一致、物理及照片参数保留、0 强度中性。每个变体还对十个表的 163,840 个采样点与独立排序单纯形参考作比较。
- 生产 C++ `Lut3d`：十份独立参考各 16,384 点，共 163,840 点；启用 AddressSanitizer / UndefinedBehaviorSanitizer，全过，最大浮点差 `1.19209e-7`，16 位量化最大差 1。这里验证同一离散表的插值一致性，不能当成 LUT 相对连续算法的误差。
- 每风格比较 32,768 个均匀 ProPhoto 随机点、32,768 个 sRGB 随机点及 57,474 个公开照片抽样点，共 1,230,100 个离网格比较。随机种子固定 20261115；公开照片按顺序每 13 像素抽样，完整图片用于联系图。不是完整照片像素的穷举数值比较。

以下误差统一为 `100 × 欧氏 Oklab 距离`，不是 DeltaE2000、可见阈值或风格审美得分。比较的是实际六位小数 / Float32 包中 65³ LUT 与连续算法。

| 候选 | 公开照片 P99 | 公开照片最大 | 极端 ProPhoto 最大 | sRGB P99，65³ / 33³ |
|---|---:|---:|---:|---:|
| 素净人像 | 0.0912 | 0.2221 | 1.2486 | 0.0105 / 0.0456 |
| 晴日留白 | 0.0673 | 0.3495 | 1.1268 | 0.0262 / 0.1006 |
| 岩青绿影 | 0.1383 | 0.4021 | 2.2157 | 0.0231 / 0.0941 |
| 浓彩街光 | 0.0934 | 0.4287 | 3.9757 | 0.0553 / 0.1987 |
| 琥珀青灰 | 0.0874 | 0.3904 | 1.4135 | 0.0163 / 0.0667 |
| 粉蓝海岸 | 0.0874 | 0.3841 | 2.0005 | 0.0422 / 0.1524 |
| 赭石旧日 | 0.1199 | 0.4279 | 1.1600 | 0.0295 / 0.1027 |
| 夜航钴蓝 | 0.1420 | 0.4331 | 2.2420 | 0.0358 / 0.1484 |
| 银灰纪实 | 0.1249 | 0.2772 | 0.3681 | 0.0135 / 0.0569 |
| 墨暖双色 | 0.1261 | 0.2780 | 0.3291 | 0.0121 / 0.0509 |

65³ 的 sRGB 随机样本 P99 误差为 33³ 的约 1/3.48 至 1/4.33；采用 65³ 是资源与误差的实测选择。极端宽色域最大误差仍高于普通照片，尤其浓彩街光，不能称为任意 ProPhoto 输入的精确变换。生成门槛设为公开照片最大 <0.75、均匀 ProPhoto 最大 <5，代表本轮工程候选限值，不代表无可见误差。

720 个固定 L=0.65、C=0.45 的极端色相压力点中，相比逐 RGB 硬裁剪，中性轴软压缩的平均色相偏移从 16.315° 降至 9.324°，最大从 46.137° 降至 34.067°；平均明度偏移从 0.013490 降至 0.001343，最大从 0.036688 降至 0.004483。这只是该压力集合的结果，不能推广为每个颜色都优于硬裁剪，也不能宣称零色相偏移。

云环境问题也保留记录：首次默认包源速度慢且 Pillow 13 不支持 AOIDC 的 Python 3.10，改用其可安装的 12.3；公开图片下载超时后，经另一云机下载并核验公开文件后传入。首次完整 JVM 回归与第 5 个 native 链接因磁盘写满失败，未记作通过；清理本轮旧生成目录，测试临时文件改放内存盘后重新执行全部 JVM 与十表 native 测试，最终成功。未删除历史证据、私人文件或其他任务产物。

交付目录：本机 `D:/AGI/GPT/releases/Spektralab-X/next-style-candidates-2026-11-15/`，含中文 / 英文各十款的 ZIP、四张公开照片与测试图联系图、来源和包清单、使用说明、LICENSE / NOTICE。外层 ZIP 用于交付，解压后经现有设置入口导入单个 `.spklook`。没有新增 APK、版本号、内置卡片或主分支发布。

下一版优先考虑素净人像、晴日留白、粉蓝海岸和银灰纪实；其余作为审美候选继续筛选。岩青绿影须优先补植物实拍，夜航钴蓝补真实 LED / 霓虹，多肤色、混合光和不同相机 RAW 都是进入内置库前的后续验证项。这是基于本轮视觉审阅与数值证据的项目判断，不是厂商或创作者背书。
