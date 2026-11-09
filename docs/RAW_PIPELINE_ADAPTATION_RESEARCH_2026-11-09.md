# RAW pipeline adaptation research

Date: 2026-11-09. Scope: primary-source research and a proposed implementation route. This document does not claim that the proposed stages are implemented or accepted on a phone. It contains no user photographs, filenames, private image measurements, or private storage paths.

## 1. Decision

Keep the camera-to-scene-linear transformation separate from an optional default development appearance. Preserve the existing neutral physical-film input contract. An embedded profile tone curve, fallback view curve, shadow rendering, and a camera JPEG appearance are different decisions; none establishes Lightroom equivalence.

First make the render intent and actual export plan inspectable. Then bound stages individually. A tiled temporary buffer, GPU row readback, or JPEG spool does not establish a bounded complete RAW pipeline: source images, destination images, codec buffers, caches, global statistics, and concurrent transactions must also be counted.

This is an adaptation recommendation, not a measured memory result. Existing validation records remain the authority for implemented code and measured scope.

## 2. Reproducible source set

The following revisions were resolved and selected files downloaded on the research date. Revision pins identify the inspected code, not a promise that a development branch is a suitable shipping dependency. References below use source line numbers at these pins.

| Project | Inspected revision | Role and license observed |
| --- | --- | --- |
| darktable | `0c23f000db413843f385e402d53a796b71d4cba0` | Module ordering, memory declarations, demosaic strips, view transform and export. Inspected code headers: GPL-3.0-or-later. |
| RawTherapee | `64c2d90a3248c7ad41842d9162368858b61aac6a` | DCP switches, global exposure histogram, normal/fast export. Inspected code headers: GPL-3.0-or-later. |
| ART | `db068cacff4d37c0f8a7e63efe337c5a4283c6de` | DCP and separate tone-curve intent. Canonical repository is `artraweditor/ART`; older repository URL redirects. Inspected code headers: GPL-3.0-or-later. |
| Adobe DNG SDK in AOSP | `de700ad461e35af50b28b861943a0b0753b10929` | Sample renderer, tiled tasks and CFA alignment. Source notices refer to the accompanying Adobe DNG SDK License Agreement, a custom license; do not label this pin BSD or Apache. |
| Android framework / CTS | tag `android-16.0.0_r1` | Capture metadata and test conversion contracts. Inspected files: Apache-2.0 notices. CTS conversion is explicitly not an image-quality reference. |

