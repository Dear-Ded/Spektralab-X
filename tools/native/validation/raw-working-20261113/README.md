# RGB16 working conversion: AOIDC evidence

Co-created with GPT (OpenAI), 2026-11-13. Public synthetic inputs only.
No private RAW, photograph, credentials or local handoff is included.
All compilation/execution took place on the existing AOIDC cloud host.

`aoidc/inputs.sha256` identifies the production adapter, overlay, test helpers
and CI input. The native source is the pinned engine plus 56 overlays, with
LibRaw 0.22.2 plus 18 overlays. `replay-final.log` records zero-fuzz replay and
source equality; GNU patch backup files are excluded from comparisons.
`arm64-final.log` / `arm64.sha256` record a complete API29/NDK27.2 ARM64 JNI build.
The 56 before/after SPIR-V hashes match.

The final RGB16 tests check 520 cases / 15,211,650 exact float values against
the frozen oracle, all integer codes, unaligned input, invalid layout and
cancellation. `working-verified.log` covers 11 real synthetic decoder fixtures,
97 policy/size/custom-WB combinations, eight orientations and converter failures.
`working-asan-final.log` instruments the changed decoder and adapter, not the
entire supplied LibRaw archive. `dispatch-results.json` retains the independent
float oracle and old-route whole metadata/pixel regressions.

`jni-verified.log` and `jni-rotated.log` each use the production JNI translation
unit and real engine/decoder; only Android platform bitmap allocation is shimmed.
Each checks 12 new working-input cases, complete standard statistics with AE
disabled, source dimensions, binning, old/new ABI, 23 cancellation checkpoints,
concurrent token, candidate cleanup, old cache and retry.

Three alternating pairs in the final CSVs run in independent processes without
concurrent compilation. Stage timing follows production bitmap ownership: the
old path releases RGB16 after conversion and before area resize. The result
summary uses medians. The complete decoder's peak RSS is essentially unchanged;
the lower conversion-stage RSS must not be described as a whole-pipeline gain.
This is warm-cache cloud CPU evidence, not physical Android performance or a
new camera-sample acceptance test. IBX remained in cooldown and was not used.

The initial shared-dependency configuration and preliminary benchmark remain
in the cloud task directory for diagnosis. Only final successful build/test
results and the corrected ownership benchmark are selected here.
`ci-syntax.log` checks YAML and shell run blocks; it is not an online Actions run.

See [implementation, results and limitations](../../../../docs/ENGINE_RAW_WORKING_CONVERSION_2026-11-13.md)
and [external primary-source research](../../../../docs/ENGINE_EXTERNAL_RESEARCH_2026-11-13.md).
