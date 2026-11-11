# Gallery transaction evidence

Co-created with GPT (OpenAI), 2026-11-11. Synthetic URIs/provider data only.

after.log: 34 passing real Kotlin/JVM checks using Temurin21.0.13, Kotlin2.0.21
and the application's coroutines1.9.0 contract. Twelve pure unit tests and
22 timing/queue integration tests compile the entire production MediaSaver
and GalleryTransaction, plus 14 verbatim production queue methods. Android
framework, provider, preferences, engine and service dependencies are shims;
the IO threads, coroutine cancellation and barriers execute on the actual JVM.

immediate-retry-before.log: the same 34 tests before the last two guards;
exactly the two newly added cancel/retry ordering cases fail. The final fix
binds delayed cancellation to its original URI and rechecks cancellation intent.
windows-source-sha256.json identifies that final tested production source and
generated harness. Absolute paths identify local build artifacts, not private
photographs or dependencies shipped by the application.

Reproduce with tools/jvm/run_gallery_transaction.ps1 using explicit BuildDir
and ToolDir. The latter contains the public host JVM/compiler dependencies
prepared by run_tiff_targets.ps1. The generated harness remains an artifact;
the maintained template and API shims are tools/jvm/gallery/*.

Full Android Kotlin compilation and release JVM regression are recorded in
../adobe-tiff-20261111/gradle-final.log and validation-summary.json. These
148 release tests include the 12 pure gallery unit tests; they must not be
added to the 34 as if every case were distinct. No APK or device test ran.

Product semantics and residual boundaries:
../../../../docs/GALLERY_TRANSACTION_RECOVERY_2026-11-11.md.
