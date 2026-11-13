# Frame edit autosave, 2026-11-13

Co-created with GPT (OpenAI). Source baseline: `2a31fd4093bc8d3fc811c75e74e66e5530617a59`.

## Confirmed failure paths

The light-table sidecar observer in `AppScreens` applied a 600 ms debounce before
recording its first baseline. An adjustment within that window became the
baseline and did not create a sidecar. Replacing the edit link cancelled its
`LaunchedEffect`, including a pending debounced adjustment. File-name filtering
also let the previous source interfere with linking another same-named photo.

The observer launched separate IO jobs for writes without waiting for a previous
write before reading a sidecar again. Returning to the table and immediately
exporting or reopening could therefore consume an older recipe.

## Resulting behavior

`EditorViewModel` establishes sidecar ownership by frame ID in the successful
source-commit transaction. The initial recipe is a baseline immediately, so an
untouched open still creates no sidecar. Real adjustments submit immutable edit
and RAW white-balance snapshots directly to `FrameEditAutosave`; no composed UI
collector is required. Successful RAW/development reopens retain ownership and
submit changes; failed candidate opens leave the previous ownership intact.
Rejected edits submit the reverted recipe when the editor rolls them back.

One IO worker coalesces pending values separately for each frame and writes them
in sequence after 600 ms of quiet. Switching sources cannot discard another
frame's pending snapshot. An ordinary photo open detaches ownership after it
commits. The last accepted undo value also wins over older queued adjustments.

Both the editor-to-table button and committed back navigation await a flush
before exposing table actions. Opening a table frame also awaits a flush before
reading its sidecar, and supersedes earlier pending table-open requests. Activity
stop requests a flush; editor disposal waits for an in-progress source commit,
then flushes and stops its worker. These writes run in the process scope rather
than the disposing view-model scope.

Editor-state JSON additionally records the frame ID. Restoration validates that
ID against the table's source URI before reconnecting subsequent edits. Existing
state files without this optional field remain readable. Table dimensions are
updated after a successful source open.

The layout, controls, card appearance, image algorithms, RAW defaults, engine
pin and version are unchanged. Navigation waits asynchronously for sidecar IO.
The existing editor-state and FrameStore file-writing implementations are retained;
this change addresses observer lifetime, baseline timing, write order and reads
of pending edits, rather than providing a power-loss durability guarantee.

## Verification

All executable checks run on AOIDC with the existing cloud Android toolchain.
The public evidence directory at `tools/native/validation/frame-autosave-20261113`
records the exact input hashes, Gradle commands,
raw logs and JUnit XML. Tests cover the production queue and real private
sidecar/index round trips using synthetic metadata, without loading JNI.

| Check | Result |
| --- | --- |
| Complete Release JVM suite | 25 classes, 194 tests; zero failures, errors or skips |
| Selected Debug Robolectric suite | 2 classes, 15 tests; zero failures, errors or skips |
| New production-queue coverage | 12 tests: early edit, source switching, latest undo, WB/development, detach, blocking-write barrier, cancelled reader, background flush, retry, disposal, edit during IO, quiet-period save |
| New real sidecar/index coverage | 3 tests: same-named sources, full RAW recipe/WB, untouched open |
| Existing grant/queue regression | 12 Debug tests |
| Complete x86_64 Debug APK | Assembled successfully; SHA-256 `2d531f9dee64b04da9b86b61377797ff0dc19ca0f52622fedaa65531c915dac4` |
| Changed source inputs | All 7 files match local, cloud snapshot and Gradle inputs |

The APK is a cloud validation artifact. Phone lifecycle and predictive-back
interaction have not been accepted on a device in this change.

The first compile identified a nullable `select` result inference error. It was
corrected with `select<Request?>`; the failed compile logs remain in `attempts`.
Reports copied from the earlier build during that failed attempt are not counted.
