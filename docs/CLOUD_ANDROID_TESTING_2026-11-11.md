# Android 云端测试可行性，2026-11-11

共创：GPT（OpenAI）。仅源码与云验证；未生成 APK、未安装应用、未改版本、未发布。未上传私有 PDF、照片或订阅资料。软件 Vulkan 不代表 Adreno。本次没有下载 emulator 或系统镜像，没有启动 Android VM。

## 结论和实测

两台云应先用于双编译器/软件 Vulkan 的宿主回归和 Android NDK 交叉构建。AOIDC 当前不能做有 KVM 加速的 Android VM；IBX 尚不能下结论，因平台冷却严格没有访问。x86_64 Android 软件设备可以补 Bionic/Android loader/文件系统测试，但无法直接运行当前只含 ARM64 的应用，也不能完成手机 GPU 验收。

AOIDC 经既有 wrapper 实测于 **2026-11-11 07:55:46Z 至 08:00:20Z（北京时间 15:55 至 16:00）**：

| 项目 | 观测 | 含义 |
|---|---|---|
| OS/CPU | Linux 5.15.0-30-generic，x86_64，4 vCPU，Xeon E5-2667 v2 | 适合现有 Linux 构建；ARM64 guest 不能获同架构 KVM 加速 |
| 外层虚拟化 | lscpu: Hypervisor vendor KVM，full virtualization | 仅说明本机是 VM，不能据此推断支持 nested virtualization |
| nested/KVM | `/dev/kvm` 不存在，CPU flags 的 vmx/svm 匹配数 0；两个 kvm nested 参数文件均无可读输出 | 当前 guest 没有可用 KVM 入口；root 权限不能弥补云平台没有暴露的 CPU 能力 |
| GPU | `/dev/dri` 不存在；既有验证使用 llvmpipe | 没有本次可见的硬件 GPU/render node |
| 内存 | 7.8 GiB RAM，7.2 GiB available；8 GiB swap，20 MiB used | 单小型软件设备已会与构建争资源；swap 不能替代可用 RAM |
| 磁盘 | 根盘 49 GiB，总已用约 32 GiB，空闲 17 GiB | 不适合同时叠加多 API 镜像和并行 native 大构建 |
| SDK | `/opt/android` 2.6 GiB；API36 rev2、build-tools35/36、CMake3.22.1、NDK27.2.12479018、platform-tools37.0.1 | 已能交叉构建；没有 emulator/system-images 目录或 SDK 包 |
| Java | 默认 PATH 为 OpenJDK21.0.13.0.3 | 不等于既有构建使用的 JDK；原 CI 明确选 JDK17，重现 CI 时仍显式选17 |
| SDK 工具 | sdkmanager/avdmanager/adb 存在 SDK 内，未在 PATH；没有 qemu-system-x86_64/qemu-system-aarch64/qemu-aarch64/qemu-aarch64-static | 无现成 Android guest 或 QEMU fallback |
| adb | `adb devices -l` 空列表 | 该查询启动了一次 ADB server，没有安装/推送应用；复用 preflight 脚本不会启动 ADB |
| 下载连通性 | 云端 GET `repository2-1.xml`: HTTP200，384394 bytes，0.326 s | 只证明这一次小型 Google SDK 元数据可达，不证明大包吞吐或持续可达 |
| SDK 告警 | sdkmanager 能列安装包，但报告只理解 XML v3、遇到 v4 | 以后装镜像先评估更新 cmdline-tools；本轮未修改 SDK |

IBX 的 **2026-11-11 09:14:12Z = 北京时间17:14:12** 冷却截止前不重试。本次工作结束时仍在截止前。已有仓库日志只证明 GCC14.2、JDK21、llvmpipe LLVM19.1.7 的历史宿主测试，**不证明 IBX 的 CPU 架构、KVM、RAM、磁盘、SDK 或当前网络**。这些字段全部待解除冷却后测量。依据：[既有 IBX 记录](../tools/native/validation/engine-android-20261111/README.md)。

## 已做的无 APK 小实验

新增 [轻探测脚本](../tools/android-cloud-preflight.sh)，`bash -n` 通过，上传 AOIDC `/tmp` 并运行成功，08:00:20Z 的输出与上述环境一致。脚本不访问网络、不启动 VM、不打印环境变量/凭据、不下载 SDK。若找到 emulator，仅执行 `-accel-check`；可读写 `/dev/kvm` 仍不自动等于 ioctl 可用。

