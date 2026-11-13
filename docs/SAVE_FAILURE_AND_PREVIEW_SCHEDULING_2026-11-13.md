# Save failures, restart state and preview scheduling, 2026-11-13

Co-created with GPT (OpenAI). Source baseline: `2b32845299a264b219dd5163f9e24f7a13509f62`.

## Persistence behavior

`FrameStore` previously swallowed write errors and used a replacement fallback
that deleted the destination before checking the second rename. It also
published edits, ratings, dimensions, imports and removals before persisting
the index. Callers could therefore display success for an unsuccessful write.

`writeAtomicText` writes a same-directory staging file, synchronizes its file
descriptor, then replaces the destination with an atomic move. Errors propagate
to the caller; there is no delete-and-retry fallback. Each file retains its last
complete value when staging or replacement fails. This is per-file atomicity:
the sidecar and index are separate commits. If the sidecar commits and the index
fails, the new sidecar remains readable, the published table state stays at the
previous index, and a retry finishes the save.

The store publishes the next list only after the index commits. A failed
removal cannot remove the published frame or begin releasing its source grant.
Import, rating, removal and style application catch storage errors and use the
existing message surface. Batch paste reports the completed count on failure.
Import progress clears in `finally`. Autosave keeps failed snapshots pending,
reports the failure once until recovery, and retries; flush callers receive
the actual exception. Recording source dimensions also handles write failure.

## Restart snapshots

`EditorStateAutosave` replaces the view-model-owned delayed `saveLoop`. Source
URI, optional frame ID, accepted edit and RAW white balance are captured together
on Main, directly after successful source/edit publication. Display-only
overrides are excluded. One IO worker in the process scope coalesces changes
after 400 ms of quiet and serializes every write. A newer source cannot be
combined with an earlier edit or overwritten by an older delayed write.

Activity stop submits the current accepted snapshot and requests an immediate
flush of both restart state and frame edits. Disposal waits for any source
commit to finish, flushes both workers, stops them and releases the session.
Failed restart writes retain their pending snapshot and previous good file.
Restore retains the existing consume-before-open behavior to prevent repeated
crashing opens, and validates an optional frame ID against its source URI.

An Activity callback requests asynchronous IO rather than blocking Main.
Immediate process termination before that IO completes is not guaranteed to
preserve the latest adjustment; file and directory power-loss durability has
not been established by these checks.

## Preview scheduling

`SelectionPreviewScheduler` returns a valid cached card before entering the
render mutex or waiting for foreground work. The 120 ms scroll delay and idle,
export and thermal waits happen outside the mutex. Ready requests can proceed
while another request waits. Under the render lock, each request checks its
source, cache and foreground work again, so identical recipes share the result
and a newly busy foreground delays uncached work.

The existing recipe/output/source-generation cache keys, native child-session
renderer, bitmap cleanup and source checks remain in use. No layouts, fonts,
card dimensions or image algorithms change in this follow-up.

## Verification

All executable verification runs on AOIDC. The public evidence directory is
`tools/native/validation/save-preview-20261113`; its runner records exact Gradle
commands and checks the changed source/test inputs before and after the build.
The complete Release JVM suite, selected Debug persistence/grant/Compose
regressions and full x86_64 Debug APK assembly are the acceptance checks.

| Check | Result |
| --- | --- |
| Complete Release JVM suite | 28 classes, 212 tests; zero failures, errors or skips |
| Selected Debug Robolectric suite | 3 classes, 26 tests; zero failures, errors or skips |
| New JVM coverage | 3 filesystem failure/replacement tests, 7 restart-state queue tests, 8 preview scheduling tests |
| Real persistence coverage | 8 tests, including 5 new staging/index/removal/restart failure and retry cases |
| Existing Debug regressions | 12 processing queue/grant tests and 6 selection-preview Compose tests |
| Complete x86_64 Debug APK | Assembled successfully; SHA-256 `5528c32c77683d7f3b4911fd5c76f9f9acfdda1547afc189350d344900a2f837` |
| Changed source/test inputs | All 11 match local files, cloud snapshot and build inputs before and after verification |

Both Gradle invocations succeeded on the first build attempt. The real
filesystem tests cause staging open and atomic replacement failures, observe
the propagated exceptions and preserved files, then exercise retry. Queue tests
gate an in-flight IO write to prove latest-state ordering. Scheduling tests gate
lock contention to prove cache bypass, duplicate suppression, foreground-work
rechecks, cancellation and source invalidation. Fixtures use synthetic metadata.

The validation APK is a cloud artifact. Phone storage/lifecycle and interactive
preview performance were not measured in this follow-up.
