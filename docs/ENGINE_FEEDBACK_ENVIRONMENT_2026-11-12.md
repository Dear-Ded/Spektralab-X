# Cloud environment simulation for Issue 17

2026-11-12. Co-created with GPT (OpenAI). This follows
`ENGINE_FEEDBACK_DISPATCH_2026-11-12.md`. All new executions in this suite are
on cloud computers. Private RAW files and their derived measurements were
not uploaded. Production algorithms, default parameters, app version and
release artifacts are unchanged.

Follow-up: `ENGINE_FEEDBACK_RECOVERY_2026-11-12.md` records the later Lavapipe
AVD continuation, successful small-image Android film exports, the 30 MP
storage-limit refusal, the 26-card shared-engine run, and production RGBA16
export checks added afterward. The results below describe this earlier suite.

## What is being approximated

The public report describes a 7028x4688 Sony input reduced to 6709x4475,
Ultramax 400 / Ultra Endura, balanced auto exposure at +0.5 EV, 36 mm,
spatial effects enabled and the Dekker split arithmetic path. Android is
reported as API 37 with a 512 MiB Java heap and about 5118 MiB available
system memory. Its GPU is Adreno 840 on a Xiaomi/HyperOS device.

These tests reproduce dimensions, normal session operations, selected math
and controlled memory/transfer conditions. They do not reproduce the Sony
compressed RAW bitstream, failing frame DSC02279, proprietary Adreno driver,
ARM64 CPU, HyperOS services, physical device or thermal behaviour. The cloud
fixtures are deliberately synthetic. A successful run cannot clear that
phone or establish that Issue 17 is fixed.

## Linux native conditions

IBX runs the existing engine through overlay 0053 on Mesa 25.0.7 / llvmpipe
LLVM 19.1.7, Vulkan 1.4.305. Its X dispatch limit is 65535, matching the
reported X workgroup limit. This is software Vulkan, not an Adreno device.
The ordinary CPU storage-range probe succeeds; its measured extension is
retained for the large-image positive cases. Strict advertised-capacity
testing is a separate negative control.

`run_feedback_environment.sh` compiles the session diagnostic and the actual
app area-resize implementation against the matching engine archives. It
generates linear ProPhoto float input at 7028x4688, resizes to the requested
working edge, and requests live / preview / full C ABI results. Forced split
math is used throughout. Experiment flags retain their production defaults.
The 6709x4475 case has 30,022,775 pixels. This exceeds the 2^24-pixel
dispatch boundary exercised only
with small forced chunks in the preceding diagnostic suite.

Memory controls use independent systemd scopes. Each log records actual
`memory.max` and `memory.swap.max=0` from inside its scope. This is a process
group limit, not a claim to reproduce Android LMKD, Java/native/GPU accounting
or the phone's complete memory pressure state.

| Case | Scope limit | Working image | Result |
| --- | --- | --- | --- |
| Available-memory approximation | 5 GiB | 6709x4475 | Three tiers passed; peak RSS 2,187,952 KiB |
| Tighter memory | 2 GiB | 6709x4475 | Live and preview complete; cgroup OOM kills full render |
| Transfer chunks | 5 GiB | 6709x4475 | Three tiers passed with 1 MiB staging and inline ring disabled; peak RSS 2,064,272 KiB |
| Smaller working image | 5 GiB | 3400x2267 | Three tiers passed; peak RSS 957,288 KiB |
| Strict advertised storage capacity | 5 GiB | 6709x4475 | Explicit status -5: `spk_sep_fir_acc: buffer exceeds maxStorageBufferRange` |

The OOM control returns scope status 143. Kernel records independently show
`CONSTRAINT_MEMCG` and the killed `session-probe` PID; it was not classified
as OOM from that status alone. The strict capacity control uses this CPU
driver's advertised 128 MiB limit. It is not the phone's reported 2 GiB
advertised range or its separate 672,000,000-byte storage test.

