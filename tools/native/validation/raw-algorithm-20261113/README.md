# RAW algorithm optimization evidence, 2026-11-13

Co-created with GPT (OpenAI). Baseline:
`12573927f6d9f4562843dc8673ebcb94ffa20353`.
Details: `docs/RAW_ALGORITHM_OPTIMIZATION_2026-11-13.md`.

All executable work ran on AOIDC in `/data/work/gpt-raw-algorithm-20261113`.
`aoidc/run-native.sh` compiles actual baseline and candidate production code,
alternates five warmed A/B processes per variant, then runs normal and
ASan/UBSan native regressions. `run-build.sh` checks numerical acceptance before
building the full x86_64 Debug APK and ARM64 JNI. No build overlapped timing.

The generated `verified-summary.json` and underlying logs establish:

- Histogram and trimmed mean are bit-identical on the 3,145,728-pixel fixture.
- Both fallback and embedded outputs are bit-identical across 9,437,184 channels
  per plan, with zero UNORM16 difference.
- Statistics median: 69.571 -> 23.939 ms, 65.59% less time.
- Fallback development median: 732.495 -> 577.359 ms, 21.18% less time.
- Embedded development median: 573.212 -> 562.720 ms, treated as unchanged.
- Normal and ASan/UBSan builds each pass 12 native groups and 96 output cases.
- Full x86_64 APK and ARM64 JNI compile/link successfully; identities are in
  `apk.sha256` and `arm64.sha256`.
- The unchanged Kotlin Release suite is `UP-TO-DATE`: 28 classes / 212 passing
  tests are retained cached results, not a newly executed suite.

`verify-ab.py` checks actual binary output dumps, float/UNORM16 error bounds,
and stage improvement gates. `verify-summary.py` additionally checks exits,
test markers, cached JVM reports and Android build results. Large synthetic
binary dumps and compiled artifacts stay on the cloud host.

`source.sha256` covers 6 uploaded source/test/runner files, including the 3
changed C++ inputs. `native-inputs.sha256` covers all 37 native source inputs;
the ARM64 and Gradle native source directories match. `baseline.sha256` covers
the 3 files extracted from the baseline commit. All 46 manifest comparisons
match local source or the local baseline archive; cloud inputs are checked
before and after execution.

The initial runner stopped because test launch files were absent from the
reused checkout. `attempts/missing-runner` preserves its exit, log and initial
CSV measurements. After completing the upload, all native and build checks
passed. An SSH transfer reset was retried normally.

The downloaded evidence archive is 19,362 bytes with 91 unique paths. SHA-256:
`91109828880e05c26fe2f384b323620123175699cdcae4a8bc21469e994191ca`.
Checksum, traversal, duplicate and link checks passed before extraction.
Logs retain their original whitespace. These are host measurements and
synthetic numerical checks, not phone frame-rate measurements.
