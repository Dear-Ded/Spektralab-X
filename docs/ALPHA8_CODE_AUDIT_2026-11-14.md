# Alpha 8 candidate: language support and device follow-up

Co-created with GPT (OpenAI), 2026-11-14.

## Scope

The phone was disconnected at the user's request after the Alpha 7 device
session documented in [the device report](REAL_DEVICE_2026-11-14.md).
This phase continues code review, corrections and cloud validation. It does
not claim that the corrected APK has been installed or tested on the phone.

The source inventory covers the Android application, import/export, state
persistence, queue/session ownership and JNI/native adaptation. The reviewed
Alpha 7 decoder baseline is described in [its audit](ALPHA7_AUDIT_2026-11-14.md).
No LibRaw or upstream engine patch changed during this follow-up. Native
and decoder regressions also run in the candidate GitHub Actions workflow.

## Changes

- Chinese remains the default, including on an English Android system.
  Settings offers Chinese and English. The choice is persisted before being
  published, and library controls receive an app-local resource configuration.
  Switching updates the existing composition without recreating the editor.
- Editor controls, settings, the light table, queue notices, import reports,
  errors and built-in style descriptions use the reviewed English catalog.
  Labels initialized before the switch are translated at display time.
  Both Chinese and English style searches work. User names, engine identifiers
  and serialized built-in recipes remain unchanged by a language switch.
- The HEIF APP1 carrier now reports the final output size in IFD0 as well as
  ExifIFD. Alpha 7's actual 1600 x 1000 image could otherwise be indexed as
  1 x 1 by MediaStore. The dedicated regression reads the generated APP1 back.
- The top editor uses `singleTop`, and `onNewIntent` records the current
  intent. Provider metadata queries move off the main thread. A later external
  open cancels publication of an earlier, slow provider result. This addresses
  the multiple editor instances observed during the phone session.
- Benchmark measurements now appear in Settings. Starting another benchmark
  joins cleanup of the preceding one before publishing new progress. The
  screenshot regression uses explicitly synthetic measurements; these are not
  measurements recovered from the disconnected phone.
- Native metadata is converted from validated UTF-8 to JNI Modified UTF-8.
  Supplementary characters use surrogate pairs and embedded NUL is encoded
  safely. Invalid, overlong, surrogate and out-of-range byte sequences are
  replaced. This fixes an encoding contract defect found by source review;
  no phone crash is attributed to it without reproduction.
- The integer view adapter retains its allocated pixels in an owning pointer
  until the final cancellation check succeeds. Cancellation unwinds that
  ownership before the public result is published. This also removes the
  analyzer's ambiguous free-then-second-atomic-check path; the diagnostic
  alone did not establish a reproduced use-after-free on the phone.

## Validation

All host execution and builds run in cloud resources. Local work is limited
to source edits, Git, source inventory, archives and evidence transfer.

AOIDC full regression round 3 completed with Gradle `BUILD SUCCESSFUL`:
222 Release tests and 354 Debug tests, zero failures, errors or skips.
The Debug suite includes language persistence and failure handling, context
unwrapping, recipe identity, narrow-screen Settings switching, visible
benchmark results, external provider routing and the HEIF metadata regression.
AOIDC lint reports zero errors, 100 warnings and 6 hints. The final targeted
round also passes all 7 localization and 2 real-composition UI tests. Its
screenshots show the complete benchmark result block and the English Settings
page at 320dp, with a synthetic editor and explicitly synthetic measurements.

The native text regression separately passed a real JDK 21 JVM with
`-Xcheck:jni` and an ASan/UBSan build. It covers all 1,112,064 valid Unicode
scalars, embedded NUL and 15 malformed input cases. The output-cancellation
regression passes ASan/UBSan, checking stage cancellation, recovery, integer
and float output parity, joined workers and partial TIFF cleanup. The changed
ARM64 JNI translation unit passes its actual NDK Clang analysis command with
zero diagnostics after the pixel ownership correction.

Final source is `c34657bde1f0318b27c6f7c7efb9a947b99226b6`, version
`0.2.00-alpha-8`, code `128`. GitHub Actions run `43570672053` completed
successfully at 07:28:54 UTC on November 14, 2026. Its final Release 222 and
Debug 354 tests have zero failures, errors or skips. RAW storage/decoder,
Vulkan/FIR, native adaptation, imported LUT parity and package inspection
also pass. This is a research-branch candidate; no release is published.

GitHub lint has zero errors, 111 warnings and 6 hints. Compared with the
published Alpha 7's report, there are two added warnings: a KTX style
suggestion for the explicit preference transaction (whose Boolean result is
checked), and `AppBundleLocaleChanges` for the dynamic resource locale.
The delivered APK includes its resources together. A future language-split
App Bundle is not covered by this APK verification and needs a split-language
delivery policy. The other eleven warnings above the offline AOIDC count
are the online dependency/version notices. No warning is suppressed to
obtain these counts.

Raw results and screenshots are in
[the validation evidence](validation/alpha8-code-audit-2026-11-14/README.md).

The downloaded signed candidate is 38,780,914 bytes, SHA-256
`702914c627851263a93464943eb6d95e387cae269a18ebd6e62434987cb30a32`.
Its checksum agrees with both the CI checksum file and packaged inspection.
The published signing certificate is unchanged. Package inspection verifies
39 JNI methods, both ARM64 libraries, 56 shaders and 113 engine assets.
AOIDC independently passes checksum, ZIP CRC, certificate, package/version,
ELF and ZIP 16KiB alignment, recomputed asset stamp and all 56 `spirv-val`
checks. Its `verification.exit` is zero. The engine asset stamp remains
`ae0291630fc4f07708f735bffd3ed6a2`.

## Limits

The second cloud provider returned a temporary free-plan cooldown. AOIDC and
GitHub Actions remain the active validation resources. No new paid instance
or persistent credential was created.

The fixed HEIF metadata, Chinese/English switching after process death, and
the final candidate's device installation still require a connected phone.
The [goal acceptance audit](ALPHA8_DEVICE_ACCEPTANCE_2026-11-14.md) maps each
requirement to its existing evidence and lists the unexecuted phone cases.
Existing user data and the two unrelated untracked worktree entries are
preserved. Private device logs and queue/history screenshots are not uploaded.
