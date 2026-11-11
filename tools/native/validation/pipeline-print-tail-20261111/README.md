# Production print tail validation (2026-11-11)

Source-only candidate: upstream pin `a46627d250545a394ca21522f8a28b0fb89e4c42` plus patches 0001..0046 and the independent 0048 Pipeline candidate. Patch 0047 only changes the C API parameter transaction and is not present in this isolated test tree. The matching Gpu virtual-interface change was fully rebuilt; no stale archive was used. The parent integration validates the complete 0001..0048 combination separately.

## Results

- GCC 11 ordinary build: **PASS, 2,796 checks, 268 print renders, 2,200,950 float values compared byte for byte**.
- System Clang 14 ASAN+UBSAN: **PASS, the same counts**. Both the production Pipeline translation unit and test probe were instrumented, including their inline bounded/external-plane helpers. Other engine archive translation units and the Vulkan driver were ordinary Release builds. Leak detection was disabled for the driver. No sanitizer category was disabled.
- Before candidate: **FAIL: production tail selection ignored**, demonstrating the probe does not accept an unnoticed fallback.
- Android NDK r27c, `aarch64-linux-android29-clang++`, C++20 syntax check of the final production Pipeline: **PASS**. This is not an Android device execution result.

The actual film-to-print matrix uses Kodak Portra 400 / Endura, a synthetic linear RGB ramp, six output spaces (sRGB, Display P3, ProPhoto RGB, Adobe RGB 1998, BT.709, BT.2020), encoding on/off, tiers 1/2, gamut off/CAM16, and requested band rows 1/7/64. Grain and glare randomness are disabled to make independently executed reference/new renders deterministic. Unset and explicit-zero selectors match, and opt-in must execute the bounded tail. A 67x50 image with seven-row cores exercises four distinct halo-window capacities.

Observed backend compute allocation at source spill release: 819,992 -> 739,568 bytes. Subsequent per-window trims reclaimed 185,724 bytes over the observed window shapes. Upload/read staging allocation is checked as zero after successful tail completion, before the probe's own output download. These are backend allocation observations on llvmpipe, not RSS or Android peak-memory measurements.

Failure cases cover external source/output writes, window/final-result reads, cancellation during those reads, GPU dispatch/cancellation/copy, invalid temporary directory, strict environment parsing, host-budget admission before pixel IO, unsupported imaging modes, and the striped Pipeline entry. Every failed/cancelled public call preserves the caller's old output, clears completion/transient Pipeline state, and is followed by a successful byte-equal retry. Both ordinary `pread` and fortified `__pread_chk` are wrapped for observable IO injection.

## Sanitizer diagnostic limit

GCC 11 ASAN+UBSAN on the original existing `film_segment` stage-array/member-pointer expression reports an out-of-bounds index and crashes before reaching the new print tail. A diagnostic-only source copy that stores the same member pointer in a local variable passes that point; a small standalone reproduction did not reproduce the report. The unmodified final candidate passes system Clang 14 ASAN+UBSAN. This evidence does not establish a product UB or prove a GCC compiler bug. The GCC diagnostic is retained in `aoidc-gcc11-diagnostic.log`; no stage-expression rewrite was included in 0048. NDK host Clang's incomplete sanitizer runtime was not used for acceptance.

## Contract

`SPK_BOUNDED_PRINT_TAIL=1` is explicit opt-in, with an absolute temporary directory. Unsupported configurations return an error without fallback. Defaults retain the original path. The host budget includes the two coverage arrays, band metadata and the admitted worker arrays; transfer arrays do not overlap workers. GPU budget covers one helper window's logical scratch payload. The retained complete negative, complete output, caller-held output, live tables/caches, backend alignment, driver memory and concurrent sessions are outside that envelope. This candidate does not claim bounded total memory or RSS.

The runner records object, executable and matching engine-archive SHA256 values in each successful log. It accepts `UP`, `B`, and `BUILD_DIR`; `SANITIZE=1 CXX=clang++-14` selects the accepted sanitizer configuration. `PIPELINE_CPP` is only a baseline diagnostic override and skips direct checks of the new private method.

Absolute cloud paths in the published logs are replaced with
`<tail-validation>`; hashes and diagnostics are otherwise unchanged.
