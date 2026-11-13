# Owned LinearRaw transfer: cloud evidence

Co-created with GPT (OpenAI), 2026-11-13. Public synthetic DNG inputs only.
All compilation and executable tests ran on AOIDC or IBX; none ran locally.
Production is the pinned engine plus 58 overlays, LibRaw 0.22.2 plus 20.

`aoidc/inputs.sha256` records all engine/LibRaw overlays and the production,
test and CI inputs. The CI file is stored remotely as `engine-gpu.yml` and maps
to `.github/workflows/engine-gpu.yml`. All 94 local/cloud input hashes matched.
`ibx/inputs.sha256` records its separate complete LibRaw build inputs; its short
test filenames map to `tools/native/`. IBX's CMake files are isolated test
configuration, not new production dependencies. All 24 IBX manifest rows matched
the corresponding local source or archived test configuration.

`engine-replay-final.log`, `libraw-replay.log`, empty final source-diff logs and
`replay-final-summary.txt` describe zero-fuzz replay of the verified official
archives. `arm64-build.log` is the full 189-step build; `final-builds.log` includes
the corrected callback's rebuild. `arm64-final.sha256` is the final library.
The before/final SPIR-V manifests match all 56 files. A first comparison used
different `./` path prefixes; normalization changed paths only, not hashes.

`dispatch-final[-asan].log`, `working-final[-asan].log` and
`parity-corrected.{log,json}` are the corrected engine's results. The 200 frozen
cases include 196 successful byte-identical float and complete metadata
payloads plus four matching nonfinite rejections. Timings, pointers and object
padding are excluded. Working conversion covers 232 cases in each build.
`black-oracle.{log,json}` checks 48 cases against the independent mathematical
black/white formula, including all orientations and half-budget settings.

`move-final-asan.log` tests the real LibRaw API in 100 WB/crop combinations and
29 owner/layout/state guards. `ibx/final-tests.log` independently covers 96
combinations and those guards with GCC 14.2, after rebuilding the whole patched
LibRaw under ASan/UBSan. IBX does not claim production engine/JNI parity.
The AOIDC sanitizer build includes all LibRaw and IO; RelWithDebInfo appends
`-O2` after `-O1`, as recorded in `toolchains-final.log`.

`jni-final.log` and `jni-final-bayer.log` rebuild and execute the actual
production JNI translation unit with the corrected IO and real engine. Android
bitmap allocation alone is shimmed. Each run includes 12 working-image cases,
23 cancellation stages, cache/retry and concurrent-token checks. Unchanged
host engine/core/volk archives are reused; the ARM64 build recompiles the full
engine. These are not physical-phone or APK tests.

`measurements-final/` contains three alternating independent baseline/candidate
process pairs per mode, medians and output hashes. Baseline is 0057, candidate
is corrected 0058, with four workers, warm source cache and no concurrent
compilation. `measure.py` is the measurement driver. `phase-memory.log` is a
separate real LibRaw diagnostic, not the production color oracle. Full-float
measurement does not include the standard-development statistics scan.

`attempts/` retains early failures: an unavailable protected decoder symbol in
the test (replaced by a null-decoder rejection), a missing fixture argument,
JNI resources without compiled SPIR-V, and the important lossless/nonzero-black
frozen mismatch. The latter exposed a production callback classifying storage
by an already-consumed pointer. Engine 0058 now records storage type before
moving ownership; final results and all measurements were rerun afterward.

`ci-syntax-final.log` checks YAML and `bash -n` for 15 run blocks in two jobs.
It is not an online Actions result. See the
[research, implementation and limits](../../../../docs/ENGINE_LINEAR_RAW_BUFFER_MOVE_2026-11-13.md).

Retrieved evidence archive SHA-256 values, verified before extraction:

- AOIDC: `64b4753b0bfeb0a156373ca3ab99b55cccd9536311187f76939cbe9e7bc1b3ec`
- IBX: `aef4f9074c37490aced9ab3c468e89edc50bd18747cd61f359ee870ab5beca2b`
