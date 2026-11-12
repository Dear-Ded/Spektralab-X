# Native adaptation regressions

The 2026-11-11 source review, TIFF/ICC/JNI corrections, benchmark corrections,
bounded-prototype contracts and upstream HDR research are recorded in
[the engine reassessment](../../docs/ENGINE_ANDROID_REASSESSMENT_2026-11-11.md).
See [the synthetic two-host evidence](validation/engine-android-20261111/).

The [default-pair 33-cube gate](../../docs/LUT_INTERACTION_GATE_2026-11-11.md)
compares the current film/paper default through the production display boundary
on both Linux hosts. Common synthetic SDR groups pass; signed stress tails do
not qualify a general RAW preview. This is an offline experiment, not an enabled
LUT interaction or a replacement for full-resolution strip rendering.

The RAW tone and desktop file-intent corrections are documented in
[RAW color fixes](../../docs/RAW_COLOR_FIXES_2026-11-10.md). `run.sh` includes
the independent exposure/highlight domain probes. They can also run without
an engine build using `bash tools/native/run_layer2_domains.sh`; set
`SANITIZE=1` for ASan/UBSan. The no-film GPU runner uses an independent double
Mac export oracle after the real ProPhoto grade boundary. See
[the current evidence](validation/raw-tone-export-20261110/).

Co-created with GPT (OpenAI, AI assistant), 2026-11-08.

Run on Linux with a C++20 compiler. No Vulkan device is needed for the `run.sh`
unit suite below; the separately documented GPU runners require one. `UP` must name the pinned upstream tree with this repository's
patches applied, as produced by `scripts/fetch-upstream.sh`.

```bash
UP="$PWD/upstream" bash tools/native/run.sh --large
UP="$PWD/upstream" STRICT=0 bash tools/native/run.sh --large
UP="$PWD/upstream" SANITIZE=1 bash tools/native/run.sh
```

The strict/default runs test both disabled and compiler-default floating-point
contraction. They do not enable fast math. `BUILD_DIR` selects a persistent output
directory; otherwise a fresh temporary directory is used and printed. Omitting
`UP` runs the app-side numerical tests only.

- `resize_reference.cpp` preserves the former two-pass arithmetic from eada743,
  run serially. The candidate is the production `resize.cpp`, with up to eight
  workers. Compare bytes for 350 shape/pattern combinations, or 355 with
  `--large` (4097 x 3073 to 2049 x 1537). Include negative/HDR values, signed
  zero, subnormals, thin images, identity resizing, aliasing and non-integral
  area boundaries. This reference is an oracle, not another production path.
- `engine_adaptation.cpp` checks the Android gainmap equation against a double
  natural-log oracle, including gamma 0.5/1/2/3 and interpolation before gamma.
  It also injects allocation failures into the actual `LookState` implementation
  and checks that failed updates retain the previous LUT/look.
- `brightness_range.cpp` checks 64 legal-control and table-boundary cases
  against an independent power-function reference, including values above two
  that later desaturation brings below white. It also emits 458,759 original
  range float32 samples for same-toolchain byte comparison with an old build:
  `brightness_range --range-only --snapshot range.f32`. The existing in-range
  interpolation, including its separate near-black approximation, is retained
  for the existing RGBA16 route. The new unquantised no-film float route uses
  the original shader power equations for brightness and exposure instead:
  the coarse near-black table and algebraically collapsed exposure can amplify
  differences at the later output transform. That route is covered separately
  by `run_nofilm_float.sh`, not by a claim of old clipped-path byte equality.
- `setup_cache_lifetime.cpp` links the actual patched `setup_cache.cpp` and
  `blob.cpp`, with small deterministic stand-ins for expensive LUT builders.
  Check LRU rotation, eviction, cache destruction, 4000 concurrent reads and
  distinct gamut knees whose old 64-byte keys collided. This checks ownership
  and keying, not the spectral integrator.
- `curve_regression.cpp` freezes the cdc7274 per-sample slope preparation and
  compares 1,148,285 double/float samples with the prepared production curve.
- `curve_wire.cpp` checks all 32 combinations of ordinary and pre-sampled
  rows, 40,960 table floats byte-for-byte, 32 actual mask offsets/pixels and
  35 malformed rows. It reproduces the former Double-to-Float x collision.
  `run.sh` includes it; `run_curve_wire.sh` also runs it alone with optional
  `STRICT=0` or `SANITIZE=1`. Ordinary row bytes and editable Double JSON stay
  unchanged; only colliding rows use the internal `-256` plus 256-sample form.
- `local_tone.cpp` reaches the production Layer2/local parser and a full-cover
  mask. Six HDR/signed cases check the independent power reference, and 36,873
  ordinary RGB triplets retain the former float arithmetic byte-for-byte.
  Zero exposure must not clip negative channels before desaturation; brightness
  must not clip recoverable values at two. The no-film float mask path uses
  the original nested exposure power formula.
- `filter_radius.cpp` links the actual `Blur::fir_radius` and numerical kernel,
  checks 3,600 rounding-boundary cases and refuses allocations during demand
  queries. It also checks the nonpositive-sigma identity path.
- `dng_forward_matrix.cpp` checks DNG ForwardMatrix conversion, single/dual
  illuminants, white balance, analog balance/camera calibration, neutral/HDR
  values and invalid-profile fallback. It runs in the `UP` suite above.