A preceding unrestricted cloud run also passed all seven session renders,
including exact full-frame cached repeat and parameter restoration, at
6709x4475. The retained run took 101.02 seconds and reached 2,427,572 KiB peak RSS. These
are cloud execution measurements, not expected phone performance.

Successful synthetic frames have no whole black/white rows or alpha errors.
This catches missing strips but is not a general image-quality oracle.
Neither low-memory termination nor capacity rejection produced a successful
damaged result resembling the reported black-strip / purple-block image.
These controls establish distinguishable failure modes; they do not exclude
memory involvement on Android or a driver-specific shader problem.

## Android diagnostic

An independent cloud host-driver check also passes on Google SwiftShader
Device (Subzero), Vulkan 1.3.0, with strict advertised limits and forced split.
It passes all 170 small-scene dispatch comparisons (9,596,670 float pairs)
and seven session/cache renders at 1537x989. This adds a driver implementation
independent of Mesa LLVM; it is neither Android execution nor a 30 MP test.

The feedback's exact source baseline is alpha-3 / versionCode 123,
`ed135c8c2bab72084ea529f66ec5e3539819a478`. These builds use the current
research tree with overlays through 0053, based on alpha-4. The relevant
Kotlin/JNI/resize/export files are identical between those tags, but native
engine overlays differ. These results do not replace an exact alpha-3 binary
comparison. The separate Chinese diagnostic report records that comparison's
scope and the log fields' actual meaning.

`build_feedback_android.sh` builds the actual app native CMake target,
including JNI, output transforms, Bitmap packing, LibRaw and libjpeg-turbo,
for the disposable emulator's x86_64 ABI. It also builds a small Java/dex
caller using the existing JNI method signatures. No APK is created or
released. The shipping app remains arm64-v8a.

`run_feedback_android.sh` selects a disposable cloud emulator explicitly and
normally requires completed boot. `ALLOW_BOOTING_AVD=1` explicitly permits
an unstable-runtime diagnostic and records boot readiness before and after
each case. Such a result is not stable app lifecycle validation.
The caller requests `app_process -Xmx512m`, then performs the normal
largeHeap growth-limit setup and records the actual runtime limit; Android
properties can override that requested command-line value. Its codec tests
a 6709x4475 synthetic Bitmap through Android Bitmap JPEG and the production
4:4:4 JPEG writer, then decodes and checks both saved files. Its pipeline
cases use real `openBitmap`, before/render/repack/output/export JNI methods,
Ultramax / Ultra Endura and the reported exposure settings. Each tier's
repacked Bitmap must match its existing render hash. The production ordinary
JPEG path is exercised as `exportBitmap -> Bitmap.compress(JPEG, 95)` in both
sRGB and Display P3, with a production native 4:4:4 sRGB control supplied the
actual resource ICC. JPEG file dimensions,
opaque alpha and whole-row coverage are checked after Android decode.

The small separate fixture library only fills synthetic Bitmap pixels and
reads full-frame row/hash statistics through AndroidBitmap locks. It does
not replace any production render, output transform, packing or encoder. This
avoids large interpreted Java loops under unaccelerated CPU emulation. It
reads the Bitmap's actual row stride and raw RGBA codes, including for P3;
those statistics do not claim a colour-managed comparison.

The cloud AVD uses Android 17 / API 37 revision 6, emulator 37.2.13,
x86_64 and Goldfish GFXStream over SwiftShader Vulkan 1.3.0. There is no
`/dev/kvm` or physical GPU available, so it runs CPU emulation. Even after
a smaller single-core display restart, system services repeatedly restart
and `sys.boot_completed` remains unset. Its actual expanded Java heap is
576 MiB (initial 192 MiB), rather than the phone's 512 MiB. A strict 512 MiB
preflight correctly rejects that mismatch. No protected system property or
security setting was changed to force it.

The final default-transfer Android run (`android-results-v6`) records:

