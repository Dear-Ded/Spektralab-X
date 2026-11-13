# Installable ARM64 research candidate, 2026-11-14

Co-created with GPT (OpenAI). Application source:
`51be487613f2bc5a57a04aead9f4ff62210b21ab`.
See `docs/RAW_ALGORITHM_OPTIMIZATION_2026-11-13.md` for integration and limits.

`run-build.sh` builds the normal ARM64 Release variant on AOIDC, without the
older x86_64 emulator init script. It selects the cloud debug certificate and
does not use official release credentials or publish the APK. The 323 inputs
in `build-inputs.sha256` pass before and after building and match local sources.

`verify-package.py` checks the actual signed APK against Gradle outputs:

- Version `0.2.00-alpha-6`, code 126, release variant, ARM64 only.
- APK SHA-256 `17d2e2db08adfd563e1719fe3189796814e3bfe2d49180aed3bac03a2c338eb5`.
- 38,648,798 bytes; local download matches the cloud SHA-256.
- All 39 Kotlin JNI declarations have native exports.
- Both native libraries match stripped build outputs and have AArch64 ELF64
  headers and 16 KiB load-segment alignment; APK ZIP alignment also passes.
- All 113 engine assets match the staged source tree byte for byte.
- v2 signature passes with the cloud Android debug certificate.
- Native RAW compilation uses `-O2 -g -DNDEBUG` and the production source.

The first complete build is successful; its record collector uses the wrong
native directory. The corrected follow-up build is mostly cached. An initial
verifier assumes a single native library, then is corrected to inspect the
Compose path library too. Both attempts are preserved, rather than presenting
their script errors as Android compilation failures. The initial Gradle log
also retains R8's Compose constructor type-check warning.

No algorithm tests are rerun for this packaging-only step. Previous native,
sanitizer and output-integration results are in `../raw-algorithm-20261113`.
No executable build or test ran locally. ADB detects no device; installation
and phone behavior remain unverified. The debug-signed research candidate
cannot update an existing installation with a different signing certificate.

The APK is a separate local/cloud artifact and is not committed to Git. The
public evidence archive has 31 unique paths / 34,876 bytes, SHA-256
`ec1cab3485083f5121d57f4a35e58f317ffa49a89cf856e4a2ef6f3aefeae78d`.
Hash, traversal, duplicate and link checks passed before extraction. Local
verification scripts match `verification-scripts.sha256` and all 323 source
inputs match the manifest. No photo or private handoff was uploaded.
