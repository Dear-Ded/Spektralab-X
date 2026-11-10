# Phone DNG colour regression

Co-created with GPT (OpenAI), 2026-11-09.

## Reproduction

The reported device is Xiaomi 13 Ultra. The report covers Pro-mode RAW and
UltraRAW, including a grey, low-saturation appearance after a colour style.
The public reference below is a Bayer DNG from that model; it does not establish
coverage of every firmware, camera module or UltraRAW variant.
The requested visual reference is Lightroom or the camera's normal colour
rendering. Metadata consistency and arithmetic tests alone do not establish a
visual match to either reference.

Reference: Roberto Pezzali's [original sample photographs and RAW download](https://www.dday.it/redazione/45667/xiaomi-13-ultra-come-scatta-le-foto-la-cina-in-50-immagini).
The photographs remain with their author and are not distributed in this repository.

| File | Metadata | SHA-256 |
| --- | --- | --- |
| `IMG_20230420_152059.dng` | Xiaomi `2304FPN6DC`, 8192 x 6144 Bayer, BaselineExposure 0, ColorMatrix1/2 and ForwardMatrix1/2, no ProfileToneCurve | `0c65be622a5a0f2e9ca6fd3f3d1e68436115df807ff5eb2553d7727fce5da982` |

The same LibRaw, white balance, gain-map correction and half-size mode isolate
the matrix choice. Mean linear ProPhoto channel range (`max(R,G,B)-min(R,G,B)`)
on this sample was 0.01930796 with LibRaw's original matrix, 0.01138080 with
patch 0004's interpolated inverse ColorMatrix, and 0.01772382 with an independent
ForwardMatrix probe. Mean brightness was similar and no route clipped highlights.
These are diagnostics of this photograph, not a target saturation setting or a
perceptual error metric.

The DNG specification distinguishes ColorMatrix (XYZ to camera, also used to
find the scene white) from ForwardMatrix (white-balanced camera to XYZ D50).
Inverting an interpolated ColorMatrix is not the profile's ForwardMatrix path.
Patch 0021 uses a valid embedded ForwardMatrix at the colour-conversion boundary,
after accounting for the white balance LibRaw actually applied. It retains the
existing fallback for files without a usable ForwardMatrix. Film and paper
settings receive the corrected linear input; no saturation multiplier is added.
LibRaw does not expose the DNG calibration/profile signature strings. A
nonidentity CameraCalibration therefore retains the legacy output rather than
assuming that calibration belongs to the embedded profile. The mathematical
helper supports general calibration; the production gate is deliberately narrower.

The Android metadata probe also used plain LibRaw. On the sampled Xiaomi DNGs,
LibRaw populated the DNG ColorMatrix tags while leaving `cam_xyz` empty, so
temperature/tint extraction and custom RAW white balance could fail. The probe
now prepares the same camera calibration as the decoder before those operations.

## Additional white-balance regressions

The original Android custom-WB path derived multipliers from the as-shot
`cam_xyz`, then the decoder selected a different matrix from those multipliers.
On `IMG_20230420_115647.dng`, a 2850 K / tint 0 request inverted to 2979.582 K /
tint +7.741 after decoding; 12000 K / tint 0 inverted to 36625.847 K / tint
-7.083. The corrected path reconciles the requested temperature/tint with the
decoder's calibration before accepting multipliers. Strong tint can alternate
between illuminant estimates; the first 13 Ultra sample at 4000 K / tint -150
also has multiple stable inverse-neutral solutions. Iteration or damping cannot
reliably choose the intended one. The UI already knows the selected temperature,
so patch 0023 passes it through both decoding modes and uses it to select the
same calibration used to derive the multipliers. This does not apply WB twice.
The original three- and four-argument decoder entry points remain available;
as-shot and multiplier-only callers keep neutral-to-illuminant inference.

`raw_white_balance.hpp` is the production helper shared by JNI and the native
integration regression. It rejects invalid controls or multipliers without
overwriting the caller's output. Temperatures/tints beyond a camera's positive
response range remain explicit errors rather than silently shifted settings.

The Robertson table also contained the published 325-mired `u=0.24702` typo.
Patch 0022 uses Lindbloom's corrected `0.24792`, as in Colour's implementation.
Independent table checkpoints catch this error even though forward/inverse
round trips using the same wrong table can pass. Forty control-range round
trips with the correction had maximum errors of 0.0173 K and 0.0000144 tint.

## Verification boundary

The native regression checks profile mathematics and fallback behaviour. The
default white-balance integration check reads actual RAW metadata without
unpacking the sensor and verifies temperature/tint-to-multiplier round trips.
The optional pixel-decoding mode separately exercises the actual decoder.
Image comparisons must use a freshly rebuilt decoder, fixed input hashes,
identical processing settings and the same output colour space.

The final metadata run covered 15 control settings on each of two 13 Ultra
files, one Mi 9 Lite DNG and one iPhone 12 Pro DNG. Of 60 settings, 53 matched
their requested white (maximum error 0.0175583 K and 0.000022021 tint); seven
were specifically rejected as outside the camera's positive response range.
These are calibration/API checks, not 60 full pixel decodes or a colour-accuracy
measurement. Twenty-four default/as-shot mathematical cases retained identical
double-precision output bytes after adding the explicit-temperature option.