The metadata integration check additionally links the same patched LibRaw and
host white-balance code used by JNI. Supply actual RAW paths; sample photographs
are not included in this repository:

```bash
UP=/path/to/upstream B=/path/to/build \
  bash tools/native/run_raw_camera.sh /path/to/sample.dng
```

To also unpack pixels and use the production compatible decoder at four key
control settings (2850/0, 4000/-150, 6500/-150 and 12000/0), enable the optional
mode against a freshly rebuilt `spk_image_io` library:

```bash
UP=/path/to/upstream B=/path/to/build RAW_DECODE=1 \
  bash tools/native/run_raw_camera.sh /path/to/sample.dng
```

This uses the product's Bayer half-size decode budget. It checks finite output
pixels, the decoder-reported calibration and its actual output matrix when a
ForwardMatrix is applicable. Legacy ASN files without a usable FM keep the compatible
conversion. This checks integration, not visual equivalence to a camera profile.

The separate custom-WB regression also needs a freshly rebuilt `spk_image_io`:

```bash
UP=/path/to/upstream B=/path/to/build \
  bash tools/native/run_raw_custom_white_balance.sh \
  /path/to/missing-as-shot.raw /path/to/valid-as-shot.dng
```

The first file must lack both valid AsShotNeutral and recoverable AsShotWhiteXY.
It uses explicit positive custom multipliers to check the decoder even when
the host has no matrix for a temperature picker. It checks actual compatible and headroom decoding, missing-source flags,
the selected WB, finite pixels and strict default rejection. Remaining files
must have valid source WB; their compatible decodes must retain that metadata.
Eight invalid-custom-WB cases also require unchanged output on rejection.
Without file arguments only those eight negative cases run; actual decoding is
explicitly skipped. Rebuild all consumers after patches 0024/0032 change the internal
`RawMetadata` layout. Patch 0032 separately supports eligible WhiteXY-only files;
the public Pixel 8 Pro sample is no longer a missing-source rejection fixture.

The `run_raw_camera.sh` metadata integration check verifies nonzero as-shot
temperature/tint and a multiplier round trip within 0.5 percent. It also checks
independent Robertson table checkpoints and custom
WB across the Android sliders, including decoder matrix re-selection. A
temperature/tint outside the camera's positive response range may be rejected;
a silently shifted target fails the regression. Custom WB carries the selected
temperature into the decoder: a dual-illuminant profile can have multiple
inverse-neutral solutions, so iterating multipliers is not a reliable substitute.
See [the phone DNG regression](../../docs/RAW_COLOR_REGRESSION.md)
for source hashes, the production calibration-signature limitation and remaining
UltraRAW coverage.
See [the rendering boundary follow-up](../../docs/RENDER_BOUNDARY_REGRESSION.md)
for the brightness fix and the remaining no-film precision/order problems.

Patch 0026 absorbs the Mac main branch's CAM16 inverse-invariant hoisting,
while keeping runtime table generation and the serial schedule. Its regression
requires the original pinned `cam16.cpp` as an explicit reference; the runner
checks its LF-normalized SHA-256 before compiling it with renamed public symbols:

```bash
UP=/path/to/upstream B=/path/to/build \
  BASE_CAM16=/path/to/pinned/engine/src/core/cam16.cpp \
  bash tools/native/run_cam16_setup.sh
```

This compares all 276,480 float64 table cells across six spaces plus setup
metadata, grids and inverse/forward edge cases. It separately compiles strict
and default production translation units, then links the actual CMake-built
archive. Timing is diagnostic; same-toolchain byte equality does not establish
cross-platform libm equality or a phone speedup.

Patch 0025 extends the lost-device latch to reject preparation before new
allocations, transfers and inline argument copies. Run its production-linked
test on a working Vulkan host:

```bash
UP=/path/to/upstream B=/path/to/build \
  bash tools/native/run_vulkan_device_lost.sh
```

The test injects only the latch, checks 16 work entry points with the inline
ring enabled and disabled, and verifies that release/trim still work. It does
not reset the GPU or exercise Android engine recreation. This regression and
the existing row-FIR checks gate the release through the reusable GPU workflow.

Patch 0027 precomputes FilmHost's channel curves without changing DI fitting.
Compare it with the frozen pinned translation unit, whose LF SHA-256 is checked
by the runner:

```bash
UP=/path/to/upstream B=/path/to/build \
  BASE_DI=/path/to/pinned/digital_intermediate.cpp \
  bash tools/native/run_di_setup.sh
```

The runner uses the APK's base/product/extra profile overlay, compares every
DI constant plus curve knots/adjacent doubles, out-of-domain interpolation,
negative/HDR development and live offsets. It runs strict/default source
variants and the actual production archive. Successes and matching baseline
rejections are counted separately. This is setup-data equivalence on one
toolchain, not Android GPU pixel identity; timing is diagnostic only.

Patch 0028 implements two Vulkan pointwise fusions, DIR subtract/curve and
XYZ-to-RGB/CAM16. Both remain disabled by default; only the exact environment
value `SPK_VULKAN_FUSION=on` enables them. Run the actual separated and fused
kernels, followed by complete film/DI renders:

