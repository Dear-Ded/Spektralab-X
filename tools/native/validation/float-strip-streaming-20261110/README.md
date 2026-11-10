# Strict float LinearRaw strip streaming

The existing bounded float LinearRaw path keeps LibRaw open/calibration, then
reads the already validated uncompressed strip one row at a time. It avoids
LibRaw's full-frame `float3_image`; the output remains a full scene-linear
ProPhoto float buffer. This is not general RAW tiling.

The format and admission limits remain unchanged: little-endian classic TIFF,
one IFD, one uncompressed chunky RGB float32 strip, full active area, at most
16,777,216 pixels and a conservative 402,653,184-byte input-plus-output payload
limit. Signed finite samples, HDR values, white balance, matrix arithmetic and
orientation retain their previous behavior. A short read fails atomically and
does not request a legacy retry. Cancellation is polled at each row.

## Frozen comparison

The pre-stream header SHA-256 is
`bd9dee58cd7f29778b535556e68c8b4f6128aec178deb0efa6352e6c48b36b35`.
Run `tools/native/run_float_dng_streaming.sh` with `UP`, `B`, `BUILD_DIR` and
`BASELINE_INCLUDE` pointing to the patched source/build and frozen header
directory. `MEMORY=1` adds a separate-process synthetic large input;
`SANITIZE=1` instruments the test and inline helper with ASan/UBSan. Linked
upstream static libraries are not instrumented by this runner.

On the GCC 11 host, normal and ASan/UBSan runs passed 42 full-pixel byte
comparisons across default and two custom white balances. Fourteen default-WB
cases also compare every decoded public metadata field, including both
matrices, source dimensions/orientation, make, model and decoder name.
The fixtures include all orientations, per-plane/fractional ranges, non-unit
neutral gains, signed zero, subnormal values and adjacent float32 values.
Short-read failure preserves caller output; replacing the source pathname
after validation still reads the retained original descriptor.

The unchanged helper/dispatcher/custom-WB suites also passed. Their cancellation
counts are measured for the current implementation, rather than required to
match the old unpacker's progress-callback count. See `fullcustom-0038.log`.

## Measured memory

A synthetic 4096 x 3072 RGB input produces 150,994,944 output bytes. Frozen and
streamed output files compare equal with `cmp`; both SHA-256 values are
`51ff2c79788e5bf559cef27364f6cac3237db0b3611d97dec3d4ab249dd58f96`.

Three alternating independent process runs measured `/usr/bin/time` maximum
RSS (KiB):

| Run | Frozen unpack | Row streaming |
| --- | ---: | ---: |
| 1 | 300192 | 152900 |
| 2 | 300176 | 152940 |
| 3 | 300220 | 153012 |

This host measurement demonstrates approximately 144 MiB less peak residency
for this strict subset. It is not a whole-process memory budget or an Android
device measurement. The process includes LibRaw metadata/calibration and the
full output allocation, but not a Vulkan engine or an imported editor session.
Timing was recorded alongside RSS during other compilation activity; it is
not a controlled mobile performance benchmark.

## Reproduction limits

All inputs here are generated synthetic fixtures, not photographs. The first
wide fixture used height 16 and was rejected by the frozen LibRaw opener;
height 64 fixes the fixture. The initial failure log is retained. The final
formal JNI checks use the production JNI source and a host Android allocation
shim, with the clean 0038 upstream build. This does not validate Android
Bitmap/Skia, URI providers or device GPU behavior.

The JNI runner accepts `RESOURCES`. This run uses the existing generated
resources at
`/data/work/gpt-optimize-wave2-20261109/raw-cancel-20261110/build/engine/resources`;
its constants file compares byte-equal to the clean 0038 upstream constants.
The clean build's source resource directory lacks generated Vulkan SPIR-V, so
using that directory alone cannot run this harness. The JNI tests instrument
the JNI translation unit, app CPU conversion code and allocation shim; the
reused engine, core, LibRaw and codec static libraries remain uninstrumented.
They exercise 11 precise-float cancellation stages, six integer stages and six
legacy-float stages, explicit cancellation classification, prior-cache
preservation, retry and targeted wrapper-allocation failure. These are host
checks, not Android device acceptance.

## Final integration records

The root task's final Android ARM64 native build (including the JNI wrapper
allocation fix) completed successfully. Debug and release build logs, exported
JNI symbols, JUnit XML and full-source verification records are included here.
The copied debug XML contains 8 suites / 53 tests; release XML contains 14
suites / 98 tests. Both have zero failures, errors or skipped tests. The final
Gradle logs report these unit-test tasks as `UP-TO-DATE`; these XML records are
retained results, not a claim that all tests executed again in that last build.

Full-source verification reports a zero-fuzz replay of 38 engine patches and
16 LibRaw patches, comparing 93 engine and 22 LibRaw source files. These are
source/build checks and do not constitute an APK or device validation.
