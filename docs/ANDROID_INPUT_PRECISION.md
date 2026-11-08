# Android input precision

共创GPT

Reviewed on 2026-11-08 in `wt-gpt-engine`. This is the next implementation plan, not a claim that the changes below have shipped. The source establishes the current format restrictions and decisions. No OEM decoder ignoring a requested color space, F16 visual artifact, or RAW preview/export mismatch was reproduced on an Android device in this review. The separate source-dimension/EXIF fixes do not resolve these color and precision restrictions.

## Current limits

| Area | Current source evidence | Consequence and evidence boundary |
| --- | --- | --- |
| Requested versus actual color space | [SourceLoader.kt:111](../app/src/main/java/com/dearded/spektralab/SourceLoader.kt#L111) sets `space` in the header callback at line 122, then returns that string at lines 139/142 without checking the final bitmap's `colorSpace`. | The string describes the request, not necessarily the decoded pixels. Android explicitly permits a different space when the request cannot be met [1]. This is an unchecked API-contract condition; no mismatch for this app's named sRGB/P3 requests was demonstrated. It is not an observed OEM failure. |
| Eight-bit input boundary | [SourceLoader.kt:139](../app/src/main/java/com/dearded/spektralab/SourceLoader.kt#L139) copies every other configuration to `ARGB_8888`. [JNI:735](../app/src/main/cpp/spektralab_jni.cpp#L735) and [JNI:808](../app/src/main/cpp/spektralab_jni.cpp#L808) accept only `RGBA_8888`. | Any higher precision returned by a decoder crosses an eight-bit RGB boundary before the engine's float input. F16 storage is eight bytes per pixel; ARGB_8888 is four [2]. This does not establish that every high-bit-depth source actually decodes to F16, or that every image shows visible banding. |
| Wide-gamut routing | [SourceLoader.kt:176](../app/src/main/java/com/dearded/spektralab/SourceLoader.kt#L176) compares the xy primary-triangle area against `1.05 * SRGB_AREA`; line 122 requests either sRGB or Display P3. | Triangle area is not containment: a smaller or similarly sized shifted triangle can still contain colors outside sRGB. The test also does not describe white point or transfer function. BT.2020/Adobe RGB routed through P3 cannot be assumed to preserve all original colors. This is a limited routing heuristic, not proof that all ICC input is broken. |
| Native color ABI | [input_transform.hpp:24](../app/src/main/cpp/input_transform.hpp#L24) documents two supported encodings and treats other strings as sRGB. [JNI:741](../app/src/main/cpp/spektralab_jni.cpp#L741) constructs the transform from the caller's string. | A new pixel format or arbitrary profile name cannot safely be passed through the existing interface. A higher precision path needs an explicit encoding/profile contract. |
| RAW decode quality | [JNI:651](../app/src/main/cpp/spektralab_jni.cpp#L651) chooses half-size when both half sensor dimensions cover the fitted target; [patch 0003:24](../patches/0003-raw-half-size-budget.patch#L24) enables LibRaw `half_size` only for `filters > 1000`. Line 661 calls `decode_raw_compatible`. | Changing the requested working/export size can change the RAW decode algorithm before resizing. LibRaw documents half-size as a processing mode, including a distinct pre-interpolation stage [3]. Half-size decode plus resize is not proven equivalent to full decode plus resize. The policy is Bayer-specific and must not be generalized to X-Trans or monochrome sources. |
| Gain-map relationship | [Photo.kt:67](../app/src/main/java/com/dearded/spektralab/Photo.kt#L67) transfers 15 gain-map values; [JNI:873](../app/src/main/cpp/spektralab_jni.cpp#L873) recovers each base channel before converting its primaries to ProPhoto. | A future conversion of the SDR base to different primaries cannot automatically reuse a per-channel gain map as an equivalent operation. That requires a defined gain-map application space and a separate reference test. This review does not add an HDR path. |

## Implementation order

| Step | Bounded implementation | Acceptance |
| --- | --- | --- |
| 1. Make the existing contract truthful | Derive the engine space from the final bitmap after scaling/configuration conversion. A small helper should accept the actual `ColorSpace`, not the requested label. Accept recognized sRGB and named Display P3; handle other/unknown spaces explicitly. Never repair a mismatch by `setColorSpace`: that changes interpretation rather than converting pixels [4]. | Robolectric native graphics tests create known sRGB/P3 bitmaps and verify final metadata; deliberately opposite requested labels cannot influence the result. Existing supported paths retain the same bytes. A synthetic unsupported space cannot silently become sRGB, and failure releases both base and copied gain-map storage. Real decoder fixtures are an additional integration check, not a simulation of all OEM behavior. |
| 2. Introduce a versioned high precision SDR input | Preserve a decoder-provided F16 buffer and add explicit format, row stride, alpha semantics, primaries/white point and transfer semantics to the native input description. Convert half values directly to float; avoid an intermediate ARGB_8888 copy or full-frame Java `FloatArray`. Keep the current eight-bit path as a named compatibility mode. | Compare existing SDR output byte-for-byte. For the new mode, compare known gradients and profiles to a float reference; test padded rows, odd dimensions, premultiplied alpha, negative/above-one finite samples, and invalid values. Record precision error separately from byte identity. Verify native allocation and bitmap locks on failure/cancellation. NDK exposes F16 format and stride-bearing bitmap info [5]. |
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
