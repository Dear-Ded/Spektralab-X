# No-Film Float Row Readback

Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.

This archive contains synthetic-only evidence. User RAW files, their output
images, paths, hashes and image measurements are kept in private research storage.

Both AOIDC and IBX use the same engine and adapter sources listed in
`source-sha256.txt`. `final-verify.log` covers 12 size/geometry cases, exact
float and graded RGBA8 equality, and 108 recovery cases. Each failure case
appends real RGBA8 data to an anonymous spool before returning false, throwing,
or cancelling at the first, middle or last strip. The test checks error
classification, descriptor cleanup, absence of named temporary files/JPEGs,
and byte-identical subsequent rendering. A graded request with a float-row
sink is explicitly rejected. Small cases repeat strip positions when fewer
than three strips exist; the 97x73 identity case has three distinct positions.
Each geometry also compares full-frame conversion, default strip conversion
and a reused `Layer2::Frame` with linear/radial/brush masks and procedural dust.
The reused placed geometry must gain exactly one shared reference during
strip construction, confirming the mask/dust grids are reused rather than
rebuilt for each strip. All three conversion routes produce identical bytes.

`frame-check.log` and `frame-check-asan.log` record the final full-frame/strip
sampling-grid checker, including invalid/incomplete strips, HDR, nonfinite
values and initially flat frames. Both normal and ASan/UBSan runs passed.

The memory fixture is a 4096x3072 signed/HDR synthetic RGB pattern. Full and
row JPEGs are byte-identical on both clouds, SHA-256
`036e12de7b302243dd01fd39ce22cacc9b75f32d1c7cbec68736682adff9fce0`.
The retained CPU float result changes from 150,994,944 bytes to at most
1,572,864 bytes (32 RGB float rows). This does not remove the resident GPU
source, rendered GPU plane, or disk spool.

| Measured KiB | AOIDC Full | AOIDC Rows | IBX Full | IBX Rows |
| --- | ---: | ---: | ---: | ---: |
| Sampled process peak RSS | 879760 | 879616 | 867564 | 867616 |
| Kernel VmHWM after engine creation | 879760 | 879616 | 867308 | 867328 |
| Full float result live | 663892 | n/a | 652572 | n/a |
| Spooling complete | 517216 | 454540 | 505624 | 443152 |

Total process peak did not decrease. Stage logs show the common high-water
mark already exists immediately after `spk_engine_create`, before the input
is generated or opened. This is a software Vulkan host measurement, not an
Android device memory measurement. At the equivalent spooling-complete stage,
RSS decreases by 62,676 KiB on AOIDC and 62,472 KiB on IBX. Comparing the full
path while its float result is live with the rows path after spooling gives
209,352/209,420 KiB lower residency, but these are different pipeline stages.

`measure_nofilm_rows.py` launches a fresh executable for each mode and samples
that process's `/proc` VmRSS/VmHWM every 5 ms after checking executable identity.
The probe also prints `/proc/self/status` before/after engine creation and at
decode/open/render/spool/encode stages. `getrusage` is supplementary only: its
initial inherited value is not treated as a new process peak. Memory logs were
collected before the final expansion of failure-injection coverage; that
expansion changes only `verify` mode, not the measured `memory` mode. The final
source hashes and final verification logs reflect the expanded coverage and
optional shared-frame constructor. The memory fixture has no Layer2 look, so
the optional shared-frame change does not change its conversion path. No new
memory measurements were required for this constructor change.

Reproduction uses `tools/native/run_nofilm_rows.sh` with existing patched
`UP`, `B`, `BUILD_DIR`, `JPEG_DIR` and `MATH_DIR`. IBX uses `RAW=0` to avoid a
new image-I/O build. The large memory probe uses
`SPEKTRAFILM_VULKAN_STRICT_LIMITS=0` because its existing full float buffer
exceeds the software device's 128 MiB strict storage-buffer range; the small
geometry/recovery tests keep strict limits enabled. The checker is separately
run through `tools/native/run_float_frame_check.sh`, then with `SANITIZE=1`.

Patch 0034 uses LF line endings and was replayed with GNU patch `--fuzz=0`
against the captured previous source; both resulting files compare byte for
byte with the sources built on the two clouds. No film/DI path, cache policy,
or pixel arithmetic is changed by this patch.

`android-final.log` records the final ARM64 NDK/JNI build with
`:app:testReleaseUnitTest :app:compileReleaseKotlin :app:externalNativeBuildRelease`.
The unchanged Kotlin/JVM tasks were up-to-date; native configuration/build ran
and succeeded. `android-source-sha256.txt` records the final adapter, JNI and
patched engine inputs. No application APK was assembled. These checks do not
execute JNI or render on an Android device.

The common startup high-water mark is independently isolated to the optional
CPU-device storage-range probe; see [the no-image controls](../engine-init-20261109/).
That diagnostic is not evidence of Android RAW decoder residency.