```bash
UP=/path/to/upstream B=/path/to/build \
  RESOURCES_DIR=/path/to/staged/android/resources \
  BUILD_DIR=/path/to/fusion-results bash tools/native/run_vulkan_fusion.sh
```

The runner uses compiler-default contraction and the existing production
archive/SPIR-V. It forces advertised Vulkan limits, compares exact float32
bits, checks tail guards and reduces the test-only group-X limit to exercise
nonzero-base 1D chunks. It also verifies unset and invalid fusion settings use
the separated path. The independent 0001-0028 llvmpipe build passed 494 cases
and 1,490,286 active float32 values, including 12 full off/on renders across
six spaces. There were 640 nonfinite reference values with matching bits;
this is not an all-finite test. No actual 2D dispatch, Adreno/Mali, hardware-FMA
route or cross-GPU parity was validated. The environment switch is a developer
diagnostic, not an Android user-facing recovery control. See the
[GPU review](../../docs/UPSTREAM_GPU_REVIEW_2026-11-09.md) for the build and evidence.

Patch 0029 adds the opt-in, pre-film SHOOT `scene_curve`. The runner checks
the parser, actual Vulkan kernel and public session API:

```bash
UP=/path/to/upstream B=/path/to/build \
  BUILD_DIR=/path/to/scene-curve-strict bash tools/native/run_scene_curve.sh
UP=/path/to/upstream B=/path/to/build STRICT=0 \
  BUILD_DIR=/path/to/scene-curve-default bash tools/native/run_scene_curve.sh
```

It covers 63 shader cases with 517 pixels each against an independent CPU
formula. Each runs with the normal and one-group dispatch cap, in separate
and in-place buffers (252 modes), with a 400-pixel sub-strip and 51 guard
floats. Full/strip results compare byte-for-byte. This test fails the former
scene kernel that omitted the dispatch push-constant base. It also checks
4 valid/12 malformed/1 wrong-type parser inputs, and paper/DI API
behavior: empty/clear bypass, SHOOT/PRINT invalidation, whole/strip rendering,
independent medium-probe isolation, invalid-delta atomicity and the frozen old
baseline. A local curve feature at EV 1 is compared with doubled input at EV 0
to check the metering coordinate. Empty input is an exact bypass; an explicit
diagonal table need not be bit-identical. Android uses 129 samples for editable
control points and preserves imported 2-4096-sample wire tables until reset.
Model/sidecar/style integration and its JVM tests are separate from this native
runner; the runner does not exercise Compose or a physical Android GPU.

Patch 0030 and the Android adapter keep no-film pixels as ungraded RGB float
until Layer2 has run. Test the actual adapter, production engine archive and
output transform:

```bash
UP=/path/to/upstream B=/path/to/build \
  BUILD_DIR=/path/to/nofilm-strict bash tools/native/run_nofilm_float.sh
UP=/path/to/upstream B=/path/to/build STRICT=0 \
  BUILD_DIR=/path/to/nofilm-default bash tools/native/run_nofilm_float.sh
```

Python 3 extracts the actual upstream output math with checked source markers.
The runner compares two negative/HDR regressions, 15 looks across 120
look/space/output/precision combinations, 780 TIFF rows, 60 cached repacks, a missing-transform rejection,
crop/turn/flip/resize and six invalid float requests. It uses the original
power equations for the new float Layer2 path, including near black. The final
production-linked strict run had regression errors of 3/1 UNORM16 codes versus
29,544/2,231 for the former early-clipped route. The expanded matrix measured
at most 2 UNORM16 codes and 1 UNORM8 code on AOIDC, and 1/1 on InstaBox GCC14.
Both strict/default adapter modes passed on InstaBox. These are bounded-error comparisons,
not universal bit equality with the upstream GPU grade.

Both scene-curve and no-film runners default to `-ffp-contract=off`; `STRICT=0`
uses compiler-default contraction. This changes the test/adapter compilation,
not an already-built engine archive or shader. The public C `spk_result` ABI
is unchanged by 0030. The Android cache remains a whole-frame RGB float vector
at 12 B/px, 50 percent larger than RGBA16, so this is not a bounded-memory
render pipeline. Geometry/resampling still precedes Android CPU grading;
the geometry test uses an in-range identity grade and does not establish
commutativity with arbitrary nonlinear grades. Film/DI retain the existing
RGBA16 path; user-LUT domain clipping and the order before WB/exposure remain
separate existing policies.

The output tests additionally need a host engine build, zlib and the generated
`upstream_output_math.inc` from `tools/parity/build.sh`. They use the actual
output transform. Compare 162 combinations of size, stride, look, local masks,
dust, sRGB/P3, display/export and exact mode across five strip heights, then
compare complete TIFF bytes and inject a failing row provider:

```bash
UP=/path/to/upstream B=/path/to/build MATH_DIR=/path/to/parity/sources \
  bash tools/native/run_output.sh
UP=/path/to/upstream B=/path/to/build MATH_DIR=/path/to/parity/sources \
  STRICT=0 bash tools/native/run_output.sh
UP=/path/to/upstream B=/path/to/build MATH_DIR=/path/to/parity/sources \
  SANITIZE=1 bash tools/native/run_output.sh
```

