# Production Print Tail and Memory Accounting

Co-created with GPT (OpenAI), 2026-11-11. Source and cloud verification only.
Version, version code, upstream pin and release settings are unchanged. New RAW
imports retain the neutral/linear default.

## Changes

| Change | Confirmed problem or result | Boundary |
| --- | --- | --- |
| 0046 | Report actual Vulkan buffer allocation sizes, separating compute buffers, upload/read staging and the inline ring. Explicit transfer trimming releases retained staging after synchronous work. | The new backend ledger overlaps logical image totals. Never add those totals. It excludes driver-private allocations and is not RSS or device-wide usage. |
| 0047 | Failed Pipeline builds previously left accepted session parameters ahead of the still-active pipeline. Build a candidate before publishing both. | Rebuild paths only; ordinary live deltas retain their existing path. This is a real C API defect, without claiming that Android's normal UI emits every rejected configuration. |
| 0048 | A guarded production paper-print tail now executes existing XYZ, gamut, FIR unsharp and CCTF kernels through external halo bands. | Explicit opt-in only. Full RAW decoding, film processing, the retained negative and the final output are still full-frame. |

The 0046 backend ledger uses each Vulkan buffer's actual memory allocation
requirement, rather than its requested pixel size. The existing logical report
remains available. Reports sample these ledgers separately; they are not an
atomic process-wide memory snapshot. Transfer trimming preserves live images,
pooled compute buffers and the inline argument ring. Other backends report the
ledger as unavailable and retain their default no-op trim.

0048 writes the post-glare XYZ image to anonymous external storage in bands,
releases its local reference, and trims idle blocks before running the tail.
Each halo window uses the same existing shaders and FIR pass order. Different
edge and centre window sizes cannot accumulate idle exact-capacity blocks:
every window starts with a flush and idle-pool/transfer trim. The sealed result
is assembled into a private GPU image; failure or cancellation keeps the
caller's old output. Production completion still uses the 0044 final flush and
publication boundary.

## Opt-in Contract

There is no new Android UI switch or default activation.

| Environment variable | Contract |
| --- | --- |
| `SPK_BOUNDED_PRINT_TAIL` | Unset or `0`: existing path. `1`: experimental tail. Other values fail explicitly. |
| `SPK_BOUNDED_PRINT_TAIL_DIR` | Required absolute temporary directory on Linux/Android. |
| `SPK_BOUNDED_PRINT_TAIL_ROWS` | Positive decimal, default 64, maximum 65536. Actual rows can be reduced by admission. |
| `SPK_BOUNDED_PRINT_TAIL_HOST_BYTES` | Positive decimal byte budget, default 64 MiB, maximum 1 TiB. |
| `SPK_BOUNDED_PRINT_TAIL_GPU_BYTES` | Positive decimal logical window payload budget, default 64 MiB, maximum 1 TiB. |

The candidate rejects DI, film scanning, overscan, EDR, scanner lens blur,
carried IIR unsharp, fusion, unsupported gamut algorithms and the striped
Pipeline entry. It uses the actual tier's unsharp sigma. Unsupported requests
fail explicitly instead of silently falling back. The existing helper API and
its default behaviour are unchanged.

CPU admission accounts for both external-plane coverage arrays, band metadata
and fixed worker arrays. Transfer arrays do not overlap worker arrays. The GPU
limit selects one window's logical scratch payload with the conservative
128 MiB storage-buffer range. Complete input/output images, caller-held old
outputs, tables/caches, alignment, staging, driver allocations, thread stacks,
allocator overhead, filesystem cache and other sessions remain outside this
envelope. This is a limited integration of time-for-memory execution, not a
total-memory guarantee or a measured phone speedup.

## Validation

The parent integration rebuilt the complete native engine for the changed Gpu
virtual interface, then rebuilt changed Pipeline consumers. Replaying all 48
patches on the clean pinned archive with zero fuzz produced 97 affected files
identical to the tested source tree.

- The complete 0001..0048 build passes 2,796 production-tail checks, 268 real
  film-to-print renders and byte comparisons of 2,200,950 float values. The
  matrix covers six output spaces, encoding on/off, two tiers, gamut off/CAM16
  and requested band rows 1/7/64. The pre-candidate probe fails because the
  production selector is ignored.
- An independent Clang 14 ASan/UBSan build of the production Pipeline and probe,
  including inline helpers, passes the same matrix. Remaining engine objects
  and the Vulkan driver are ordinary Release builds; leak detection is off.
- Rejected-parameter regression passes 70 checks on both hosts. The independent
  old executable fails 20 checks; 20 successful outputs, totalling 88,160 bytes,
  remain byte-identical. Coverage includes five failure classes and recovery
  without restoring a whole parameter snapshot.
- Transfer accounting passes 24 checks for each inline-ring mode on both hosts.
  A 4 MiB upload/read warm-up is explicitly trimmed; a subsequent 4 KiB read
  retains only the small read staging allocation. Existing device-lost cleanup
  also passes both modes.
- NDK r27.2 ARM64/API29 compilation of changed GPU/Pipeline/API consumers passes.
  This does not execute the Android runtime or a phone's GPU driver.

One small llvmpipe case observes compute allocations of 819,992 -> 739,568 bytes
at the source spill boundary, and 185,724 bytes reclaimed across four window
shapes. These are specific allocation observations, not whole-render peak RSS.
The retained GCC 11 sanitizer diagnostic in an existing film stage expression
remains unexplained; the original candidate passes the independent Clang 14
sanitizer test. No unproven compiler-bug or product-UB conclusion is drawn.

Evidence: [transfer accounting](../tools/native/validation/transfer-memory-20261111/README.md),
[parameter transaction](../tools/native/validation/params-transaction-20261111/README.md),
[production tail](../tools/native/validation/pipeline-print-tail-20261111/README.md).

## Remaining Work

The new code does not establish Lightroom/camera-JPEG appearance parity, whole
RAW/film external execution, concurrent-session memory ownership or Adreno
driver acceptance. Private photograph results are not published. Broader colour
schemes need explicit pixel transforms, matching file profiles and Android
display contracts; an engine colour-space name alone is insufficient.
