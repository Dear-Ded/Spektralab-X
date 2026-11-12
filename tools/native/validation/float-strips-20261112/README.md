# Float32 LinearRaw Strips

Co-created with GPT (OpenAI), 2026-11-12. Synthetic fixtures only.

Baseline: `ed135c8c2bab72084ea529f66ec5e3539819a478` (alpha-3).
Baseline helper SHA-256:
`37e6f8f4a5f15787a4bc82b8bc91f12181f460bb5c07b93e9bd25e020f77bce0`.
Candidate helper SHA-256:
`2209e275098210b0150defec79c561cd78f573caf210f41e866f8654e54e5bd2`.

`aoidc/` and `ibx/` contain separately compiled GCC 11/GCC 14 results, source
hashes, old-helper failures/new-helper successes and small public evidence
records. No DNG fixture, private photograph, APK, object file or credential is
included. Decoder libraries are reused rather than rebuilt by this runner;
AOIDC uses the current patched archive, IBX the prior float-stream archive.
The comparison always uses the same archive for baseline and candidate.

```bash
UP=/path/to/patched/upstream B=/path/to/build \
BUILD_DIR=/path/to/dedicated/tests \
BASELINE_INCLUDE=/path/to/frozen/alpha3/header/directory \
MEMORY=1 bash tools/native/run_float_dng_strips.sh
```

Use `SANITIZE=1` and `ASAN_OPTIONS=detect_leaks=0` to instrument probes and the
inline helper; the linked archive remains uninstrumented. Compilation uses four
independent workers on the two 4-core/8-GiB hosts. `MEMORY=1` writes larger
synthetic inputs and starts the separate, small measurement process after the
fixture writer exits, avoiding inherited fork/exec peak-RSS contamination.

Both hosts pass 300 byte-identical cases, 124 cancellation boundaries, eight I/O
mutations and 15 malformed cases, plus the unchanged historical helper suite.
AOIDC's final sanitizer matrix and actual Android JNI ARM64/API29 compilation
pass. IBX also runs the strip test with Python optimization enabled.

See [the implementation report](../../../../docs/ENGINE_FLOAT_STRIPS_2026-11-12.md)
for the exact format limits, measured memory ranges and remaining work.
