# Float DNG precision investigation

Co-created with GPT (OpenAI), 2026-11-09.

This is an observed limitation and an isolated reference experiment, not an
enabled production float decoder. The WhiteXY repair does not address it.

## Production result

Six generated, uncompressed float32 DNG 1.4 files exercise Bayer and LinearRaw.
The writer uses explicit SampleFormat 3, matching DNG/BackwardVersion 1.4,
constant or fractional black, and explicit WhiteLevel. Each file is unpacked
twice, with the existing default options and with integer conversion disabled;
both current production decode policies are also called. No real camera input
is needed to reproduce the storage-level behavior.

| Same input sample | Default unpack, file maximum 2 |
| --- | --- |
| -0.125 | 0 |
| 0.18000000715255737 | 1474 |
| 0.18000002205371857 | 1474 |
| 0.5 | 4095 |
| 1 | 8191 |
| 2 | 16383 |

LibRaw's `CONVERTFLOAT_TO_INT` option is enabled by default. In
`src/decoders/fp_dng.cpp`, `convertFloatToInt` chooses its scale from the larger
of integer WhiteLevel, actual maximum sample and 1. Outside its integer target
range it changes the scale and maximum to 16383, clamps negative samples to
zero and truncates to uint16. The original float allocation is then released.
This happens during unpack, before the current production processing pipeline.

Changing only a distant high-valued strip from 2 to 4 leaves the other input
strips byte-identical. Nevertheless, the production compatible output of the
same 0.180000007 region changes from 0.08996719121932983 to
0.04498359560966492 in all three channels, for both Bayer and LinearRaw.
This is not solely a demosaic issue. The independent Python float32 multiply
and truncation reference exactly reproduces the unpacked integer values.

The neighboring float gap of `1.4901161193847656e-8` collapses to zero in the
integer data. The negative sample is also lost. These are separate from the
output scaling problem. The experiment does not claim that every negative or
above-white value must remain visible in a finished DNG rendering.

With conversion disabled, unpack retains all test samples exactly, but the
current `dcraw_process` returns `-4`: `raw2image_ex` expects integer raw/image
storage. The headroom decoder explicitly rejects floating-point files and
preserves its previous output on failure. Clearing one option is therefore not
a working float adaptation.

Fractional BlackLevel is an additional dependency. In the 0.25-black fixture,
Bayer preserves `dng_fblack` while the legacy integer black is zero; the current
LinearRaw archival path does not preserve the same original floating black.
It must not be treated as just another quantization rounding error.

Probe sources:

- `tools/native/dng_float_precision.cpp`
- `tools/native/dng_float_precision_fixtures.py`
- `tools/native/run_dng_float_precision.sh`

Observed values, original input hashes, both unpack records, production outputs
and independent references are in
[the six-case record](../tools/native/validation/wave3-20261109/float-precision/fixtures/results.json).

## Implementation route

Start with a bounded, explicit float LinearRaw path, where no demosaic is
needed. Preserve the original metadata and float3 storage, normalize using
supported original black/white values, apply source or selected white balance
and its prepared color matrix, then apply orientation and the chosen output
quantization policy. Retain failure atomicity and reject unsupported metadata
rather than silently skipping corrections.

The reference experiment remains separate from the production decoder. A
product path must additionally cover or explicitly reject ActiveArea/default
crop, spatial black/deltas, linearization, opcodes/gain maps, already-applied
white balance and unsupported profiles. It must budget float input plus float
output (at least 24 B/px before other scratch), including the old editor session.

Unequal per-plane WhiteLevel requires special attention. LibRaw's existing
`src/utils/open.cpp` also adjusts `cam_mul`/`pre_mul` by the per-plane integer
white-minus-black range. Combining that already-adjusted white balance with a
new independent per-plane normalization applies part of the correction twice.
The independent reference must stay fixed while this dependency is resolved.
Uniform-white-only experiment support cannot be described as full LinearRaw.

## Isolated LinearRaw result

The original 19-case experiment accepted 11 cases and explicitly rejected 8.
Its unequal-white diagnostic failed the unchanged independent reference by
`0.5313475131988525`; that result is retained, not rewritten as a pass.

The corrected experiment reads the original rational AsShotNeutral and restores
its reciprocal before preparing the existing ForwardMatrix transform. It
normalizes each float plane using the original rational black and integer white,
without reusing LibRaw's range-adjusted open-stage WB. The production decoder
and LibRaw integer path remain unchanged.

The final suite has 26 cases: 17 accepted and 9 explicitly rejected. The fixed
absolute tolerance remains `2e-6`; complete-image maximum error is
`4.76837158203125e-7`. All eight orientations, fractional per-plane black,
unequal and permuted white, nonunit-green and six-decimal rational neutral pass.
Scaling all AsShotNeutral values together leaves output byte-identical.
Changing only the distant maximum strip from 2 to 4 leaves 8064 other output
components byte-identical; adjacent float samples remain distinguishable.

Three explicit legacy-WB runs still fail the same mathematical reference, with
maximum errors `0.5313475131988525`, `1.794459581375122` and
`0.9143797159194946`. This isolates a normalization/WB interaction when building
a float path; it does not establish that LibRaw's existing integer correction
is itself wrong. The Python reference uses an independently inverted matrix and
the stored rationals; it does not consume the prepared C++ matrix.

Sources are `dng_float_linear_reference.cpp`, `dng_float_linear_fixtures.py` and
`run_dng_float_linear_reference.sh` under `tools/native/`. The
[prototype evidence](../tools/native/validation/wave3-20261109/float-linear/README.md)
retains the original 19-case, intermediate 25-case and final 26-case results,
source hashes and explicit support limits. No production entry invokes it.

The prototype accepts only one little-endian classic TIFF IFD, uncompressed
chunky RGB float32, full ActiveArea, constant rational black, explicit three-plane
integer white, rational ASN and a supported single FM. Unknown tags are rejected.
It intentionally retains above-white diagnostic values; specification clipping
and final rendering remain product decisions. Custom WB, WhiteXY, nonidentity
calibration, compressed float, complete metadata inheritance, memory admission,
cancellation and atomic integration still require work. Its 24 B/px counts
only input/output float payload, not total RSS.

Bayer needs a separate float black-level, gain/opcode and demosaic pipeline.
The current integer AHD implementation cannot directly consume the retained
float mosaic. A different demosaic algorithm changes the image and needs its
own numerical and camera comparison, rather than being folded into a claimed
transparent optimization.

## Primary references and limits

The investigating agent directly checked Adobe DNG Specification 1.7.1.0,
September 2023, pages 16/20 (float storage), 29 (WhiteLevel), 99 (linearization
and black/white normalization), and 124 (backward-version requirement). The
definition permits WhiteLevel per sample plane; it does not require identical
white levels. Early preservation of negatives and final rendering clipping are
different decisions. The mathematical reference here does not claim complete
Adobe SDK equivalence or Lightroom appearance.

```text
https://helpx.adobe.com/content/dam/help/en/photoshop/pdf/DNG_Spec_1_7_1_0.pdf
LibRaw baseline: b93f6e45c194f5df9b02a43b1af9a54b4f41f33f
src/decoders/fp_dng.cpp
src/utils/open.cpp
src/preprocessing/raw2image.cpp
```

The root web reader could not fetch this PDF; the agent's direct download and
page inspection were used. No full SDK/PDF archive or user camera sample is
included in the repository. This investigation is not phone acceptance testing.
