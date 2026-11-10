# Isolated float LinearRaw evidence, 2026-11-09

Co-created with GPT (OpenAI). This is an experimental host probe, disconnected
from production defaults. It does not call `dcraw_process()` and is not a complete
DNG renderer. No user photographs, APK, release, or subjective colour assessment
are part of this evidence. Only the standalone probe was compiled against the
existing AOIDC archives; these runs did not rebuild production libraries.

## Runs and retained failure

`prototype-19.log` and `prototype-19-results.json` preserve the original 19-case
run before correcting source WB. Eleven cases passed, eight were explicitly
rejected, and a diagnostic bypass of the unequal-WhiteLevel rejection produced
the independently expected error 0.5313475131988525. Accepted cases had maximum
absolute error 1.1920928955078125e-7 at the fixed 2e-6 tolerance.

`prototype-asn-25.*` is the first corrected run: 16 accepted, nine rejected, and
three legacy-WB expected failures. `prototype-asn-26.*` adds a scaled-neutral
invariance assertion and is the final result: 17 accepted, nine rejected, three
expected failures, maximum absolute error 4.76837158203125e-7 at the unchanged
2e-6 tolerance. The Python matrix reference still uses Gaussian elimination and
does not consume the C++ prepared matrix or copy C++ output as its expectation.

The original failing DNG and the now accepted `per-plane-white` DNG have the
same SHA-256, `3f208f44dff7909926eaa91c9ed680da4f2c7e838678e6db360bfbb9142cae99`.
Their BlackLevel is (0.25,0.375,0.125), WhiteLevel (4,3,2), AsShotNeutral
(0.5,1,0.75). The original open-stage `cam_mul` is approximately
(2,1.33333337,2.66666675); using it after per-plane normalization produces relative
WB (1.5,1,2). Restoring original neutral before matrix preparation gives relative
WB (2,1,1.33333337), and the corrected full-image error is
1.1920928955078125e-7. The old mode remains explicitly available as
`--legacy-open-wb`; it is evidence, not a supported alternate colour contract.

| Legacy-WB case | Maximum absolute error |
| --- | ---: |
| per-plane-white | 0.5313475131988525 |
| per-plane-white-permuted | 1.794459581375122 |
| rational-neutral-white | 0.9143797159194946 |

The final fixtures retain all earlier cases, accept the formerly unsupported
unequal WhiteLevel input, and add a permuted white/black case, unequal-white
orientation 6, nonunit-green neutral, six-decimal rational neutral, a scaled
neutral equivalent, and missing/zero-neutral rejection. The scaled-neutral case
is byte identical to its unscaled counterpart. All eight orientations pass.
Changing only the maximum band from 2 to 4 leaves 8064 shared output components
byte identical. One-ULP adjacent inputs remain distinguishable, with output gaps
2.9802322387695312e-8, 1.4901161193847656e-8, and 2.2351741790771484e-8 for the
max2, fractional-black, and per-plane-black cases respectively.

## Independent numerical contract

The reader obtains exact stored RATIONAL black and AsShotNeutral, and integer
WhiteLevel, from the original TIFF directory. In the corrected experimental
mode it replaces only the probe's `cam_mul` with reciprocal original neutral
before invoking the public existing `CameraLibRaw::interpolate_dng_matrix` and
`apply_dng_forward_matrix` hooks. It records both original neutral and LibRaw's
open-stage WB. It never represents restored source WB as custom user WB.

The fixture/reference use one D50 calibration with identity ColorMatrix.
ForwardMatrix is (row-major):

```text
0.70  0.20  0.06422
0.20  0.75  0.05
0.05  0.10  0.67521
```

The Python reference rounds FM and neutral to the actual six-decimal TIFF
rationals, row-normalizes FM to D50 (0.96422,1,0.82521), independently inverts
the ProPhoto XYZ matrix, then applies
`inverse(ProPhotoXYZ) * FMnormalized * diag(max(neutral)/neutral)` to
`(sample-black[c])/(white[c]-black[c])`. The C++ experiment obtains the prepared
post-WB transform from production code, independently normalizes the float3
samples, applies relative source WB plus that transform, and maps orientation.

This output intentionally retains negatives and values above one for diagnostic
comparison. DNG's prescribed upper clipping and final rendering are not claimed.
The input float3 payload and output float3 payload are 12 bytes/pixel each, or
24 bytes/pixel combined. This is not total process RSS or a memory admission cap.

## Why unequal WhiteLevel is legal

Primary source: Adobe DNG Specification 1.7.1.0, September 2023:
https://helpx.adobe.com/content/dam/help/en/photoshop/pdf/DNG_Spec_1_7_1_0.pdf

Directly fetched and inspected on 2026-11-09; PDF SHA-256:
`abdecfd8e104e8b86cc054d3a40b677bb2ebe87131bfde080a1df3ee16eb2f8d`.
The browser tool did not return the PDF, so its primary-source text was read
directly with urllib/pypdf. No large specification passage is copied here.

- Page 29 specifies WhiteLevel SHORT/LONG, count SamplesPerPixel, with floating
  default 1.0. It imposes no common-white restriction.
- Page 36 defines AsShotNeutral SHORT/RATIONAL, count ColorPlanes, in linear
  reference space. Thus its source WB belongs after per-plane normalization,
  not in the stored float sample coordinate system.
