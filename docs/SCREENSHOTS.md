# 界面截图（Screenshots）

编辑器和观片台各页面在 JVM 上渲染成 PNG，不用真机、不用模拟器、不加载引擎。
用来在改界面时看到它真实的样子：字体、间距、层次、文字是否截断。

- 测试：[`app/src/testDebug/java/com/dearded/spektralab/Screenshots.kt`](../app/src/testDebug/java/com/dearded/spektralab/Screenshots.kt)
- 工具：Robolectric 4.17（native graphics）+ Roborazzi 1.77.0
- 尺寸：1080 × 2400，440 dpi（393 × 873 dp，小米常见），深色，竖屏
- CI：[`.github/workflows/screenshots.yml`](../.github/workflows/screenshots.yml)，独立 workflow，不碰 `apk` / `release` job

## 拿截图

CI 每次构建后上传两个 artifact：

- `screenshots-<run_number>`：全部 PNG（zip）
- `contact-sheet.png`：所有页面拼在一张图上，未压缩，在 run 页面点开就能在浏览器里看

job summary 里有联系表的链接和下载命令。命令行：

```bash
gh run list -R Dear-Ded/Spektralab-X -w screenshots -b <分支> -L 1
```

```bash
gh run download <run-id> -R Dear-Ded/Spektralab-X -n screenshots-<run_number> -D tools/work/shots
```

或者在 aoidc 上本地出图（约 1–2 分钟，第一次要下载 Robolectric 的 android-all，约 8 分钟）：

```bash
tools/aoidc.sh run "spk-build /data/work/<会话名>/repo :app:testDebugUnitTest --tests com.dearded.spektralab.Screenshots --rerun"
```

```bash
tools/aoidc.sh get /data/work/<会话名>/repo/app/build/outputs/roborazzi tools/work/shots
```

输出在 `app/build/outputs/roborazzi/<名字>.png`。

## 覆盖的页面

| 文件 | 内容 |
|---|---|
| `00_launch_intro` | 开屏动画（停在终态的一帧） |
| `01_editor_empty` | 空白页（没有照片） |
| `10_style` | 风格（面板半屏，默认） |
| `11_panel_folded` | 面板收起到工具栏 |
| `12_panel_tall` | 面板展开（拖动把手） |
| `13_style_none` | 风格，胶片「无」 |
| `20`–`23_develop_*` | 冲洗四步：胶卷、相纸、曝光与放大、胶片效果 |
| `24_develop_none` | 冲洗，胶片「无」 |
| `30`–`35_adjust_*` | 调整六段：光线、色彩、分色、曲线、调色盘、质感 |
| `36_adjust_masks` | 局部蒙版（一个线性渐变，展开） |
| `14_style_strength` | 风格已套用，强度面板打开（轻/中/重 + 滑杆，70% = 中） |
| `15_zoomed` | 照片放大后，1:1 旁出现「适应」 |
| `25_develop_3_dark` / `26_develop_3_bright` | 冲洗③，暗 / 亮两张假照片，曝光补偿下一行显示「偏暗」/「偏亮，高光有溢出」 |
| `27_develop_4_grain_off` | 冲洗④，颗粒关闭，强度灰掉并写明原因 |
| `37_adjust_light_moved` | 调整·光线，曝光 +0.6、对比度 +15，滑杆旁出现 ↺ |
| `41_border_date_rebate` | 边框，135 片边 + 日期印在帧间，横纵向边距灰掉并写明原因 |
| `40_border` | 边框（画廊白边） |
| `50_crop` | 构图 |
| `60_settings` | 设置页 |
| `61_changes` | 修改记录 |
| `62_paste` | 粘贴调整 |
| `63_queue` | 正在处理（进行中、排队、完成、失败各一） |
| `64_import` | 导入风格（一个 .cube） |
| `65_update` | 更新提示（有新版本） |
| `66_detail_1to1` | 1:1 查看 |
| `70_table_grid` | 观片台网格（14 张，有选中、淘汰、星级、已编辑标记） |
| `71_table_loupe` | 单张查看（沉浸） |
| `72_table_loupe_controls` | 单张查看，点一下出现上下控件 |
| `73_table_rolls` | 观片台按卷 |
| `74_table_roll_strip` | 一卷的片条 |

加一页：在 `Screenshots.kt` 里写一个 `@Test`，用 `editor(ui)` 或 `content { … }` 摆好界面，`tap("文字")` 点到要的状态，`shot("名字")` 截图；底部面板、对话框这类自成窗口的用 `screenShot("名字")`（整屏）。

