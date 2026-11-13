# Selection preview validation, 2026-11-13

Co-created with GPT (OpenAI). Public sources and synthetic pixels only.
See [behavior, verification and limits](../../../../docs/SELECTION_PREVIEWS_2026-11-13.md).

- `aoidc/verified-summary.json`: final test totals and zero exit codes.
- `aoidc/results/`: 24 Release and 5 selected Debug raw JUnit XML files.
- `aoidc/native-normal.log` and `native-sanitized.log`: eight exact small-input
  comparisons, parent isolation, inherited RAW state and ownership assertions.
- `aoidc/gradle-final.log`: final full x86_64 Debug build and selected tests.
  `gradle.log`/`release.log` retain the earlier successful pass.
- `aoidc/input.sha256` and `input-check.log`: 185 matching source inputs.
- `aoidc/arm64.log`, `arm64.sha256`, `binaries.sha256`, `jni-symbols.log`:
  rebuilt native artifacts and exported `openPreviewV1` entries for both ABIs.
- `aoidc/apk.sha256` and `apk-jni.sha256`: the final x86_64 Debug APK and its
  packaged JNI library. Neither the APK nor native binaries are committed.
- `aoidc/selection-preview-393.png`: Compose layout screenshot with a synthetic
  renderer. `native-selection-srgb.png` uses actual native output instead.
- `aoidc/attempts/`: preserved missing-platform-shim and JSON-parameter-type
  setup failures, superseded by the final zero-exit native runs.

The downloaded evidence archive was checked for matching SHA-256, duplicate
entries, escaping paths and links before extraction. It contained 69 paths and
595481 bytes, SHA-256:

```text
e27071ef1286e866dc03ea369ae50fab090d052b8d311f8e05357b05a998cddf
```

Final Debug APK SHA-256:

```text
83b2bf36f2c569180d28958354e75ba038de8e343aaf0f0ae586808c1108ab15
```

These are cloud host tests and cross-compiles, not physical-device acceptance.
The sanitized test instruments the JNI/adaptation/test code; Release engine and
LibRaw support archives are not instrumented, and LSan is disabled for Vulkan.