Keep `BUILD_DIR` to run `output_rows RESOURCES --memory-full` and
`output_rows RESOURCES --memory-rows` in separate processes. These include
reading encoded output for a digest, and do not measure Android App PSS.

Two research harnesses investigate upstream algorithm limits separately from
byte-preserving production regressions:

```bash
UP=/path/to/upstream B=/path/to/build bash tools/native/run_blur_study.sh
UP=/path/to/upstream B=/path/to/build bash tools/native/run_blur_study.sh --kernel
UP=/path/to/upstream bash tools/native/run_iir_reference.sh
```

`blur_boundary.cpp` exercises the actual Vulkan blur and reports reversal and
constant-extension diagnostics, plus interior sample counts (`nan` if empty).
Its kernel mode measures a central impulse against a normalized discrete
Gaussian; it does not grade photographs. `iir_boundary_reference.cpp` is a CPU
candidate for the Triggs/Sdika boundary conversion, tested against explicit
long-double tail continuation. Carrying state across five chunk sizes preserves
double scan bytes. This does not implement GPU streaming or change the product
boundary rule. See [the algorithm study](../../docs/ENGINE_ALGORITHM_RESEARCH.md).

For isolated RSS measurements, run these in separate processes after building:

```bash
BUILD_DIR=build-native bash tools/native/run.sh
build-native/engine_adaptation --memory-reference
build-native/engine_adaptation --memory-cache
```

These report peak RSS and an output digest for the same generated input. The
reference is serial and the candidate may be parallel; their elapsed times do
not establish a phone speedup or an old/new production throughput comparison.

`render_regression.py` compares actual film rendering against a completed
baseline parity study. Pass its directory containing `manifest.json`, `inputs/`
and per-case `.rgb` files. The baseline manifest records the input hashes and
complete parameters. Use the same Vulkan driver/device, a fresh candidate
binary and a fresh output directory:

```bash
python3 tools/native/render_regression.py \
  --binary /path/to/candidate/spk_android \
  --resources /path/to/build/engine/resources \
  --baseline /path/to/baseline/renders --out /path/to/candidate/renders \
  --raw-root /path/to/cc0/raw/fixtures
```

This script uses `tools/parity/host_parity.py` and its NumPy dependency for the
shared Layer 2 fixture. It fails on a changed byte in the engine print, sRGB/P3
exports or sRGB/P3 views. It records hashes and logs for every case. Stochastic
effects are compared only with deterministic fresh-process seeds; this is not
a general assertion that arbitrary random renders must match.

Linux native parity, APK compilation and execution on physical Android GPUs
are separate verification steps. See [the adaptation record](../../docs/ANDROID_ENGINE_ADAPTATION.md).
The completed 2026-11-08 run's per-output hashes and byte comparisons are in
[the render evidence](../../docs/validation/android-engine-render-2026-11-08.json).

## JPEG row conversion and anonymous spooling

The extreme, unframed JPEG path converts 32 rows at a time, writes an anonymous
RGBA8 file, frees the full render result, then reads rows into the existing
quality-98 4:4:4 optimized-Huffman encoder. Optimized coding still retains
whole-image coefficients. Converting directly from a live full result can
increase peak memory; the spool separates these lifetimes. It requires about
4 B/px of temporary disk space in addition to the final JPEG. This is bounded
output conversion, not a bounded engine or codec.

```bash
UP=/path/to/upstream B=/path/to/build MATH_DIR=/path/to/parity/sources \
  JPEG_DIR=/path/to/libjpeg-prefix BASELINE_JPEG_CPP=/path/to/frozen/jpeg444.cpp \
  BUILD_DIR=/path/to/jpeg-strict bash tools/native/run_jpeg_rows.sh
```

`JPEG_DIR` contains `include/jpeglib.h` and `lib/libjpeg.a`. The optional frozen
writer is compiled under a different symbol; without it, the test compares
the current full-buffer and row APIs. `STRICT=0` uses compiler-default
contraction. `SANITIZE=1` instruments the harness and adapter units, not the
existing engine/codec archives. The recorded ASan/UBSan run disabled leak
detection; it does not establish leak freedom in Vulkan or dependencies.

Each run checks 48 cases and 384 complete-file byte comparisons across four
row heights. RGBA16 and signed/HDR float, sRGB/P3 export transforms, padded
strides, local masks, dust, vignette, multi-marker ICC and failure cleanup are
included. Run memory modes as separate processes:

```bash
build/jpeg_rows /path/to/resources /path/to/output --memory-full
build/jpeg_rows /path/to/resources /path/to/output --memory-rows
build/jpeg_rows /path/to/resources /path/to/output --memory-full-float
build/jpeg_rows /path/to/resources /path/to/output --memory-rows-float
```

These use the production export transform at 4097x3073. They capture startup
`getrusage` first, reset Linux VmHWM with `/proc/self/clear_refs=5` after
transform preparation and before source allocation, then measure conversion
and encoding. They retain both stage and lifetime peaks. Same-input complete
file sizes/FNV-1a are reported before deletion; smaller cases compare every
file byte directly. AOIDC GCC 11 and IBX GCC 14 recorded about 47 MiB lower
output-stage peaks, while engine startup continued to dominate lifetime peak.
See [the two-host evidence](validation/jpeg-rows-20261109/) and
[scope/results](../../docs/validation/optimization-wave1-2026-11-09.json).

