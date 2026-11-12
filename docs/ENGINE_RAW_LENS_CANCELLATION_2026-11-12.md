# RAW Lens Correction Cancellation, 2026-11-12

Co-created with GPT (OpenAI). Continues research parent `e1fc2d1` on
`codex/engine-android-review-20261111`. The upstream pin remains
`a46627d250545a394ca21522f8a28b0fb89e4c42`; LibRaw remains patched 0.22.2.
This is source development, with no version change, APK or release.

## Confirmed Gap

The compatible RAW decoder forwards cancellation into LibRaw and OpcodeList2
GainMap processing. Its `CameraLibRaw::opcode_list3` callback also executes
WarpRectilinear and FixVignetteRadial after demosaicing, before camera-to-output
conversion. Those full-image lens loops did not poll cancellation. A request
arriving there waited for that correction to finish before the next check.

The regression compiles the frozen pre-0050 header and actually invokes the
LibRaw callback. Nonidentity warp, vignette and combined lists all report zero
internal checks. Requiring lens cancellation makes that baseline fail; the
failure is retained in `baseline-gap.log`.

## Change and Ownership

Patch `0050-opcode-list3-cancellation.patch` passes the existing
`RawDecodeProgress` from the compatible decoder into its camera callback.
It checks list parsing, operation and channel boundaries, each 65,536 samples
of temporary-channel copying, and each warp/vignette row. OpenMP workers only
poll the serialized, sticky cancellation state. The initiating thread throws
`RawDecodeCancelled` after the workers join.

The progress object outlives the decoder. Cancellation may partially modify
the decoder's private camera image, which is discarded. `DecodedRaw` and JNI
publish only completed candidates, so no partial result replaces a previous
image. Existing callers without a callback use the optional null progress
argument; the headroom entry still has no new cancellation API.
`CameraLibRaw` gains a private progress pointer, so consumers of that internal
C++ header must be rebuilt together. LibRaw's own ABI and public JNI names
are unchanged; the Android compilation below rebuilds both consumers.

Lens coefficients, interpolation, clipping, matrix preparation, white balance,
crop/orientation policy and half-size selection retain their existing
arithmetic. RAW stays neutral/linear by default; this change adds no exposure,
saturation or display look. It does not add support for other opcode IDs.

The existing cancellation and precision probes also now compare all three
`ProfileDynamicRange` metadata fields added in 0049. Those comparisons had
omitted the fields; production metadata itself is unchanged here.

## Verification

AOIDC and IBX independently replay all 50 engine patches on the fixed upstream
archive with `--fuzz=0`. The archive SHA-256 on both hosts is
`52ef254ffa7c70484a411c269f3f837f75d5a215ed7332d5fdab267317408f05`.
Public evidence contains synthetic inputs' logs and hashes only:
[validation directory](../tools/native/validation/raw-lens-cancel-20261112/README.md).

Each host checks:

- 80 production pixel and complete-metadata pairs against the previous decoder:
  integer Bayer/LinearRaw, warp/vignette/combined/identity/malformed lists,
  orientations 1/6, full/half-size requests and as-shot/custom WB.
- 852 helper/camera cancellation positions across one and four OpenMP workers:
  warp 161, vignette 53 and combined 212 positions per worker configuration.
  A callback exception inside worker processing becomes cancellation; a retry
  reproduces the frozen pixels and the unused fourth channel stays intact.
- Eight production-decoder cancellation/retry runs, enumerating 1,674 ordinal
  positions with one decoder worker. False callbacks preserve pixels and all
  metadata; cancellation preserves the caller's previous result and OpenMP
  policy. Parallel callback cadence is not a portable fixed count.
- The original upstream lens tests still pass malformed, no-op, vignette and
  warp cases. Identity/malformed fixtures retain the previous behavior.

AOIDC additionally instruments the helper, camera callback and production
adapter with ASan/UBSan. Existing LibRaw, engine and system libraries are not
sanitizer-instrumented; leak detection is disabled. The actual JNI harness
uses the newly built image-IO/LibRaw archives and the existing unchanged core,
engine and Volk archives. It exercises a nonidentity, rotated Bayer fixture:
old/cancellable ABI bytes, 6 integer and 6 fallback cancellation stages,
11 precise-float stages, public token cancellation from another thread,
candidate cleanup, previous-session/cache reuse and retry all pass.

The first JNI run selected the source resources directory, which lacks generated
SPIR-V, and failed engine creation. The successful rerun uses the matching
host build's generated resources; both logs are retained.

The precision dispatcher regression also passes under ASan/UBSan with its
expanded metadata comparison: 17 float, 3 custom-float, 5 fallback and 4 integer
cases, plus 77 cancellation positions. Independent float reference error is
`4.76837158203125e-7`, below the existing `2e-6` tolerance.

The actual production RAW decoder and JNI translation units compile with NDK
27.2.12479018 for AArch64/API 29. This is object compilation, not an APK,
complete Android link, device install or Adreno/Mali acceptance.

## Reproduction and Limits

```bash
UP=/path/to/upstream-with-0050 OLD_UP=/path/to/upstream-through-0049 \
  B=/path/to/current-decoder-build OLD_B=/path/to/frozen-decoder-build \
  FIXTURES=/path/to/synthetic-integer-fixtures BUILD_DIR=/path/to/results \
  bash tools/native/run_opcode_list3_cancellation.sh
```

Set `SANITIZE=1 ASAN_OPTIONS=detect_leaks=0` for the adapter instrumentation.
Generate the two source fixtures with `raw_precision_input_fixtures.py`.
The runner builds the candidate adapter directly and reuses supplied matching
LibRaw archives; it does not rebuild the entire rendering engine.

Cancellation remains cooperative. In-flight library operations, allocations,
one active row, resize/statistics and GPU operations can finish before a check.
There is no measured phone latency guarantee or new whole-pipeline memory
bound. Output compatibility does not establish camera-JPEG color matching,
full DNG-renderer conformance or correctness of every existing lens formula.
