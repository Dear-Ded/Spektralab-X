# Export appearance: proof first, mapping candidates second

Follow-up: this is a historical candidate study. A later upstream-source
comparison identified the Mac's already-shipped knee `[0.7,0.995,6]`, which is
now explicitly requested for Android file output. The threshold-0.8 and hard
boundary candidates below were not adopted. See
[the implemented Mac intent](../../../../docs/RAW_COLOR_FIXES_2026-11-10.md).

Co-created with GPT (OpenAI), 2026-11-10. Research snapshot: app `3662467`;
upstream pin `a46627d250545a394ca21522f8a28b0fb89e4c42` plus the repository
overlays. Production code, physical-film input and default colors are unchanged.
Only synthetic numbers are included here.

Integration follow-up: the later
[JPEG color preview](../../../../docs/JPEG_COLOR_PREVIEW_2026-11-10.md) adds the
bounded preview-tier adapter bridge, with the actual file target assigned to
the bitmap before Android color-managed drawing. The full file-size proof
discussed below remains a separate design. None of this probe's alternative
gamut algorithms was selected for production.

## Smallest useful product step

Add an explicit export-color proof that shows the selected file target through
the display target. Keep the existing edit view and saved rendering intent.
This addresses the demonstrated preview/export disagreement without silently
changing the user's film or export appearance. A same-space proof can reuse
the current destination export transform. A different-space proof needs a
second matrix-only conversion after file clamp/quantization; assigning a
different bitmap profile or calling `setOutput(fileTarget,true)` alone is not
that conversion.

An existing full proof implementation is in the patched upstream:

- `engine/src/pipeline/view.hpp`, Request: `export_intent`, `target_space`,
  `rgba16`, `long_edge`, `proof_long_edge`, `proof_display`. Result additionally
  records file dimensions and moved/clipped/outside counters.
- `engine/src/pipeline/engine.cpp:1972` selects the file CAM16 transform with
  the lightness shoulder off; `:1983` adds a matrix-only transform from the file
  target to the proof display.
- `engine/src/pipeline/view_process.cpp:198` converts for the file, `:204`
  clamps/quantizes to the selected 8/16-bit file depth on the GPU, `:205`
  downsamples, `:211` converts file-target codes for the display, then packs
  RGBA8. Film and geometry run at the selected file size before proof reduction.

The existing path is a useful contract, **not a drop-in Android implementation**.
Android `passthrough.hpp:121` requests encoded ProPhoto with
`export_intent=false` and disabled upstream adjustments. The app then applies
its RAW development and Layer2 on the CPU output side. Calling upstream proof
without equivalent app processing would omit these operations. JNI `setOutput`
currently selects only one destination conversion; Editor passes display/false
and ProcessingQueue passes file/true. There is no Android proof bridge yet.

For the minimal adapter bridge, freeze the current edit and export settings;
read the same undeveloped cached ProPhoto as export; apply development once,
Layer2 once, the existing UNORM16 grade boundary, the chosen file transform,
file clamp and depth quantization, then file-target to screen-target decode /
matrix / encode with compression off. Tag the resulting bitmap for its actual
screen target. Do not replace the undeveloped cache with proof pixels or apply
the file CAM16 stage twice. A request/revision token must discard obsolete
proof results after edits or target changes.

Two useful fidelity levels must be named accurately: a cache-resolution proof
shows export **colors** at that working resolution; it is not a full-resolution
file proof. A full file-size proof must run the actual file resize/geometry and
spatial effects before output conversion and reduce only afterward. JPEG codec
loss/subsampling is outside either pre-encoder proof; an exact final-file check
requires decoding the encoded private file. Paper frames also need the queue's
actual composition order. These are acceptance boundaries, not extra features
to imply by enabling the CAM16 boolean.

## Candidate comparison on IBX

GCC 14.2, O2, FP contraction off, `-Wall -Wextra -Werror`. The probe links the
already built core-double library and baked color data, without GPU creation,
user images or a new physical-film render. The previous day's production float
versus independent core-double proof is not repeated. Source/library identities
and observed results are in `ibx-gcc14.log`.

The candidates are the existing radial knee, a threshold-0.8 late knee, and an
exploratory hard radial gamut boundary with an exact target-RGB membership
bypass. Each target receives a 9x9x9 linear RGB cube and five explicit mixed
in-gamut, negative, out-of-gamut and HDR examples.

| Property | Default knee 0/1/6 | Late knee 0.8/1/6 | Membership + hard radial boundary |
| --- | --- | --- | --- |
| Preserves all already in-gamut RGB | No | No | Exactly, by bypass |
| Protected interval | No positive-width interval | `Mp/Cmax <= 0.8` | RGB cube membership |
| In-gamut grid preserved | Not claimed | 257 sRGB / 262 P3 in the trust interval | All 729 for each target, zero component difference |
| Smooth radial shoulder | Yes | Yes | No: boundary derivative changes 1 to 0 |
| Uses existing Cmax grid | Yes | Yes | No for OOG: direct public-double inverse bisection |
| Handles HDR without tone/lightness policy | No | No | Explicitly rejects a neutral ray outside the cube |

