# Existing UI, optional film and paper

Co-created with GPT (OpenAI), 2026-11-13. Supersedes the scope-control UI evidence in
`../composable-styles-20261113/`. The user explicitly requested no UI changes.

Source comparison against `214cb818dbdc939f98300423e9a1b207a8bb8ff3`:

- `EditorScreen.kt`: identical (`git diff --exit-code`).
- `ImportSheet.kt`: identical (`git diff --exit-code`).
- `docs/UI2.md`: identical (`git diff --exit-code`).
- `Styles.kt`: the complete `StyleStrip` / `StyleStripContent` composable region
  is identical after LF normalization. Only recipe model / persistence changes
  remain outside that region.

The existing save dialog records the current selection automatically. No scope
controls, additional buttons, or altered None-card behavior remain. The old
`_application_scope` preference is no longer read.

Builds and executable tests run only on AOIDC, using Java 21, Android SDK 36 and
Robolectric SDK 34. Screenshots use generated RGB pixels; they do not contain
private photographs or represent a full editor / phone rendering acceptance.
No APK is built or released by this check.

The first launch failed before Gradle because the source archive made `gradlew`
non-executable on Linux. The launcher logs are retained in `attempts/`; restoring
its execute bit allowed the builds to run. No source workaround was needed.

The final logs, XML reports, source checksums, and screenshots in this directory
record the corrected revision. `summary.json` contains the aggregate counts.

Final results: 22 Release suites / 172 tests and 1 targeted Debug suite / 3 tests;
all passed without failures, errors or skips. The two UI cases save and reapply
the selected film/filter combination and the no-film/filter combination through
the unchanged name-only dialog. The third case exports and reimports an actual
`.spklook` with its original ProPhoto LUT.

All ten local/cloud source SHA256 values match. The three screenshots have been
visually inspected at 393 dp and 320 dp. Evidence archive SHA256:
`8cea8dfc10cdf5a73681c755def8a75e8003b006708e466ef928d2b386fd3315`.