新增 [Android 原生探针](../tools/android-cloud-native-probe.cpp)。用 AOIDC 已有 NDK27.2 实际分别编译 **ARM64/API29 与 x86_64/API29**，两者成功。ELF 均为 PIE，解释器 `/system/bin/linker64`，四个 LOAD 段均 `0x4000` 对齐；NEEDED 为 libandroid/libdl/libm/libc，无动态 libc++ 或 libvulkan 依赖。探针通过 dlopen 查 Vulkan loader，输出 ABI、真实 Android API/page size、设备名/类型/API/driver，以及 SSBO/workgroup/alignment 限制。

**运行结果尚无**：云端没有 Android linker/Bionic guest，也没有连接设备，不能把 ELF 编译通过写成 Android native 执行通过；没有为此直接尝试在 glibc Linux 上运行 Android ELF。此探针只检查 loader/枚举/公告 limits，不 dispatch shader，不验证 SPK 数值、JNI 或同步正确性。

可重现编译（在 Linux checkout，SDK 路径按 preflight 结果替换）：

```bash
ndk=/opt/android/ndk/27.2.12479018
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
for target in aarch64-linux-android29 x86_64-linux-android29; do
  "$toolchain/${target}-clang++" -std=c++17 -O2 -static-libstdc++ \
    -Wl,-z,max-page-size=16384 tools/android-cloud-native-probe.cpp \
    -landroid -ldl -o "/tmp/android-cloud-native-probe-${target}"
  "$toolchain/llvm-readelf" -lW -d "/tmp/android-cloud-native-probe-${target}"
done
```

未来已有设备时，可用 `adb -s SERIAL push /tmp/android-cloud-native-probe-ABI /data/local/tmp/spk-probe`，再 `adb -s SERIAL shell 'chmod 700 /data/local/tmp/spk-probe && /data/local/tmp/spk-probe'`。这里 ABI 是上面的完整 target 后缀。推送的是独立 native executable，不是 APK，也不创建应用包；测试后移除此明确路径。先用 `adb -s SERIAL shell getprop ro.product.cpu.abilist` 核对 ABI，不能依赖 ARM 翻译。

## 两云复查入口

只使用既有 wrapper，不读取 wrapper 源码、配置或凭据。将脚本复制到 `/tmp` 后运行；**IBX 仅在冷却截止后**：

```powershell
& 'D:/Tools/Git/bin/bash.exe' 'D:/AGI/Claude/other2/tools/aoidc.sh' put 'D:/AGI/GPT/.codex/worktrees/engine-android-research/wt-gpt-engine/tools/android-cloud-preflight.sh' '/tmp/android-cloud-preflight.sh'
& 'D:/Tools/Git/bin/bash.exe' 'D:/AGI/Claude/other2/tools/aoidc.sh' run 'bash /tmp/android-cloud-preflight.sh'
& 'D:/Tools/Git/bin/bash.exe' 'D:/AGI/Claude/other2/tools/ibx.sh' put 'D:/AGI/GPT/.codex/worktrees/engine-android-research/wt-gpt-engine/tools/android-cloud-preflight.sh' '/tmp/android-cloud-preflight.sh'
& 'D:/Tools/Git/bin/bash.exe' 'D:/AGI/Claude/other2/tools/ibx.sh' 'bash /tmp/android-cloud-preflight.sh'
```

IBX 的 put 调用尚未实测，不因列出命令而声称上传成功。若 wrapper 用法不同，以其正常 CLI usage 为准；不要展开凭据排查。随后单独查询 `sdkmanager --list_installed` 和 SDK metadata 连通性，避免把连通性检查混入默认只读 preflight。

## 官方资料核验和边界

`web.run` 对本次检索多次空返回，因此没有把空结果当证据。另用本机 `curl.exe -L --max-time 25` 抓取以下官方 HTML/XML，实际获得所述段落/包记录（2026-11-11），而非仅凭记忆给参数：

