# Adobe TIFF and application validation

Co-created with GPT (OpenAI), 2026-11-11. Synthetic inputs only.

The full Linux host engine uses the same 49-patch/17-LibRaw-overlay source
verified in ../dng-dynamic-range-20261111. configure.log/build.log/host.time
record its fresh 167-target build, with PIC and -ffp-contract=off. This is
AOIDC CPU Vulkan llvmpipe, not Android runtime or phone GPU acceptance.

adobe-before.log preserves the real-engine failure of the initial candidate:
its CAT02 matrix and Bradford ICC disagree. adobe-after.log/cmm-final.log
and adobe/cmm_result.json validate the corrected file-local PCS matrix,
actual compressed TIFF samples and production Kotlin ICC through LCMS 2.12.
adobe/engine_metadata.json is explicitly the original engine CAT02 metadata,
not the final Adobe export matrix. The independent oracle computes the final
matrix from published Adobe and ROMM D50 colorants.

legacy.log compares 848,640 bytes using actual engine/CAM16 metadata from
old/current production adapters. jni.log covers 16 target/film/grade cases;
jni-before.log and jni-compatibility.log retain old-three-target complete TIFF
byte parity. jvm.log executes the actual Kotlin binding against production
JNI with host platform shims. tiff-targets.log checks queue serialization.
icc.log records actual production profile generation and independent Java CMM.

android-final.log rebuilds the changed native adapter with NDK27.2 ARM64/API29.
android-exports.log and android-elf.log retain 38 JNI exports and three LOAD
segments aligned to 16 KiB. android-final.sha256 identifies that native library.
No APK was produced.

gradle-final.log records complete debug Kotlin compilation and release JVM
regression: 20 suites, 148 tests, zero failures/errors/skips. The final command
is --offline --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g
:app:compileDebugKotlin :app:testReleaseUnitTest. The unit-test resource task
is not application APK assembly. validation-summary.json binds final source
hashes, XML counts, ELF/export checks and zero APK artifacts. Raw XML is under
repo/app/build/test-results/testReleaseUnitTest. Existing compiler deprecation
warnings remain; no warning is relabeled a failed check.

The source-only reproduction and mathematical contract are documented in
../../../../docs/ADOBE_RGB_TIFF_2026-11-11.md. Gallery-specific cancellation
timing tests and their distinct host boundary are in
../gallery-transaction-20261111/README.md.