With `RAW_DECODE=1`, the first 13 Ultra sample and the iPhone 12 Pro sample
also passed eight actual compatible decodes: 2850/0, 4000/-150, 6500/-150 and
12000/0 on each file. Output RGB and reported matrices were finite; the actual
calibration recovered the selected white within 0.018 K and 0.000023 tint.
All four Xiaomi decodes used the expected FM with zero measured matrix error.
The iPhone sample has no ForwardMatrix tags and correctly retained the legacy
conversion; that fallback is not evidence of a post-WB mismatch. The regression
uses Bayer half-size decoding where supported (13 Ultra output 3072 x 4096;
iPhone linear output 3024 x 4032). These are real pixel decodes, with numerical
checks rather than a visual or measured colour-accuracy comparison.

A separate headroom-mode probe on the first 13 Ultra file at 4000/-150 also
matched the requested calibration and FM, with finite pixels and zero measured
matrix error. This covers the new explicit-temperature entry point in the
second decoding mode; it is not a full headroom-mode parameter sweep.

The two public 13 Ultra samples `IMG_20230420_152059.dng` and
`IMG_20230420_115647.dng` returned 4176.60 K / tint 3.2146 and 4329.92 K /
tint -5.3170 respectively. Relative multiplier round-trip errors were
`7.05e-7` and `4.32e-7` with the corrected metadata probe. An initial independent
NumPy arithmetic oracle covered 600 profiles and 7200 camera RGB vectors; a
later SDK source review exposed a shared assumption in both implementations
about how dual camera-calibration endpoints were composed. It was not a check
against the complete SDK. The regression now freezes separate NumPy matrix and
coloured/HDR vector results for single and dual illuminants, including different
non-diagonal calibration matrices and both endpoint clamps. These validate
matrix construction, not visual equivalence to Lightroom's complete profile
rendering. Production still gates nonidentity CameraCalibration as above.

The current helper follows the SDK's composition of the complete camera matrix
at each endpoint before interpolation. The DNG specification describes
interpolating CM and CC separately, which can differ for nonidentity dual CC.
The identity-CC production gate makes those forms equivalent here. The SDK also
normalizes/rounds each ColorMatrix before interpolation; this patch retains the
existing CM values. On the two public 13 Ultra files, independently enabling
that normalization changed inferred CCT by about 1.6 K and 19.8 K, respectively.
This small SDK-consistency difference remains separate from the grey-render fix.

On the second public 13 Ultra photograph, identical full RAW decode, 512-edge
area preparation and rendering settings produced the following diagnostics:

| Path | Mean encoded luma, before / after (0-255) | Mean HSV saturation, before / after |
| --- | --- | --- |
| No film | 117.152 / 116.987 | 0.223145 / 0.286810 |
| Portra + Supra | 115.128 / 114.091 | 0.381652 / 0.440348 |

These describe one photograph, not a saturation target. The valid-FM path also
prepares the old `rgb_cam` because LibRaw's AHD demosaicer uses it for Lab-based
direction selection, and because a rejected FM must retain the old conversion.
An earlier prototype skipped that preparation; it changed small demosaic details
and was discarded. Image comparisons above use the corrected preparation.

Two additional limitations were found but are separate from this fix:

- A legal Pixel 8 Pro DNG using AsShotWhiteXY without AsShotNeutral is rejected by
  the current mandatory camera-WB check. It does not silently decode as grey.
- The no-film view quantizes/clamps to UNORM16 before app-side Layer 2. A synthetic
  input above 1 loses highlight colour before a later negative exposure can
  recover it. The 13 Ultra reference above did not reach that boundary.

This source fix does not implement the entire Adobe camera-profile rendering
pipeline (for example ProfileHueSatMap, ProfileLookTable or ProfileToneCurve).
Cloud Linux rendering and Android compilation do not replace testing the user's
own Pro-mode RAW/UltraRAW on the physical device.
The final source passed both strict and default native regression suites, the
host image-IO build, and Android `assembleRelease` / `testReleaseUnitTest` (the
unchanged JVM tests were up to date). At that validation stage no version,
published APK or release asset was changed. Subsequent
[beta2 preparation](BETA2_RELEASE_PREPARATION.md) changes the source version to
0.1.19-beta2 / 121; the currently published beta does not include this source fix.
Sample hashes and the validation scope are also recorded in
[`validation/raw-color-2026-11-09.json`](validation/raw-color-2026-11-09.json).
The [boundary follow-up](RENDER_BOUNDARY_REGRESSION.md) adds reproduced
no-film float/quantization errors, a brightness-range fix and additional RAW
rejection/recovery findings. Its known failures are not counted as passes here.

## Primary references

- [Adobe DNG specification and SDK](https://www.adobe.com/support/downloads/dng/dng_sdk.html),
  camera-to-XYZ D50 transform and ForwardMatrix normalization.
- [Adobe SDK dng_color_spec.cpp](https://github.com/Autoenhance-ai/adobe-dng-sdk/blob/main/dng_sdk/source/dng_color_spec.cpp),
  `SetWhiteXY`, `NeutralToXY` and matrix interpolation (mirror of Adobe source).
- [LibRaw 0.22.2 conversion](https://github.com/LibRaw/LibRaw/blob/0.22.2/src/postprocessing/postprocessing_utils_dcrdefs.cpp),
  `scale_colors` and `convert_to_rgb` processing boundaries.
- [Colour Robertson implementation](https://colour.readthedocs.io/en/v0.4.7/_modules/colour/temperature/robertson1968.html),
  corrected 325-mired checkpoint and source attribution.
