# Vulkan finite-output recovery evidence

Captured on AOIDC, 2026-11-12. Co-created with GPT (OpenAI).
Report: `docs/ENGINE_FINITE_OUTPUT_RECOVERY_2026-11-12.md`.

The baseline is the pre-0054 implementation used by research commit `173cafa`.
The candidate adds overlay 0054 plus Kotlin recovery changes. All execution and
compilation occurred on the cloud computer. Fixtures are generated RGB images.

- `fault/`: baseline accepts 21 materialisations, including 18 corrupt cases
  that also pass the existing JNI alpha/flat check. Candidate rejects those 18
  with exact coordinates, and passes 3 normal cases, 21 view cases and 3 proofs.
  Nine normal whole-pixel hashes match the baseline.
- `ci/`: the final candidate-only runner completes, including a real derived
  shader that skips one workgroup. `3113/3114 groups` is the required rejection.
- `sanitizers/asan-system.*`: the same ASan/UBSan boundary binary completes on
  system LLVM 15 Vulkan, with no disabled checks. `llvm21-driver-teardown.*`
  preserves the bundled LLVM 21 driver allocation-family mismatch at device
  destruction after all checks have completed. Its nonzero status is retained.
- `warm/`: 26 film cards, previous 12 MP export, idle trim, resident editor and
  separate 6709x4475 export. All 33 whole-frame hashes match the prior run;
  zero black/white rows and zero alpha errors; editor negative cache retained.
- `android/`: API 37 native fault matrix and 1281x855 diagnostic JNI export.
  All 18 bad cases are rejected. All 13 normal/JPEG records match the previous
  run. This is TCG Goldfish/GFXStream execution through adb/app_process; the
  emulator has no completed boot marker. The owned AVD was stopped afterward.
- `build/`: full x86_64 and ARM64 JNI linking, shader validation and resource
  staging. The staged validator matches the tested SPIR-V.
- `gradle/`: final Debug/Release Kotlin compilation and 7 ExportRetryTest cases.
- Root manifests record tested source/artifact hashes, environment and zero-fuzz
  overlay replay. Research HTML is represented only by hashes; source links
  and the relevant interpretation are in the report.

Expected nonzero results are the omitted-workgroup negative control and the
recorded LLVM 21 sanitizer driver failure. The normal matrix, system-driver
sanitizer, Android matrix/export, builds, Gradle and warm run exit zero.

The original 61 text files are retained outside Git in
`/data/work/gpt-finite-output-20261112/public-evidence.tgz`, SHA-256
`bcd2ea847f3ac5ba3fc537dc6dd7bb1db0211dd8249a677a30617f6c823a3582`.
Git may normalize text line endings. No RAW, f32, JPEG, shared library, dex,
full shader binaries, emulator startup log or private handoff is in this tree.