## LibRaw CRX maintenance

```bash
LIBRAW_SOURCE=/path/to/patched-libraw BUILD_DIR=/path/to/crx-probe \
  bash tools/native/run_libraw_crx_quantizer.sh
BEFORE_SOURCE=/path/to/before AFTER_SOURCE=/path/to/after \
  BEFORE_LIBRARY=/path/to/production/libspk_libraw.a BUILD_DIR=/path/to/crx-files \
  bash tools/native/run_libraw_maintenance_files.sh /path/to/input.dng /path/to/input.CR3
```

The helper probe runs the actual decoder translation unit with ASan/UBSan,
excluding vptr because it does not link complete LibRaw RTTI. It covers 464
quantizer cases and signed-bitstream reachability; it is not a whole-file
fuzzer and does not claim to fix all quantizer shifts. The file runner copies
the production archive and replaces only CRX rebuilt with its production
flags. Four public Xiaomi DNGs plus an EOS R5 CR3 produced ten byte-identical
Bayer/RGB16 output pairs. This archive experiment is distinct from the
successful Android NDK rebuild of the entire patched dependency. Normalize
CRX CRLF to LF before replaying the new LF patch, as Android CMake does for
its build copy. See [the detailed evidence](validation/libraw-maintenance-20261109/result.json).

## DNG WhiteXY production decoding

```bash
UP=/path/to/upstream B=/path/to/build BUILD_DIR=/path/to/whitexy \
  bash tools/native/run_dng_whitexy.sh
```

Requires engine 0032 and the complete rebuilt LibRaw 0021 overlay, with matching
headers. The Python standard-library fixture writer creates 25 small DNGs;
98 compatible/headroom and default/custom calls cover Bayer/LinearRaw, single
and dual calibration, FM/noFM, signatures, selected RAW IFD/IFD0 fallback,
invalid metadata and unchanged output on failure. The independent reference
checks Robertson, complete AB*CC*CM endpoint interpolation, neutral and source
WB provenance. Default synthetic grey must have maximum RGB difference below
0.0003. These are numeric contracts, not camera appearance measurements.

`SPK_DNG_WHITE_XY=0` builds the probe against pre-0032 headers. Its `--baseline`
mode records the old default failures; `--compare-baseline /path/to/old/fixtures`
compares six unchanged control outputs. Real-file byte comparisons and Pixel 8
Pro integration use `dng_whitexy_samples.py --old ... --new ... --samples ...
--output ...`. The recorded 12 real ASN output pairs are compatible16 with a
half-size budget; Pixel's headroom test is full size. No tests enable full-float
RAW input decoding. Results: [WhiteXY evidence](validation/white-xy-20261109/)
and [support boundaries](../../docs/DNG_WHITE_XY_2026-11-09.md).

## Safe cancellation boundaries

```bash
UP=/path/to/upstream B=/path/to/build MATH_DIR=/path/to/generated/math \
  BUILD_DIR=/path/to/cancellation bash tools/native/run_output_cancellation.sh
```

IBX GCC 14 strict and default runs each cancel eight real noFilm view boundaries
and compare recovery renders to baseline. Seven stop before float CPU readback
allocation. Full/row conversion, parallel worker joins, TIFF partial-file cleanup
and cancellation classification are tested; uncancelled outputs retain full-file
parity. The generation token also reaches JNI Bitmap/repack/JPEG/TIFF. Bitmap
runtime, mobile GPU drivers and peak RSS are outside the host tests. In-flight
GPU calls and codec internal stages are not preempted. See
[the recorded scope and logs](validation/output-cancellation-20261109/result.json).

The current runner requires 0033 and additionally checks ten integer noFilm
boundaries (nine before CPU allocation), nine real DI view boundaries (eight
before CPU allocation), integer adapter parity/pre-cancel, and deterministic
mid-row cancellation of the legacy look packer. Every recovery render matches
the uncancelled reference. The legacy JNI direct pack and malloc-time
cancellation are source/NDK checks, not Android runtime tests. See
[wave-three evidence](validation/wave3-20261109/).

## Spool disk reservation

```bash
BUILD_DIR=/path/to/spool-disk bash tools/native/run_spool_disk_probe.sh
SANITIZE=1 BUILD_DIR=/path/to/spool-disk-asan bash tools/native/run_spool_disk_probe.sh
```

The small header-only fixture wraps fallocate/fstatvfs/write/flush to exercise
reservation failures, unsupported filesystems, interrupted calls and cleanup
without filling a shared disk. It also performs a real small allocation and
checks that preallocation does not let incomplete data pass seal(). The IBX
sanitizer run includes leak detection. The production reservation covers only
the exact RGBA8 spool, after rendering; final JPEG and gallery space are separate.
See [results](validation/spool-disk-20261109/result.json).

## Float DNG precision and isolated reference

```bash
UP=/path/to/upstream B=/path/to/build BUILD_DIR=/path/to/float-precision \
  bash tools/native/run_dng_float_precision.sh
```

This records the existing float-to-integer loss and scale dependency using six
small legal-format float32 files. It is an expected-limitation probe, not a
production fix. It compares retained float unpack, the default integer unpack,
and actual compatible/headroom calls against an independent reference.
See [the investigation](../../docs/DNG_FLOAT_PRECISION_2026-11-09.md).

