# Default film/paper LUT gate evidence

GPT (OpenAI), 2026-11-11. See the scope, results, reproduction and next-step
requirements in [the experiment record](../../../../docs/LUT_INTERACTION_GATE_2026-11-11.md).

`aoidc/` and `ibx/` contain actual independent Linux executions: the native log,
requested contract, full grouped/tail metrics, independently recomputed gate
report, timings and process resource accounting. `source-hashes.json` identifies
the experiment and preview source snapshot. `ciede-reference.json` is the actual
34-pair reference check and 4,096-pair metric cross-check, not image validation.

Both engines have the same 459 file contents in `engine/`; hashing the canonical
path-ordered SHA-256 manifest with `LC_ALL=C sort -z` gives
`1aa7cfe5b3fd1db7d9cf53bb9d6ee8c62e88944da7f579f949fce9aecf34bf8a`.
The source is upstream `a46627d250545a394ca21522f8a28b0fb89e4c42` plus all 49
patches from baseline `c5666a270e0c6f9bcbcd692bcfd122b0a917d359`.

AOIDC uses the existing full host build. IBX independently compiles the engine
with GCC 14.2, native RAW off, the existing pinned Vulkan headers and four build
workers. Its system has libz.so.1 but no libz.so development linker name; an
isolated linker-directory symlink resolves `-lz`, without changing the engine
source or system package state. Both probes use four llvmpipe workers and strict
128 MiB storage-buffer limits, not a 128 MiB total-process budget.

Each report validates 74,750 CSV data rows: four method/depth combinations of
10,244 held-out colors, 14,839 scene pixels for interpolation, 14,839 for spatial
deletion and 4,096 additional spatial-ablation pixels. Training nodes do not
contribute to reported error. Raw CSV is reproducible with the committed runner;
the compact evidence retains grouped distributions, worst inputs and outputs,
contract and seed instead of committing the large generated tables or binaries.

This is an offline decision report. It does not enable LUT preview, change
exports, certify natural photographs, compare Lightroom, claim Android speed,
or complete full-resolution bounded rendering. There is no APK or private image.
