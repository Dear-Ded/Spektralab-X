# 一键风格库 / Style library

> 状态：调研完成（2026-11-03），`Styles.kt` 按本文实现，共 87 款；第 7 节是用户导入的规格（2026-11-04）。改风格先改这里。
> 负责：风格库线（`styles` 分支）。界面怎么展示风格归主会话，见第 6 节。

## 1. 原则

- **风格 = 已打包的胶片 + 相纸 + 冲印 / 效果参数 + 冲印后调整（Layer 2，`Look`）。** 色彩科学来自胶片和相纸，Look 只补冲印店或相机厂商在成片上做的那一步。不改引擎，不加胶片数据。
- **只模仿公开描述的观感，不复制任何产品。** 厂商没有公开参数，每款都是按官方文字描述和公开样片近似，局限写在映射表里。
- **命名与法律**（同 [UI.md](UI.md) 附录 B）：
  - 显示名一律用描述性中文（“静默纪实”），不用厂商商标或相机模式名（Classic Chrome、Velvia、Leica Classic 等都不作显示名）。
  - `Style.inspiredBy` 存“灵感来源”文字，界面可作副标题，写作“灵感来自 富士胶片模拟 Classic Chrome”。这是指称性使用，不暗示授权；“关于”页已有不隶属声明。
  - 胶卷只以纯文本型号出现（“Portra 400”），不画包装、不用品牌配色。CineStill 持有 “800T” 商标，来源文字里写“去掉防光晕层的电影卷”，不写该名。
  - 摄影师只作“灵感来自”，不用其肖像或作品。

## 2. 引擎能做什么

**可用胶片（`assets/engine/profiles`，只能用这些 id）**

| 类型 | stock id |
|---|---|
| 彩色负片 | `kodak_portra_160` `kodak_portra_400` `kodak_portra_800` `kodak_portra_800_push1` `kodak_portra_800_push2` `kodak_ektar_100` `kodak_gold_200` `kodak_ultramax_400` `fujifilm_c200` `fujifilm_pro_400h` `fujifilm_xtra_400` |
| 反转片（直接扫描，不经相纸） | `fujifilm_provia_100f` `fujifilm_velvia_100` `kodak_ektachrome_100` `kodak_kodachrome_64` |
| 电影负片 | `kodak_vision3_50d` `kodak_vision3_200t` `kodak_vision3_250d` `kodak_vision3_500t` `kodak_verita_200d` `cinestill_50d` `cinestill_800t` |
| 黑白 | `fujifilm_neopan_acros_100_ii` `ilford_hp5_plus_400` `kodak_tmax_100` `kodak_tri_x_400` |
| 彩色相纸 / 拷贝片 | `kodak_supra_endura` `kodak_portra_endura` `kodak_endura_premier` `kodak_ultra_endura` `kodak_ektacolor_edge` `fujifilm_crystal_archive_typeii` `kodak_2383` `kodak_2393` |
| 黑白相纸 | `ilford_multigrade_iv_rc` |

**风格能设的参数**：曝光补偿、冲印曝光、Y / M 滤镜（CC）、画幅（4–200 mm，超出范围引擎拒收整个参数包）、颗粒（0–2）、光晕（0–4）、眩光，以及 Look 的色温、色调、对比、亮度、饱和、自然饱和、高光、阴影、黑点、白点、褪色、暗角、四个色轮（整体 / 暗部 / 中间调 / 高光）和 8 段 HSL。

**做不到、只能近似的**

1. **没有曲线。** 厂商模式的核心常是一条专门的影调曲线（理光正片、尼康深色调单色），只能用对比、高光、阴影、黑点、褪色拼出来。ROADMAP 已把参数曲线列为最优先的新能力。
2. **黑白片上 HSL 和饱和不起作用。** 黑白输出是灰阶，Look 的 HSL 只对彩色像素生效，所以“红滤镜压暗天空”这类效果（尼康深色调单色、富士 Acros+R）做不了；引擎有 `camera_filter`（w25 等），但 `Style` / `Edit` 目前没有这个字段。色轮能给黑白片上色调（硒调、棕调）。
3. **没有交叉冲洗化学。** 反转片在引擎里只能直接扫描，不能按 C-41 冲成负片；交叉冲洗用反转片加色轮和对比模拟。
4. **没有片基灰雾和过期老化。** 过期胶卷用褪色、降对比、滤镜偏色和加颗粒模拟。
5. **推拉冲只有 Portra 800 的推一档、推两档有实测数据**；黑白推冲和彩负拉冲是曝光与 Look 近似。
6. **HSL 只有 8 段且有过渡**，“只改肤色”“只改天空”做不精确；没有肤色保护、没有清晰度 / 锐度、没有局部调整。
7. **钨丝灯片**（200T / 500T / 800T）：引擎按胶片的参考光源换算输入，日光照片不会自动偏蓝。想要“日光下用灯光片”的蓝调要靠 Look 色温，本库没有做这一款。

## 3. 调研：器材厂商的风格模式

只列与本库相关的模式；描述摘自官方说明，必要时加评测文章。