`run_dng_float_linear_reference.sh` exercises an isolated float LinearRaw
prototype with an independent Python full-image reference. It does not call
dcraw_process or replace a production entry point. Original rational black,
white and AsShotNeutral metadata, and the boundary between normalization and
LibRaw's rewritten white balance, must remain explicit in its evidence.

## Transient session scheduling

`TransientSessionLeaseTest` runs on the release JVM path. It covers both
dispatcher directions, cancellation while waiting, candidate metadata failure,
cancellation on native return, committed-handle retirement, export cleanup and
the sampling window across retries. IBX also compiles the two small production
helpers and runs these seven tests independently. This checks ordering and
cleanup contracts; actual JNI, UI, installed-session memory, ordinary previews
and small contact-sheet thumbnails are outside the test. See
[IBX logs](validation/transient-lease-20261109/).

## No-film float row readback

`run_nofilm_rows.sh` requires patch 0034 and matching rebuilt engine archives.
It compares full-vector and borrowed-row float/packed bytes across geometry and
grading, and checks sink failure, exception, cancellation and recovery.
`run_float_frame_check.sh` checks the shared full/strip sampled-frame validator;
set `SANITIZE=1` for ASan/UBSan. `measure_nofilm_rows.py` runs both export paths
in separate child executables and records sampled RSS and observed kernel HWM.
Use the `synthetic` input for public evidence; camera files and derived outputs
stay private. The JPEG probe omits ICC, while production JNI requires a profile.

Only no-film `exportJpeg444` consumes this row sink. Other exports and the
preview cache are unchanged. A lower float readback allocation does not imply
a lower whole-process peak or bounded RAW decoding. See
[the implementation and reproduction steps](../../docs/NOFILM_ROW_READBACK_2026-11-09.md).

## Bounded external-plane point chain

```bash
UP=/path/to/upstream B=/path/to/build RESOURCES_DIR=/path/to/build/engine/resources \
  BUILD_DIR=/path/to/bpc bash tools/native/run_bounded_point_chain.sh
MEMORY=1 UP=... B=... RESOURCES_DIR=... BUILD_DIR=... bash tools/native/run_bounded_point_chain.sh
```

Requires patch 0039. It compares banded external-plane execution of the pointwise print_linear chain (xyz to RGB, CAM16 gamut, CCTF) with an independent whole-frame run byte for byte across band heights, band orders and worker counts, and checks that a 4097x3073 plane is refused by a strict 128 MiB range while bands pass. `MEMORY=1` adds separate-process VmHWM runs. Synthetic data, host llvmpipe only; not a phone measurement and not wired into the render pipeline. See [the design](../../docs/ENGINE_EXTERNAL_PLANE_DESIGN_2026-11-10.md).

## External-plane publication and cancellation

Patch 0042 requires 0039-0041. Fill an input with disjoint `write_rows` calls,
then `seal()` it before use. Readback requires a sealed plane. Bounded runs own
their output through an exclusive Producer and publish only on complete success;
failure/cancellation invalidates that output. Published input remains immutable.
The caller must own the Gpu exclusively and provide a thread-safe cancellation
callback. Cancellation occurs between I/O chunks and GPU stages, not inside a
blocking system call or an in-progress dispatch.

```bash
UP=/path/to/patched/upstream BUILD_DIR=/path/to/publication \
  bash tools/native/run_external_plane_contract.sh
UP=/path/to/patched/upstream BUILD_DIR=/path/to/publication-asan SANITIZE=1 \
  bash tools/native/run_external_plane_contract.sh
```

This Linux-only harness links no engine archive. It injects short/interrupted
I/O, EOF, reservation/write failure, worker creation failure and cancellation;
checks concurrent coverage/commit, abandoned output and descriptor cleanup;
and compares synthetic float bytes including signed zero and NaN payloads.
`BEFORE_FIX=1` reproduces the unsealed-read bug against 0041. The point/print GPU
runners now require 0042 and also exercise cancellation at each stage, final
publication and recovery against independent whole-frame bytes. See
[the report](../../docs/ENGINE_EXTERNAL_PLANE_PUBLICATION_2026-11-11.md).

## Bounded prototype contracts and upstream kernel references

Patch 0041 requires 0039/0040. It refuses impossible one-row/halo budgets,
invalid geometry, insufficient FIR halo and an IIR run without carried state.
The contract probe includes before/after rejection checks; the existing GPU
point/print runners still compare legal execution with whole-frame bytes.

```bash
UP=/path/to/patched/upstream B=/path/to/build BUILD_DIR=/path/to/contracts \
  bash tools/native/run_bounded_contract.sh
UP=/path/to/patched/upstream B=/path/to/build BUILD_DIR=/path/to/contracts-asan \
  SANITIZE=1 bash tools/native/run_bounded_contract.sh
UP=/path/to/patched/upstream B=/path/to/build BUILD_DIR=/path/to/upstream-oracles \
  bash tools/native/run_upstream_kernel_reference.sh
```

The oracle runner downloads two tests from fixed upstream 223205f, verifies
their SHA-256, then compiles them against the supplied engine archive. It checks
DI/Cineon/Oklab and headroom kernels against independent float64 references,
guards and repeated execution. It does not replace production math or port
main's HDR pipeline. A cached file with the wrong hash fails before compilation.