- Page 99 processes sample planes independently, subtracts black, and rescales
  each plane by its own WhiteLevel minus maximum computed black. It prescribes
  upper clipping and recommends retaining negative values in early stages.
- Pages 20 and 124 establish float sample encoding and the DNG 1.4 backward
  compatibility boundary. The fixtures explicitly use SampleFormat 3,
  BitsPerSample 32 and DNG/BackwardVersion 1.4.

The relevant project LibRaw `src/utils/open.cpp` lines 977-1005 adjust
`cam_mul` and `pre_mul` by per-plane integer white-minus-black ranges for
non-mosaic DNG. That adjustment suits its integer processing path; retaining it
while separately normalizing the original float planes applies a range
adjustment twice. The experiment demonstrates this interaction; it does not
change LibRaw or claim that all integer uses of that adjustment are incorrect.

## Reproduction

Use the existing patched source and archives, without rebuilding the library:

```bash
UP=/data/work/gpt-optimize-wave2-20261109/upstream \
B=/data/work/gpt-optimize-wave2-20261109/build-host \
BUILD_DIR=/data/work/gpt-optimize-wave2-20261109/float-linear-asn \
bash /data/work/gpt-engine-impl-20261108/repo/tools/native/run_dng_float_linear_reference.sh
```

The final 26-case run reused the already compiled unchanged C++ probe and ran
the updated Python fixture suite directly. Its fixture SHA values are embedded
in the results JSON. The prior 19-case artifacts are immutable in this folder.

## SHA-256 manifest

| File | SHA-256 |
| --- | --- |
| prototype-19.log | 0c5b254c9098f0c37e62edbce9a636d993c6be8052679957e3adf67184bba98d |
| prototype-19-results.json | d3227fe6130fe9a17dd104fde7620019d8f55a10dce3c15a902cd12695387f3b |
| prototype-asn-25.log | b6622d4903844f8708c346ae2ce6c0ad0d259002411307ac86fbed3abb57fe32 |
| prototype-asn-25-results.json | 7bb6e2a10fe4f46b229ff4111949ad95718c16e24e9a53ce42e1559cdd75da3b |
| prototype-asn-26.log | fbf4979197da92462800b2237f172f8e09f28a282d7e604b6a075fb64960204b |
| prototype-asn-26-results.json | c01b18c4ba475ac6a94d19a84ae022f403a65f507427d767bbfd00b5ed096146 |
| Original 19-case dng_float_linear_reference.cpp | 47812eb5ae59a6e9cfbe55ff0f8eb6073916b09d19256cb7219a32c50d993f92 |
| Original 19-case dng_float_linear_fixtures.py | b2f2010c9f7e9c6fee7e224f25db601cdb5b21d8c8e832e2ac7a16d3399d2431 |
| Corrected dng_float_linear_reference.cpp | e276b9fd95dd9b8b18e8814b2e1fc9f1f68a782dbaeb37e077ebd5a548e38a3b |
| Intermediate 25-case dng_float_linear_fixtures.py | bf5522abf504948825d75359fc88e5d0a5d241089c103a49a9339cf9ef45f377 |
| Final dng_float_linear_fixtures.py | ad5f84f4d1f5c9f40c8ad27f0d932dae8b5f40f92eecc4b71862dc1db08cbd64 |
| run_dng_float_linear_reference.sh (unchanged) | b86c1477521e20bc15a0550405fdc671b6195f35a20ac7b473db9a980f449439 |
| AOIDC libraw/src/utils/open.cpp | 87530daa60184ff4aa30663f805c9647344b55c81a567680e21efa7fd608ffac |
| AOIDC engine/src/io/raw_camera.hpp | cad767a3d5accda735043c8ba0a72a99c315a6bf3930619df5764cb8fe71a58e |
| AOIDC engine/src/io/dng_forward_matrix.hpp | 137277bc946364905c414615e78fc4402181ce3eef76708229565a2dcb0e5930 |
| Existing AOIDC engine/libspk_libraw.a | 0328bacbe21e7c732a628330c90722d60da8915cf4d334c1e00788e857521d5a |

## Supported scope and publication dependencies

The prototype accepts only little-endian classic TIFF, one IFD, chunky
uncompressed RGB float32 LinearRaw, full ActiveArea, constant per-plane rational
black, explicit three-plane integer white, original rational AsShotNeutral,
one FM and three camera channels. It rejects unknown tags rather than silently
skipping crop/opcode/profile operations. The prepared matrix's 0.001 normalized
neutral lower bound is enforced explicitly. Custom WB, WhiteXY, nonidentity
calibration, alternate profile selection, no-FM, spatial black, compressed float,
and other DNG variants are outside this prototype's accepted contract.

A publishable entry still needs selected-IFD metadata/provenance and inheritance;
checked original neutral/black/white transport without modifying integer-path
WB; source versus custom WB handling; explicit compatible clipping/output
policy; crop/scale/orientation, opcode and profile handling or complete reliable
rejection; dimensions, finite/range checks, memory admission, cancellation and
failure-atomic integration. Host evidence does not establish Android behaviour
or visual equivalence to Lightroom. Bayer support additionally requires float
demosaic: clearing LibRaw's conversion flag alone leaves its existing
`dcraw_process()` unable to consume float raw pointers. No production default
or temporary global normalization workaround is introduced here.