| 厂商 | 模式（官方名） | 官方描述摘要 | 来源 |
|---|---|---|---|
| 富士 | Provia / Velvia / Astia | 通用；浓郁高反差适合风光；更柔的色彩与反差 | [Fujifilm Film Simulation](https://www.fujifilm-x.com/en-gb/products/cameras/x-s10/feature-color/) |
| 富士 | Classic Chrome | 柔和色彩、加强阴影反差，沉静 | 同上 |
| 富士 | Classic Neg. | 色彩加强、硬调，增加纵深 | 同上 |
| 富士 | Nostalgic Neg. | 琥珀色高光、浓郁阴影，像冲印出来的照片 | 同上 |
| 富士 | Eterna / Eterna Bleach Bypass | 柔和色彩、浓郁阴影的电影感；低饱和高反差 | 同上 |
| 富士 | Pro Neg. Std / Hi、Reala Ace | 柔和层次与肤色；忠实色彩加硬调 | 同上 |
| 富士 | Acros / Sepia | 细节丰富的黑白；棕褐色调 | 同上 |
| 徕卡 | Leica Looks：Classic | 高反差、柔和饱和、温暖略褪色，像经典电影 | [Leica Looks（德）](https://leica-camera.com/de-DE/fotografie/leica-looks)、[Leica LUX](https://leica-camera.com/en-US/press/leica-lux) |
| 徕卡 | Contemporary | 明亮阴影、自然色彩、微微偏红 | [Map Camera：Contemporary](https://news.mapcamera.com/maptimes/%E3%80%90leica%E3%80%91what-is-leica-looks-q3-leica-contemporary%E7%B7%A8/) |
| 徕卡 | Teal / Brass / Chrome / Selenium | 冷调配暖高光；温暖；含蓄的纪实；硒调黑白 | [Robin Schimko 评测](https://www.robinschimko.com/blog/the-return-of-the-leica-looks)、[Map Camera：Selenium](https://news.mapcamera.com/maptimes/%E3%80%90leica%E3%80%91what-is-leica-looks-q3leica-selenium%E7%B7%A8) |
| 哈苏 | HNCS 自然色彩方案 | 不做风格化，追求真实、过渡平滑的色彩 | [Hasselblad HNCS](https://www.hasselblad.com/learn/hasselblad-natural-colour-solution) |
| 理光 | GR 图像控制：正片 / 负片 / 高反差黑白 / 漂白旁路 / 复古 | 正片浓郁偏暖、蓝偏青；负片低饱和高反差 | [GR III 表现力](https://www.ricoh-imaging.co.jp/english/products/gr-3/feature/03.html)、[GR Blog：负片](https://www.grblog.jp/en/article/7151/) |
| 理光 | GR IV：电影（黄）/ 电影（绿） | 黄：庄重；绿：清冷 | [GR IV 表现力](https://www.ricoh-imaging.co.jp/english/products/gr-4/feature/03.html) |
| 索尼 | 创意外观 FL / IN / SH / VV2 | FL：沉静色彩加锐利反差，突出天空与绿色；IN：压低反差和饱和的哑光；SH：明亮通透柔和 | [索尼帮助指南](https://helpguide.sony.net/ilc/2230/v1/en/contents/TP0002911200.html)、[Sony 支持文章](https://www.sony.com/electronics/support/articles/00297211) |
| 佳能 | 照片风格：标准 / 人像 / 风景 / 忠实 | 人像优化肤色；忠实按 5200 K 还原、反差低 | [Canon Picture Style](https://global.canon/en/imaging/picturestyle/editor/understanding.html)、[EOS 手册](https://cam.start.canon/en/C016/manual/html/UG-04_Shooting_0230.html) |
| 尼康 | 优化校准：鲜艳 / 风景 / 深色调单色 / 浓郁人像；创意优化校准（Denim 等 20 款） | 鲜艳是“照片冲印效果”；深色调单色压暗阴影和中间调、带强红滤镜 | [Z f 手册](https://onlinemanual.nikonimglib.com/zf/en/picture_controls_guid-a25e1e42-5373-bd1b-2b29-a1193384268a_30.html)、[Nikon USA](https://nikonusa.com/learn-and-explore/c/tips-and-techniques/picture-controls-step-by-step) |
| 松下 | 照片风格：L.ClassicNeo / L.Monochrome / L.Monochrome D | L.Monochrome D：强调高光与阴影的动感黑白；L.ClassicNeo 未取得官方原文，按评测描述为低饱和怀旧 | [LUMIX Photo Style](https://www.panasonic.com/global/consumer/lumix/lumix-color/photo-style-color.html)、[LUMIX 黑白](https://www.panasonic.com/global/consumer/lumix/lumix-color/photo-style-monochrome.html)、[GH7 手册](https://eww.pavc.panasonic.co.jp/dscoi/DC-GH7/html/DC-GH7_DVQP3109_eng/0072.html) |
| 奥林巴斯 / OM | 艺术滤镜：粗颗粒胶片 / 淡雅色彩 / 交叉冲洗 / 柔和棕褐 / 复古 | 粗颗粒：高反差带颗粒黑白；交叉冲洗：反差强、色偏明显 | [OM SYSTEM 介绍](https://note.jp.omsystem.com/n/n40c31dd870e2)、[ePhotozine](https://www.ephotozine.com/article/olympus-art-filters-24172) |
| 适马 | 色彩模式：Teal and Orange / Powder Blue / Warm Gold / Forest Green / Cinema / Sunset Red / FOV Classic Blue | 青橙对比；明亮通透的蓝；柔和暖色；反转片式绿色；整体偏黄、低饱和、阴影重 | [SIGMA Color Mode Library](https://www.sigma-global.com/en/shooting-with-sigma/sigma_color_mode_library/)、[Teal and Orange 开发说明](https://press.sigmaphoto.com/corporate/04/development-of-color-mode-teal-and-orange-for-sigma-sd-quattro-dp-quattro/) |
| 宾得 | 自定义图像：里美（SATOBI）/ 金色 / 淡雅 / 反转片 / 漂白旁路 / 交叉冲洗 | 里美：六七十年代彩照的青蓝、暗黄、褪红；金色：高光偏金、阴影偏蓝；淡雅：高调低反差低饱和 | [PENTAX Custom Image](https://www.ricoh-imaging.co.jp/english/products/custom_image/)、[参数说明](https://www.ricoh-imaging.co.jp/english/products/custom_image/parameter/) |

**胶卷与冲洗**

| 主题 | 结论 | 来源 |
|---|---|---|
| 推冲 | 颗粒变粗、反差升高、暗部细节变少；彩色片高光偏红黄、阴影偏青蓝 | [Push processing（维基）](https://en.wikipedia.org/wiki/Push_processing)、[holygrain](https://holygrain.com/blog/film-stock-push-processing-cinema/) |
| 拉冲 | 反差变软、高光保留更多 | [Neptune Photo](https://neptunephoto.com/blog/push-pull-processing) |
| 过期胶卷 | 灰雾升高、反差下降、阴影发闷、偏色、感光度下降 | [Indie Film Lab](https://www.indiefilmlab.com/blog/how-to-shoot-expired-film-and-actually-get-good-results)、[Photovision：Base Fog](https://help.photovisionprints.com/portal/en/kb/articles/base-fog) |
| 漂白旁路 | 留银：色彩压低、黑位厚重 | [Bleach bypass（维基）](https://en.wikipedia.org/wiki/Bleach_bypass) |
| Verita 200D | 饱和度高、肤色暖、黑位深，动态范围比 Vision3 短 | [Kodak 新闻稿](https://www.kodak.com/en/company/press-release/kodak-verita-200d-color-negative-film/) |

**手机厂商**：沿用 [ROADMAP.md](ROADMAP.md) M2“手机厂商风格与调色盘”和 [UI.md](UI.md) 2.8 的调研结论，不重做。补充两条：华为 XMAGE 三种风格的官方说明见 [Choose Your XMAGE Style](https://consumer.huawei.com/en/support/content/en-us15950824)；任务说明里的“徕卡 Vibrant / Authentic / Essential”实际是小米手机上的“徕卡鲜艳 / 徕卡原生”两种色彩风格，徕卡相机本身用的是上表的 Leica Looks，本库两者分别放在“手机”和“相机”分类。

## 4. 映射表

缩写：Look 列只写非零项；“暗部 / 中间 / 高光 / 整体 h·s”是色轮的色相（度）和强度；HSL 写“段：色相 / 饱和 / 明度”。色相约定：0 红、30 橙、45–60 金黄、120 绿、180 青、210–240 蓝、300 品红。

### 4.1 实验室（5，已有，未改）

| id | 名称 | 灵感来源 | 胶片 → 相纸 | 冲印 / 效果 | Look | 局限 |
|---|---|---|---|---|---|---|
| lab_gold | 暖金冲扫 | Frontier 扫描仪出片 | Portra 400 → Supra Endura | +1 EV，颗粒 0.8 | 对比 8、饱和 10、暗部 190·10、高光 45·8 | 扫描仪的锐化和自动校色不模拟 |
| lab_airy | 粉调冲扫 | Noritsu 扫描仪出片 | Portra 400 → Portra Endura | +1.3 EV，冲印 0.85，M −3 | 对比 −6、饱和 −8、褪色 22、高光 −15、中间 15·6 | 同上 |
| lab_wedding | 明亮婚礼 | 婚礼冲扫店的过曝出片 | Pro 400H → Crystal Archive | +2 EV，645 | Noritsu 基础，褪色 18 | 同上 |
| lab_vivid | 风光冲扫 | Frontier 扫描的风光片 | Ektar 100 → Ektacolor Edge | +0.3 EV | Frontier 基础，饱和 6 | 同上 |
| lab_daily | 日常彩负 | 街头冲扫店的日常彩负 | Ultramax 400 → Supra Endura | +0.7 EV，颗粒 1.1 | Frontier 基础，对比 4 | 同上 |

### 4.2 相机（25，新增）

| id | 名称 | 灵感来源 | 胶片 → 相纸 | 冲印 / 效果 | Look | 局限 |
|---|---|---|---|---|---|---|
| cam_chrome | 静默纪实 | 富士 Classic Chrome | Provia 100F（扫描） | −0.3 EV | 饱和 −28、对比 6、阴影 −14、高光 −8；红 0/−12/0，绿 −10/−20/0，蓝 −12/−15/0 | 原模式的阴影曲线更硬，只能用阴影滑杆近似 |
| cam_classic_neg | 硬调彩负 | 富士 Classic Neg. | X-Tra 400 → Crystal Archive | +0.3 EV，冲印 1.1 | 对比 12、饱和 −6、暗部 180·14、高光 20·8；绿 +15/−10/0，红 0/+8/0 | 原模式按 Superia 调校，本库用同家族 X-Tra 400，绿色偏青的程度靠 HSL 估计 |
| cam_nostalgic | 琥珀相册 | 富士 Nostalgic Neg. | Ultramax 400 → Ultra Endura | +0.5 EV | 对比 6、阴影 −10、黑点 2、高光 40·16；蓝 0/−15/0 | — |
| cam_eterna | 柔光电影 | 富士 Eterna | Vision3 50D → 2383 | +0.3 EV，眩光 0.5 | 饱和 −25、对比 −6、高光 −12、阴影 −6、中间 170·5 | Eterna 胶片本身不在库里，用 Vision3 50D 代替 |
| cam_bleach | 低饱高反 | 富士 Eterna Bleach Bypass | Vision3 250D → 2383 | 眩光 0.5 | 饱和 −50、对比 18、黑点 4、高光 −10 | 真正的留银会让高光也带银，Look 只能整体降饱和 |
| cam_astia | 柔和正片 | 富士 Astia | Provia 100F（扫描） | −0.3 EV | 对比 −10、饱和 −8、高光 −10；橙 0/0/+6，红 0/−6/0 | Astia 胶片不在库里 |
| cam_cine_warm | 暖调影院 | 徕卡 Looks · Classic | Vision3 250D → 2383 | +0.3 EV，颗粒 0.8 | 对比 14、饱和 −15、色温 8、褪色 12 | — |
| cam_bright_shadow | 明亮当代 | 徕卡 Looks · Contemporary | Portra 160 → Portra Endura | +0.5 EV | 阴影 18、对比 −4、整体 10·4 | “微微偏红”靠整体色轮，会连黑位一起轻微偏红 |
| cam_teal | 青调夜色 | 徕卡 Looks · Teal | Vision3 250D → 2383 | −0.3 EV，颗粒 1.0 | 色温 −6、对比 8、暗部 190·16、高光 35·10 | — |
| cam_posi | 街拍正片 | 理光 GR 正片 | Ektachrome 100（扫描） | −0.3 EV | 对比 8、饱和 12、色温 4；蓝 −15/+10/0，绿 −8/0/0 | GR 正片是专门的曲线加 12 区色彩校正，这里只有 8 段 HSL |
| cam_nega | 街拍负片 | 理光 GR 负片 | Ultramax 400 → Supra Endura | +0.3 EV，颗粒 1.2 | 饱和 −18、对比 10、暗部 185·12、高光 −6 | — |
| cam_cinema_y | 黄调电影 | 理光 GR IV 电影（黄）、适马 Cinema | Verita 200D → 2383 | 颗粒 0.7 | 饱和 −18、阴影 −10、整体 55·6、高光 50·6 | 两家只公开了一句描述，色偏强度是估计 |
| cam_fl | 冷峻胶片 | 索尼创意外观 FL | C200 → Crystal Archive | +0.3 EV | 饱和 −15、对比 12、暗部 200·6；蓝 0/+15/−10，青 0/+10/0，绿 0/+10/0 | 索尼的清晰度增强没有对应参数 |
| cam_instant | 即显哑光 | 索尼创意外观 IN | Gold 200 → Portra Endura | +0.5 EV | 对比 −18、饱和 −20、褪色 30、高光 −10 | — |
| cam_skin | 暖肤人像 | 佳能照片风格 · 人像 | Portra 400 → Portra Endura | +0.7 EV | 色温 3、自然饱和 8；红 0/+6/+4，橙 0/+6/+6 | 佳能的肤色是按色相精调的，8 段 HSL 会连带橙色物体 |
| cam_vivid_print | 鲜艳冲印 | 尼康优化校准 · 鲜艳 / 风景 | Ektar 100 → Ektacolor Edge | +0.3 EV，冲印 1.05 | 对比 8、饱和 15；蓝 0/+12/0，绿 0/+12/0 | — |
| cam_denim | 牛仔蓝调 | 尼康创意优化校准 · Denim | X-Tra 400 → Crystal Archive | +0.3 EV | 色温 −12、褪色 18、饱和 −20、暗部 220·12 | 尼康只公开了名字和样片 |
| cam_classicneo | 清淡复古 | 松下 L.ClassicNeo | C200 → Crystal Archive | +0.5 EV | 饱和 −22、褪色 15、对比 −4、高光 45·5；蓝 0/−20/0，绿 −8/−10/0 | 未取得官方原文，按评测描述 |
| cam_pale | 淡彩轻柔 | OM 淡雅色彩、宾得淡雅 | Pro 400H → Crystal Archive | +1.3 EV，冲印 0.9 | 饱和 −25、褪色 20、对比 −8、高光 200·5 | — |
| cam_cross | 交叉冲洗 | OM / 宾得交叉冲洗 | Ektachrome 100（扫描） | −0.3 EV | 对比 20、饱和 15、色调 −8、暗部 220·14、高光 70·16 | 引擎不能把反转片按 C-41 冲，见 2 节第 3 条 |
| cam_teal_orange | 青橙电影 | 适马 Teal and Orange | Vision3 250D → 2383 | +0.3 EV | 对比 8、暗部 185·20、高光 30·14；橙 0/+10/0，蓝 −20/0/0，绿 +30/−20/0 | — |
| cam_powder | 粉蓝清透 | 适马 Powder Blue | Provia 100F（扫描） | — | 亮度 10、饱和 −10、整体 210·4；蓝 0/+10/+10，青 0/0/+10 | — |
| cam_forest | 森林绿意 | 适马 Forest Green | Provia 100F（扫描） | −0.3 EV | 对比 6；绿 0/+25/−5，黄 +10/+10/0 | — |
| cam_satobi | 褪色昭和 | 宾得里美（SATOBI） | Ektachrome 100（扫描） | −0.3 EV | 褪色 12、对比 −4；蓝 −20/0/0，黄 0/−25/0，红 0/−20/+5 | — |
| cam_gold_blue | 金辉蓝影 | 宾得金色 | Portra 400 → Supra Endura | +0.7 EV | 高光 50·18、暗部 225·12 | — |

不单独做的：富士 Pro Neg. 用 `neg_portrait`，Provia / Velvia 用 `slide_positive` / `slide_velvia`，哈苏 HNCS 放在手机分类的 `ph_hncs`，宾得反转片用 `slide_velvia`，尼康风景并入 `cam_vivid_print`，索尼 SH 与 `cam_pale` / `lab_wedding` 重合。

### 4.3 负片（12：7 已有 + 5 新增）

| id | 名称 | 灵感来源 | 胶片 → 相纸 | 冲印 / 效果 | Look | 局限 |
|---|---|---|---|---|---|---|
| neg_amber | 琥珀记忆 | Gold 200 的暖调日常 | Gold 200 → Portra Endura | +0.7 EV，Y −4 | 高光 40·14、暗部 25·6 | — |
| neg_hard | 硬负片 | 硬调彩负 | X-Tra 400 → Crystal Archive | +0.7 EV，冲印 1.15，M +2 | 对比 10；红 +8/−12/0 | — |
| neg_cool | 冷负片 | 冷调彩负 | X-Tra 400 → Crystal Archive | +0.3 EV，Y +4，M +2 | 对比 6、饱和 −15、暗部 190·12 | — |
| neg_soft | 柔和写实 | C200 的柔和写实 | C200 → Crystal Archive | +0.7 EV | 自然饱和 −18、阴影 10 | — |
| neg_portrait | 柔肤人像 | Pro 400H 人像（同富士 Pro Neg. Std） | Pro 400H → Crystal Archive | +1 EV，645 | 对比 −10 | — |
| neg_natural | 自然 | Portra 160 中画幅 | Portra 160 → Endura Premier | +0.5 EV，6×7 | — | — |
| neg_classic | 深影经典 | 深影暗角的经典冲印 | Portra 400 → Ektacolor Edge | 冲印 1.2 | 暗角 −30 | — |
| neg_push1 | 增感一档 | Portra 800 推一档 | Portra 800 Push 1 → Portra Endura | 颗粒 1.3 | — | 引擎实测数据，无需近似 |
| neg_push2 | 增感两档 | Portra 800 推两档 | Portra 800 Push 2 → Portra Endura | 颗粒 1.6 | 暗部 190·6、高光 40·6 | 色偏按柯达对推冲的描述轻微加强 |
| neg_night | 夜色人像 | Portra 800 按标称感光度 | Portra 800 → Portra Endura | +0.7 EV，颗粒 1.1，光晕 1.2 | — | — |
| neg_pull | 减感柔调 | Portra 400 拉一档 | Portra 400 → Portra Endura | +1.3 EV，冲印 0.9 | 对比 −12、高光 −8 | 没有拉冲数据，用过曝加降反差近似 |
| neg_expired | 过期胶卷 | 过期多年的彩色负片 | Gold 200 → Portra Endura | −0.7 EV，M −5，颗粒 1.6 | 褪色 28、对比 −14、饱和 −15、暗部 300·8 | 没有灰雾模型；真实过期卷偏色因卷而异 |

### 4.4 反转（5：4 已有 + 1 新增）

| id | 名称 | 灵感来源 | 胶片 | 冲印 / 效果 | Look | 局限 |
|---|---|---|---|---|---|---|
| slide_positive | 正片 | Provia 正片 | Provia 100F | −0.3 EV | 色温 8、饱和 10 | — |
| slide_velvia | 浓郁风光 | Velvia 风光正片 | Velvia 100 | −0.3 EV | — | — |
| slide_documentary | 旧杂志 | 旧杂志纪实正片 | Ektachrome 100 | −0.3 EV | 饱和 −25、对比 8、阴影 −10；蓝 −20/−10/0 | — |
| slide_kodachrome | 浓色街头 | Kodachrome 街头 | Kodachrome 64 | −0.7 EV | — | — |
| slide_dark | 欠曝浓彩 | 风光摄影常用的反转片欠曝 | Velvia 100 | −1 EV | 对比 6 | — |

### 4.5 电影（7：4 已有 + 3 新增）

| id | 名称 | 灵感来源 | 胶片 → 拷贝片 | 冲印 / 效果 | Look | 局限 |
|---|---|---|---|---|---|---|
| cine_soft | 影院柔调 | Vision3 250D 印 2393 | Vision3 250D → 2393 | +0.5 EV，冲印 0.9 | 饱和 −20、阴影 15 | — |
| cine_night | 霓虹夜行 | 去掉防光晕层的电影卷夜景 | 800T（无防光晕层）→ 2383 | −0.5 EV，M +4，光晕 1.5 | 对比 8 | — |
| cine_bypass | 留银 | 漂白旁路冲洗 | Vision3 500T → 2383 | 颗粒 1.2 | 饱和 −45、对比 15 | 同 cam_bleach |
| cine_day | 日光电影 | 去掉防光晕层的日光电影卷 | 50D（无防光晕层）→ 2383 | +0.3 EV | — | — |
| cine_verita | 浓彩电影 | Verita 200D 印 2383 | Verita 200D → 2383 | +0.3 EV | — | 引擎实测数据 |
| cine_lamp | 灯光片柔调 | Vision3 200T 印 2383 | Vision3 200T → 2383 | — | 饱和 −8、阴影 6 | 日光照片不会偏蓝，见 2 节第 7 条 |
| cine_dark | 暗夜胶影 | Vision3 500T 印 2393 | Vision3 500T → 2393 | −0.3 EV，颗粒 1.2 | 对比 6、阴影 −8 | — |

### 4.6 黑白（11：4 已有 + 7 新增）

| id | 名称 | 灵感来源 | 胶片 → 相纸 | 冲印 / 效果 | Look | 局限 |
|---|---|---|---|---|---|---|
| bw_silver | 银盐黑白 | Acros II 银盐 | Acros II → Multigrade IV | — | — | — |
| bw_street | 街头黑白 | Tri-X 街头 | Tri-X → Multigrade IV | — | 对比 10 | — |
| bw_harbour | 港街黑白 | Tri-X 中画幅街拍 | Tri-X → Multigrade IV | 645 | 对比 15、暗角 −25 | — |
| bw_soft | 柔和黑白 | HP5 柔和印放 | HP5 → Multigrade IV | — | 对比 −8、褪色 15 | — |
| bw_selenium | 硒调黑白 | 徕卡 Looks · Selenium | T-Max 100 → Multigrade IV | 颗粒 0.6 | 对比 8、暗部 310·8 | 真硒调只染银密度高的暗部，色轮按亮度分区近似 |
| bw_sepia | 棕调黑白 | 富士 Sepia、索尼 SE、OM 柔和棕褐 | HP5 → Multigrade IV | — | 对比 −4、暗部 25·8、中间 35·14、高光 40·10 | — |
| bw_hicon | 高反差黑白 | 理光 GR 高反差黑白 | Tri-X → Multigrade IV | 颗粒 1.6 | 对比 35、黑点 6、暗角 −30 | 原模式带强红滤镜效果，做不了（2 节第 2 条） |
| bw_deep | 深调黑白 | 尼康深色调单色 | Acros II → Multigrade IV | — | 亮度 −10、对比 12、高光 12 | 同上，红滤镜做不了 |
| bw_dynamic | 动感黑白 | 松下 L.Monochrome D | HP5 → Multigrade IV | 颗粒 1.1 | 对比 22、高光 10、阴影 −8 | — |
| bw_grainy | 粗粒黑白 | OM 粗颗粒胶片 | Tri-X → Multigrade IV | 半格，颗粒 2.0 | 对比 25、暗角 −20 | 用半格画幅放大颗粒 |
| bw_push | 增感黑白 | HP5 推两档 | HP5 → Multigrade IV | −1 EV，颗粒 1.7 | 对比 18、阴影 −10 | 没有黑白推冲数据 |

### 4.7 手机（16，新增）

数字直出没有颗粒，手机风格颗粒都设 0.3–0.4；底子用最干净的 Portra 160 / Ektar 100。

| id | 名称 | 灵感来源 | 胶片 → 相纸 | 冲印 / 效果 | Look | 局限 |
|---|---|---|---|---|---|---|
| ph_authentic | 原生光影 | 小米 · 徕卡原生 | Portra 160 → Supra Endura | +0.3 EV | 对比 12、阴影 −12、暗角 −30 | — |
| ph_vibrant | 鲜艳明快 | 小米 · 徕卡鲜艳 | Ektar 100 → Endura Premier | +0.3 EV | 自然饱和 15、对比 4、阴影 6 | — |
| ph_ccd | 早期 CCD | 徕卡 M9 CCD（小米徕卡一瞬） | Provia 100F（扫描） | −0.3 EV | 对比 10、色温 3；红 0/+12/0，蓝 −10/0/0 | CCD 的高感噪点不模拟 |
| ph_mono50 | 低感黑白 | 徕卡 M3 + 50 度黑白卷（小米徕卡一瞬） | T-Max 100 → Multigrade IV | 645，颗粒 0.3 | 对比 6 | 用 T-Max 100 代替 ISO 50 胶片 |
| ph_hncs | 自然色彩 | 哈苏自然色彩方案（OPPO / 一加） | Portra 160 → Endura Premier | 645 | 自然饱和 −5、高光 −10 | — |
| ph_zeiss_natural | 自然写实 | vivo · 蔡司自然色彩 | Ektar 100 → Endura Premier | +0.3 EV | 饱和 −12、色温 −3、对比 4 | — |
| ph_zeiss_vivid | 鲜明锐利 | vivo · 蔡司鲜明色彩 | Ektar 100 → Ektacolor Edge | +0.3 EV | 对比 10、饱和 10、自然饱和 8、色温 −2 | 锐度没有对应参数 |
| ph_xmage | 原色真实 | 华为 XMAGE 原色 | Portra 160 → Ultra Endura | +0.3 EV | 对比 8、饱和 −4 | — |
| ph_xmage_bright | 明亮暖光 | 华为 XMAGE 明亮 | Portra 400 → Ultra Endura | +1 EV | 阴影 15、高光 −10、对比 −4、色温 3 | XMAGE 鲜艳与 `ph_vibrant` 重合，不单列 |
| ph_bright | 明亮通透 | 三星相机的明亮直出 | Portra 400 → Endura Premier | +0.7 EV | 阴影 20、自然饱和 15、对比 −2 | — |
| ph_cool | 冷峻高反 | Pixel 相机的冷调高反差 | Ektar 100 → Supra Endura | — | 色温 −8、对比 12、阴影 −10、饱和 −4 | — |
| ph_studio_bw | 影棚黑白 | 手机影棚人像黑白 | Acros II → Multigrade IV | 645 | 对比 14、高光 8、暗角 −40 | 布光效果做不了，只有暗角 |
| ph_amber | 琥珀暖调 | 苹果摄影风格 · 琥珀 | Portra 400 → Portra Endura | +0.5 EV | 色温 10；橙 0/+8/0 | 苹果的色偏集中在肤色上，这里是整体色温 |
| ph_rose | 玫瑰金调 | 苹果摄影风格 · 玫瑰金 | Portra 400 → Portra Endura | +0.5 EV | 色调 8、色温 4、高光 10·8 | 同上 |
| ph_quiet | 安静淡调 | 苹果摄影风格 · 安静 | Portra 160 → Portra Endura | +0.5 EV | 褪色 20、饱和 −20、对比 −8、色温 3 | — |
| ph_dramatic | 戏剧光影 | 苹果摄影风格 · 戏剧 | Portra 160 → Supra Endura | — | 对比 18、阴影 −15、饱和 −6 | — |

### 4.8 摄影师（6，已有；分类名由“大师”改为“摄影师”）

来源文字按原提交（997959e）的描述推断。

| id | 名称 | 灵感来源 | 胶片 → 相纸 | 冲印 / 效果 | Look | 局限 |
|---|---|---|---|---|---|---|
| m_snow | 雪中街头 | Saul Leiter 的雪中街头 | Kodachrome 64（扫描） | +0.3 EV，颗粒 1.2 | 褪色 25、饱和 −10、色调 6 | — |
| m_dye | 南方染印 | William Eggleston 的染印色彩 | Gold 200 → Ektacolor Edge | +0.3 EV，冲印 1.1，Y −3 | 饱和 15；红 0/+15/−10 | 染印法的色彩分离不模拟 |
| m_road | 公路大画幅 | Stephen Shore 的公路大画幅 | Portra 160 → Portra Endura | +0.5 EV，4×5 | — | 画幅原为 250 mm，见第 5 节 |
| m_cape | 海角光 | Joel Meyerowitz 的海角光线 | Portra 160 → Supra Endura | +1 EV，冲印 0.9，4×5 | 对比 −10 | 同上 |
| m_airy | 日光轻盈 | 精致婚礼摄影的明亮胶片 | Pro 400H → Crystal Archive | +2 EV，冲印 0.85，645 | Noritsu 基础 | — |
| m_flash | 闪光日记 | 直闪日记式彩色摄影 | Ektachrome 100（扫描） | −0.3 EV | 中间 30·10 | 直闪的硬光和红眼不模拟 |

## 5. 修正

- `m_road`、`m_cape` 的画幅原为 250 mm，超出引擎 `film_format_mm` 的 4–200 范围，`validate_delta` 会拒收整个参数包，这两款应该从未成功套用过。改为 127 mm（4×5，与胶片页画幅选项一致）。
- 分类“大师”改名“摄影师”。分类只用于界面分组，预设和导出 JSON 不存风格 id 或分类（`Preset` 存的是胶片、相纸和参数值），所以旧预设不受影响。

## 6. 交给主会话的界面事项

- `Style.inspiredBy` 已填好每款的灵感来源，风格卡片可加副标题“灵感来自 …”；现在的副标题是胶片全名（`Stock.label`，带品牌），按附录 B 可考虑改成“型号 · 工艺”。
- 分类从 6 个变成 8 个（实验室 / 相机 / 负片 / 反转 / 电影 / 黑白 / 手机 / 摄影师），“相机”有 25 款，三列网格（UI.md 2.3）会比横条好用。
- 本库需要的引擎 / 界面能力：参数曲线、`camera_filter` 进 `Edit` 和 `Style`（黑白红滤镜）、按色相的肤色保护。都在 ROADMAP M2 里。

## 7. 用户导入：格式、转换与安全（2026-11-04 调研）

ROADMAP M2 两条“用户自定义”的调研结论在这里落成规格。代码：`SpkLook.kt`（格式）、`CubeLut.kt`（LUT）、`PresetImport.kt`（转换器）、`FilmRecipe.kt`（胶片方案）、`StyleImport.kt` 与 `ImportSheet.kt`（安卓侧读取、存储、界面）。

### 7.1 `.spklook` 格式（version 1）

一个 zip，或单独一个 `look.json`。zip 里只认三个顶层条目，按名字整读进内存，**从不按条目名写文件**：

| 条目 | 必需 | 上限 |
|---|---|---|
| `look.json` | 是 | 256 KB |
| `lut.cube` | 否 | 12 MB（65³ 的文本约 7.4 MB） |
| `thumb.jpg` | 否 | 2 MB |

整个 zip ≤ 16 MB，最多 16 个条目，解压字节边读边计数（防 zip 炸弹）。其他条目、带 `..` 或绝对路径的条目不读，写进导入报告。

`look.json` 字段（全部按名字，缺的取默认，不认识的列进报告“已忽略”）：

```json
{
  "format": "spklook", "version": 1,
  "name": "我的风格", "author": "", "license": "CC-BY-4.0", "category": "导入",
  "engine": { "film": "kodak_portra_400", "paper": "kodak_portra_endura",
              "exposure_ev": 0.7, "print_exposure": 1.0, "y_cc": 0, "m_cc": 0,
              "format_mm": 36, "grain": 1.0, "halation": 1.0, "glare": 1.0 },
  "layer2": { "temperature": 0, "tint": 0, "exposure": 0, "contrast": 0, "brightness": 0,
              "saturation": 0, "vibrance": 0, "highlights": 0, "shadows": 0,
              "black_point": 0, "white_point": 0, "fade": 0, "vignette": 0, "vignette_mid": 50,
              "wheels": { "global": {"hue": 0, "sat": 0, "lum": 0}, "shadows": {}, "midtones": {}, "highlights": {} },
              "hsl": { "red": {"hue": 0, "sat": 0, "lum": 0}, "orange": {}, "yellow": {}, "green": {},
                       "aqua": {}, "blue": {}, "purple": {}, "magenta": {} } },
  "lut": { "file": "lut.cube", "strength": 1.0, "space": "srgb" },
  "source": { "kind": "cube", "file": "Teal.cube", "note": "" }
}
```

- `engine` 每个字段都可省：省掉的就**沿用当前照片的设置**，所以只有 Layer 2 的文件（XMP、.cube）套用时不换胶片。`film` / `paper` 不在当前版本的胶片目录里时，套用时跳过并提示。
- 数值全部按滑杆范围夹紧：Layer 2 同 `AdjustPanel.kt`（色温 / 色调 ±100、曝光 ±3、对比 / 亮度 ±50、饱和 / 自然饱和 / 高光 / 阴影 ±100、黑场 / 白场 0–50、褪色 0–100、暗角 ±100、暗角范围 0–100、色轮色相 0–360 取模、强度 0–100、明亮度 ±100、HSL ±100）；`engine` 同胶片页（曝光补偿 ±4、放大曝光 0.2–3、Y / M ±40 CC、画幅 4–200 mm、颗粒 0–2、光晕 0–4、眩光 0–5）。
- `license` 写 SPDX 标识；导入时不校验许可，只显示。
- `lut.space`：`srgb`（默认，市面上的 LUT 基本都按 sRGB / Rec.709 显示做）或 `prophoto`（按本应用冲印输出的编码做）。

### 7.2 LUT 放在哪、怎么算

- 位置：冲印之后、Layer 2 之前（ROADMAP 结论）。Layer 2 跑在引擎输出的 ProPhoto 编码值上（γ 1.8，中灰 0.3857，见 `layer2.hpp`），所以 sRGB LUT 先**烘焙**成 ProPhoto 域的表：每个格点 ProPhoto 编码 → 线性 → sRGB 线性（裁到 0–1）→ sRGB 编码 → 原 LUT（四面体插值）→ 反算回 ProPhoto 编码。矩阵是 ProPhoto（D50）与 sRGB（D65）之间的 Bradford 适配矩阵。
- 局限：超出 sRGB 的颜色进 LUT 前会被裁进 sRGB，强度 100% 时高饱和色会被压一点。强度在原生侧按 `mix(输入, 查表, 强度)` 做，拖强度只重新打包。
- 原生接口（由调整工具线在 Layer 2 入口实现）：一张边长 N ≤ 65 的表，float RGB，**R 变化最快**，值域是 ProPhoto 编码，加一个强度。风格库线负责解析、重采样、烘焙；原生侧只做四面体插值和混合。
- `.cube`：认 `TITLE`、`LUT_1D_SIZE`、`LUT_3D_SIZE`、`DOMAIN_MIN` / `DOMAIN_MAX`（Adobe 写法）和 `LUT_1D_INPUT_RANGE` / `LUT_3D_INPUT_RANGE`（Resolve 写法）、`#` 注释；1D 在前、3D 在后时 1D 当整形曲线先用。3D 边长 2–129（更大的拒收，内存原因），超过 65 时四面体重采样到 65；纯 1D 展开成 33³。文件 ≤ 64 MB。来源：[ResolveCafe：Cube LUT 说明](https://github.com/CommandPost/ResolveCafe/blob/main/docs/developers/luts.md)。
- HaldCLUT PNG：边长 L³（L 为级数，2–16），每通道 N = L² 个采样，像素序号 i = r + g·N + b·N²，行优先、R 最快。按 sRGB LUT 处理，N > 65 重采样。解码前先读尺寸，边长不是立方数或超过 4096 拒收。来源：[HaldCLUT 说明](http://www.quelsolaar.com/technology/clut.html)。

### 7.3 Lightroom XMP / DNG 预设

只取 `crs:` 字段；属性写法和元素写法都认。**解析前拒收任何含 `<!DOCTYPE` 或 `<!ENTITY` 的文件**，再用正则取值，不经过 XML 解析器，所以没有外部实体和实体膨胀的问题。DNG 预设在文件里找 `<x:xmpmeta` … `</x:xmpmeta>` 那一段，文件 ≤ 32 MB。`ProcessVersion` 低于 6.7 拒收（Process 2010 / 2003 的滑杆含义不同）。报告分“对应 / 近似 / 忽略”三类，做法参照 [redlamp 的 Lightroom 预设说明](https://github.com/pdcgomes/redlamp/blob/main/docs/recipes/lightroom-presets.md)。

| crs 字段 | 本应用 | 结果 |
|---|---|---|
| `Exposure2012` | 曝光（夹到 ±3） | 对应 |
| `Contrast2012` | 对比 × 0.5 | 近似（范围不同） |
| `Highlights2012`、`Shadows2012` | 高光、阴影 | 近似（曲线形状不同） |
| `Whites2012` | 正值 → 白场 × 0.25；负值 → 高光 × 0.3 | 近似 |
| `Blacks2012` | 负值 → 黑场 × 0.25；正值 → 褪色 × 0.3 | 近似 |
| `Saturation`、`Vibrance` | 饱和、自然饱和 | 对应 |
| `IncrementalTemperature`、`IncrementalTint` | 色温、色调 × 0.5 | 近似 |
| `Temperature`、`Tint`（开尔文） | — | 忽略：每张照片自己的白平衡 |
| `HueAdjustment*` / `SaturationAdjustment*` / `LuminanceAdjustment*`（Red Orange Yellow Green Aqua Blue Purple Magenta） | 8 段 HSL | 对应（段一一对应；色相幅度约 ±30°） |
| `ColorGrade{Shadow,Midtone,Highlight,Global}{Hue,Sat,Lum}` | 四个色轮 | 对应 |
| `SplitToning{Shadow,Highlight}{Hue,Saturation}` | 暗部 / 高光色轮 | 对应（旧版分离色调） |
| `ColorGradeBlending`、`SplitToningBalance` | — | 忽略：没有对应参数 |
| `PostCropVignetteAmount`、`PostCropVignetteMidpoint` | 暗角、暗角范围 | 近似 |
| `GrainAmount` | 引擎颗粒 = 值 / 50 | 近似：胶片颗粒按乳剂模拟，不是数字噪点 |
| `ConvertToGrayscale` | 饱和 −100 | 近似：彩色片上去色，不是黑白胶片 |
| `Clarity2012`、`Texture`、`Dehaze`、`Sharpness`、降噪、镜头校正、`ToneCurve*`、`CameraProfile`、`Look` | — | 忽略：没有对应参数（曲线等调整工具线加上后再接） |
| `Auto*` 为真 | — | 忽略：自动值逐张计算 |

值为 0 或 False 的字段不进报告。

### 7.4 spektrafilm 预设 JSON

spektrafilm 桌面 GUI 把整个界面状态存成 JSON（`gui_default_state.json`，`spektrafilm_gui/persistence.py`）。胶片和相纸的 id 与本应用的 stock id 相同（同一份数据），所以还原度最高。认两种写法：新版分组（`simulation.selection.film_stock`、`simulation.enlarger.print_exposure` / `y_filter_shift` / `m_filter_shift`、`camera.exposure_compensation_ev` / `film_format_mm`、`grain.active`、`halation.active`、`glare.active`、`simulation.io.scan_film`）和旧版平铺（`film_stock`、`print_paper`、`print_exposure`、`print_y_filter_shift`、`print_m_filter_shift`）。spektrafilm 的滤镜偏移单位是 CC（`EnlargerParams`，`filter_shift_scale` 为 1），直接进 `y_cc` / `m_cc`。颗粒粒径、光晕散射、DIR 耦合、相纸曲线形变、放大机预闪、扩散滤镜、扫描仪锐化这些本应用没开放的参数列进“忽略”。来源：[spektrafilm](https://github.com/andreavolpato/spektrafilm)（`src/spektrafilm_gui/state.py`、`params_mapper.py`、`runtime/params_schema.py`）。

### 7.5 富士配方文本

Fuji X Weekly 一类的配方是每行“键: 值”的纯文本（[示例](https://fujixweekly.com/)）。按“胶片模拟”选本库的底子，再叠加调整，结果一律标“灵感来自 富士配方（近似）”，显示名用文件名或用户自己起的名字，不用模式名：

| 配方项 | 做法 |
|---|---|
| Film Simulation | Classic Chrome → `cam_chrome`，Classic Negative → `cam_classic_neg`，Nostalgic Neg. → `cam_nostalgic`，Eterna → `cam_eterna`，Eterna Bleach Bypass → `cam_bleach`，Astia → `cam_astia`，Provia → `slide_positive`，Velvia → `slide_velvia`，Pro Neg. Std / Hi → `neg_portrait`（Hi 加对比 6），Reala Ace → `neg_natural`，Acros → `bw_silver`，Monochrome → `bw_street`，Sepia → `bw_sepia` |
| Highlight（−2 … +4） | 高光 × 10 |
| Shadow（−2 … +4） | 阴影 × −10（正值压暗） |
| Color（−4 … +4） | 饱和 × 6 |
| White Balance 偏移 “+4 Red & −5 Blue” | 色温 += (R − B) × 3，色调 += (R + B) × 1.5 |
| Dynamic Range DR200 / DR400 | 高光 −10 / −20 |
| Grain Effect Weak / Strong，Small / Large | 颗粒 1.0 / 1.5；Large 用半格画幅放大颗粒 |
| Color Chrome Effect / FX Blue、Clarity、Sharpness、Noise Reduction、ISO | 忽略 |
| Exposure Compensation、白平衡本身（Auto / Daylight 等） | 忽略：逐张设置 |

### 7.6 胶片方案

- **胶片方案**（默认）：一个基于已有胶片的别名 JSON，与仓库里的 `profiles-extra/cinestill_50d.json` 同一种结构：`derived_from` 加 `info`（名称、`target_print`、`antihalation`），引擎只读这几个键。另带一个 `spektralab_x.recipe` 对象，存推荐相纸、曝光补偿、颗粒等参数，套用时按 `.spklook` 的 `engine` 处理。没有光谱数据，没有许可负担。
- **完整胶片数据**（进阶）：校验后试开一次，失败即删除。校验项照引擎 `profile.cpp` 的要求：`info.type` 为 negative / positive，`info.stage` 为 filming / printing，`channel_model` 为 color / bw；`data.wavelengths` 81 点、380–780 nm、步长 5；`log_sensitivity`、`channel_density` 为 81×3，`base_density` 81 点（这三项允许 null，已打包的 Portra 400 就有 62 个 null）；`log_exposure` 至少 2 点、严格递增，其余数值有限；`density_curves` 为 n×3，负片逐通道不减、反转片逐通道不增，容差 0.005（已打包数据里负片最大逆行 0.0016（HP5），反转片最大回升 0.0010）；文件 ≤ 4 MB（Portra 400 是 207 KB）。没有 `metadata.license` 的在报告里提示。
- 存放：`filesDir/user/profiles/user_<名>.json`，名字只留 `[a-z0-9_-]`，总长 ≤ 96（引擎 `valid_stock_name`）。引擎资源每次重新安装都会整个删掉 `engine/`，所以安装后要再把这个目录复制进 `engine/profiles`（`UserProfiles.syncInto`），再读胶片目录。试开用 `NativeEngine.warmUp(engine, film, paper)`。

### 7.7 导出

当前编辑导出成 `.spklook`：胶片、相纸和冲印参数进 `engine`，Layer 2 进 `layer2`，有 LUT 就带上原始 LUT，附一张 512 px 缩略图，通过系统分享面板发出。只导出风格，不导出照片本身的白平衡、裁切和边框。

### 7.8 需要其他线配合的接入点

| 接入点 | 文件 / 线 | 内容 |
|---|---|---|
| LUT 阶段 | `layer2.*`、JNI，调整工具线 | 接收烘焙好的表和强度，见 7.2 |
| `Edit` 里的 LUT | `Model.kt` | `lutId`、`lutStrength` 两个字段，进撤销历史；不发给引擎 |
| 用户胶片同步 | `EngineHost.build` | `EngineAssets.install` 之后调一次 `UserProfiles.syncInto(dir)` |
| 设置入口 | `EditorScreen.kt` 设置面板，主会话 | 放 `ImportStylesButton` |
| 外部打开 | manifest、`MainActivity.handleIntent` | 本线单独提交 |
