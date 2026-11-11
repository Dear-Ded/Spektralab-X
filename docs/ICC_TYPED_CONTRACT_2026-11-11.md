# Android ICC and output transfer contract

Co-created with GPT (OpenAI, AI assistant), 2026-11-11.

## Result and scope

Follow-up: [explicit Adobe TIFF export](ADOBE_RGB_TIFF_2026-11-11.md) now closes
the real-engine matrix and JNI checks. Its independent PCS test found that the
engine's CAT02 transform did not match the new Bradford ICC colorants; only the
new Adobe file intent is corrected. The earlier transfer-only findings below
describe the preceding three-target state and do not prove matrix agreement.

This review found no new ICC arithmetic defect reachable through the current
Android sRGB, Display P3 or ProPhoto TIFF choices. It adds an independent Little
CMS comparison, rather than changing the native ICC parser or RAW defaults.
The new evidence is under
[`tools/native/validation/icc-typed-contract-20261111/`](../tools/native/validation/icc-typed-contract-20261111/).
This is a host arithmetic/profile test, not an Android device display test.

The reviewed upstream output implementation is the complete 1..48 patch replay:
`engine/host/output.cpp` SHA-256
`c4d6bf5b2288298e96b2be983f3a0d7c6ac7b4e91f33be8cfdeea645588c6c9b`.
The Kotlin production generator SHA-256 is
`02987ad15d0826b56b78920fba00839e7dc5d8b1aa13e749cac43aa03b4d32ab`.
Its tested ICC bytes have SHA-256
`04e9d00188531524a16ba754615edb6cb92cd2a7ff0025e84f3b81769b584f60`.

## Actual application path

- `SourceLoader.kt:111-166` asks Android ImageDecoder to convert decoded SDR
  content into named sRGB or Display P3, then checks the returned Bitmap space.
  This path does not pass an arbitrary source ICC to the engine. Normalization
  and rejection of unsupported source profiles belong to the platform decoder.
- `input_transform.cpp` uses named space TRCs independently for each channel,
  then transforms linear RGB into linear ProPhoto. CMake's extraction at
  `app/src/main/cpp/CMakeLists.txt:118-128` deliberately excludes `parse_icc`.
  The host dependency's matrix/TRC parser therefore is not an Android source
  ICC parser. Its unsupported LUT profiles cannot be described as an Android
  parser bug without a separate reachable application path.
- `ProcessingQueue.kt:795-832` selects sRGB resource ICC, Display P3 ICC, or the
  production ROMM generator together with the matching target identifier.
  `spektralab_jni.cpp:1523-1626` builds a local output transform for that target
  before emitting TIFF rows. JNI treats the supplied profile as opaque bytes;
  a deliberately mismatched native caller is outside the validated Kotlin path.
- `output_transform.cpp:149-171` retains signed/headroom values through RAW
  development and grading, then clamps and rounds encoded ProPhoto to UNORM16
  before target conversion. This is the existing desktop grade boundary
  documented in `docs/PARITY.md:104` and tested by `nofilm_float.cpp`. It is not
  a promise to emit signed or HDR TIFF samples. The final TIFF samples are
  unsigned 16-bit SDR codes.

An unknown space string in the older RGBA8 native entry point still selects
the documented sRGB assumption; the F16 V1 entry point rejects unknown names.
The normal Kotlin loader supplies validated named spaces. No incorrectly tagged
UI export was reproduced from either an unknown name or a malformed source ICC.
This review does not establish how every Android decoder handles malformed
profiles, and it does not justify extending the native parser to arbitrary ICC.

## Independent checks

`run_icc_typed_contract.sh` extracts the same upstream CCTF functions as the
production CMake code, compiles a small CPU probe, and compares its actual float
results against Little CMS plus the published inverse equations. It needs no
Vulkan engine rebuild. The Python reader loads the actual Kotlin-generated ICC,
uses all three channel curves independently, and asks the CMM for RGB-to-XYZ
relative-colorimetric conversion with optimization disabled.

Both AOIDC (Little CMS runtime 2.12) and local Windows (runtime 2.18) passed:

| Check | Result |
| --- | --- |
| Actual CCTF decode/encode grid | 4,118 in-domain samples for each of sRGB/P3, ROMM and Adobe RGB |
| Largest decode absolute difference | 1.7931e-7, sRGB/P3 |
| Largest encode absolute difference | 1.4226e-7, sRGB/P3 |
| ROMM channel curves | 12,291 samples; largest difference 6.3775e-7 |
| Actual ROMM ICC RGB-to-XYZ | 32 cases; largest difference 6.4553e-6 |

