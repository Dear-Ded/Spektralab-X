# RAW statistics and edit lifecycle

The later compatible-decoder one-shot storage contract is documented in
[RAW storage lifetime](ENGINE_RAW_STORAGE_LIFETIME_2026-11-13.md). It releases
owned original samples only after processing and metadata capture, retaining
the processed image for LibRaw's original orientation/gamma output. Statistics
readiness and candidate publication below keep their existing contracts.

Co-created with GPT (OpenAI), 2026-11-10. No version, package or release change.
This follow-up narrows the source/readiness and failure contracts described in
[RAW development v1](RAW_DEVELOPMENT_V1_2026-11-09.md).

## Lazy source statistics

Opening a RAW in neutral mode or with physical film no longer runs the
complete-frame logarithmic statistics scan. A remembered standard-mode choice
also does not disable the fast decode while film is selected. Only active
no-film standard development requests an unbinned decode and prepares statistics
before working-size resize. The existing scanner and tone arithmetic remain
unchanged.

`prepare_raw_development_stats` returns whether the complete source was scanned.
`rawStatsReady` records that provenance; histogram `available` records whether
the scan found usable automatic-exposure samples. An all-black source is ready
with unavailable samples. Standard mode currently prepares statistics even with
AE disabled, so subsequently enabling AE does not require another decode.

A neutral session has no prepared statistics even if its decode was already
unbinned. Enabling no-film standard therefore reopens any RAW session whose
`rawStatsReady` is false. JNI rejects standard parameter/card requests on such a
session rather than silently using an empty histogram. The old
`rawStatsBinned` diagnostic remains separate from readiness.

## Candidate edit publication

When an edit needs the complete RAW source, the editor stages its requested edit
before changing the accepted UI edit, wanted parameters, preferences, history or
save request. The replacement decodes and validates its rendering metadata as a
candidate. Only successful candidate commit publishes the new session and edit;
the retired session is released afterwards. Failed or cancelled preparation
leaves the last accepted session and edit usable. A newer open cancels and joins
the earlier open before preparing its own candidate.

Undo and redo first inspect their target without popping a history stack. When
the target requires a candidate reopen, the stack moves only on successful
commit. A failed or cancelled candidate therefore does not consume that undo or
redo step. Undo/redo requests are ignored while an open is busy.

New RAW imports and legacy records still default to neutral. Saved per-photo
standard intent remains explicit; sidecars and export jobs preserve it. Physical
film bypasses RAW display development. These changes neither add a display curve
to film input nor claim camera-JPEG or Lightroom appearance matching.

## Validation and remaining gaps

The standalone native helper passed nine synthetic groups on AOIDC/GCC 11.4 and
IBX/GCC 14.2, both normally and under ASan/UBSan. Source hashes matched across the
workspace and both clouds. Coverage includes null-source bypass for neutral and
film, field-for-field scanner parity, AE-disabled preparation, all-black
readiness, and unchanged prior statistics after invalid input or cancellation.
Logs, flags and hashes are in
[raw-lifecycle validation](../tools/native/validation/raw-lifecycle-20261110/README.md).

The root integration run passed ARM64 native compilation, Kotlin compilation and
JVM tests; the final run also includes the JPEG color-preview source. Logs and
relevant JUnit XML are in
[Android integration validation](../tools/native/validation/raw-color-inspection-20261110/README.md).
JVM coverage includes the decision to reopen an unready RAW, exclude
film/non-RAW requests, and reuse ready statistics. Existing candidate tests cover
native-handle ownership and cancellation. Read-only review found no additional
deterministic defect in the revised candidate/history paths.

This is not an Android ViewModel integration-test result. High-value remaining
tests should exercise the actual editor coroutines with a controllable decoder:

- Hold a candidate beyond the save debounce, then reject metadata; accepted UI,
  wanted edit, saved state, queue snapshot and history must remain unchanged.
- Fail or cancel a staged undo/redo, then succeed; stacks must remain unchanged
  on failure and move exactly once on success.
- Replace a staged mode change with a new import; the old candidate must not
  publish an edit, history movement or URI into the new photo.

No real-image rerender, device latency benchmark or Android visual acceptance was
performed for this lifecycle helper change.

## Upstream check snapshot

The upstream recheck in this round recorded:

| Branch | Checked revision |
| --- | --- |
| `Windows_Linux` | `d13a46ec5497f5596ead9900ec685abaf5e0750e` |
| `main`, previous check | `d4b8145f6f9bdade15cda014776e99521adf0b5f` |
| `main`, this check | `fd4e1dbcd1951114c8709c775a706911c9444c3b` |

The checked `main` range contains one README trademark commit and no engine
changes. `Windows_Linux` remains at the recorded revision. These are the results
of this round's check, not a permanent statement of the latest upstream state;
the engine pin and local rendering algorithms were not changed by that recheck.
