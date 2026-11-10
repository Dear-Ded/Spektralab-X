# RAW and rendering boundary follow-up

Co-created with GPT (OpenAI), 2026-11-09.

Later source review implemented the no-film float boundary in patch 0030,
then repaired signed/HDR handling in local masks and Double-to-Float curve
serialization. The original observations below are retained as historical
evidence, including their then-unresolved status. Current outcomes and remaining
geometry/memory/cancellation limits are recorded in
[the deep review](ENGINE_DEEP_REVIEW_2026-11-09.md) and
[alpha1 source validation](validation/alpha1-2026-11-09.json).

This follows the [phone DNG investigation](RAW_COLOR_REGRESSION.md). It compares
the pinned upstream desktop Vulkan view with the actual Android C++ adaptation
on the same Linux host, using eight synthetic RGB swatches in a 16 x 8 image.
The 24 render comparisons cover no-film, film and DI paths, including neutral
sRGB/P3 display and export conversion. This is not a physical Android GPU test
or a comparison with Lightroom's complete camera-profile pipeline.

## Fixed: brightness table loses recoverable values

The Android brightness table covers encoded ProPhoto values from zero to two.
Legal exposure and contrast controls can produce larger values before later
desaturation or tone adjustments bring them below white. Clamping the lookup
input to two therefore changes visible output rather than merely discarding
values that would eventually clip.

Reproduction: encoded ProPhoto `[1,0,0]`, Layer 2 exposure `+3 EV`, brightness
`+50`, saturation `-100`. Upstream produces RGB16 `[27368,27368,27368]`; the old
Android path produced `[22859,22859,22859]`. The fix evaluates the original power
function above two and retains the exact old lookup expression at or below two.

| Comparison across eight swatches | Old maximum RGB16 error | Fixed maximum RGB16 error |
| --- | ---: | ---: |
| No-film brightness case | 4509 | 1 |
| DI brightness case | 1532 | 2 |
| Film brightness case | 0 | 0 |
| Selected red no-film swatch | 4509 | 0 |

`tools/native/brightness_range.cpp` checks 64 legal-control and threshold cases
against an independent double-precision power reference. The old implementation
fails this regression; the fixed implementation passes. A same-toolchain sweep
of seven brightness settings over 65,537 inputs produced 458,759 float32 outputs
that compared byte-for-byte equal before and after the fix. The portable test
does not hardcode a platform-specific libm hash.

## Confirmed: no-film quantization is too early

The Android adapter requests encoded ProPhoto RGBA16 with upstream adjustments
disabled, then runs its own Layer 2. Desktop no-film grading retains float
values until adjustments finish. The following comparisons still fail after
the brightness fix:

| Linear ProPhoto input and controls | Upstream RGB16 | Android RGB16 |
| --- | --- | --- |
| `[4,2,0.8]`, exposure `-3 EV` | `[55012,37430,22498]` | `[25467,25467,22498]` |
| `[-0.01,0.18,0.18]`, exposure 0, saturation `-100` | `[17674,17674,17674]` | `[19904,19904,19904]` |

The first case loses highlight colour before exposure can recover it. The
second loses negative colour components before they contribute to luma.
Changing only the intermediate container is insufficient: Android Layer 2
also unconditionally clamps negative RGB before exposure, whereas upstream
does that only when exposure is nonzero.

The required repair boundary is encoded ProPhoto float -> complete Android
Layer 2 -> UNORM16 -> output conversion. It must preserve the user-LUT-before-WB
order, all global/local controls, geometry and row/cache ownership. Moving only
exposure ahead of the existing RGBA16 boundary would leave the other errors.
This broader repair is not included in the brightness fix.

## Fixed: selected white balance needlessly requires source WB

`Xiaomi_-_Yi.RAW`, SHA-256
`04aad34d8c3e2892fab76a9ef01b53c51eddddc98256f477eae1f2e607a50974`,
lacks valid source `cam_mul`, but has a usable camera matrix. The production
helper derives custom WB at 6500 K / tint 0. Before patch 0024 the decoder
unconditionally required source WB and rejected that valid custom selection.

Both compatible and headroom decoders now require as-shot WB only when the
caller has not supplied validated custom multipliers. Unavailable source WB
remains all zero with `camera_whitebalance_valid=false`; actual selected WB
continues to be recorded separately in `post_scale_pre_mul`. The source value
is never fabricated from the selected white. Default decoding remains strict.

The new production-linked regression passed actual Yi decodes at 2304 x 1728
(compatible) and 4608 x 3456 (headroom), checked finite pixels and output matrices,
and verified the applied WB ratios. Two missing-source default rejections and
eight invalid-custom rejections preserved the caller's output. Two valid-source
RAW controls retained their original source WB and reported a true validity flag.
The existing four-file metadata suite still matched 53 settings and explicitly
rejected seven outside-camera-range settings; its eight actual compatible
decodes also passed. These checks do not claim old/new pixel byte identity.

Patch 0024 changes an internal C++ metadata layout. The host image-IO library,
JNI and all tests using it must be rebuilt together. This fix does not make
missing-as-shot files open automatically in the normal Android import flow.

## Confirmed RAW rejection and recovery gaps

This section records the investigation before beta2 preparation. The native
rejections below remain relevant; the editor recovery consequence was repaired
later, as described after the original observation.

