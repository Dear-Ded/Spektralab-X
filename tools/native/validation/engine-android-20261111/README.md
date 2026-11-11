# Engine/Android source evidence, 2026-11-11

Co-created with GPT (OpenAI). Baseline Android source: `72d1afd`; upstream
`a46627d250545a394ca21522f8a28b0fb89e4c42` plus engine patches 0001-0041.
All images used by these logs are synthetic. No private RAWs, JPEGs, rendered
photographs or private-image metrics are included.

See [the detailed review and scope](../../../../docs/ENGINE_ANDROID_REASSESSMENT_2026-11-11.md).
`source-sha256.txt` binds the changed production/test sources and the new patch.
Hashes normalize CRLF to LF, matching Git checkout differences between hosts.
No APK, install, version update or release.

## AOIDC

GCC 11.4, JDK 17, Vulkan llvmpipe LLVM 15.0.7. `aoidc/` contains:

- ICC baseline rejection and corrected production Kotlin/CMM pass.
- TIFF baseline preview-intent failure and corrected 12 complete-file
  comparisons: two storage paths, three targets and two look states. Real
  film/paper is exercised with grain/glare/halation/couplers disabled for
  deterministic output. ICC is an opaque writer fixture here; actual profile
  validity/math is checked by the separate CMM probes.
- A true JVM loading production JNI, using production `NativeEngine.kt` and
  only an Android Bitmap type stub, checks native symbol binding, three
  targets, descriptor ownership and identical legacy/new sRGB output. Its
  static dependencies were rebuilt PIC; this is not an Android runtime test.
- ASan/UBSan TIFF and bounded-contract probes. Instrumented adapters/harnesses
  link existing uninstrumented engine/codec archives, with leak detection off.
- Current bounded point and print GPU regression, plus independent upstream
  DI/headroom kernel oracles pinned at 223205f.
- Actual Android NDK r27.2 ARM64 API 29 syntax compilation of production JNI.
- Actual `:app:testReleaseUnitTest --max-workers=2 --console=plain` log and
  JUnit XML summary: 18 suites / 133 tests / zero failures, errors or skips.
- Fresh strict engine patch replay: 41 patches, `--fuzz=0`; 95 touched files
  match the actual tested tree. This does not re-validate LibRaw patch replay.

## IBX

GCC 14.2, JDK 21, Vulkan llvmpipe LLVM 19.1.7. `ibx/` contains:

- Independent JDK CMM baseline rejection and 4100 RGB-sample corrected pass.
- The 0039/0040 planner fails, and the same 89 contract cases pass with 0041.
- main 1952c63 `hdr_curve_unit`: nine CPU checks, zero failed. These are the
  upstream CPU curve checks, not a Vulkan HDR port or Android HDR integration.
- Pinned DI/headroom independent float64 references, linked to IBX's existing
  compatible engine build. This is a second compiler/driver oracle run, not
  a second rebuild of the entire current Android engine.

## Limits

Host tests do not establish phone appearance, device performance, thermal
behavior, mobile GPU precision, whole-process memory bounds or cross-platform
byte identity. The bounded chain remains a prototype outside production
Pipeline. Complete-frame caches, RAW scratch, codec coefficients and page
cache need separate accounting. Newly imported RAW keeps its linear default.