Late-knee trust-zone output equals the uncompressed RGB/XYZ matrix roundtrip
within 4.9e-15. This isolates the known baked sRGB matrix roundtrip residual.
Late-knee output still exceeds the RGB cube by more than 1e-6 in two sRGB and
one P3 grid cases, so it cannot replace final packing clamp. It also still
compresses near-boundary in-gamut colors. A direct boundary projection is exact
for the tested feasible rays but needs many inverse CAM16 evaluations and is
not a proven general-purpose mapper: this small probe does not prove all
fixed-J/h rays have monotonic cube membership.

Do not combine an in-gamut bypass with the late knee only for out-of-gamut
pixels. At the modeled d=1 boundary, the late knee returns 0.97817974, giving a
2.182% chroma-radius discontinuity against the inside identity. More generally,
for a fixed J/h radial function with f(1)=1, nondecreasing f and f(d)<=1 for all
d>1 force f(d)=1 beyond the boundary. Exact boundary preservation, smooth
monotone radial shoulder and strict containment cannot all be obtained just by
moving this scalar threshold. Relax preservation inside a trust region, use a
hard projection, or adopt a different perceptual/lightness mapping contract.

File quantization is measurable even before JPEG: for 1001 scalar linear-light
samples, clamp/encode/round/decode has maximum linear difference 0.004314 at
8-bit and 0.00001733 at 16-bit. A proof should retain this operation ordering.
This scalar fixture is not a test of the upstream GPU proof implementation.

## Primary references and applicability

Official upstream source at the fixed pin is available at
`https://github.com/JamesQiu2005/SpektraLab/tree/a46627d250545a394ca21522f8a28b0fb89e4c42`.
The proof observations above refer to that tree after the repository overlays;
the generated patches and prior replay manifests identify the modified files.
The existing engine remains the source of production CAM16 setup and math.

ACES official RGC implementation guidance:
`https://docs.acescentral.com/rgc/guides/rgc-implementation/`.
The 2026-11-10 downloaded HTML SHA256 is
`49ece2277c171727f4f9e328a87356bc14f5cd4ffbfa30cdb5c7e039f2aabd9a`.
Its test section uses saturated sweeps and ColorCheckers over exposures to
verify a zone of trust. Its parametric section treats exposed compression
parameters as creative correction operators rather than the fixed RGC.
Apply the **acceptance practice**, not a name or coefficient transplant: ACES
RGC is a scene-side AP1 operator, whereas this candidate is a final target-gamut
CAM16 operator. The existing knee's scale/limit convention is not the ACES
output-transform curve. No ACES implementation is imported or claimed.

W3C CSS Color 4:
`https://www.w3.org/TR/css-color-4/#gamut-mapping`.
The 2026-11-10 downloaded HTML SHA256 is
`b66ad2af540a7550fd34e0d95f8dce3f49b9d39e255fdfed56f0ec1de9c78000`.
Section 14 describes clipping hue changes, perceptual chroma reduction, binary
search with local MINDE, EdgeSeeker and ray tracing. Its algorithms include an
already-in-gamut return. This is a useful boundary-preserving alternative
contract, not evidence that a CAM16 radial threshold can implement Oklch local
MINDE or that browser SDR mapping is automatically suitable for film/HDR.
No W3C algorithm code is copied; the exploratory bisection here is separately
written against the current engine's public double transforms.

The web tool returned empty results during this bounded check; both primary
documents were actually fetched from their official HTTPS sites on IBX and
the cited passages inspected. The document snapshots are dated/hash-fixed,
not described as source-code revisions. Research adds no SDK or other licensed
dependency; new probe/runner follow this repository's GPL/NOTICE terms.

## Integration priorities and acceptance

1. Export-color proof first, same-target parity against current packed export,
   then P3/sRGB cross-display conversion with independent matrix/ICC fixtures.
   Validate target/depth/edit revision changes, raw/film/DI, Layer2, cancelled
   recovery, unchanged undeveloped cache and no extra native render for a
   cache-only proof. Full-resolution proof must use the queue's lease and file
   settings, with status that distinguishes it from the cache proof.
2. Keep a late-knee versioned output intent as a candidate only. Acceptance
   needs saturated gradients, ColorChecker-like exposure sweeps, hue/lightness
   preservation, clipping count and actual proof/export parity. Do not silently
   change existing saved edits or legacy exports.
3. Boundary-preserving mapping needs its own full contract and tests for near
   black/white, nonmonotonic rays, signed/HDR and perceptual errors before any
   product choice. The included hard projection is a diagnostic candidate.

Reproduce the synthetic experiment using existing host outputs:

```sh
UP=/path/to/patched-upstream B=/path/to/build-host BUILD_DIR=/tmp/intent-probe \
bash tools/native/validation/output-intent-20261110/run.sh
```

No default output mapping, version, release or production file was changed.
