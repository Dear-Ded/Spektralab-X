# RAW storage lifetime: AOIDC evidence

Co-created with GPT (OpenAI), 2026-11-13. Public synthetic inputs only.
All compilation and executable tests ran on AOIDC; IBX was not used this round.

Production source is the pinned engine plus 57 overlays, LibRaw 0.22.2 plus 19.
`aoidc/inputs.sha256` matches the local production/test/CI inputs byte for byte.
`engine-replay.log`, `libraw-replay.log` and empty source-diff logs record clean
zero-fuzz replay against the verified official archives. `arm64-build.log`
records the complete 189-step NDK27.2/API29 build. Both SPIR-V manifests match.

`normal-tests.log` covers the first 20 direct LibRaw lifetime cases and 97
working cases. `asan-tests.log` repeats those with the entire LibRaw and IO
instrumented, not a prebuilt uninstrumented LibRaw archive. `final-asan.log`
adds 16 Bayer lifetime and 72 working cases, then the complete precision suite.
The sanitizer build is RelWithDebInfo; its trailing `-O2` takes precedence over
the earlier `-O1`. See `toolchains.log`; no O1-only claim is made.

`parity-final.log` and `parity-results.json` compare separately linked frozen
0056/candidate 0057 executables. `parity-bayer-results.json` adds all Bayer
orientations: 168 cases, 164 successful byte-identical pixel and field-by-field
metadata payloads, four unchanged nonfinite rejections. Timing, padding and
pointers are excluded. Output files remain synthetic cloud test artifacts.

`jni.log` contains actual production JNI translation-unit tests with real
engine/decoder, once with Bayer and once with rotated LinearRaw. Android bitmap
platform allocation alone is shimmed. The host reuses the unchanged engine,
core and volk archives from 0056, with new IO/LibRaw archives. The Android build
recompiles the complete engine and LibRaw.

`decode-memory.csv`, `bayer-memory.csv`, `full-float-memory.csv` each alternate
three independent frozen/candidate process pairs with four workers and no
concurrent compilation. `memory-summary.json` contains medians and units.
`phase-memory.log` is a separate real LibRaw ownership diagnostic; its RGB16
hash is not the production adapter's color oracle. Timing does not improve
uniformly. These are warm-cache cloud measurements, not phone acceptance.

`parity.log` retains an initial failure: count-based cancellation used the
callback total from a prior multiworker run. LibRaw AHD reports progress only
from worker 0 inside a dynamically scheduled tile loop, so the number of these
callbacks can vary. The runner now uses one worker for this indexed suite; concurrent
and late-output cancellation remain covered by working and JNI tests. The
production cancellation contract was not weakened to make a callback count fit.

`ci-syntax.log` parses YAML and checks all 15 run blocks with `bash -n`.
It is not an online Actions result. See the
[implementation and limits](../../../../docs/ENGINE_RAW_STORAGE_LIFETIME_2026-11-13.md).