- Pixel 8 Pro sample
  `Google_-_Pixel_8_Pro_-_16bit_(4:3).dng`, SHA-256
  `45420c595401547cca950ae58d552bc82b32d31ce1d58c082df33004016197f5`,
  supplies legal AsShotWhiteXY `[0.322046,0.326646]` without AsShotNeutral.
  LibRaw's public `cam_mul` is `[0,1,0,0]` and `cam_xyz` is empty. Actual
  production decoding fails with `valid camera/as-shot RGB white balance is
  required`; it does not proceed to a silently chosen automatic WB. The file
  also has nonidentity CameraCalibration, so recovering the white requires
  proper metadata/calibration handling rather than forcing the FM gate open.
- Both public 13 Ultra samples reject 2000 K / tint +150 as outside the
  camera's positive response range. The editor currently releases its old
  session and clears `ui.source` before native WB validation; its error handler
  only clears busy state and shows the error. The WB controls require a source
  and image, so this path cannot retry WB without reopening the photo. The
  native rejection is reproduced; the UI consequence is established by code
  tracing, not physical-device automation. This was the pre-beta2 recovery gap.

Beta2 keeps a candidate session separate until decoding and metadata preparation
succeed. Failure or cancellation closes the candidate and preserves the previous
source, session and edit history, including restarting its film cards. The final
Android build passes all 29 JVM tests, including eight ownership/transaction
helper tests. These tests are not full ViewModel or physical-device UI automation;
temporary coexistence of two sessions still raises peak memory. See the
[beta2 preparation record](BETA2_RELEASE_PREPARATION.md).

There is no real single-illuminant DNG among the 16 DNGs in the inspected
34-fixture collection. Single-illuminant coverage remains mathematical rather
than a claim of real-file integration coverage.

## Other measured limits

Neutral sRGB/P3 display/export comparisons across all three rendering paths
had maximum RGB16 error zero or one with exact output math. Those cases did
not expose an additional output colour-space bug. The DI viewing table is
bounded to `[0,1]`; its remaining rounding differences are distinct from the
no-film high-range clipping failure. Upstream film printing itself has a
UNORM16 boundary, which the Android adapter retains.

The existing brightness LUT also approximates steep near-black values. At
brightness +50, input code 9 produces 79 instead of the direct formula's 114;
the maximum error over all 65,536 input codes is 35. This approximation is
unchanged by the out-of-range fix.

BaselineExposure is another difference from full DNG default rendering.
The examined LibRaw code parses/stores the tag, but this decoder does not apply
it. Actual tags include Pixel 4a `+0.9 EV` and iPhone 12 Pro `-0.3022167683 EV`;
both public 13 Ultra files have zero. Adobe's sample renderer combines total
baseline exposure with user exposure and Stage3 gain in its exposure ramp.
Adding this gain unconditionally to every film path would not be equivalent
to that pipeline, especially with scene auto-metering enabled. This is recorded
as a rendering-policy gap, not as the demonstrated cause of the Xiaomi report.

At this investigation stage, the version and existing release were unchanged. Numerical
agreement in these small cases does not establish device-wide byte identity
or full Lightroom/camera appearance.
The final brightness source passed the strict and default native suites and
Android `assembleRelease` / `testReleaseUnitTest`; the unchanged JVM tests were
up to date. The known no-film and Pixel default-decode cases remain unresolved.
The later editor-recovery fix is described above. The custom-WB decoder
regression is separate from those failures.

After patch 0024, `spk_image_io`, the custom-WB regressions and the Android native
library were rebuilt with the changed header. The final strict native suite and
Android `assembleRelease` passed; `testReleaseUnitTest` remained up to date.
Patch 0024 also applied to an independent clean four-file base with GNU patch
`--fuzz=0`, and all four resulting files matched the built cloud sources by
SHA-256. The Windows-only `raw_render` tool was source-reviewed, not compiled.
Machine-readable outcomes and explicit failures are recorded in
[the boundary validation record](validation/render-boundary-2026-11-09.json).

## Source boundaries

- Android: `app/src/main/cpp/layer2.cpp`, `passthrough.hpp`,
  `EditorViewModel.kt` and the RAW JNI entry point.
- Pinned upstream: `engine/src/shaders/vulkan/spk_view_grade_body.glsl`,
  `engine/src/pipeline/engine.cpp` and `engine/tests/gpu_vulkan_no_film.cpp`.
- LibRaw 0.22.2: `src/metadata/tiff.cpp`, `src/metadata/identify.cpp`,
  `src/postprocessing/postprocessing_utils_dcrdefs.cpp`.
- Adobe SDK: `dng_render.cpp`, baseline exposure and exposure-ramp construction;
  see the SDK references in the linked phone DNG investigation.

## Later WhiteXY follow-up

The Pixel 8 Pro default failure above is a historical result. Engine patch 0032
and LibRaw overlay 0021 now retain its recorded xy and derive camera WB with
the selected calibration. Both default decode policies and their custom-WB
reopens pass scoped integration; the original failure record is retained.
See [the numeric contract and support limits](DNG_WHITE_XY_2026-11-09.md) and
[wave2 evidence](validation/optimization-wave2-2026-11-09.json).
