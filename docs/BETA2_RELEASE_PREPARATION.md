# 0.1.19-beta2 preparation

Historical, unpublished preparation stage. Superseded by
[`0.2.00-alpha-1`](ALPHA1_RELEASE_PREPARATION.md) on 2026-11-09 after the
upstream migration expanded. The version and test counts below describe this
earlier snapshot; beta2 was not published.

Prepared on 2026-11-09. Co-created with GPT (OpenAI).

This is a source and validation record, not a published release. The comparison
base is the published `v0.1.19-beta`, commit
`6aff78e9d1b7ac0e137526b36781271561ef95f8`, published on
2026-11-08 at 10:16:56 UTC. The working source version is `0.1.19-beta2`,
installation code `121`; the numeric version remains `0.1.19`.

## Changes since beta

| Area | Change in beta2 | Scope |
| --- | --- | --- |
| DNG colour | Use a valid embedded ForwardMatrix to transform camera RGB to XYZ D50 and linear ProPhoto, accounting for already applied relative WB | Patch 0021; retain the old path when the FM/calibration conditions cannot be established |
| RAW WB metadata | Metadata probing and decoding use the same camera calibration preparation | JNI and `raw_white_balance.hpp`; fixes rejection of some phone DNG adjustments |
| Temperature and tint | Correct the Robertson 325-mired table entry; carry the user's known Kelvin value through both decode modes | Patches 0022/0023; avoid choosing another inverse-neutral solution under strong tint |
| Missing source WB | Accept an otherwise valid explicit custom WB without inventing an as-shot WB | Patch 0024; add an explicit validity flag; normal default opening still requires source WB |
| Brightness | Evaluate the existing formula for values above the LUT's upper bound of 2 | Preserve every tested value in the previous domain; fixes premature clipping after upstream exposure/contrast |
| Editor recovery | Build a candidate native session and commit it only after decode and metadata preparation succeed | Preserve the previous image, URI, edits, history and cards after failure/cancellation; coordinate shutdown and ownership |
| Update detection | Compare alpha/beta/rc numeric components, including `beta2`, `beta.2` and `beta10` | The fix runs in beta2; the already installed beta needs a manual upgrade |
| Vulkan recovery | Reject preparation after a recorded device loss, including rechecks under locks | Patch 0025, selected from upstream; cleanup remains usable and the first diagnosis is retained |
| CAM16 setup | Hoist table and row invariants out of the inverse-search inner loop | Patch 0026; retain double precision, table dimensions, binary search and public API |
| DI setup | Precompute FilmHost channel curves instead of allocating and copying for each query | Patch 0027; retain interpolation, DIR arithmetic and full colour fitting |
| Release checks | Make APK and GPU checks prerequisites for publication; add device-loss and CAM16 checks | Reusable GPU workflow; SDK 36 instructions and patch index corrected |
| Release metadata | Prepare beta2/121, Chinese and English summaries, validation and upstream review records | No release assets replaced during preparation |

The UI redesign, bounded resize/FIR scratch, small Vulkan pool request policy,
curve setup, LUT/HDR/export fixes and Sony ARW6 support are already in the
comparison base. They are not counted again as beta2 additions. Claude's recent
changes already included in that base are likewise not new beta2 features.

## Validation

The machine-readable final record is
[`validation/alpha1-2026-11-09.json`](validation/alpha1-2026-11-09.json),
which separates this completed stage from the subsequent alpha migration.
Earlier RAW and boundary records retain their historical beta/120 version and
the failures observed at that stage; those failures have not been rewritten
as passes.

- RAW: 60 file/settings combinations, 53 matching WB targets and seven
  explicitly outside the camera's positive response range. Twelve actual RAW
  decodes passed, including both modes for a missing-source-WB fixture;
  ten rejection/atomicity checks retained the output. This is not an assertion
  of Lightroom camera-profile equivalence or old/new RAW pixel identity.
- Brightness: 64 legal-control/boundary cases passed, and 458,759 float32
  values in the previous LUT domain remained byte-identical with the same
  toolchain. In 24 actual same-host rendering comparisons, the maximum RGB16
  error for the affected no-film brightness cases fell from 4,509 to one,
  and DI from 1,532 to two. Film cases remained zero.
- Candidate-session and updater changes: all 29 JVM tests passed in the final
  Android build, including eight ownership/transaction helper tests and five
  version-comparison tests. These are not full ViewModel/device UI tests.
- CAM16: six colour spaces, 276,480 double Cmax cells, 180 setup metadata
  doubles and 4,704 grid values matched byte for byte in each of strict-TU,
  default-TU and actual production-archive comparisons. Public inverse,
  forward, viewing setup and knee checks also passed. One Linux run reduced
  total table construction from about 2.3 s to 1.6-1.7 s; phone timing is unmeasured.
- DI: compare all constants and curve/develop boundaries for 20 supported
  colour-negative stocks and six settings per stock. Of 120 combinations,
  94 succeed and 26 are rejected by both implementations with matching errors;
  matching rejection is not counted as a successfully supported setup.
  Strict, default and production-archive comparisons are recorded separately.
- Device loss: a red control failed on the old implementation. With the fix,
  both inline-ring modes rejected all 16 tested work entry points with the
  same latched diagnosis and still released their buffers. The hook sets the
  loss flag; it does not perform a real GPU reset or Android engine recreation.
- Frozen-input film regression: ten actual film cases, five output planes
  each, matched all 50 baseline files byte for byte on the same Vulkan host.
  RAW preparation is outside this frozen-float-input comparison.

See [RAW colour](RAW_COLOR_REGRESSION.md),
[render boundaries](RENDER_BOUNDARY_REGRESSION.md),
[upstream review](UPSTREAM_REVIEW_2026-11-09.md) and
[GPU migration review](UPSTREAM_GPU_REVIEW_2026-11-09.md) for mechanisms,
reproduction commands and limits.

## Known remaining limits

1. The no-film intermediate is still UNORM16 before app-side Layer 2. It can
   lose recoverable HDR highlight colour and negative RGB before subsequent
   exposure/desaturation. The reproduced failures remain in the boundary record.
   Correcting this requires carrying float values through the full adjustment
   and output path, rather than adding an early exposure workaround.
2. A legal Pixel 8 Pro WhiteXY-only DNG still fails default opening. Explicit
   custom-WB decoder acceptance is a different, narrower fix.
3. BaselineExposure is not yet a defined default-rendering policy; it is zero
   in both public 13 Ultra samples and was not demonstrated as their grey-colour cause.
4. The existing brightness LUT's near-black approximation is unchanged: at
   brightness +50, input code 9 gives 79 rather than the direct formula's 114.
5. No real single-illuminant DNG was available in the inspected fixture set.
   The user's own Pro-mode RAW/UltraRAW and physical Android GPU remain untested.
6. A candidate and the previous session briefly coexist. Failure preserves the
   old state, but the resulting peak-memory behaviour still needs device testing.

## Publication and installation

The old beta updater considers `beta2` equal to `beta`; installing beta2's
updater cannot repair the client before that installation. Download and install
the eventual formally signed APK over beta manually, retaining the package name
and signing certificate. The cloud validation APK uses an Android Debug
certificate and is not the official upgrade artifact.

Publication is not triggered by the text `pending` or `待发布`. A push to `main`
with this unreleased VERSION triggers the release workflow after APK, GPU and
formal-signature checks pass. A manual workflow dispatch builds without publishing.
This preparation has not pushed `main`, created a tag or published a release.
