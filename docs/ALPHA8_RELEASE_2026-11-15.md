# Alpha 8 audit and release

Co-created with GPT (OpenAI), 2026-11-15, Asia/Shanghai.
Target: `0.2.00-alpha-8`, Android version code 128.

## Scope

This release combines the November 14-15 device acceptance records with a new
cloud audit after the phone was disconnected. The last installed candidate
was source `c4a45e576a1dd694371542adc663252b5ae56d1d`, APK SHA-256
`08597164a18553b73c21654c58803492545df65951fd20bb01554c0cbdad04c8`.
Its accepted observations and the unsuccessful attempts remain documented in
[the expanded device record](ALPHA8_EXPANDED_DEVICE_2026-11-14.md).

New refinements add separately labelled stage-sampled PSS, explain missing
peak RSS in benchmark recommendations, clarify queue history/cancellations,
and explain settings/GPU/memory constraints on actual working dimensions.
No user size preference or neutral RAW default is changed. Stage PSS reads
are outside timed renders; they can miss transient peaks and never replace
RSS in admission or recommendation calculations.

## Validation Status

The first cloud source inventory covers 648 maintained source/configuration
files, 4,328,589 bytes. Every uploaded byte hash matched; all 221 Python AST,
XML and shell syntax checks passed. This excludes historical generated evidence
and third-party binary assets, and does not claim every line is manually proven.
The first targeted cloud run passed 36 tests without failures/errors/skips.
The final maintained-code inventory covers 651 files / 4,338,516 bytes, with
all byte hashes matching and 222 syntax checks passing. The first complete
cloud suite passes 225 Release / 371 Debug cases with no failures/errors/skips,
and lint reports zero errors / 100 warnings / 6 hints in the offline cloud
environment. The final fixes add five package-boundary tests and two navigation
tests. All 17 final targeted cases pass, including language and Settings UI.
Final all-suite, native analyzer and signed release/package/provenance results
will be recorded below when their gates complete.

## Audit Findings and Coverage

| Area | Inspection and change |
| --- | --- |
| Android sources | All Kotlin is compiled and linted; manual review focuses on entry/import, session replacement, cancellation, persistent editor/frame state, export publication and navigation. Existing regression suites cover other modules; this is not a claim of line-by-line proof. |
| UI/localization | Preserve existing neutral theme, homepage and user choices. Add narrowly scoped size/queue explanations and separate sampled PSS/RSS labels. Native-graphics tests cover 320 dp English Settings/queue and real Chinese/English navigation. |
| Import boundary | Confirmed skipped-entry decompression-budget bypass, fixed by accounting for all expanded bytes, including unknown/duplicate/directory payloads. Old code fails four rejection tests; final five cases pass. See the [security review](validation/alpha8-release-2026-11-15/security-review.md). |
| JNI and upstream overlays | Existing UTF-8/MUTF-8 and display-pixel ownership fixes retained. Reapply current LibRaw overlays before analyzing the actual ARM64 compilation database. Enforce zero-fuzz application in Android CMake, as in CI. |
| Build/release | Original key stays in GitHub Secrets. Full release tests, native sanitizers/parity, package/signature/16 KiB checks and provenance are mandatory gates. |
| Shipped Maven dependencies | 112 resolved components queried against OSV on 2026-11-15 local: no registered advisory. Point-in-time service results do not establish defect-free libraries or scan native dependencies. |

The real AppScreens navigation tests begin a long press 160 ms into the entry
animation and cross rows after it settles, in Chinese and at 320 dp in English.
The first test attempt failed waiting for an endlessly animated empty editor
to become idle, before navigation. Controlling the test animation clock fixes
that harness assumption; both final tests pass without another production
gesture change. This does not identify the one unconfirmed device occurrence.

The first broad native scan encountered an old local cloud LibRaw copy and
therefore is retained as a stale-input attempt. Fresh Android CMake configuration
reapplies all current patches; its CRX source hash then matches the separately
replayed final source. Only the refreshed scan is final native evidence.

## Device Boundary

The phone was disconnected for these final changes. The new PSS sampling,
queue text and working-size explanations have not been reinstalled on it.
Existing device data covers one Xiaomi 13 Ultra / Android 16 using public
synthetic inputs, including a 24 MP input processed at 3547x2364 under the
measured 128 MiB storage-buffer cap. It does not establish real-camera RAW
coverage, full 24 MP export, Adreno numerical parity or long-run battery/heat
behaviour. USB-connected thermal samples do not measure battery drain.
One immediately-after-navigation device gesture formed no selection; its
cause remains unproven unless separately reproduced and identified below.
