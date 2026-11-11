# Preview publication identity

GPT (OpenAI), 2026-11-11. Source changes only; no APK, version change or release.

The editor could publish a completed slow preview after a newer edit, downgrade
a preview with a live result for the same edit, or label a repacked live cache
as preview quality. Inspection callbacks also missed temporary preset overrides.
A bitmap could be tagged with the current window profile instead of the profile
actually installed on its native session.

`PreviewPublication` binds a frame to a source generation, native session, edit
revision, quality, output profile and completion sequence. Repacking reads the
retained cache tier on the serial engine dispatcher. Slow results must match
the current intent; live results can still make progress during a drag, without
replacing newer or higher quality results for the same edit. Detail and proof
callbacks check the same source and intent. Failed output-transform setup rejects
the candidate session; display bitmaps retain the session's accepted profile.

No film math, RAW tone default, export pixels or upstream pin changes here.
The JNI render revision check remains authoritative for native cache validity.
In particular, a failed render followed by a look-only repack does not prove
stale pixels can publish: JNI rejects a retained cache with an old revision.

## Verification

- Seven pure identity tests and eleven coroutine scheduling tests pass (18/18).
  The scheduling harness extracts production editor methods verbatim, then
  manually advances Main and engine dispatcher queues.
- Against baseline `c5666a270e0c6f9bcbcd692bcfd122b0a917d359`, the same harness
  has seven failures; the candidate passes all eighteen. Source/handle lifecycle
  and profile setup are explicit test fixtures, not full Android lifecycle tests.
- An independent reviewer reran all eighteen tests and checked the JNI cache
  revision guard. No additional confirmed must-fix was found in this scope.
- AOIDC: `:app:compileDebugKotlin :app:testReleaseUnitTest` succeeds offline,
  21 suites / 155 tests / zero failures or errors. These include the seven pure
  identity tests, so 155 and 18 are overlapping counts.

Run the scheduling suite using `tools/jvm/run_preview_publication.ps1`, a cached
Java/Kotlin/JUnit runtime and kotlinx-coroutines-core-jvm 1.9.0. `-EditorRef
c5666a270e0c6f9bcbcd692bcfd122b0a917d359` selects the baseline editor. The runner
requires an isolated `-BuildDir` and writes the exact extracted source and hashes.
Evidence is under `tools/jvm/validation/preview-publication-20261111/`.

The adapters do not exercise real Bitmap allocation, native pixels, Adreno,
Android lifecycle or display behavior. They do not model the JNI revision guard;
do not infer a native defect from an adapter that omits that guard. No device
performance or visual acceptance is claimed.
