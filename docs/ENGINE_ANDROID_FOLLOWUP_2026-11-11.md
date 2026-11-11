# Engine Source Follow-up, 2026-11-11

Follow-up: patches 0046..0048 now add backend allocation accounting, rejected
parameter transaction protection and an opt-in production paper-print tail.
See [the integration record](ENGINE_PRINT_TAIL_INTEGRATION_2026-11-11.md).
The helper-only status below describes the earlier 0043..0045 stage.

Co-created with GPT (OpenAI). Source and cloud validation only. Version,
version code, packaging and release settings are unchanged. New RAW imports
retain the existing neutral/linear default.

## Confirmed Fixes

| Patch | Reproduced problem | Result and boundary |
| --- | --- | --- |
| 0043 | The ASN path rejected valid nonidentity CameraCalibration and ignored usable ForwardMatrix data, despite already parsing calibration signatures. | Extend only finite nonidentity CC with explicit ASN, valid signatures and usable FM. Select CC by matching signatures and use the same matrix for demosaic coefficients and final conversion. Preserve near-identity, non-finite, invalid-signature and no-FM fallbacks. |
| 0044 | A cancelled striped run left band coordinates behind; the next full-frame Scene Latitude result differed from a fresh Pipeline. Failed print completion could also overwrite caller output and retain a stale `done` flag. | Scope-bound cleanup restores coordinates, sweep/progress pointers and mask resources. All four entries clear completion. Print commits output after final phase flush, with report allocation before striped output commit. |
| 0045 | FIR windows and small constants could borrow large idle pooled allocations. Per-worker vectors could retain extra capacity, while worker count ignored CPU payload budget. | Exact logical GPU allocations for external windows; fixed maximum CPU arrays; preflight payload admission reduces worker slots or refuses before claiming output. Guard uint32 RGB element indexing and size_t byte capacity. |

0044 changes production Pipeline failure handling. 0045 strengthens the
external-plane helpers; it does not enable them in the production render path.
FIR weights, pass order and the pointwise shader expressions are unchanged.

## Evidence

Two independent hosts use GCC 11/LLVM 15 and GCC 14/LLVM 19 respectively.
They rebuilt decoder objects independently for 0043 and rebuilt the native
engine for the changed Blur signatures in 0045. Source replay applies all 45
engine patches with zero fuzz and compares the 96 affected files to the tested
integrated source tree.

- ASN matrix contracts improve from 40/80 to 80/80; maximum matrix error is
  8.3913e-8. Forty unit-CC and forty no-FM output pairs stay byte-identical.
- Sixty-four compatibility records include direct explicit-WB calibration-gate
  checks. The broader draft fails these controls; the final restricted patch
  matches baseline. Metadata/gate equality is not a claim that every malformed
  synthetic file successfully decodes to an image.
- Existing WhiteXY controls cover 25 files, 98 decodes and 37 matrix contracts;
  82 successful non-ASN outputs stay byte-identical. The oracle uses reported
  temperature for default-WB interpolation, so this does not independently
  certify CCT inference or a complete Adobe render.
- Production recovery has 42 checks. Both hosts reproduce the old coordinate
  contamination and premature output replacement. The final combined build
  also passes the existing 494-case Vulkan fusion suite, including 12 real
  film-to-print renders. This is within-build fusion parity, not a photographic
  cross-release golden comparison.
- External execution passes 901 point-chain cases and 2251 FIR print-run cases
  per host, comparing 102764160 and 75470400 float values respectively. Tests
  include strict 128 MiB range rejection, different band orders, 1/4 workers,
  cancellation and successful retry. Budget/warm-pool checks pass 56 contracts;
  external-plane failure tests retain their 94 passing contracts.
- Targeted ASan/UBSan checks and NDK r27.2 ARM64/API29 source compilation are
  recorded separately. They do not certify phone drivers or an Android runtime.

Evidence: [ASN calibration](../tools/native/validation/asn-calibration-20261111/README.md),
[Pipeline recovery](../tools/native/validation/pipeline-recovery-20261111/README.md),
[bounded payload](../tools/native/validation/bounded-budget-20261111/README.md).

## Research Applied

Adobe's fixed SDK mirror
[`de700ad461e35af50b28b861943a0b0753b10929`](https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/source/dng_color_spec.cpp)
initializes identity calibration and adopts stored CC only for matching camera
and profile signatures (lines 134-162); its FM formula uses the inverse of
AnalogBalance * CameraCalibration (lines 589-595). The patch implements the
selection algebra without copying the SDK implementation or redistributing SDK
code. It deliberately leaves unsupported legacy paths unchanged.

The memory review also checks darktable's
[`tiling.c` at c96cf1d](https://github.com/darktable-org/darktable/blob/c96cf1d0de8fff468e9eddee28621e91f83bdf1a/src/develop/tiling.c):
live full-frame buffers, module overhead and tile overlap must all be considered
before selecting a tile size. This informed the explicit, limited payload ledger;
we do not import a heuristic and relabel it as a process-wide memory proof.

## Remaining Work

0043 addresses a specific calibration compatibility case. It does not establish
the cause of the reported Xiaomi appearance difference. No camera-JPEG or
Lightroom visual match is claimed.
Small matrix/CCT differences, SDK clamp/crop boundaries and the distinction
between old host artifacts and current Android execution remain to be separated.
No private photographs or measurements are included in this report.

The next bounded production step is a guarded print tail after glare: exclude
DI, overscan, scanner blur, EDR and carried IIR, and compute unsharp sigma from
the actual tier. Require external GPU ownership and include live input/output,
retained driver staging and cache reservations before claiming a total budget.
This step is not enabled by the current patches.

The host payload ledger excludes thread stacks, allocator overhead, tables,
driver staging/alignment, idle pools and unrelated application buffers. Exact
logical capacity is not exact physical GPU allocation. Persistent uploads may
increase allocation cost. Full RAW decode, film intermediates, caches, final
packing and IIR recurrences still need separate integration and measurement.

The [V2.3.2 source review](UPSTREAM_V2_RESEARCH_2026-11-11.md) records its official
release, minilab imaging changes, Vulkan/HDR gaps and all 45 patch dispositions.
It does not include a V2 build or runtime test. Changing imaging calibration,
output contracts or platform implementation requires separate migration work;
these three fixes do not change the upstream pin.
