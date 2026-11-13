# Export/routing validation, 2026-11-13

Co-created with GPT (OpenAI). Public source and synthetic data only. Builds and
executable tests ran in the cloud; Windows only edited sources and handled
archives, hashes and Git.

See [the behavior, results and limits](../../../../docs/EXPORT_ROUTING_FIXES_2026-11-13.md).

## Read these results

- `aoidc/jni-final/cases.csv`: normal=0, asan=0, before=1; the last is the
  required negative control. `jni-final.exit=0` means all expectations hold.
- `aoidc/jni-final/normal.log` and `asan.log`: five non-flat patterns accepted
  by Bitmap/JPEG/TIFF and a true-flat control refused by all three paths.
  Bitmap bytes match preview, JPEG dimensions/markers and TIFF descriptor
  ownership are checked. These are host JNI tests, not device acceptance.
- `aoidc/native/cases.csv`: the first eleven frame/output regression suites
  pass. Its last two JNI rows failed during setup and are superseded by
  `jni-final/`. Do not treat `native.exit=1` as a passing aggregate run.
- `aoidc/native/before-rgba16.log` and `before-float.log`: new tests fail with
  alpha-6 headers. The JNI before log reproduces the solid-color error.
- `aoidc/jvm/summary.csv`: Release 172 and selected Debug 67 tests, with no
  failures, errors or skips. Raw XML results are retained alongside it.
- `aoidc/arm64.log`, `x86-debug.log` and their hashes: successful cloud builds.
  `x86-before-refresh.sha256` records the earlier library before forcing the
  header-dependent rebuild. No APK was published by this validation.
- `aoidc/source-check.exit=0` and `native-input-check.exit=0`: 161 Gradle-tree
  inputs and 45 host-native inputs match the local snapshot. Refer to their
  SHA-256 manifests and `linked-archives.sha256` for the compiled inputs.
- `ibx/run.log`, `result.exit=0`, and `source.sha256`: independent GCC 14.2
  plain/ASan/UBSan checks for both frame validators, with matching input hashes.
- `aoidc/native/quiet-benchmark-*.csv` and `ibx/benchmark.csv`: synthetic 30 MP
  validator cost; best of three, not phone or whole-export performance.

## Preserved setup failures

`aoidc/initial-native*` retains the first attempt: the installed Clang binary
was versioned, the selected engine-only build lacked RAW source metadata,
and its JNI compilation did not complete. `aoidc/native/jni-after*.log` retains
the subsequent wrong-codec ABI and test-shim linkage failures. These are not
successful tests or evidence of a production renderer failure.

`run-jni-final.sh` records the corrected Linux-host codec, RAW archive build
and compiler selection. ASan/UBSan instrument the JNI and directly compiled
adapter units; the prebuilt engine/LibRaw/JPEG archives are outside that scope.
The JNI host run disables leak checking for the existing Vulkan-driver
limitation. The standalone validator runs enable leak checking.

The earlier, separate GCC pipeline-recovery diagnostic remains unresolved.
This change does not claim to fix it or to provide physical-device acceptance.

The downloaded AOIDC archive has 105 unique entries, 26148 bytes, SHA-256
`1834f1388c28755538e3607712ff37ef702a6dc558664cd1c4b3701252565e63`.
The IBX archive has four entries, SHA-256
`deccedc7e6a8b99601b0924bf2cc34129046f925522f3996d5ea52584de1ef12`.
Archives remain in the local research directory; extracted public evidence is
committed here. No private RAW, photo, derivative, audit PDF or handoff is included.
