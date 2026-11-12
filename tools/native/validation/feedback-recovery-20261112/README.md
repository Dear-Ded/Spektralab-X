# Cloud feedback recovery evidence

2026-11-12. Co-created with GPT (OpenAI). Public synthetic inputs only.
Interpretation and implementation details:
`docs/ENGINE_FEEDBACK_RECOVERY_2026-11-12.md`.

The cloud text archive SHA-256 is
`922e4300289213ef21027a12d4a11c1fee57b7f2e8dcb293403477b391152e6d`.
The archive retains original bytes; Git may normalize text line endings.
Binaries, dex, f32, full images,
emulator startup logs and private handoff files are excluded.

- `native/frame-check*.log`: fault injection, padded rows, opaque dark content,
  layout overflow, last pixel and 30 MP checking; ordinary and ASan/UBSan passed.
- `native/final-native-warm.exit`: 0. Final 26-card shared-engine lifecycle,
  33 checked frames, 6709x4475 full result and cached editor after export.
- `native/preliminary-25-card/`: completed LLVM 15 exploration, 25 profiles and
  earlier card settings. Not the final acceptance configuration or a directly
  comparable performance benchmark.
- `android/candidate/cases.csv`: 1281x855 pipeline exit 0; 6709x4475 exit 137.
  The latter is an uncaught storage-range exception followed by RuntimeInit
  self-SIGKILL, established by `candidate-crash.txt` and `logcat-tail.txt`.
  It is not evidence of an OOM kill or a damaged saved image.
- `android/baseline/`: the earlier diagnostic caller used renderBitmap before
  repack. Its cache precondition failed; production renderReuse is now used.
- `android/error-wrapper.*`: final diagnostic entry point prints an injected
  invalid-mode exception to stderr and returns 1 as intended.
- `build/gradle-compile.exit`: 0, Debug and Release Kotlin compilation.
- `build/final-android-build.exit`: 0, final complete x86_64 JNI/dex build;
  source and resource hashes are in `final-android-build-inputs.txt`.
- `build/arm64-final.exit`: 0, final production JNI object compilation. The
  initial `arm64-jni.exit` is 1 because that isolated source tree initially
  lacked the existing LibRaw patches; those were supplied before the final run.
- `android/emulator-final-status.txt`: this owned AVD was stopped normally
  after the tests, preserving its on-disk configuration and all results.

The AVD remained boot-incomplete and used TCG / Goldfish GFXStream / llvmpipe,
not the phone's Adreno driver. Java heap expanded to 576 MiB. Host native
Vulkan success is separate from Android success; the CPU host retains the
existing probed storage-range extension, which the guest does not use.
The actual failing DSC02279 file remains unavailable.
