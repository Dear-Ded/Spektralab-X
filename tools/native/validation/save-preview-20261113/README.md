# Save and preview scheduling evidence, 2026-11-13

Co-created with GPT (OpenAI). Source baseline:
`2b32845299a264b219dd5163f9e24f7a13509f62`.
Implementation details: `docs/SAVE_FAILURE_AND_PREVIEW_SCHEDULING_2026-11-13.md`.

All executable checks ran on AOIDC under
`/data/work/gpt-save-preview-20261113`. `aoidc/run-cloud.sh` records the complete
offline Gradle invocations. The reusable build checkout is
`/data/work/gpt-composable-styles-20261113/gradle-repo`, with its unchanged pinned
upstream under `/data/work/gpt-rank-selection-20261113/upstream`.

`source.sha256` covers all 11 changed Kotlin source/test inputs. They match the
local files, cloud source snapshot and build checkout before and after tests.
Final `release.exit`, `debug.exit` and `input-check.exit` are zero. The complete
Release suite and filtered Debug suite each ran successfully on their first
build attempt; only the cloud connection needed retries.

`verify-results.py` reads the actual JUnit XML and rejects nonzero command
exits, suite failures, skips or unexpected class/test counts. Its generated
`verified-summary.json` reports:

- Release: 28 classes / 212 tests, zero failures, errors or skips.
- Selected Debug: 3 classes / 26 tests, zero failures, errors or skips.
- Complete x86_64 Debug APK assembled; its identity is in `apk.sha256`.

There are 23 additional tests relative to the baseline: 18 JVM tests and 5
real private-sidecar/index/restart-state tests. Existing grant and Compose
selection-preview regressions also pass. Synthetic metadata is used throughout;
no private photos or APK binaries are included in this directory.

The downloaded evidence archive has 47 unique paths and is 13,764 bytes.
SHA-256: `ca59864f32cb3abad6becfc505e68a5624cdf672abc066171888484ac3d924b3`.
Checksum, traversal, duplicate and link checks passed before extraction.
Raw Gradle logs retain their original whitespace. The results establish the
tested IO and scheduling behavior, not phone power-loss durability or frame rate.
