# RAW lens correction cancellation evidence

Co-created with GPT (OpenAI), 2026-11-12. Source parent `e1fc2d1`;
fixed upstream `a46627d250545a394ca21522f8a28b0fb89e4c42` plus 50 patches.
Private photographs, derived photographs and private measurements are excluded.

AOIDC root: `/data/work/gpt-opcode-cancel-20261112`.
IBX root: `/data/home/gpt-opcode-cancel-20261112`.

Both hosts independently apply the 50 patches to the same pinned archive with
zero fuzz. The 97 affected-file hashes agree across hosts. AOIDC also compares
all 97 fresh-replay files to the source copy used by the adapter, JNI and NDK
compiles. Local patch/probe files match the collected cloud manifests.

Each host's `results.json`, `normal.log` and `final-helper.log` record:

- 80 frozen-decoder pixel/full-metadata comparisons, including the 0049
  ProfileDynamicRange value and presence/invalid flags.
- 852 helper/camera cancellation positions: 161 warp, 53 vignette, 212
  combined positions, each with one and four OpenMP workers. The final probe
  additionally throws from a callback partway through worker processing and
  verifies cancellation classification and retry.
- 8 complete production decoder cancellation/retry runs, totaling 1,674
  ordinal positions with one decoder worker. Previous output, metadata and
  caller OpenMP policy survive cancellation.
- An intentionally failing baseline requiring lens cancellation, retained in
  `baseline-gap.log`. Its normal observation mode records zero checks inside
  all three nonidentity lens lists.

`patches-sha256.txt`, `source-sha256.txt`, `probe-sha256.txt` and
`pixels-metadata-sha256.txt` are byte-identical across hosts. The last manifest
identifies all 80 successful pixel and metadata pairs. The binaries serialize
metadata explicitly, excluding nondeterministic timing and C++ padding.

AOIDC additionally records:

- `sanitized.log`: ASan/UBSan on the directly compiled production adapter and
  probe. `final-helper.log` comes from the final instrumented helper revision.
- `host-configure.log` / `host-build.log`: a real decoder-only CMake build of
  current image IO and LibRaw. JNI links those archives and reuses the existing
  unchanged engine/core/Volk archives and their generated shader resources.
- `jni-sanitized.log`: production JNI with a rotated, nonidentity Bayer fixture,
  old/cancellable ABI source parity, cancellation, prior session/cache and retry.
  `jni-initial-resources.log` retains the initial harness failure caused by
  selecting the source directory instead of generated shader resources.
- `dispatch.log`: the precision dispatcher regression with the expanded
  ProfileDynamicRange metadata comparison and unchanged independent oracle.
- `android-jni-command.txt` and `android-objects-sha256.txt`: actual JNI and
  RAW decoder object compilation with NDK 27.2.12479018, AArch64/API 29.

Sanitizer instrumentation does not include supplied LibRaw/engine/system
libraries; leak detection is disabled. Android objects are not a complete link
or device test. Cooperative checks do not provide a measured phone latency or
whole-pipeline memory bound. No APK, release or rendering-formula change.

Reproduce the numeric/cancellation tests with
`tools/native/run_opcode_list3_cancellation.sh`; see
[the report](../../../../docs/ENGINE_RAW_LENS_CANCELLATION_2026-11-12.md) for
required source/build/fixture variables and limits.