## Production TIFF intent, JNI binding and ICC

```bash
BUILD_DIR=/path/to/icc bash tools/jvm/run_icc_profile.sh
javac -d /path/to/cmm tools/jvm/ProfileCmm.java
java -Djava.awt.headless=true -cp /path/to/cmm ProfileCmm /path/to/icc/prophoto.icc
UP=/path/to/upstream B=/path/to/build CODEC=/path/to/jpeg-prefix \
  MATH_DIR=/path/to/generated/math VULKAN_HEADERS=/path/to/Vulkan-Headers/include \
  BUILD_DIR=/path/to/tiff bash tools/native/run_jni_tiff.sh
```

The ICC runner compiles the production Kotlin generator using Kotlin 2.0.21
already cached by Gradle (or `KOTLIN_COMPILER_CP`/`KOTLIN_STDLIB`). It checks
profile structure and independent XYZ values. `ProfileCmm.java` separately
checks 4100 RGB samples using the JDK's CMM, suitable for the second host.

The TIFF runner includes the actual production JNI translation unit, with only
Android platform APIs stubbed for the Linux host. It links production engine
and codec archives, checks complete TIFF bytes for twelve film/nofilm/target/look cases,
preview intent retention, descriptor ownership and old/new entry points.
`SANITIZE=1` instruments the probe and adapter units, not existing archives.

`JVM=1` instead builds a shared JNI library and runs the actual production
`NativeEngine.kt` from a JVM, using a type-only Android Bitmap stub. This mode
requires all supplied static archives built with position-independent code,
the JDK compiler, and the Kotlin 2.0.21 Gradle cache. Use a separate output
directory and do not combine `JVM=1` with `SANITIZE=1`. `JAVA_INCLUDE` overrides
the default JDK 17 header directory; `RESOURCES` overrides engine resources.
This exercises actual native symbol resolution and TIFF output, not Android
Bitmap runtime, a device install or an APK build.

The typed transfer/profile runner additionally compares actual upstream CCTFs
and the Kotlin-generated ROMM ICC with an independent Little CMS runtime:

```bash
UP=/path/to/patched/upstream BUILD_DIR=/path/to/typed-icc \
  PROPHOTO_ICC=/path/to/icc/prophoto.icc \
  bash tools/native/run_icc_typed_contract.sh
```

See [the ICC contract](../../docs/ICC_TYPED_CONTRACT_2026-11-11.md),
[DNG HDR-profile guard](../../docs/DNG_PROFILE_DYNAMIC_RANGE_2026-11-11.md),
[offline LUT rejection](../../docs/LUT_OFFLINE_EXPERIMENT_2026-11-11.md), and
[cloud Android feasibility](../../docs/CLOUD_ANDROID_TESTING_2026-11-11.md)
for each probe's domain, reproduction and device-test limits.

## Precision-preserving Deflate LinearRaw strips

```bash
UP=/path/to/patched/upstream B=/path/to/decoder/build \
  BASELINE_INCLUDE=/path/to/b7ec00e/header/directory \
  BUILD_DIR=/path/to/new-results MEMORY=1 bash tools/native/run_float_dng_deflate.sh
```

Requires C++20, Python 3 and zlib headers/library, with patched LibRaw 0.22.2
built with `USE_ZLIB`. It compares predicted, shuffled float32 RGB strips to
frozen uncompressed float output, an independent matrix/orientation reference
and LibRaw's unpacked words. It also exercises the production precision
dispatcher, cancellation, malformed/truncated/trailing streams, I/O mutation,
SHORT/LONG arrays and strip admission. `SANITIZE=1 ASAN_OPTIONS=detect_leaks=0`
instruments the probes, not supplied archives or system zlib.

The decoder-only CMake project in `float_dng_deflate_host/` can build fresh
`spk_libraw` and `spk_image_io` archives without Vulkan/resources. Set
`SPK_UPSTREAM` and `SPEKTRALAB_LIBRAW_SOURCE` to verified patched source trees.
Large-fixture generation exits before a separate memory measurement process
starts. Output remains a complete float plane. Evidence, commands and limits:
[Deflate report](../../docs/ENGINE_FLOAT_DEFLATE_2026-11-12.md).

## RAW lens-correction cancellation

```bash
UP=/path/to/upstream-with-0050 OLD_UP=/path/to/upstream-through-0049 \
  B=/path/to/decoder-build OLD_B=/path/to/previous-decoder-build \
  FIXTURES=/path/to/synthetic-integer-fixtures BUILD_DIR=/path/to/lens-results \
  bash tools/native/run_opcode_list3_cancellation.sh
```

The source fixtures are `integer-bayer.dng` and `integer-linear.dng` from
`raw_precision_input_fixtures.py`. Uses C++20, Python 3 and matching patched
LibRaw headers/archives with zlib. The runner compares 80 frozen production
pixel/full-metadata pairs, exercises the actual camera callback with one/four
workers and enumerates eight production cancellation/retry cases. A baseline
requiring lens cancellation must fail. `SANITIZE=1 ASAN_OPTIONS=detect_leaks=0`
instruments the probe and directly compiled adapter, not existing archives.
See [implementation, evidence and Android limits](../../docs/ENGINE_RAW_LENS_CANCELLATION_2026-11-12.md).