## 怎么绕开引擎

`NativeEngine` 是 JNI，Robolectric 里加载不了。测试用的是**真的** `EditorViewModel` 和
`LightTableViewModel`：

- 编辑器：ViewModel 启动引擎时 `System.loadLibrary` 失败，和没有 GPU 的手机一样落到 `error`；
  测试等它落定，再用反射把 `_ui` 换成假的 `UiState`（`Fake.ui()`）。之后的点击都走真的 composable，
  只是不会触发渲染。
- 观片台：用反射把 14 张假照片放进 `FrameStore` 的 `_frames`（并标记已加载，不读索引文件），
  再把它们各个尺寸的缩略图预先塞进 `Thumbnails` 的缓存，网格和单张查看不用去解码。
- 照片、胶片卡片：`docs/images/samples/` 里对比图的左右两半和 `films_dunes.jpg` 的六格。
  胶片目录（`Fake.catalog`）照抄内置胶片和相纸的 id 和名字。

`app/src/main` 没有为截图改任何代码，所有包装都在测试文件里。

## 选型

比较了三个主流方案（2026-11 调研）：

| | Paparazzi（cashapp） | Roborazzi（Robolectric） | Compose Preview Screenshot Testing（Google） |
|---|---|---|---|
| 渲染器 | layoutlib（Android Studio 预览同款） | Robolectric native graphics（真实 Android 框架 + Skia） | layoutlib |
| 与 AGP 8.13 / Kotlin 2.0.21 | 稳定版 1.3.5 停在 2024，按 AGP 8.4 / compileSdk 33 构建；2.0 仍是 alpha，alpha04 起要 JDK 21，构建于 AGP 9 | 1.77.0（2026-10）可用；要加一个编译参数（见下） | 独立插件 `com.android.compose.screenshot` 官方已停止维护，改为 AGP 9.5+ 的 test suites，本项目用不上 |
| 不走 ViewModel、点进子页 | 只能渲染一个 composable 的静态状态，不能点击 | 能用 Compose 测试 API 点击、拖动，再截图 | 只渲染 `@Preview`，不能交互 |
| 中文 | layoutlib 自带字体 | native runtime 自带 Noto Sans CJK；本项目又自带 Noto Sans SC，实测无方块 | 同 Paparazzi |
| CI 耗时 | 快 | aoidc 首次约 8 分钟（下载 android-all），之后约 1 分 15 秒跑完 20 张 | 快 |

选 **Roborazzi**，理由：

1. 唯一一个在现在的 AGP 8.13 + Kotlin 2.0.21 上有维护中稳定版的方案。Paparazzi 的稳定版太旧，
   alpha 要求 JDK 21 和 AGP 9；Google 的独立插件已经停维护。
2. 要截的大多是"点进去之后"的状态（冲洗第三步、调整第五段、面板展开），这些状态在
   `Controls` 里是 `remember` 的局部变量，没有参数可传。只有能点击的方案能截到它们而不改
   `EditorScreen.kt`（ui-visual 在大改它）。
3. 用的是真实的 Android 框架和字体渲染，`Typeface.CustomFallbackBuilder` 拼出来的 Inter + Noto Sans SC
   和手机上一致。

## 注意事项

- **Kotlin 元数据**：Roborazzi 1.77 用 Kotlin 2.3 编译，2.0.21 读不了它的元数据。`app/build.gradle.kts`
  只给单元测试编译加了 `-Xskip-metadata-version-check`，应用本身的编译不受影响。升级 Kotlin 到 2.2 以上后可以删掉。
- **SDK 34**：Robolectric 跑 SDK 35/36 要 JDK 21，CI 和 aoidc 是 JDK 17，所以测试按 SDK 34 渲染。界面差别不大。
- **只在 debug 单元测试里**：依赖都是 `testDebugImplementation`，`release` job 跑的
  `testReleaseUnitTest` 看不到这个测试；`ui-test-manifest` 只进 debug APK。
- **动画**：主时钟手动推进（`autoAdvance = false`），截图前推进 1.5 秒让弹簧停稳；
  光晕这类无限动画不会卡住等待。截出来的是动画终态，看不到过渡本身。
- **截不到的**：GPU 渲染出的真实冲印效果（照片是现成的样图）、系统栏。
- **不要用 `performScrollTo`**：它是动画滚动，时钟暂停时永远等不完，测试会卡死；用 `swipe` 代替（见 `editorMasks`）。
