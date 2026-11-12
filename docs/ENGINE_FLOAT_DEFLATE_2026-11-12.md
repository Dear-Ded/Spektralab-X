# Precision-Preserving Deflate LinearRaw, 2026-11-12

Co-created with GPT (OpenAI). Continues research commit
`b7ec00e93972155e6f902bd96421ff0f20c0b03b`, reviewed against released alpha-3
`ed135c8c2bab72084ea529f66ec5e3539819a478`. Source development only: version
and engine/LibRaw pins stay fixed; no APK or release is produced.

## Confirmed Gap and Result

Alpha-3 enabled LibRaw's existing Deflate decoder. That allowed decoding but
did not admit compressed input to Android's precision-preserving float reader.
Even a file satisfying every other strict float LinearRaw requirement entered
the explicitly labelled `float_legacy_quantized` compatibility policy.

The strict reader now admits little-endian classic TIFF float32 chunky RGB
strips with Compression 8 and Predictor 3. It seeks each validated strip in
logical image order, feeds zlib from a 16 KiB input buffer, and restores the
floating predictor within one row. Byte-plane differences cross plane
boundaries with an RGB stride and reset at each row. Sample reconstruction
uses explicit byte order, independent of host endianness. This is an independent
implementation; no external decoder source is copied into the adapter.

The existing per-plane rational black/white normalization, original
AsShotNeutral, custom WB, prepared ForwardMatrix, orientation and scene-linear
ProPhoto conversion are unchanged. Signed values, adjacent float words and
above-white headroom pass through that same arithmetic. New RAW remains
neutral/linear by default; there is no automatic exposure or display look.

Previously supported uncompressed layouts retain their bytes. Newly supported
compressed files intentionally switch from the quantized compatibility policy
to `float_linear_v1`; they are compared to identical uncompressed float samples,
not to the old quantized output.

## Failure and Residency Contract

Metadata admission still precedes output allocation: at most 1024 disjoint
strips, SHORT/LONG offsets and counts, valid file ranges, no overlap with tags
or directories, bounded dimensions and the existing conservative float payload
admission. Compressed byte counts must be positive; decoded size is checked by
the row consumer rather than inferred from compressed length.

Every strip must end after exactly its declared rows, include a valid zlib
trailer/checksum and consume exactly its compressed byte count. Short output,
extra output, truncated/header/checksum failures, dictionaries, concatenated
streams and trailing bytes fail. Cancellation is checked between rows and
inflate/input steps; it does not interrupt a blocking system read. The candidate
is published only after all strips and the final cancellation check succeed.
Supported-but-corrupt streams do not retry through integer conversion, and
errors/cancellation preserve the caller's previous pixels and metadata.

Decoder scratch comprises one `12 * width` row, the 16 KiB compressed buffer,
bounded strip metadata and zlib's internal state/window. Old stream state is
released before initializing the next strip. The full oriented float RGB
output still resides in memory. This is not an end-to-end memory budget.
Android already links zlib; host float/dispatch runners now link it explicitly.

## Validation

Both hosts run the same production helper and dispatcher against a frozen
`b7ec00e` helper linked to the same decoder within that host. AOIDC uses the
current alpha-3 decoder build. IBX freshly replays all 17 repository LibRaw
overlays on a separate 0.22.2 tree and rebuilds decoder/image I/O archives with
zlib and OpenMP, replacing the earlier incomplete float-stream build for these
final checks. The decoder-only CMake project avoids Vulkan/resource builds.

| Check, per host | Result |
| --- | --- |
| GCC 11.4 AOIDC / GCC 14.2 IBX | 499 byte-identical compressed/control cases |
| Layouts | All 8 orientations, default/custom WB, 1/7/full/oversized rows, forward/reverse/shuffled storage, SHORT/LONG fields, compression levels 0/1/6/9 |
| Varied row widths | 32, 33, 65, 2048 and 8192; finite edge/subnormal/adjacent words |
| Independent LibRaw unpack | 23 raw float-word comparisons, byte exact |
| Independent matrix/orientation oracle | Maximum absolute error `2.384185791015625e-7`, tolerance `2e-6` |
| Helper cancellation | 279 inspect/decode boundaries |
| Production dispatcher cancellation | 253 boundaries, no fallback or partial publication |
| Bad streams/layouts | 15 explicit rejections, including corruption in the final strip |
| Retained input stream | Truncation and path replacement injections pass |
| Strip admission | 1024 accepted; 1025 rejected before output allocation |
| Existing uncompressed strip suite | 300 byte comparisons, 124 cancellation boundaries and 15 malformed cases |
| Existing helper/dispatcher controls | Pass; historical maximum error `4.76837158203125e-7` |

AOIDC also runs ASan/UBSan with leak detection disabled. Existing decoder
archives and system zlib are not instrumented. IBX reruns the explicit-check
Deflate suite with Python optimization enabled. These are distinct validations,
not counts to combine into a single pass rate.

Actual `spektralab_jni.cpp`, with the candidate header and original Android
build flags, compiles using NDK `27.2.12479018` for AArch64/API29. It produces
an object, not an APK or device test. No Adreno/Mali, display, thermal or album
validation is implied.

## Full-Resolution Memory Control

Separate native processes, started after the fixture writer exits, decode
synthetic 4096 x 3072 single uncompressed and reverse-ordered 64-row compressed
strips. Three alternating runs per host yield the identical full-output hash
`230b6b1056805b83`:

| Ordinary build | Uncompressed peak RSS | Deflate peak RSS |
| --- | --- | --- |
| AOIDC | 152888-153052 KiB | 153104-153176 KiB |
| IBX | 152692-152840 KiB | 152872-153040 KiB |

The output alone is 150994944 bytes (144 MiB). Total RSS remains about 149 MiB;
this cannot satisfy a 128 MiB process limit. The comparison shows bounded
compressed input residency rather than another whole decompressed input plane.
No speed improvement or phone performance claim is made. Sanitizer RSS is
retained separately and is not used in this table.

## Reproduction and Limits

See [evidence and replay commands](../tools/native/validation/float-deflate-20261112/README.md).
The validated production header SHA-256 is
`f2a30d40fc679b78c84bc7e991c5d26f229bb2a22d147d279e4924d4be9534d9`.

Still outside this strict reader: tiling, predictors 34894/34895, float16/24,
float Bayer demosaicing, multiple IFD/profile selection, arbitrary crop,
opcodes/calibration/profile tags and HDR development. Unsupported layouts
retain explicit compatibility labelling. This change does not establish
camera JPEG/Lightroom appearance equivalence or explain a particular private
camera file. Private originals, pairings, measurements and derived images stay
outside the repository. Spectral film formulas, LUT experiments and bounded
print-tail defaults are unchanged.
