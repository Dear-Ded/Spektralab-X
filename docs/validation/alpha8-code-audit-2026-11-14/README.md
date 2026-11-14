# Alpha 8 Code Audit Evidence

Co-created with GPT (OpenAI), 2026-11-14.

Final source: `c34657bde1f0318b27c6f7c7efb9a947b99226b6`.
Candidate: `0.2.00-alpha-8`, version code `128`.

The Android and native tests run in cloud resources. `aoidc` contains the
earlier complete Gradle round, the final language/UI checks, exhaustive JNI
text checks and the final instrumented output-cancellation regression.
The owning pixel pointer removes the NDK analyzer diagnostic: the final
ARM64 JNI analysis exits successfully with an empty diagnostic log.
`analysis-before.log` records the exact path that prompted that change.

The 320dp Chinese/English screenshots use Robolectric native graphics and a
synthetic editor state. The benchmark values shown in the result screenshot
are injected test values. They are not measurements from the phone.

Final GitHub Actions run: `43570672053`. Its APK, Android tests, RAW decoder
storage checks, Vulkan regressions and package inspection correspond to the
source above. The earlier successful run `43569864585` used `ee7eb60` and
does not contain the final pixel ownership change.

Final Android results: Release 222 tests in 29 suites and Debug 354 tests in
48 suites, with zero failures, errors or skips. Lint has zero errors, 111
warnings and 6 hints. The two warnings added since Alpha 7 are the explicit
preference-edit KTX suggestion and the App Bundle locale-delivery caution;
this candidate is a complete APK, and no split-language AAB is validated.
The separate offline AOIDC report has 100 warnings and 6 hints; the eleven
additional GitHub warnings are online dependency/version notices.

Candidate signature and independent package validation pass. The APK is
38,780,914 bytes, SHA-256
`702914c627851263a93464943eb6d95e387cae269a18ebd6e62434987cb30a32`.
`package-inspection/verified-package.json` records the published certificate,
39 JNI methods, 56 shaders and 113 engine assets. `cloud-verification`
independently verifies checksum, ZIP CRC, package/version, published
certificate, ARM64 ELF/ZIP 16KiB alignment, asset stamp and all 56 shaders
using `spirv-val`. The recorded exit code is zero.

This is a research-branch candidate, with
no new public release or release attestation. The published Alpha 7 remains
on `main`. The phone was disconnected before candidate installation; the
candidate's on-device language persistence and HEIF fix remain unverified.
