# Export frame checks and external file routing

Co-created with GPT (OpenAI), 2026-11-13. Base: `22425a1` (alpha-6).

## Corrected behavior

The old frame validators inspected RGB on a fixed `width / 256` by
`height / 256` grid. At 1024 by 1024, a four-pixel periodic pattern can put
every sampled point on the same color. A visible image then fails export
with a black, white or solid-color error.

RGBA16 now combines RGB extrema with the existing complete alpha scan. Once
any channel exceeds the original 16-code-value flat tolerance, only the alpha
scan continues. Float validation examines every valid RGB component across
ordered strips. Once variation is established it stops updating extrema,
while continuing to reject NaN and either infinity everywhere. Both validators
retain constant auxiliary state; strip validation does not retain an image.
Padding is excluded, and existing layout, completeness and true-flat checks
remain in place. These checks do not establish that every rendered RGB value
is semantically correct.

External opens now obtain the provider's `DISPLAY_NAME`. A numeric content URI
for `Neutral-HALD.PNG` enters the style importer. Camera RAW names such as DNG,
NEF and CR3 open as photos even with `application/octet-stream`. An absent,
blank or octet-stream declared MIME can fall back to the provider's type.
Unavailable metadata falls back to the URI name and declared MIME.

DNG is ambiguous between a photograph and a preset. External DNG opens follow
the RAW photo route; users can still select a DNG preset in the existing explicit
import picker. The preset reader and its embedded-XMP contract are unchanged.
The compatibility test uses a synthetic TIFF header plus an XMP packet, not a
real camera file or a RAW-decoder test.

UI, version, RAW development defaults, film profiles and experimental defaults
are unchanged. No APK release is part of this change.

## Regression coverage

The native frame suites cover five 1024-square non-flat patterns, full and
multiple strip heights, padded strides, signed/HDR float input, unsampled NaN
and positive/negative infinity, per-channel flat boundaries, corrupt alpha
after proven variation, missing strips and invalid layouts. The new cases fail
against the frozen alpha-6 headers.

`ExternalFileRoutingTest` has ten Robolectric tests covering provider metadata,
RAW extensions/types, generic MIME fallback, Hald names, ordinary images,
style/recipe files, unavailable/null metadata, actual MainActivity Hald routing
for VIEW/SEND, and explicit DNG preset import. Existing release and selected
debug tests also run in the cloud. The new routing class joins the existing
screenshots workflow; the float native suite joins engine-gpu with ASan/UBSan.

The JNI harness includes the production translation unit and links the engine
and codecs. Only Android Bitmap allocation and JNI byte-array access use host
shims. It opens synthetic F16 input and exercises five non-flat patterns plus
a true-flat control through Bitmap, streaming JPEG and descriptor TIFF exports.
It checks exact Bitmap bytes against preview, complete JPEG markers, dimensions
and TIFF descriptor ownership. It is not physical-device acceptance.

## Verified results

| Cloud check | Result |
| --- | --- |
| AOIDC, GCC 11.4 and Clang 14, two frame suites with/without ASan/UBSan | 8/8 suites pass |
| Independent IBX, GCC 14.2, the same two suites with/without ASan/UBSan | 4/4 suites pass; input hashes match |
| AOIDC no-film conversion, output rows and cancellation regressions | 3/3 suites pass; 120 color cases; 162 x 5 output-strip comparisons |
| AOIDC production JNI, GCC normal and Clang ASan/UBSan | Both pass all 5 non-flat x 3 export routes and 3 true-flat controls |
| Frozen alpha-6 frame tests and production JNI | Both frame tests fail; JNI fails with the expected solid-color export error |
| Release JVM | 22 classes, 172 tests, zero failures/errors/skips |
| Selected Debug JVM, including the ten new routing tests | 10 classes, 67 tests, zero failures/errors/skips |
| Android native compilation | arm64 JNI and x86_64 Debug APK build successfully |

The initial host-JNI attempts selected an Android codec archive or an incomplete
RAW build, and the unversioned `clang++` command was absent. Clang also exposed
the existing test shim's incorrect C++ linkage for `kmp_set_blocktime`; the shim
now uses C linkage. These failed attempts are retained. The corrected host runs
use the Linux codec, matching RAW archives, and `clang++-14`. Read the final JNI
results in `aoidc/jni-final/`; the earlier combined `native.exit=1` is preserved
and is not an all-pass result.

The compiled snapshot checks match 161 app/test/workflow inputs in the Gradle
tree and 45 native inputs in the host tree. After transferring headers, their
timestamps were refreshed to force the existing x86 Ninja build to rebuild;
its final JNI SHA-256 is
`44183569e973e2e1f4acfe2e938ef6c473997361b76866d22de59461208b7565`.
The arm64 JNI SHA-256 is
`adaf8e74999ce916c5c0448af3ee80d88bcf8ee692937ab5ad427cf05151aa9f`.
These identify test builds, not a new release.

## Validation cost

Whole-frame inspection necessarily does more work than the old sparse grid.
With no other task from this validation running, the AOIDC GCC benchmark gave
the following best of three timings on 6709 x 4475 synthetic buffers:

| Validator/input | Old grid, ms | Complete validation, ms |
| --- | ---: | ---: |
| RGBA16 gradient | 46.04 | 45.96 |
| Float gradient | 2.55 | 119.44 |
| RGBA16 true-flat | 45.24 | 219.59 |
| Float true-flat | 2.20 | 237.92 |

The independent IBX run measured 7.95/29.54 ms for RGBA16/float gradients and
74.80/97.90 ms for true-flat buffers. Different CPUs and compilers make these
unsuitable for cross-cloud speedup claims. These are validator-only timings,
not end-to-end export times or phone benchmarks. The extra cost buys full
pixel coverage and finite-value checks without a new frame-sized allocation.

## Reproduction

All builds and executable tests for this change run in the cloud.

```sh
BUILD_DIR=/tmp/frame-rgba SANITIZE=1 bash tools/native/run_rgba16_frame_check.sh
BUILD_DIR=/tmp/frame-float SANITIZE=1 bash tools/native/run_float_frame_check.sh

UP=/path/to/patched-upstream B=/path/to/host-build \
RAW_BUILD=/path/to/matching-raw-build CODEC=/path/to/jpeg-prefix \
MATH_DIR=/path/to/generated-math VULKAN_HEADERS=/path/to/vulkan/include \
BUILD_DIR=/tmp/frame-jni bash tools/native/run_jni_frame_check.sh
```

Use `APP_CPP` with alpha-6's complete `app/src/main/cpp` for the JNI negative
control. Compile the two frame tests against that directory for their negative
controls. The source of the benchmark is `tools/native/frame_check_benchmark.cpp`;
compile it separately against the old and new headers with the same compiler
and optimization flags. Its output is validator time for synthetic buffers,
not end-to-end export time or a phone performance claim.

Raw results and exact input hashes are stored under
[`tools/native/validation/export-routing-20261113`](../tools/native/validation/export-routing-20261113/).

## Limits

The host JNI sanitizer build instruments the adapter translation units, not
the prebuilt engine, LibRaw or JPEG archives. Leak checking stays enabled in
the standalone frame tests. It is disabled for the JNI host run because of
the existing host Vulkan-driver limitation; this is not a claim of driver
leak coverage. Existing export cancellation and output-byte comparisons run
separately.

The earlier GCC 11 sanitizer diagnostic in pipeline recovery remains
unresolved. This change does not alter that engine code or classify the
diagnostic as a compiler bug. No new emulator or physical-phone acceptance
is claimed.
