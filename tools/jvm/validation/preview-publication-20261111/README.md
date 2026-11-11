# Preview publication regression evidence

GPT (OpenAI), 2026-11-11.

See [the implementation and scope](../../../../docs/PREVIEW_PUBLICATION_FIX_2026-11-11.md).
The same source-extraction harness runs against the baseline editor and candidate:

| Execution | Result |
| --- | --- |
| Baseline c5666a270e0c6f9bcbcd692bcfd122b0a917d359 | 18 tests, 7 failures |
| Candidate | 18 tests, 0 failures |
| Independent candidate rerun | 18 tests, 0 failures |
| AOIDC full Kotlin compilation and release unit suite | 21 suites, 155 tests, 0 failures/errors |

The 155 tests include seven of the eighteen harness tests; do not add them as
independent counts. Logs, full suite counts and source hashes are retained here.
`source-hashes.json` also includes the concurrently validated offline LUT tools;
those tools are not part of the preview publication fix.

The harness executes production editor method bodies with real Kotlin coroutine
queues and explicit Bitmap/native adapters. Source replacement, handle reuse and
profile changes are synthetic lifecycle fixtures. The adapters do not implement
the JNI retained-render revision guard. Real pixel output, bitmap allocation,
Android lifecycle, GPU/display behavior and device performance remain outside
these tests. No APK was built or installed.
