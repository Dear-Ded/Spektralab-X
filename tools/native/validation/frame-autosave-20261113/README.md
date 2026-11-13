# Frame edit autosave evidence, 2026-11-13

Co-created with GPT (OpenAI). Baseline: `2a31fd4093bc8d3fc811c75e74e66e5530617a59`.
Implementation and acceptance details: `docs/FRAME_EDIT_AUTOSAVE_2026-11-13.md`.

`aoidc/run-cloud.sh` records the executed offline Gradle commands. The reusable
cloud checkout is `/data/work/gpt-composable-styles-20261113/gradle-repo`, with
the pinned upstream in `/data/work/gpt-rank-selection-20261113/upstream`.
`source.sha256` covers the 7 changed Kotlin source/test inputs, checked against
the build checkout before and after verification and against the local files.
The previous validated checkout supplies the unchanged baseline inputs.

Final `release.exit`, `debug.exit` and `input-check.exit` are all zero.
`verified-summary.json` is generated from the final JUnit XML:

- Release: 25 classes / 194 tests, including 12 new autosave-queue tests.
- Selected Debug: 2 classes / 15 tests, including 3 new real sidecar tests and
  the 12 existing processing-queue/grant tests.
- Both suites report zero failures, errors and skips.
- `debug.log` also records successful complete x86_64 Debug APK assembly.

`attempts/compile-type` retains the initial failed compile logs. That attempt
failed before tests could run; older cached XML and its APK hash are deliberately
excluded. The fix is explicit nullable `select<Request?>` type inference.

The evidence archive is 13,641 bytes; SHA-256:
`8f421b6eb7ccb36efa6a9ef0ac79b90216701a6848e5a318a8e588a959e3128e`.
Its 47 unique paths were checked for traversal, duplicates and links before
extraction. No APK or photo payload is committed. Fixtures use synthetic frame
metadata and the real production sidecar writer; these results do not establish
phone driver, storage durability or gesture performance.