License sources: [darktable](https://github.com/darktable-org/darktable/blob/0c23f000db413843f385e402d53a796b71d4cba0/LICENSE), [RawTherapee](https://github.com/RawTherapee/RawTherapee/blob/64c2d90a3248c7ad41842d9162368858b61aac6a/LICENSE), [ART](https://github.com/artraweditor/ART/blob/db068cacff4d37c0f8a7e63efe337c5a4283c6de/LICENSE.txt), [Adobe SDK](https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/LICENSE). Code licensing does not automatically cover separately distributed camera profiles, LUTs, example images, trademarks or documentation. This research adds no upstream implementation or data to the application.

## 3. Scene-linear and development intent

### darktable: a view transform is a separate module

`src/common/iop_order.c:213-294` orders RAW exposure/input conversion and later sigmoid/filmic/output conversion separately. Its JPEG/TIFF ordering at `:429` explicitly acknowledges nonlinear input before `colorin`; merely decoding an sRGB JPEG transfer function does not reconstruct camera scene radiance. `src/iop/sigmoid.c:217-227` declares a scene-referred input and a view-transform purpose. Its model at `:342-346` constrains scene zero, middle gray and infinity against display targets.

Adaptation: represent the source domain and development intent explicitly, and apply a display/view transform at an identified boundary. Do not copy darktable's entire module order into this physical film model; creative bloom/grain placement and film exposure semantics differ.

Sources: [module order](https://github.com/darktable-org/darktable/blob/0c23f000db413843f385e402d53a796b71d4cba0/src/common/iop_order.c#L213), [sigmoid declaration and mapping](https://github.com/darktable-org/darktable/blob/0c23f000db413843f385e402d53a796b71d4cba0/src/iop/sigmoid.c#L217).

### RawTherapee and ART: profile color and profile appearance are separable

RawTherapee `rtengine/dcp.cc:1520` independently accepts `use_tone_curve`, `apply_look_table`, and `apply_baseline_exposure`. `step2ApplyTile` at `:1572` scales exposure, converts to ProPhoto as needed, optionally applies look/tone, then returns to working RGB. Its look/tone branch clips negative values (`:1616`); this branch is unsuitable as an unconditional signed scene-linear film-input stage. The missing-tone fallback at `:1317` is conditional on Adobe profile copyright; it is not equivalent to the SDK sample renderer's initialized default-curve policy.

ART `src/engine/procparams.cc:2304` separately stores profile tone, look, baseline offset, HueSatMap and early-look choices. Constructor defaults alone do not prove the application's selected processing profile. `src/engine/iptonecurve.cc:47-104` dispatches distinct curve modes; the mode named `NEUTRAL` is still a tone-curve algorithm, not a guarantee that the whole pipeline is identity.

Adaptation: persist both the chosen mode and resolved profile decisions. A profile's technical color transform may remain useful without enabling its creative look. A HueSatMap is not automatically a purely colorimetric correction: its provenance, encoding and range handling also need a declared contract. Do not silently map absent metadata to a universal curve, and do not equate a UI label with mathematical neutrality.

Sources: [RT DCP](https://github.com/RawTherapee/RawTherapee/blob/64c2d90a3248c7ad41842d9162368858b61aac6a/rtengine/dcp.cc#L1520), [ART parameters](https://github.com/artraweditor/ART/blob/db068cacff4d37c0f8a7e63efe337c5a4283c6de/src/engine/procparams.cc#L2304), [ART tone modes](https://github.com/artraweditor/ART/blob/db068cacff4d37c0f8a7e63efe337c5a4283c6de/src/engine/iptonecurve.cc#L47).

### Adobe: sample rendering defaults are not RAW normalization

`source/dng_render.cpp:2108-2164` initializes the sample renderer with an ACR3 default curve and shadows, changes output-referred behavior, accepts a valid profile curve as an override, and handles `DefaultBlackRender=None`. Earlier at `:977-1014`, total baseline exposure and Stage3 gain enter rendering. The render shadow operation is distinct from sensor BlackLevel subtraction. These are useful reference semantics; they do not reproduce the complete current Lightroom pipeline.

Adaptation: retain an optional, versioned default-development branch. Keep sensor calibration, camera-to-working color, baseline render exposure, profile tone and display encoding as separately testable decisions. A profile curve can validly darken an image; being unlike a camera JPEG does not alone prove an implementation bug.

Source: [SDK renderer](https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/source/dng_render.cpp).

### Android: preserve the capture contract, do not promote a test helper to a renderer

`DngCreator.java:44` describes RAW sensor data plus CaptureResult metadata, with color conversion occurring in post-processing. Lens-shading metadata is recommended. CTS `image_processing_utils.py:784-838` separates canonical CFA channels, black/white levels, gains and CCM, but explicitly calls its demosaic rudimentary and unsuitable for image-quality analysis. It downsamples/combines CFA channels and clips; it is not a signed wide-gamut replacement for a full RAW developer.

Adaptation: validate dynamic/static black-white metadata, CFA ordering, gains and matrix contracts independently. Android's DNG writer is not evidence that vendor camera JPEG processing has been reproduced. No current closed vendor Android ISP is asserted to be audited here.

Sources: [DngCreator](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/hardware/camera2/DngCreator.java), [Camera ITS conversion](https://android.googlesource.com/platform/cts/+/android-16.0.0_r1/apps/CameraITS/utils/image_processing_utils.py).

## 4. Memory and tiling: adopt contracts before algorithms

| Operation | Upstream evidence | Adaptation and limit |
| --- | --- | --- |
| Per-module memory | darktable `src/develop/tiling.h:25-40`: CPU/GPU factors, max temporary buffer factors, fixed overhead, overlap and alignment. | Declare input/output/staging and worker counts separately. A factor is an estimate, not an OS reservation. |
| Demosaic | darktable `src/iop/demosaic.c:595-716`: algorithm-dependent border and per-pixel scratch; CPU/GPU differ; CFA-aligned valid rows. | Implement one decoder/algorithm first. Keep original sensor coordinates and CFA phase; validate tile cores against full-image processing. Do not reuse a single halo for all algorithms. |
| General filter tasks | SDK `dng_filter_task.cpp:55-153`: per-thread source/destination tile buffers, explicit source-area mapping, Get/process/Put. | Bound scratch per worker and choose concurrency from the full live set. SDK still uses image abstractions; tiled work alone does not prove disk-backed source/destination. |
| CFA task alignment | SDK `dng_mosaic_info.cpp:1144-1150`: `fUnitCell` is the CFA repeat size; maximum task dimensions respect it. | Align sensor tiles independently of output crop/orientation. File compression tiles and compute halos are separate boundaries. |
| Global exposure | RT `rawimagesource.cc:3914-3995`: thread-local histograms merged into one statistic; `simpleprocess.cc:852` resolves exposure before later processing. | Freeze one global estimate for the render. Preserve present sampling/weights before changing reduction. Per-tile AE is not equivalent; halo pixels must not be double counted. |
| Grain | darktable `grain.c:417,644-661` deliberately does not allow tiling: filename-hash offset depends on ROI width. | A useful counterexample. Preserve the current seed, physical full-frame coordinates, dimensions, scale and draw order. A tile-local RNG silently changes the effect. |
| Recursive blur | darktable `common/gaussian.c:198-323`: forward/backward recurrence initialized at image edges. | A finite halo does not exactly reproduce a recurrence. Carry boundary states or use ordered multi-pass scans; replacing it with truncated FIR is a versioned effect change. |

Sources: [tiling declarations](https://github.com/darktable-org/darktable/blob/0c23f000db413843f385e402d53a796b71d4cba0/src/develop/tiling.h#L25), [demosaic requirements](https://github.com/darktable-org/darktable/blob/0c23f000db413843f385e402d53a796b71d4cba0/src/iop/demosaic.c#L595), [SDK filter task](https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/source/dng_filter_task.cpp), [SDK mosaic](https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/source/dng_mosaic_info.cpp), [RT histogram](https://github.com/RawTherapee/RawTherapee/blob/64c2d90a3248c7ad41842d9162368858b61aac6a/rtengine/rawimagesource.cc#L3914), [grain restriction](https://github.com/darktable-org/darktable/blob/0c23f000db413843f385e402d53a796b71d4cba0/src/iop/grain.c#L644), [recursive Gaussian](https://github.com/darktable-org/darktable/blob/0c23f000db413843f385e402d53a796b71d4cba0/src/common/gaussian.c#L198).

Halation requires the actual project's filter support to be derived. Do not borrow a desktop bloom halo as proof: the inspected darktable `bloom.c:321` itself labels `5 * radius` a guess. For a finite chain, derive the composed receptive field and retain the original image-edge extension. For recursive or global operations, record the necessary state/pass. A visibly seam-free example alone does not prove equivalence on impulses, extreme highlights or maximum radius.

Do not infer that RawTherapee is a fully out-of-core decoder: `simpleprocess.cc:838` allocates a full `Imagefloat`. Its source flushing at `:884-890` is gated by lifecycle and accompanied by an old warning about batch processing. The general lesson is last-use ownership, not unconditional early release of a still-reusable session.

Additional sources: [bloom halo warning](https://github.com/darktable-org/darktable/blob/0c23f000db413843f385e402d53a796b71d4cba0/src/iop/bloom.c#L305), [RT source/working lifetimes](https://github.com/RawTherapee/RawTherapee/blob/64c2d90a3248c7ad41842d9162368858b61aac6a/rtengine/simpleprocess.cc#L838).

## 5. Export resolution, quality and fallback

darktable `src/libs/export.c:380-430` carries dimensions, upscale and high-quality-processing separately. `src/imageio/imageio.c:1301-1403` snapshots quality, computes actual dimensions, logs a memory restriction when an upscale limit reduces the result, and distinguishes early versus late downsampling. The warning is specifically in that export size path; it is not proof that every allocation failure has a visible fallback. `src/imageio/format/jpeg.c:49-54` keeps JPEG quality and subsampling as different parameters.

RawTherapee `simpleprocess.cc:110-150` separately selects normal and fast processing; the fast branch moves resize before denoise. At `:2300` it also adjusts processing parameters. Therefore identical final dimensions and JPEG quality do not establish identical image processing. A fast path is a product quality choice.

Recommended export record: requested output dimensions, source/working dimensions, actual output dimensions, processing-quality mode, codec quality/subsampling/bit depth, profile/render intent, and any fallback reason. Show a short actionable notice if actual dimensions or processing mode changes. Keep codec quality separate from full-resolution claims. Prefer a clearly failed original request or an explicit allowed fallback over silent algorithm substitution. Reservation/free-space estimates must retain their documented uncertainty and failure cleanup.

Sources: [export controls](https://github.com/darktable-org/darktable/blob/0c23f000db413843f385e402d53a796b71d4cba0/src/libs/export.c#L380), [actual export plan](https://github.com/darktable-org/darktable/blob/0c23f000db413843f385e402d53a796b71d4cba0/src/imageio/imageio.c#L1301), [JPEG parameters](https://github.com/darktable-org/darktable/blob/0c23f000db413843f385e402d53a796b71d4cba0/src/imageio/format/jpeg.c#L49), [RT quality paths](https://github.com/RawTherapee/RawTherapee/blob/64c2d90a3248c7ad41842d9162368858b61aac6a/rtengine/simpleprocess.cc#L110).

## 6. Executable priorities and acceptance

| Priority | Proposed bounded change | Acceptance before claiming completion |
| --- | --- | --- |
| P0 | Freeze a render/export plan containing source domain, working color, intent, effective profile choices and requested/actual dimensions. Keep the current neutral default. | Persistence/retry reproduces the same plan; reduced output has a visible reason; final file dimensions/profile/sampling agree. No implicit tone activation for old jobs. |
| P0 | Continue bounded readback/output stages and explicit lifetime accounting. | Existing no-token and cancelled paths; signed/HDR synthetic values; row/full packed-byte equality; flush-before-release; cache transaction semantics; stage RSS and total RSS distinguished. Real-phone runtime remains a separate gate. |
| P1 | Introduce an optional versioned RAW default-development mode, after the neutral camera conversion. Keep it outside physical film input. | Independent SDK default/neutral/embedded/fallback ablations; negative/overrange handling documented; no automatic camera-JPEG or Lightroom-match claim; neutral/film baseline unchanged. |
| P1 | Resolve current AE globally once, then bound point operations in full-frame coordinates. | Tile-size/order/thread-count invariance; exact current statistic before later AE algorithm changes; seeds/crop/orientation stable; cancelled/failed jobs retain the committed old image. |
| P2 | One RAW decoder/demosaic tiled path with a verified halo and CFA alignment. | Synthetic CFA phase, odd edges/crops, extreme highlights, opcode/gain-map boundaries and real RAW parity; measured source+scratch+GPU+output residency, I/O, cancellation and disk cleanup. |
| P2 | Halation/local effects after receptive-field classification; recursive scans only with state propagation. | Impulses on and across every tile edge, maximum radii, corners, signed/HDR inputs, full-frame comparison. Any changed approximation is named/versioned as a quality mode. |
| P3 | Full out-of-core scheduler/codec-source admission if preceding measurements still require it. | Bounded whole-process live set under concurrency; disk quota/full/interrupted cleanup; CPU/GPU device validation; mobile RSS and latency, not desktop stage-only extrapolation. |

Suggested first implementation is P0 export-plan transparency plus the already scoped output-stage work, not a blanket DCP tone switch or an immediate general scheduler rewrite. This research establishes design constraints; it does not establish a new current-production DCP defect. A future bug claim needs a reproducible input, its exact metadata contract, an independent reference and an isolated failing operation.

## 7. Research limitations

This was targeted source inspection, not a complete audit, mobile benchmark, dependency update or port. No current Android vendor ISP or proprietary editor internals were inspected. Initial official web-tool requests returned empty results; later fixed raw-GitHub requests returned the darktable tiling/grain and RawTherapee DCP sources. Fixed official GitHub/Gitiles downloads were used for the full source set and retained privately with file hashes. GitHub development revisions and the Android tag should be re-evaluated when implementation begins. Algorithms are compared by their actual contracts, not by similar UI names.
