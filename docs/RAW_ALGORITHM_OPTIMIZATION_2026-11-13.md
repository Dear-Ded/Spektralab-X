# RAW algorithm optimization, 2026-11-13

Co-created with GPT (OpenAI). Baseline:
`12573927f6d9f4562843dc8673ebcb94ffa20353`.

## Result

The complete-source RAW exposure histogram now partitions large frames by rows,
up to eight workers, with a private integer histogram per worker. Small frames
below 500,000 pixels and single-row sources retain a serial scan. Integer bins
and sample counts merge before the existing trimmed log-mean calculation, so
worker scheduling cannot change exposure. Cancellation is reported after all
workers join, and a failed thread start leaves its unassigned rows to the
calling thread. At most seven extra 4096-bin histograms are allocated per scan;
their bin arrays total 224 KiB, in addition to counters and thread resources.

The standard RAW fallback tone curve has fixed contrast 1.5. Its existing
log-logistic function is algebraically `x^1.5 / (x^1.5 + A)`, where
`A = grey^1.5 * (1/grey - 1)` and `grey = 0.1845`. For `x <= 1`, the implementation
evaluates `x * sqrt(x)` directly. Above one it evaluates the reciprocal form
`1 / (1 + A * (1/x) * sqrt(1/x))`, avoiding overflow. This removes per-extremum
logarithm and exponential calls without an approximation table. Embedded PCHIP
curves, exposure limits, metadata validation, neutral bypass and the source
storage contract retain their existing behavior.

## Cloud A/B Measurements

AOIDC, Linux x86_64, GCC 11, `-O2 -ffp-contract=off`, synthetic 2048 x 1536 RGB
source (3,145,728 pixels). The probe uses the actual production implementation
from the baseline commit and the changed implementation. It alternates five
complete processes per variant; each process warms its scanner and both
development paths before timing them. No build runs during these measurements.
The development timings include the same output-buffer copy in both variants.

| Stage | Baseline Median | Candidate Median | Time Reduction |
| --- | ---: | ---: | ---: |
| Complete-source exposure statistics | 69.571 ms | 23.939 ms | 65.59% (2.91x throughput) |
| Standard fallback development | 732.495 ms | 577.359 ms | 21.18% |
| Embedded-profile development | 573.212 ms | 562.720 ms | 1.83%, treated as unchanged |

These are host stage measurements, not phone frame rate or end-to-end decode,
film rendering or export acceleration. Statistics samples range from
66.551-73.462 ms before and 17.034-28.311 ms after; all five individual timings
for every stage are retained in `verified-ab.json` and the raw CSV files.

## Numerical and Integration Checks

The old/new A/B dumps have bit-identical 4096-bin histograms and trimmed log
means. Each of the fallback and embedded plans produces 9,437,184 float channel
values; all are bit-identical between implementations on this fixture. The
UNORM16 difference is also zero. Dumps remain on the cloud host and are not
committed as binary fixtures.

The standalone production test expands from 9 to 12 groups. New coverage checks
serial-oracle histogram parity across padded, odd-height, threshold and
single-row sources, repeating each scan to exercise scheduling. A separate
327,680-pixel stress check compares the fallback against the original stable
log/exp formula over float magnitudes, negative values and five exposure settings
from -8 to +8 EV, with an absolute encoded-channel limit of `6e-8`. A cancellation
request during a large scan must leave the previously published statistics
unchanged. Existing grey-anchor, monotonicity, hue-ratio, embedded-curve,
metadata, strip/thread and disabled-bypass tests also pass.

Both GCC normal and Clang 14 ASan/UBSan builds pass the 12 groups. The production
output integration test passes 96 cases in each build: sRGB/P3, display/export,
8/16-bit, full-frame and reordered strips, spatial Layer 2, neutral/standard/
embedded plans, source immutability and cancellation recovery. ASan instruments
the app-side conversion and tests; the supporting pinned engine is Release,
and leak detection is disabled as in the existing host setup.

The first native runner stopped because the reused cloud checkout lacked the
test launch scripts. The upload was completed and the runner then passed;
the initial logs and timings are retained under `attempts/missing-runner`.

## Android Verification

The complete x86_64 Debug APK and ARM64 JNI library compile and link successfully
on the cloud host after numerical acceptance. All 37 native source inputs match
between the Android build checkouts. The 6 uploaded source/test/runner inputs
are checked before and after execution. The unchanged Kotlin Release suite
remains green at 28 classes / 212 tests; Gradle reports it `UP-TO-DATE`, so these
are cached results, not another newly executed suite.

| Artifact | SHA-256 |
| --- | --- |
| x86_64 Debug APK | `1037ed55f99c752f4fe22877b673e47a38ea84eea8ff33b1aa14fafd626543ae` |
| ARM64 JNI | `b7101c3029ee4b8aa1716220c1c8e76df117a707742352cdf91f07d0e1155e47` |

The public scripts, raw logs, CSV measurements, XML and verification summaries
are in `tools/native/validation/raw-algorithm-20261113`. Phone performance has
not been measured in this change.
