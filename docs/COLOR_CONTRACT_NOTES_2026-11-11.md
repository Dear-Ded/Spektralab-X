# Color contract research notes

Co-created with GPT (OpenAI), 2026-11-11. Source-only observations; no new color
algorithm or RAW default is introduced by these notes.

The engine's working-space names, file ICC profiles, Android bitmap color-space
labels, and HDR transfer/data-space signals are separate contracts. A supported
name alone does not establish an end-to-end conversion. Current Android display
output selects sRGB or Display P3 (`DisplayColor.kt:20-40`); `tagOutput` labels
codes that the native output transform has already converted. HEIF deliberately
uses 8-bit sRGB (`HeifExport.kt:9-15`). `InputTransform` decodes the source curve
and applies an RGB matrix into linear ProPhoto D50 (`input_transform.cpp:22-34`).
The strict floating DNG route remains scene-linear ProPhoto and preserves signed
and above-white finite samples; its limited eligibility is independent of HDR
bitmap display support (`float_dng_linear.hpp`, `raw_precision_input.hpp`).

## Current support layers

| Layer | Present source contract | Expansion boundary |
| --- | --- | --- |
| Engine matrices and curves | `colour.cpp` exposes sRGB, Display P3, ProPhoto RGB, Adobe RGB (1998), ITU-R BT.709, ITU-R BT.2020, ACEScg and ACES2065-1. ACES curves are identity. | Eight internal names do not imply eight Android display or file paths. |
| Desktop host ICC output | `host/output.cpp::output_icc` handles sRGB, Display P3, ProPhoto, Adobe RGB and BT.2020. | Matrix, white-point adaptation and transfer encoding must match the embedded profile; no arbitrary ICC guarantee. |
| Android TIFF | sRGB, Display P3 and ProPhoto RGB, with an explicit destination transform and profile. | Adobe RGB or BT.2020 SDR would need matching profile generation, conversion and independent CMM/file tests. |
| Android display and ordinary encoded input | Explicit sRGB/P3 paths; F16 SDR input has its own finite/range contract. | Display bitmaps, textures and input decoders must agree on actual codes, rather than relabel an existing bitmap. |
| HDR and scene-linear intermediates | Separate RAW float and optional input gain-map contracts. HEIF output remains 8-bit sRGB. | BT.2020 primaries do not add PQ, HLG, a gain map or HDR output metadata. ACES interchange also needs an explicit floating file contract. |

This table is a source support inventory, not a claim of eight end-to-end phone
render tests. The production print-tail matrix separately tests six encoded
output spaces on llvmpipe. No new colour-space option is enabled by this work.

Film/print looks are a different extension axis. Measured spectral profiles need
the engine's wavelength grid, sensitivity/density/characteristic data and a
validated viewing/printing model. An alias retains its parent's measured data;
a creative LUT or preset is not a newly measured film stock. New film/paper
pairings also need their own calibrated preview assets where a paired LUT is
used. A wider output gamut alone cannot repair missing camera calibration or
create a new physical film model.

## BT.2020 transfer precision

The engine uses the BT.2020 10-bit practical constants alpha 1.099, beta 0.018.
Android's fixed AOSP `android-16.0.0_r1` `ColorSpace.Named.BT2020` implementation
uses alpha 1.0993 with encoded cutoff 0.08145 (linear cutoff 0.0181). This is a
precision contract difference, not evidence that the engine curve is incorrect.
ITU-R BT.2020-2 Table 4 explicitly permits the former constants for 10-bit
systems and the latter for 12-bit systems. Existing sRGB/P3 output is unaffected.

A future BT.2020 Android output must explicitly choose either a custom matching
ColorSpace or a real decode/re-encode into the named Android curve before
tagging. Merely changing the space label is insufficient. Likewise BT.2020 SDR
does not imply PQ/HLG HDR; those require distinct transfer and output contracts.

Pinned first-party evidence, accessed 2026-11-11:

- ITU-R BT.2020-2 official PDF, PDF page 6 / printed page 4, Table 4:
  https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.2020-2-201510-I!!PDF-E.pdf
  SHA-256 `eed65cf78c95923964c5420ac4f60d1c83f68823c7a71b05a5e31818ebd03374`.
- AOSP ColorSpace.java, fixed tag `android-16.0.0_r1`, line 1651 for BT.2020
  parameters and lines 1731-1754 for separate HLG/PQ spaces:
  https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/graphics/java/android/graphics/ColorSpace.java?format=TEXT
  Decoded source SHA-256
  `51f2905f72b7d180065ee9269fb82ef61250bac718acf79d6f244d6be4ebdd98`.
  This source was actually downloaded and decoded on AOIDC; the access date is
  not an SDK release date.

The separate reproducible LibRaw Deflate dependency result is documented under
`tools/native/validation/libraw-deflate-20261111/`. It is a format-support gap
and has not been established as a cause of any user's photo appearance.
