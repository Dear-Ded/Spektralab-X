# Optional RAW development v1

Co-created with GPT (OpenAI), 2026-11-09. No version, package or release change.

## Product behavior

New RAW imports and existing records keep linear display by default. In the
develop panel, a RAW with no film can explicitly select experimental standard
development. The selected mode, automatic exposure switch and RAW exposure are
saved with that photo, copied by the exposure group, restored by undo/sidecar and
retained in queued exports. Film presets do not carry these per-photo choices.
Selecting a film bypasses this display development and preserves its settings
for returning to no film. Physical film input stays scene-linear.

Wire keys are `x_raw_development` (`neutral` or `standard_v1`),
`x_raw_auto_exposure` (boolean, initially true) and `x_raw_exposure_ev`
(finite -8 to +8). Missing legacy keys mean neutral, true and zero. Unknown
versions and invalid exposure are rejected, not silently interpreted as a
different look. JNI removes the app keys before calling the physical engine.

Standard applies the selected DNG baseline exposure/offset once, an optional
global automatic correction and the separate RAW EV before tone mapping.
Valid embedded tone points take precedence; absent points use the documented
log-logistic v1 fallback. Invalid explicit metadata, mixed profile sources and
unsupported extra profile selection refuse standard mode while neutral and film
remain accessible. This is a custom SDR rendering intent, not a complete DCP
renderer or a claim of Lightroom/camera appearance matching. See the algorithm
and fixed primary references in the linked validation record below.

## Source And Output Contract

The parser retains bounded metadata without modifying decoded pixels. Statistics
are measured once before working-size resize; standard opens use an unbinned
decode so preview and full export meter the same source domain. Enabling standard
after a binned neutral open reopens that photo without binning, preserving its
edit/history. This has a full-decode memory/latency cost. A failed reopen leaves
the previous native session alive; JNI refuses standard on a binned statistic.

The engine float cache remains encoded ProPhoto with its actual linear toe,
signed values and highlight headroom. Packing copies one pixel into local
storage, applies development once, then Layer 2, existing quantization and the
output transform. Bitmap preview/export, cached repack, JPEG float strips, TIFF
rows and no-film cards use this same ordering. Film/DI integer paths are not
developed. Output-only changes reuse the existing cache; pending film/geometry
changes require a new render before repacking. Params and plans are committed
only after validation and successful engine acceptance. Native cache revisions
also reject an old frame after accepted film/geometry parameters whose following
render failed or was cancelled; a successful new render refreshes the revision.

The before image stays neutral. Standard's RAW exposure can recover source
headroom before its tone curve; a later Layer 2 exposure slider cannot undo
clipping already imposed by that curve. Embedded endpoints are preserved,
including a final output below white; no generic brightening substitutes for
the file's curve.

## Verification And Remaining Limits

- Metadata: both clouds pass 26 synthetic cases, including malformed curves,
  large counts and profile inheritance. Original/new decoded pixels match.
- Algorithm: standalone synthetic tests include literal dark-toe values,
  baseline/offset once, pre-tone HDR recovery, shape-preserving interpolation,
  immutable shared statistics, cancellation and exact neutral bypass.
- Output: each cloud passes 96 same-geometry cases covering sRGB/P3,
  display/export transforms, RGBA8/16, Layer 2 spatial masks, whole/row/borrowed
  strips, cancellation/recovery and unchanged input storage.
- Android source gates: ARM64 native build, Kotlin compilation and release JVM
  unit tests pass, including new persistence, legacy, queue and cache-policy tests.
  No APK was assembled and these checks do not substitute for phone runtime QA.

Same-plan consistency is not screen/file equality. Existing display matrix
clipping and export CAM16 compression differ; nonlinear tone and downsampling
also do not commute. Different resolutions may therefore look different. Full
output comparison must use the same geometry and output intent.

Three private indoor camera examples support this work, but are not a calibrated
color target or Lightroom exports. The fallback/automatic correction can improve
overall contrast yet still differ in blue, green, cyan or skin rendition. A
no-film improvement alone does not close the separate report of dull film
results. Named negative/paper and slide outputs require their own verification.
No per-camera matrix or global saturation boost was fitted to those examples.

Separate paired negative/paper and slide checks used current decoder pixels and
an independently built SDK neutral linear reference through the same physical
engine. Their close outputs do not establish camera-like appearance, but place
the remaining visible rendition difference downstream of the tested decoder
contract. The SDK final render clips to [0,1], so it is not a complete HDR source
oracle. Actual film gamut behavior also requires its own comparison; a small
no-film display/export difference cannot establish a small film difference.

ProfileLookTable/HueSatMap are not implemented by this helper. Auto black
rendering is reported as `black_auto_not_applied`; only None's no-adjustment
behavior is implemented. PCHIP interpolation is not the Adobe SDK spline. These
limits remain explicit rather than being called full DNG rendering support.

Exposure here is a scene-linear gain before the curve. It does not reproduce the
SDK's highlight-preserving negative-exposure ramp or its per-channel clipping
before RGB tone. Profile tone points are evaluated in linear ProPhoto before the
final transfer encoding; a darkening embedded curve is not replaced merely
because its result is darker. Explicit standard mode on a non-DNG RAW uses the
generic fallback; the feature is not limited to DNG files.

Public reproducibility records:

- `tools/native/validation/dng-render-metadata-20261109/README.md`
- `tools/native/validation/raw-development-20261109/README.md`
- `tools/native/validation/raw-development-integration-20261109/README.md`
- `tools/native/validation/output-gamut-review-20261109/README.md`

Private originals, rendered comparisons, float buffers and per-image measurements
are deliberately kept outside the repository.
