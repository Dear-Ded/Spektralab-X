# RAW precision and GainMap evidence

Co-created with GPT (OpenAI), 2026-11-10. Synthetic images only. See
[the source adaptation report](../../../../docs/RAW_PRECISION_CANCELLATION_2026-11-10.md).

| Record | Scope |
| --- | --- |
| `float-custom-{normal,sanitized}.log` | AOIDC GCC 11 helper, production dispatcher and independent custom WB oracle |
| `ibx-custom-{normal,sanitized}.json` | IBX GCC 14 helper custom WB results; these are not full dispatcher runs |
| `decoder-{normal,sanitized}.log` | Actual compatible decoder with 0037/0038; eight frozen-old pixel and complete non-timing metadata comparisons, cancellation positions, callback exception, retry, OpenMP restoration |
| `gain-original-final.log` | Original 0037 implementation: six in-memory and five actual-DNG boundary assertions fail; retained as before-fix evidence |
| `gain-fixed-{normal,sanitized}.log` | Fixed GainMap header, exact control pixels, oversized-list rejection, large row/column pitch, actual LibRaw-parsed synthetic DNGs; synthetic invalid API black-pattern state is also checked |
| `ibx-gain/{normal,sanitized}.log` | Same fixed GainMap tests with GCC 14 and the matching patched LibRaw archive |
| `gain-fixed-replay.log` | Initial 0038 patch replay; line offsets were subsequently corrected without changing its resulting source |
| `engine-0038-replay.log` | All 38 engine patches replayed with zero fuzz against the pinned verified source archive |

The compatible-decoder tests enumerate callback positions with one LibRaw
worker, because callback counts can depend on scheduling with multiple workers.
The GainMap test executes worker cancellation and separately invokes the shared
callback state from eight std::threads, checking serialization and sticky cancel.
The first unrestricted enumeration encountered a count mismatch; setting the
test worker policy made the ordinal assertions reproducible. No production
output or callback policy was modified to make that assertion pass.

For decoder sanitization, both the test translation unit and actual
`raw_decoder.cpp` are instrumented. GainMap is an inline header compiled into
its instrumented probe. Existing LibRaw and other linked archives are reused,
not rebuilt with sanitizers. The corresponding limitations also apply to the
separate [production JNI evidence](../raw-open-cancellation-20261110/README.md).

The invalid black-pattern dimensions are an artificial API state. Pinned
LibRaw bounds the SHORT dimensions parsed from a DNG; this test does not prove
ordinary DNG files can produce that state. The oversized-list and extreme-pitch
issues are separately reproduced through actual synthetic DNG open/unpack.

Reproduction, with a patched upstream and existing archives:

```sh
UP="$upstream" B="$build" BUILD_DIR="$scratch/wb" \
  BASELINE_FLOAT_PROBE="$old_helper" bash tools/native/run_float_custom_wb.sh
UP="$upstream" B="$build" OLD_B="$frozen_old_build" \
  FIXTURES="$generated_integer_fixtures" BUILD_DIR="$scratch/cancel" \
  bash tools/native/run_compatible_raw_cancellation.sh
UP="$upstream" B="$build" BUILD_DIR="$scratch/gain" \
  bash tools/native/run_gain_map_boundary.sh
```

Add `SANITIZE=1` for the instrumented variants. The GainMap runner uses
`UBSAN_OPTIONS=halt_on_error=1 ASAN_OPTIONS=detect_leaks=0` in this recorded run.
No APK build, installation, release, phone-driver acceptance or complete
out-of-core pipeline is established by these host tests.