## Integer LinearRaw black normalization

With `raw_color_audit` built against engine patches through 0051:

```bash
python3 tools/native/raw_color_audit_regression.py /outside-repo/basic --probe /path/to/current/audit
python3 tools/native/linear_raw_black_regression.py /outside-repo/expanded \
  --probe /path/to/current/audit --baseline /path/to/frozen/audit
```

Build the frozen executable from the same diagnostic source against patches
through 0050. The optional baseline checks byte preservation outside the new
uniform nonzero RGB-black case; matching worker settings are required when
comparing diagnostic metadata. See [scope and evidence](../../docs/ENGINE_LINEAR_RAW_BLACK_2026-11-12.md).

## Experimental IIR terminal boundary

```bash
UP=/path/to/upstream-through-0052 B=/path/to/current-build \
BASELINE_UP=/path/to/upstream-through-0051 BASELINE_B=/path/to/frozen-build \
BUILD_DIR=/outside-repo/iir-results bash tools/native/run_iir_boundary_gpu.sh
```

Build `spektralab_engine` and `spektralab_vulkan_resources` for both trees first.
Uses C++20 and the actual engine/core/volk archives. The Gaussian probe compares
574 public synthetic cases against an explicit-tail oracle with float32 forward
storage, checks the original default payloads, and rejects the legacy boundary
with the new gate. The direct shader probe separately compares alias and
independent outputs in 640 cases. Both run automatic math selection and forced
split product; logs report the actual probe decision. A test failure exits nonzero.

The `SPK_IIR_BOUNDARY=triggs-v1` process switch is diagnostic and bounded to
active IIR sigmas `[3,256]`. It does not invalidate session caches or define a
saved editing mode. See [implementation and mobile limits](../../docs/ENGINE_IIR_BOUNDARY_2026-11-12.md).

## IIR correction in actual film and print stages

```bash
UP=/path/to/upstream-through-0052 B=/path/to/current-build \
BASELINE_UP=/path/to/upstream-through-0051 BASELINE_B=/path/to/frozen-build \
BUILD_DIR=/outside-repo/new-results bash tools/native/run_iir_pipeline.sh
```

Uses the same engine/core/volk and Vulkan dependencies as the boundary probe,
plus initialized profile resources. `RESOURCES` and `BASELINE_RESOURCES` can
override the matching build resources. C++20 `-fno-access-control` observes
existing Pipeline stages; production code needs no diagnostic hooks.

Ten synthetic cases compare fourteen snapshots each, check negative/final
bytes against public Pipeline calls, and independently check real corrected
IIR dispatches against explicit-tail recurrence. Fresh deterministic draw
history controls grain and glare. With the optional frozen build, all default
stage bytes must match or the runner exits nonzero. The CPU domain tool uses
actual stock initialization and blur accessors for 120 parameter cases;
two thin full-width Vulkan fixtures separately require transactional rejection
above sigma 256. Output includes CSV, JSON and raw float streams; keep binaries
and float payloads outside Git. These checks do not measure real-film fidelity
or mobile performance. See [results and parameter limits](../../docs/ENGINE_IIR_PIPELINE_2026-11-12.md).

## Difference-state IIR at larger radii

```bash
UP=/path/to/upstream-through-0053 B=/path/to/current-build \
BASELINE_PIPELINE=/path/to/frozen-0052-pipeline/current \
BUILD_DIR=/outside-repo/new-results bash tools/native/run_iir_large_radius.sh
```

Requires the actual Vulkan engine/core/volk archives, resources, C++20, Python 3
and `spirv-val`. `RESOURCES` overrides the build's initialized resources.
Runs 1040 independent-tail/alias/public-entry cases and 574 mixed-FIR/reversal
cases for both automatic and forced-split selection. A negative control must
complete with numerical failures on the old direct-state shader. Complete
v1/v2 Pipeline probes compare public entry points, small-stage snapshots,
sampled large-width calls and range failures; the optional frozen directory
checks both default and v1 stage payloads. All positive gates fail nonzero.

`SPK_IIR_BOUNDARY=triggs-v2` accepts IIR sigmas `[3,1024]`, with no change to
v1's `[3,256]`, the legacy default, or FIR routing. The mode remains diagnostic,
without session-cache or sidecar integration. See [numerical results and limits](../../docs/ENGINE_IIR_LARGE_RADIUS_2026-11-12.md).

## Film dispatch and actual session diagnostics

```bash
UP=/path/to/upstream-through-0053 B=/path/to/matching-build \
BUILD_DIR=/outside-repo/new-results LP_NUM_THREADS=2 \
bash tools/native/run_feedback_dispatch.sh
```

Compiles synthetic full-stage chunk comparisons and an actual C ABI session
probe using the production app area resize. Requires C++20, initialized Vulkan
resources and matching engine/core/volk archives. Runs normal and forced-split
math, one/seven-workgroup caps, public negative/final comparisons, three native
tiers, repeat/cache and print-edit restoration. Private input files supported
by the session probe must remain local with their generated results. See
[results and mobile limits](../../docs/ENGINE_FEEDBACK_DISPATCH_2026-11-12.md).