1. [Emulator acceleration](https://developer.android.com/studio/run/emulator-acceleration)：Linux VM 加速需要 KVM；CPU 与系统镜像架构须匹配。`swiftshader` 是 GLES/Vulkan 软件实现；`swiftshader_indirect` 自36.4.9弃用。CPU 虚拟化与 GPU 软件渲染是独立问题，换成 SwiftShader 不会生成 `/dev/kvm`。
2. [Emulator command line](https://developer.android.com/studio/run/emulator-commandline)：`-accel off` 被描述为不受支持且很慢，当前文档要求 **`ANDROID_I_WANT_MY_TCG=yes`** 才允许；不是 `ANDROID_EMULATOR_FORCE_TCG`。`-no-accel` 已列入弃用选项。页面本身也提示 emulator CLI 被新的 Android CLI 取代，因此下载后先核对实际二进制 `-help`/`-help-gpu`，不把网页参数当已执行结果。
3. [Gradle Managed Devices](https://developer.android.com/studio/test/gradle-managed-devices)：负责测试设备生命周期及 instrumentation；CI 软件图形示例仍用 `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`。它与新版 emulator 弃用表存在文档版本差异，必须记录实际 AGP/emulator 组合。GMD 不解决 KVM 缺失或 ARM64-only 应用的 x86 ABI 不匹配。
4. [Cuttlefish get started](https://source.android.com/docs/devices/cuttlefish/get-started)：明确先验证 KVM，云实例需要供应商层的虚拟化设置。它不是 AOIDC 无 KVM 的绕行方案，不在本轮自行重启主机或安装 host packages。
5. [Cuttlefish GPU](https://source.android.com/docs/devices/cuttlefish/gpu)：gfxstream 将 guest GLES/Vulkan 调用转发给 host；加速图形需要相应 host driver。SwiftShader 为软件实现。页面的 Virgl 路径明确不支持 Vulkan，不能用于这个 Vulkan 引擎验收。gfxstream 转发到软件 host 驱动仍不会变成手机 Adreno。
6. [SDK repository XML](https://dl.google.com/android/repository/repository2-1.xml) 和 [AOSP system image XML](https://dl.google.com/android/repository/sys-img/android/sys-img2-1.xml)：仅下载元数据，未下载 zip。stable Linux x64 emulator 37.2.13 的压缩包349658551 bytes；API35 default x86_64 rev2为782404023 bytes，ARM64为769099654 bytes；API36 default x86_64 rev2为844217077 bytes。大小是压缩下载量，不能当解压后空间。

## 最小搭建方案和预算

**首选条件路径：某云真正暴露可用 KVM 后**，只建一个 API35 AOSP x86_64 AVD；先跑独立 x86_64 native 探针，然后移植 synthetic core harness。ARM64生产 `.so` 继续独立交叉构建。不要为 x86 测试直接改 app 的 `abiFilters` 或 ARM64 OpenMP 逻辑。

以下是待执行方案，尚未下载、接受许可、创建 AVD 或启动 emulator；须先协调避开主 agent 构建，并按实际 emulator help 校验参数：

```bash
export ANDROID_HOME=/opt/android
sdk="$ANDROID_HOME"
# sdkmanager --licenses 是单独的许可步骤；确认现有许可覆盖后安装。
"$sdk/cmdline-tools/latest/bin/sdkmanager" \
  'emulator' 'system-images;android-35;default;x86_64'
echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd \
  -n spk_api35_x64 -k 'system-images;android-35;default;x86_64'
"$sdk/emulator/emulator" -version
"$sdk/emulator/emulator" -accel-check
"$sdk/emulator/emulator" -help-gpu
"$sdk/emulator/emulator" @spk_api35_x64 -accel on -gpu swiftshader \
  -no-window -no-audio -no-snapshot -cores 2 -memory 2048 -partition-size 2048
```

boot 使用独立命令轮询指定 `adb -s emulator-5554 shell getprop sys.boot_completed`，设置总截止（建议首次10分钟）、每次 adb 调用 timeout；不能无限 `adb wait-for-device`。记录 `getprop ro.build.fingerprint`、ABI、`getconf PAGESIZE`、探针输出和 emulator 完整版本。对失败保留启动日志，不自动换开关使 Vulkan 测试静默跳过。测试结束关闭此指定实例，不 kill 别人的设备。

资源预算是调度估算，不是实测性能：1个2-vCPU/2-GiB guest，预留 host emulator/SwiftShader 额外1-2 GiB；单编译 worker，Gradle最多2 GiB，勿同时启动大 Vulkan render。SDK下载约1.13 GB压缩量（API35 x86镜像+emulator），解压镜像/AVD/userdata/日志再预留6-10 GiB，另留构建和安全余量。建议扩到 **至少25 GiB可用空间** 后再搭建；当前17 GiB不是必然装不下，但不应占满主验证盘。首次只用64x64或256x256合成数据；禁用快照，避免镜像副本堆积。

**无 KVM 回退**：AOIDC 当前执行宿主 GCC/Clang+llvmpipe 回归、SPIR-V验证、NDK ARM64编译/ELF检查、JVM单元测试。若在协调后的独立资源预算内试验，x86 TCG 可按所装版本设置 `ANDROID_I_WANT_MY_TCG=yes` + `-accel off`，给10分钟启动/总15分钟硬截止；它是官方标记不支持的探索路径，不承诺能启动，不设成CI必过项。ARM64全系统软件模拟成本更高，同样不能预报可用。QEMU user-mode+自行拼Bionic sysroot不是完整 Android，缺少 framework/driver/sandbox，不作为主方案。

## 建议测试矩阵与限制

| 层 | 目标 | 当前可做 | 证明范围/后续 |
|---|---|---|---|
| 两云宿主 | AOIDC GCC11/LLVM15；IBX GCC14/LLVM19 | IBX冷却后复核；已有数学/边界回归继续利用 | 双编译器/软件driver oracle；不叫 Android 运行测试 |
| Android二进制 | ARM64/API29 NDK27.2，生产配置 | 已有原生完整构建；本轮双ABI独立probe编译 | JNI导出、NEEDED、16KiB ELF、SPIR-V；无ZIP/加载证明 |
| JVM/资源 | `:app:testReleaseUnitTest`、现有Debug Robolectric | 无需APK；先 `--dry-run` 核对任务图，再选择任务 | JSON/状态/URI/色彩路由，不能证明JNI/MediaCodec/SAF真设备行为 |
| AVD native | x86_64/API35 AOSP、SwiftShader | 等KVM/资源；先独立native probe，再core synthetic harness | Android libc/loader/files/temp storage/软件Vulkan；ARM CPU代码不在此覆盖 |
| ARM设备 native | ARM64 API29与现代4K/16K页设备 | 需可访问真机或同架构云设备；独立native executable仍不用APK | ARM64实际执行、限制查询、数值回归；probe本身不证明引擎渲染 |
| 应用集成 | instrumentation/GMD/真实应用 | **目前不执行** | 需解除打APK及安装限制；测试APK与应用APK通常都要构建/安装 |
| 真实Adreno | 多机型/driver/内存容量/温控 | 当前两云无法覆盖 | 生产ARM64 shader数值、设备丢失、峰值RSS/PSS、GPU allocation、热降频和交互验收 |

生产 app 当前仅 `arm64-v8a`，minSdk29/target36/compile36；没有 GMD/instrumentation runner 配置。CMake 明确 `BUILD_TESTING OFF`，故不能承诺 `ctest` 已可执行 Android 引擎测试。现有 CI `.github/workflows/android.yml` 的 Build 直接 `assembleRelease`，后续还存在发布条件，**不在本轮触发 workflow_dispatch/push main 来“验证一下”**。若将来落地测试，另建不含打包/发布动作的 source-only workflow；本轮仅交付方案，未修改CI。

真正的 Android native 引擎 harness 还需单独连接生产 core，复用合成数据、数值容差、256线程/每stage至少8 SSBO的拒绝合同，以及print-tail取消/临时盘失败/输出原子发布测试；必须区分驱动公告内存、实际allocation ledger和进程RSS/PSS。小探针未执行这些测试，不将既有宿主2796检查移名为 Android通过。完整RAW/最终图、CPU缓存、driver私有内存仍不在bounded-tail预算内，见 [集成边界](ENGINE_PRINT_TAIL_INTEGRATION_2026-11-11.md) 和 [GPU/ABI核查](GPU_BINARY_AND_ANDROID_ABI_2026-11-11.md)。
