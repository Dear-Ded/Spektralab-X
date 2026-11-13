# Exact order statistics: cloud evidence

Co-created with GPT (OpenAI), 2026-11-13. All compilation and executable tests
ran on AOIDC. Inputs are synthetic numeric vectors and generated GPU images;
no private media, derived private files or handoffs were uploaded.
Baseline is the pinned engine plus 58 overlays; candidate adds only 0059.
The existing 20 LibRaw overlays are unchanged.

`aoidc/inputs.sha256` records all 59 engine and 20 LibRaw patches, four new
test/runner files and the CI workflow. All 84 input hashes matched the local
working tree. `production-sources.sha256` records the 148 tested production
source files. Patch-generated `.orig` and `.rej` backup files are excluded.

`engine-replay-final.log`, `official-pin.sha256` and `replay-summary.txt` prove
zero-fuzz replay from the verified official source archive. `ci-reverse-patch.log`
also verifies that reversing only 0059 exactly restores frozen 0058 source and
reapplying restores the candidate. An early comparison included historical
`.orig` files and failed on backups; actual production files were identical.
This bookkeeping failure and a missing `rg` during toolchain collection are
retained in `attempts/`; final collection used `grep`.

`release.log`, `focused-final.log` and `clang.log` each record 1,460 production
vector cases, independent full-sort rank/median checks and frozen/candidate
bit parity. The corresponding `*-candidate.csv` contains all output rows and
`*-outputs.sha256` records both equal CSV hashes. The three configurations are
GCC 11.4 O2, GCC 11.4 ASan/UBSan and NDK Clang 18.0.3 targeting Linux x86_64.
Each compares its own old/new result; no cross-compiler bit-parity claim is made.

The sanitizer runner compiles the actual changed pipeline and latitude
translation units plus the test. Release engine/core/volk archives supply
otherwise unused stage-table dependencies. This does not instrument the entire
engine. `focused.log` and `attempts/focused-support-link.log` retain the first
link failure without those support archives; `focused-final.log` is the passing
corrected run. No production math changed to fix that harness issue.

`host-build.log` and `frozen-host-build.log` are two separate clean 85-step
Release engine/resources builds. `gpu.log` records 48 real film/print cases:
normal and forced-split math, seven meters plus AE-off, and three synthetic
scenes. `gpu-auto.csv` and `gpu-split.csv` include full/strided and applied EV
bits. `gpu-outputs.sha256` records both sides of complete negative/final float
payloads and CSVs; both were byte-compared successfully. Binary pixel payloads
remain outside Git. Fixture optical/noise effects are disabled to isolate AE;
production defaults are unchanged.

`arm64-build.log` is the full 189-step NDK 27.2/API29/arm64-v8a JNI build and
`arm64.sha256` identifies the library. The three SPIR-V manifests contain the
same 56 hashes for frozen host, candidate host and candidate ARM64 resources.
These are cloud builds and renders, not a phone, APK or mobile-performance test.

`measurements/` contains three independent alternating baseline/candidate
process pairs per stage and distribution, with 30 calls per process and
65,536 samples per call. `measure.py` is the exact measurement driver;
`performance.json` and `measurements/summary.json` contain the same medians.
All six checksums per stage/distribution match. Measurements use non-sanitized
GCC O2 probes, include the original per-call sample copy/allocation and ran
without concurrent compilation. They measure CPU statistics, not full export.

`ci-syntax.log` checks YAML, all 16 run blocks in two jobs and both new runner
scripts with `bash -n`. It is not an online Actions result. `toolchains.log`
records compilers and build settings; `final-status.json` summarizes scope.
`package_evidence.py` is the collection/replay script, not a production dependency.

The retrieved archive SHA-256 was verified before extraction:
`a3fe5b22fafc60b778e7d75f7ba3a8e84ccb416c52afce5468ed922e2d61c123`.
See [external algorithms, implementation and timing](../../../../docs/ENGINE_ORDER_STATISTICS_2026-11-13.md).
