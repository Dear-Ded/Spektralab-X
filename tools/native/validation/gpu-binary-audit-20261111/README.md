# Android native and shader evidence

Co-created with GPT (OpenAI), 2026-11-11. Project terms in NOTICE apply.
Only project-owned native code, generated resources and public formats are
inspected. No PDF, private photograph, APK or compiled shared library is included.

The complete source is pinned upstream plus patches 0001..0048 and the Android
`USE_ZLIB` change. Android NDK 27.2/API29/arm64-v8a built the native shared
library in 186 steps. `build.time` records the actual four-job run: 78.51 seconds,
383% CPU, no swapping. GNU time's maximum child RSS is not an aggregate peak.

`android-elf.txt`, symbols, selected compiler/linker commands and the summary
record the AArch64 ELF, three 16 KiB-aligned LOAD segments, exported JNI symbols,
dynamic dependencies, static OpenMP and the enabled LibRaw decoder. Shader
validation covers all 53 modules with `spirv-val --target-env vulkan1.0` and
compares every module hash against the matching host build. `modules.tsv`
records each module, layout, capabilities and mathematical decorations.
`blob.tsv` records all 71 constant-blob entries and their checked byte ranges.

Paths in the exported text logs are normalized: `<android-validation>` and
`<tail-validation>` stand for independent cloud working directories. Hashes and
numeric tool outputs remain unchanged. Full disassembly is retained privately
in the validation environment; the published report is
[`docs/GPU_BINARY_AND_ANDROID_ABI_2026-11-11.md`](../../../../docs/GPU_BINARY_AND_ANDROID_ABI_2026-11-11.md).

This evidence certifies compilation, linkage and the inspected format/layout
contracts. It does not certify Android loading, APK ZIP alignment, Adreno/Mali
driver execution, photographic agreement or phone thermal/performance results.
