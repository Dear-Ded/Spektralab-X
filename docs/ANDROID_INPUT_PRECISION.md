# Android input precision

共创GPT

Reviewed on 2026-11-08 in `wt-gpt-engine`; steps 1 and the bounded SDR portion of step 2 were implemented on 2026-11-10. Gamut routing now tests containment after white-point adaptation. Broader HDR and arbitrary-profile preservation remain plans. No OEM decoder ignoring a requested color space, F16 visual artifact, or RAW preview/export mismatch was reproduced on an Android device in this review. Source validation is recorded in [the precision integration report](INPUT_PRECISION_INTEGRATION_2026-11-10.md).

## Current limits

| Area | Current source evidence | Consequence and evidence boundary |
| --- | --- | --- |
| Requested versus actual color space | [SourceLoader.kt](../app/src/main/java/com/dearded/spektralab/SourceLoader.kt) now derives the native input label from the final bitmap after resizing/configuration conversion. Recognized sRGB and named Display P3 proceed without relabelling; unsupported spaces fail with owned bitmap storage released. | Step 1 implemented. Android permits an unmet requested target [1], so the actual result is checked. This closes the unchecked contract without claiming an observed OEM decoder failure. Native-graphics tests cover final metadata, byte preservation, format conversion, unsupported transfer/gamut cleanup and real sRGB/P3 JPEG decoding. |
| Eight-bit input boundary | [SourceLoader.kt](../app/src/main/java/com/dearded/spektralab/SourceLoader.kt) preserves decoder-provided `RGBA_F16` for eligible encoded SDR headers with no attached gain map. [JNI](../app/src/main/cpp/spektralab_jni.cpp) reads half values directly using the versioned F16 SDR entry. | Finite unpremultiplied codes must lie in `[0,1]`; negative, above-one and nonfinite codes fail explicitly. Named sRGB/Display P3 and Android's encoded extended-sRGB tag are supported under that value restriction. The old RGBA8 and gain-map paths remain compatibility modes. This preserves existing precision; it does not turn an eight-bit source into higher precision or add HDR support. |
| Wide-gamut routing | `SourceLoader.wideSource` adapts the source XYZ transform to sRGB/D65 with Bradford, reconstructs its primaries from transform columns and checks all three against the sRGB boundary, with `1e-4 xy` perpendicular-distance tolerance. | Replaces the incorrect area heuristic, including small triangles outside one sRGB edge and D50 profiles. P3 remains the only wider available input target; BT.2020/Adobe RGB routed through P3 cannot be assumed lossless. Invalid or degenerate inputs are not classified as wide. |
| Native color ABI | [input_transform.hpp](../app/src/main/cpp/input_transform.hpp) retains the two supported encodings. `openBitmapF16V1` validates its encoding, format, stride and alpha mode explicitly before conversion. | Versioned SDR contract implemented; arbitrary profile names, extended-range float and HDR transfer functions are not accepted by this entry. The existing RGBA8 ABI is retained. |
| RAW decode quality | [JNI:651](../app/src/main/cpp/spektralab_jni.cpp#L651) chooses half-size when both half sensor dimensions cover the fitted target; [patch 0003:24](../patches/0003-raw-half-size-budget.patch#L24) enables LibRaw `half_size` only for `filters > 1000`. Line 661 calls `decode_raw_compatible`. | Changing the requested working/export size can change the RAW decode algorithm before resizing. LibRaw documents half-size as a processing mode, including a distinct pre-interpolation stage [3]. Half-size decode plus resize is not proven equivalent to full decode plus resize. The policy is Bayer-specific and must not be generalized to X-Trans or monochrome sources. |
| Gain-map relationship | [Photo.kt:67](../app/src/main/java/com/dearded/spektralab/Photo.kt#L67) transfers 15 gain-map values; [JNI:873](../app/src/main/cpp/spektralab_jni.cpp#L873) recovers each base channel before converting its primaries to ProPhoto. | A future conversion of the SDR base to different primaries cannot automatically reuse a per-channel gain map as an equivalent operation. That requires a defined gain-map application space and a separate reference test. This review does not add an HDR path. |

## Implementation order

| Step | Bounded implementation | Acceptance |
| --- | --- | --- |
| 1. Make the existing contract truthful (implemented) | `SourceLoader.preparePhoto` derives the engine space from the final bitmap after scaling/configuration conversion. It accepts recognized sRGB and named Display P3 and explicitly rejects other spaces. No `setColorSpace` relabelling is used. The separate film-thumbnail route performs an actual Skia conversion from arbitrary SDR RGB profiles. | `PhotoInputColorTest` and `FilmThumbnailColorTest` exercise native graphics, unchanged supported bytes, unsupported-space cleanup, independent conversion references, and actual JPEG/PNG decoders. They do not simulate all OEM decoder behavior. |
| 2. Introduce a versioned high precision SDR input (bounded SDR implemented) | Preserve eligible decoder-provided F16. Convert encoded sRGB/P3 half values directly to native float using actual row stride and explicit opaque/premultiplied/straight alpha. No intermediate ARGB_8888 or Java `FloatArray`. Record `f16_sdr_v1` versus RGBA8 compatibility in source diagnostics. | All 65,536 half patterns, odd/padded/unaligned rows, alpha, gradient precision and invalid SDR-domain rejection are tested. Production-JNI tests cover source/render/repack, unlock after single/multiple-worker failure and unchanged source bytes. Android native-graphics tests cover routing, scaling and ownership. Physical-device allocation/cancellation and OEM high-bit-depth decoder acceptance remain unverified. |
| 3. Define color/HDR semantics before broadening formats | Handle source transfer decoding and chromatic adaptation explicitly, then enter linear ProPhoto. For PQ/HLG, define reference white, luminance scale, scene/display interpretation and any tone/gamut mapping as part of the input mode. Treat Ultra HDR gain maps as a separate reconstruction contract. | Include sRGB, custom ICC RGB, Display P3, Adobe RGB, BT.2020 SDR, PQ/HLG and gain-map fixtures with reference metadata. Verify saturated primaries, neutral ramps and highlights before testing film output. A wider format alone is not evidence of correct HDR. API 34's broader target transfer-function support [1] is a capability, not an automatic color policy. |
| 4. Make RAW mode reproducible | Record full/half-size choice, source and decoded dimensions, orientation, white balance, LibRaw/engine version and input-mode version with the task. Choose full decode for a reference-quality mode and keep the existing half-size policy as an explicit performance mode. | Same file and same mode are deterministic. Compare both modes at identical final dimensions on fine texture, color edges and noise; report numerical/image differences and runtime separately. Do not require or claim byte equality between different demosaic paths. Cover Bayer, X-Trans, rotation and non-square-pixel metadata. |

For color-space normalization, a rare SDR fallback can draw into a newly allocated bitmap tagged with the intended target color space, then validate its actual space. Avoid a full-frame `getPixels`/`setPixels` conversion: those APIs expose sRGB integer colors, which can conceal unwanted conversions in a test [4]. Use native bytes (`copyPixelsToBuffer`) for the unchanged-path comparison and a color-transform reference for intentional conversions. Do not add this conversion to the gain-map branch until its application-space tests exist.

## Budget and validation

Let `P = width * height`. The entries below are payload calculations for overlapping buffers, not measured total process peaks. Use checked 64-bit arithmetic. Real bitmap reservations must use `allocationByteCount`; addressing uses `rowBytes`, including padding [4].

| Stage | Payload to account for | Example at 24,000,000 pixels |
| --- | --- | --- |
| Existing eight-bit bitmap plus native RGB float output | `4P + 12P`, before any separate engine-owned copy | 384,000,000 bytes |
| Direct F16 bitmap plus native RGB float output | `8P + 12P`, before any separate engine-owned copy | 480,000,000 bytes |
| F16 to ARGB_8888 copy while original is alive | `8P + 4P`; native RGB creation is a later stage if the original has been recycled | 288,000,000 bytes during the copy |
| Extra F16 color-conversion bitmap while original is alive | `8P + 8P`; add `12P` only if native output overlaps both | 384,000,000 bytes, or 672,000,000 with that overlap |
| Gain map, decoder scratch and engine ownership | Actual allocation sizes and lifetimes, plus every concurrently resident input/output and GPU allocation | Must be measured; not included in the totals above |

The current [DeviceProfile.kt:56](../app/src/main/java/com/dearded/spektralab/DeviceProfile.kt#L56) estimate of 100 bytes per working pixel is a heuristic. It does not establish that adding another F16 or profile-conversion buffer is affordable. Reserve the proposed stage peak before decoding, include simultaneous preview/export residency, and release reservations with the corresponding objects. A constrained-memory fallback must report the chosen precision/RAW mode; it must not silently change a reproducible export's mode.

Validation must separate four claims: unchanged compatibility-path bytes, new-mode numerical accuracy, Android decoder/JNI integration, and physical-device performance/memory. Robolectric and Linux Kotlin tests do not prove OEM HEIF decode, ARM64 performance, thermal HAL behavior or whole-pipeline memory usage.

### Primary references

All references were checked on 2026-11-08.

1. [ImageDecoder.setTargetColorSpace](https://developer.android.com/reference/android/graphics/ImageDecoder#setTargetColorSpace(android.graphics.ColorSpace)): requested color space can be unmet; API-level transfer-function restrictions.
2. [Bitmap.Config](https://developer.android.com/reference/android/graphics/Bitmap.Config): eight-bit, 10-bit and F16 storage layout and precision.
3. [LibRaw data structures](https://www.libraw.org/docs/API-datastruct.html): `half_size`, `filters`, output dimensions and processing stages.
4. [Bitmap](https://developer.android.com/reference/android/graphics/Bitmap): `getColorSpace`, `setColorSpace`, pixel-copy methods, `rowBytes` and `allocationByteCount`.
5. [Android NDK Bitmap](https://developer.android.com/ndk/reference/group/bitmap): supported native formats and bitmap access API.