| Case | Actual result |
| --- | --- |
| 6709x4475 codec | Pass; Android JPEG 5,706,151 bytes and native 4:4:4 9,474,238 bytes both decode at full size, with zero whole black/white rows or alpha errors |
| 1281x855 JNI film smoke | Exit 134 / SIGABRT in emulator Vulkan allocation, before image open |
| 6709x4475 JNI film export | Skipped because the smoke prerequisite failed |

The abort is independently identified by the guest log's
`MESA: FATAL: Unsupported virtual memory feature`. Symbolization against the
exact JNI binary places it at `VulkanGpu::create_inline_ring`, allocating a
64 KiB host-visible buffer while `spk_engine_create` initializes Vulkan.
The stack then enters GFXStream `ResourceTracker::createCoherentMemory` in
`vulkan.ranchu.so`. This is an emulator-path failure, not reproduction of
the reported successful-but-damaged phone export; its contribution from the
unaccelerated host is not independently proven. The completed codec results
do not establish that the unexecuted ordinary JPEG film export is correct.

A separate 1281x855 control disables the optional inline ring and uses 1 MiB
staging (`SPEKTRAFILM_VULKAN_INLINE_RING=0`,
`SPEKTRAFILM_VULKAN_STAGING_BYTES=1048576`). It also exits 134 with the same
emulator error, now at `create_buffer -> probe_math_mode -> spk_engine_create`.
Both aborts precede image open. Disabling the ring therefore does not make
this AVD suitable for film tests. No further large JNI run is counted.

An earlier Java-loop codec run (`v4`) also passes, but its orchestration ends
with a shell parse error after a runner update, so it is not counted as a
completed suite. All three full-frame CSV rows match the final native-fixture
codec statistics exactly. The `v5` helper-load failure was an absent C++
shared runtime, corrected by static C++ linkage; it was not classified as
OOM from exit 137. These failed preparations are retained as diagnostics,
not counted as engine test passes.

This invokes production JNI but not Kotlin EngineHost, Compose, MediaStore,
activity lifecycle or the APK's Android application memory policy. In
particular, `openBitmap` converts encoded synthetic sRGB into linear ProPhoto;
it does not claim to decode the failing Sony RAW. PNG samples and JPEG files
are structural diagnostics, not an independently colour-managed reference.

## Reproduction

```bash
UP=/path/to/upstream-through-0053 B=/path/to/matching-host-build \
BUILD_DIR=/outside/repo/new-native-results \
bash tools/native/run_feedback_environment.sh

UP=/path/to/upstream-through-0053 ANDROID_HOME=/opt/android \
BUILD_DIR=/outside/repo/android-build \
bash tools/native/build_feedback_android.sh

ANDROID_HOME=/opt/android BUILD_DIR=/outside/repo/android-build \
RESULTS=/outside/repo/new-android-results ADB_SERIAL=emulator-5560 \
bash tools/native/run_feedback_android.sh
```

Linux native testing requires working per-user systemd scopes (or root system
scopes), C++20, `/usr/bin/time`, and the already built engine. Android requires
NDK 27.2.12479018, CMake/Ninja, the existing pinned native dependencies,
Java 17+, SDK 36 platform/build tools for compilation, and a running test AVD.
Runtime SDK, heap, fingerprint and ABI are recorded separately from those
compile-time choices.

`ANDROID_CASES=codec` or `pipeline` restricts Android execution to that group;
the default runs both. A suite exit of zero only means orchestration completed:
check every row of `cases.csv` and its prerequisite skips to judge success.

The diagnostic now reads the C ABI error only after `spk_reprint` returns,
so rejected runs retain the actual error rather than an argument-evaluation
order-dependent stale string. It also records capabilities before rendering
and supports `--first-pass` to avoid unnecessary cached repetitions in the
environment matrix. These are diagnostic changes only.

Raw synthetic records are under
`tools/native/validation/feedback-environment-20261112/`.
