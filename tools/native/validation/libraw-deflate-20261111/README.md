# LibRaw Deflate floating DNG dependency evidence

Co-created with GPT (OpenAI), 2026-11-11. Public synthetic fixtures only.

On AOIDC (Linux x86_64, GCC 11.4.0), two clean complete builds used the same
LibRaw 0.22.2 source and the same 16 project LibRaw patches. The sole feature
difference was `USE_ZLIB` plus the zlib link. All six cases passed their expected
outcomes. This is a host decoder test, not an Android device or APK test.

| Synthetic DNG | Baseline unpack/process | zlib build | Equality checked |
| --- | --- | --- | --- |
| 32-bit float Bayer, uncompressed | success | success | old/new raw and processed bytes |
| 32-bit float Bayer, Deflate/Predictor 3 | -100008/-100008 | success | raw equals generated float32; processed equals uncompressed |
| 32-bit float LinearRaw RGB, uncompressed | success | success | old/new raw and processed bytes |
| 32-bit float LinearRaw RGB, Deflate/Predictor 3 | -100008/-100008 | success | raw equals generated float32; processed equals uncompressed |
| 16-bit integer Bayer, uncompressed | success | success | old/new raw and processed bytes |
| 12-bit integer Bayer, lossless JPEG | success | success | old/new raw and processed bytes |

All fixtures are 64 x 48. Floating cases preserve signed values, values above 1,
and adjacent representable float values on unpack. A second decoder instance
uses the engine's normal 16-bit linear ProPhoto processing parameters for the
processed-byte comparisons. Capability bit 64 changes from absent to present.
`results.json` records the real status codes and SHA-256 digests. Its `memory`
field is only meaningful when `process` succeeds; allocation was not attempted
after the expected baseline Deflate failures.

## Root cause and minimal integration

Linking `z` into the final JNI shared library does not select a preprocessor
branch while compiling LibRaw. The pinned first-party implementation guards
`deflate_dng_load_raw()` with `USE_ZLIB`; the fallback throws a decode exception.
The minimal Android integration is to add `USE_ZLIB` to `spk_libraw` compile
definitions and attach `z` as a PUBLIC link dependency of `spk_libraw`. The
existing JNI link may remain. The project CMake change is maintained separately
from this test harness.

First-party source accessed by successful HTTPS download on 2026-11-11:

- https://raw.githubusercontent.com/LibRaw/LibRaw/0.22.2/src/decoders/fp_dng.cpp
  - SHA-256 `db7e17ba960be72f16ac3fa2751398addc3a966757ddd1f685a73230380073b2`.
  - Lines 328-329 select the real decoder; line 380 restricts this decoder to
    floating samples; line 479 contains the disabled implementation.
  - In the tested project-patched file these are lines 340-341, 392, and 491;
    the difference is the existing DNG tile-stream hardening patch.
- `src/utils/utils_libraw.cpp`, tested source lines 221-222, exposes
  `LIBRAW_CAPS_ZLIB` only with the same macro.

The source archive is the project's pinned LibRaw 0.22.2 archive, SHA-256
`02275a04cf0d1477ab9fd7ee231ddea59e7b14497458da6e3ba957517d6655c7`.
The patch and compiled artifact fingerprints are in `sha256.txt`.

## Reproduce

Use the project's prepared LibRaw 0.22.2 source with its complete LibRaw patch
set applied. The probe requires CMake, a C++20 compiler, Python 3.9+, OpenMP and
zlib development files. From the repository root:

```bash
LIBRAW_SOURCE=/path/to/patched/LibRaw-0.22.2 \
BUILD_DIR=/path/to/dedicated/deflate-probe \
JOBS=4 bash tools/native/run_libraw_deflate.sh
```

The generator reuses `dng_float_precision_fixtures.py`; all pixels and metadata
are generated locally. The runner builds separate baseline and candidate
archives, creates the six DNGs and asserts every status and byte comparison.

## Support boundary

This proves the missing compiled decoder for these two legal floating Deflate
inputs and unchanged output for four adjacent controls. It does not establish
all predictor, tiling, bit-depth, malformed-input, camera or Android runtime
combinations. The integer compressed control uses lossless JPEG, not integer
Deflate: this LibRaw Deflate routine explicitly accepts floating samples only.
No RAW development, exposure, saturation, white balance, tone or color-space
defaults change. This defect has not been established as the cause of any
user-provided photograph's appearance. No private sample or measurement is
included.