The generator's `para` function type 3 preserves ROMM's linear toe. Its D50
colorant columns and each channel's quantized curve agree with the engine
within the reported s15Fixed16 and float error. A gamma-only 1.8 profile would
describe different near-black codes. No second chromatic adaptation was added:
matrix-profile colorants are already expressed in the D50 PCS. The generic
host parser's failure to interpret a `chad` tag is not, by itself, evidence that
its D50 colorants require another adaptation.

The SDR measurements do not establish arbitrary ICC v4 support, absolute
colorimetric intent, HDR or extended-range ICC conformance. Negative and >1
samples are recorded separately as engine extensions. sRGB/P3 and ROMM remain
finite for the sampled signed/headroom inputs; Adobe's ordinary power functions
produce NaN for negative values. Current Android target choices do not include
Adobe RGB.

## Minimal Adobe RGB TIFF contract for later work

The engine already recognizes `Adobe RGB (1998)` and uses exact gamma
`563/256 = 2.19921875`, not an approximate 2.2. A future TIFF choice needs all
of these pieces to agree before being exposed:

1. Request the existing Adobe target conversion and an explicitly selected
   gamut policy. Clip remaining out-of-domain target **linear** RGB to [0,1]
   before its fractional-power encoding, as the Adobe document specifies.
   A name/ICC-only change can leave NaNs for out-of-gamut colors.
2. Use D65 primaries R=(.64,.33), G=(.21,.71), B=(.15,.06), with D65 white
   (.3127,.3290). For ICC relative PCS, use Bradford-adapted D50 colorants,
   approximately rows [.60974,.20528,.14919], [.31111,.62567,.06322],
   [.01947,.06087,.74457]. Each ICC `rXYZ/gXYZ/bXYZ` tag stores a **column** of
   that matrix. Preserve the source white/adaptation contract for other intents.
3. Supply a valid ICC profile with three matching gamma TRCs, inspect the actual
   written TIFF tag and sample bytes, and compare RGB-to-XYZ through an
   independent CMM, including saturated/negative-matrix cases. The existing
   opaque-writer TIFF test alone does not prove profile/pixel agreement.

AOSP's fixed `android-16.0.0_r1` source at `ColorSpace.java:1690-1696` constructs
`Named.ADOBE_RGB` with gamma 2.2. It therefore is not an exact ICC source for
the engine's Adobe output codes. The analytical maximum difference between
the two decoding power functions is 0.0001306622067502 at encoded input
0.634685180796. The encoding functions have the same maximum absolute
difference, about 8.563 UNORM16 codes or 0.0334 UNORM8 codes. This is a small,
explicit contract difference, not a defect affecting today's target choices.
The maximum follows by solving `a*x^(a-1) = b*x^(b-1)` for each pair of powers.

This document does not add that output option or change current gamut mapping.

## Primary references

Accessed 2026-11-11; access date is not a release date.

- [ICC.1:2022, profile version 4.4.0.0](https://www.color.org/specification/ICC.1-2022-05.pdf),
  sections 6.3.2, 9.2.4/31/46, 9.2.15 and 10.18: D50 PCS, colorant columns,
  chromatic adaptation and function types 0..4. Download SHA-256
  `aad8e33128635893e38ae780def3b29e661e4541be03cb235c67dd94d558001b`.
- [Little CMS `lcms2.18` cmsgamma.c](https://raw.githubusercontent.com/mm2/Little-CMS/lcms2.18/src/cmsgamma.c),
  `DefaultEvalParametricFn` and the parametric function table. SHA-256
  `aff7cf627450b89daa5a40ec9bb76d141a4f53f33bd72f358baaca0fcf679436`.
  Its type numbering is ICC function type plus one. Runtime versions above are
  queried from each loaded CMM, not inferred from this source tag.
- [Adobe RGB (1998) Color Image Encoding, version 2005-05](https://www.adobe.com/digitalimag/pdfs/AdobeRGB1998.pdf),
  sections 4.3.1.1-3, 4.3.6.1 and 4.3.7.3 (PDF pages 10, 13 and 15): primaries,
  exact gamma, range, linear-domain clipping and D50-adapted matrix. The primary
  PDF was downloaded and the range/gamma page rendered for visual checking;
  SHA-256 `bf7b5001127d662455d55370fd7101c5b2ebb6af7f25268640df7bce392afe31`.
- [AOSP ColorSpace.java, android-16.0.0_r1](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/graphics/java/android/graphics/ColorSpace.java),
  the RGB matrix/TRC model, named spaces and `adapt` contract. This is a fixed
  reference source, not a claim about the user's installed Android build.
  Retrieved source SHA-256
  `51f2905f72b7d180065ee9269fb82ef61250bac718acf79d6f244d6be4ebdd98`.
