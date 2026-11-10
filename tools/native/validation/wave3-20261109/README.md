# Wave-three scoped evidence

Co-created with GPT (OpenAI), 2026-11-09.

The [machine record](../../../../docs/validation/optimization-wave3-2026-11-09.json)
is the scope index. Final AOIDC cancellation logs include real DI in strict and
default adapter builds. The earlier `wave3-cancel-strict/default.log` snapshots
and IBX integer-cancel logs predate the real-DI extension.

`wave3-android-final.log` executed the release Kotlin compiler and unit-test task
alongside the native build task. The ten XML suites contain 73 tests with no
failures, errors or skips. `wave3-app-source.sha256` records the exact 136 app and
harness inputs compared with the local tree after extraction; it does not cover
the entire repository. `wave3-native-final.sha256` explicitly selects the
`3e2p1y2g` CMake build configured against the wave-three upstream tree, avoiding
an older parallel CMake output directory.

The subsequent `wave3-confirmed-build.log` validates the staged source tree
`b906de2a6e949400e82d4139bf4f97ff3898012a`, exported through git archive, with
the unchanged public `0.1.19-beta / 120` version files. Kotlin compilation,
73 JVM tests and the native build task passed again; `junit-confirmed/` contains
the ten new XML reports. The native library SHA remained unchanged. Later
committed additions append this evidence and documentation only. Version bump,
fastlane notes and release-workflow edits remain local, outside the source push.

`wave3-replay/result.json` retains its original earlier 66-test, UP-TO-DATE JVM
snapshot. Its patch replay and source comparisons remain valid; use the final
Android record for the new lease tests. `float-precision` records observed
production limitations. `float-linear` contains separate prototype evidence,
including expected failures, and is not a production decoder acceptance claim.

No Android runtime, phone GPU, real user RAW appearance, APK or release was
tested here. Small spool-disk and IBX coroutine evidence live in the neighboring
`spool-disk-20261109` and `transient-lease-20261109` directories.
