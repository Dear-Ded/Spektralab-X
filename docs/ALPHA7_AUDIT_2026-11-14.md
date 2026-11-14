# Alpha 7 audit and release record

Co-created with GPT (OpenAI), 2026-11-14. Release candidate: `0.2.00-alpha-7`,
code 127. Cloud regressions pass; signed release CI and final package inspection
are pending. Phone installation-warning resolution remains unverified.

## Scope and Baseline

Public alpha-6 starts at `22425a1b2c659615d09c6229f5de743891c748cb`.
The reviewed research baseline is `fee70ee9fea4d6477776d32b5261bc2ce02f0df8`.
Alpha-7 also includes the seven intervening commits for complete export-frame
checks, external-file routing, LUT baking, full-recipe previews, edit persistence,
save failure reporting, and RAW statistics/development optimization.

The baseline maintained-file inventory, excluding generated historical evidence
and third-party font binaries, contains:

| Area | Files | Bytes | Coverage |
| --- | ---: | ---: | --- |
| Android Kotlin | 110 | 1,308,249 | All-source lint/compilation; manual entry, update, import, persistence and publication review |
| JNI and application C++ | 34 | 302,811 | Actual ARM64 compilation database and manual ownership/bounds review |
| Engine and LibRaw patches | 79 | 628,659 | Overlay review and pristine-source replay; final set is 60 engine / 23 LibRaw |
| Android manifest and XML | 79 | 206,944 | Permission/export/provider boundaries, lint and packaged manifest |
| Build and release scripts | 4 | 16,633 | Dependency pins, signing identity, update and release path |
| CI workflows | 7 | 25,198 | Build/test coverage, artifact naming and release gates |
| Tools and regression sources | 249 | 1,305,477 | Source inventory, runner review and targeted cloud execution |

This distinguishes automated coverage from detailed manual inspection. It is
not a claim that every line was manually proven correct or that every possible
input and phone has been tested. Binary inspection covers actual ZIP entries,
DEX checksums and signatures, manifest, ELF segments/JNI, SPIR-V word boundaries,
staged assets, APK identity/signature and provenance.

All executable tests and builds ran on AOIDC, IBX or GitHub Actions. Local work
was source/Git inspection, editing, file transfers, hash/archive checks and
read-only ADB discovery. No connected phone was found. Private photographs,
measurements, handoff files and authentication material were not published.

## Confirmed Findings and Changes

| ID | Finding | Resolution and evidence |
| --- | --- | --- |
| A7-01 | The earlier delivered research APK had an Android Debug certificate | Actions 43466329167 produced a candidate with the original published certificate; signature, alignment and identity verification passed. All release-key candidates now verify that certificate; fallback artifacts say `debug-key`. This does not identify the unknown phone warning. |
| A7-02 | UserProfiles, UserStyles and LutStore could delete a good target while retrying a failed rename | Use the shared fsync + atomic same-directory replacement without deleting the destination. Failure tests preserve the old file and allow retry. |
| A7-03 | ManualRolls published memory state before a write and swallowed storage failures | Write the proposed list before publishing it; propagate failures and retain selection/navigation. Pack, rename, release, dissolve and reload regressions pass. |
| A7-04 | LUT rendering and sharing tables could commit different generations | New `SPK3` cache stores baked floats and original cube in one atomic file. A blocked legacy cube write no longer affects the commit; truncated records fail both reading paths. SPKL/SPK2 files keep their original baked values and remain readable. |
| A7-05 | CRX quantizer/residual operations admitted invalid shifts and signed overflow | LibRaw 0027 validates representable QP/shift ranges and uses checked 64-bit intermediate arithmetic. The real decoder is compared with an independent oracle, retaining the earlier maintenance rule for negative indices. |
| A7-06 | Phase One S read before checking EOF and treated a byte count as a word count; 32-bit masks and residual arithmetic were undefined | LibRaw 0028 uses a byte pointer, bounded memcpy/zero padding for the last partial word, pre-read EOF, 64-bit masks and checked decoded arithmetic. Unaligned and exact-final-byte cases are covered. |
| A7-07 | Olympus used undefined shift masks and insufficient refill/width validation | LibRaw 0029 uses bounded masks and validates exact reads, residual widths and tags. Invalid/truncated inputs throw instead of consuming invalid data. |
| A7-08 | The earlier GCC 11 instrumented pipeline recovery diagnostic remained unexplained | Engine 0060 reads each stage member-function pointer into a local before invocation. Both GCC 11 and Clang 14 pass the actual 42-check recovery harness. Normal stage order/function and numeric expressions are unchanged. This is not a general compiler-defect proof. |
| A7-09 | Installation did not revalidate identity and could leave a failed installer session | Recheck package, strictly newer version, non-debuggable flag and certificate immediately before creating the session; abandon an uncommitted session on failure. Six Robolectric tests cover rejection, acceptance and session cleanup. |
| A7-10 | Import/library callers could dismiss a failed save/delete without useful feedback | Report failures, preserve retry state and prevent duplicate save taps. Report partial film/style saves and failed cleanup explicitly. |
| A7-11 | Profile deletion removed the user original before a possibly failing engine-copy deletion | Delete the disposable engine copy first and the user original last. A new failure test reproduces original loss on the old ordering; all ten persistence tests pass after the fix. |

## Cloud Verification

Final source replay used verified upstream archive SHA-256
`52ef254ffa7c70484a411c269f3f837f75d5a215ed7332d5fdab267317408f05`
and LibRaw 0.22.2 archive SHA-256
`02275a04cf0d1477ab9fd7ee231ddea59e7b14497458da6e3ba957517d6655c7`.
All 60 engine and 23 LibRaw patches apply with `--fuzz=0`. Failed earlier
attempts are retained: a short context block in 0028 needed to be combined
before zero-fuzz replay; no failed attempt is counted as a passing run.

AOIDC final run `gpt-alpha7-final-r3-20261114` verifies every uploaded input
before and after the Android run. It passes 221 Release tests and 340 Debug
tests, with zero failures, errors or skips, and lint has zero errors. Both GCC
11 and Clang 14 with ASan/UBSan pass 1,515 actual CRX/oracle cases, 16,235 Phase
One bitstream checks and 44 Olympus checks. IBX independently passes all 1,515
CRX cases using GCC 14 and ASan/UBSan. Tests use controlled synthetic inputs;
they do not substitute for affected real-camera files.

The runtime dependency query checked 112 Maven components against OSV with no
reported advisories on 2026-11-14 (local date). This is a point-in-time query,
not a native-library scan or assurance that dependencies contain no defects.

The updated CI runs Release/Debug tests and release lint, full native
adaptation regressions in strict and compatible modes, complete sanitized
LibRaw storage/precision/linear/working paths, LUT interpolation parity, Vulkan
FIR/view/order/finite-output checks and pipeline failure recovery. Its final
run identifiers and package results will be appended after completion.

Candidate Actions `43539700200` (`f26d398`) passes the full build, 221 Release
and 340 Debug tests, lint, both native adaptation modes, RAW storage sanitizers
and Vulkan checks. FIR tests compare 74,180,082 float32 values in 368 cases,
all byte-identical; statistics pass 1,460 production vectors, and recovery
passes 42 checks. The final inspection step correctly prevented publication
but failed on a verifier parser assumption: newer apksigner output uses
`V2 Signer:` instead of `Signer #1`. The actual certificate is the published
one. The parser now accepts both real formats, still requires one signer and
the exact certificate, and passes six acceptance/rejection checks. That failed
run is retained as evidence and is not counted as a successful package gate.

The final profile deletion fix was also checked before/after in cloud:
one test fails on the previous ordering with the user original missing, then
all ten UserLibraryPersistenceTest cases pass after the change. The main-branch
CI must repeat the complete tests with this final code before publishing.

## Static Diagnostics Triage

The actual ARM64 compilation database contains 118 translation units; all 118
completed Clang analysis. Plists contain 52 diagnostics. One additional JNI
use-after-free warning is text-only because the analyzer could not emit its
cross-file plist path, so the total reviewed warning count is **53**, not 52.
The three fixed decoder translation units were then reanalyzed against the
complete final overlay: zero bitwise-shift diagnostics remain, with one
existing dead-store diagnostic per file. Source hashes and reports are in
`tools/native/validation/alpha7-audit-20261114/public-evidence/reanalysis/`.

| Diagnostic group | Disposition |
| --- | --- |
| CRX shift, Phase One 32-bit shift, two Olympus shift paths | Confirmed; addressed by 0027-0029 and real-source sanitizer tests |
| OutputTransform null source (2) | Report assumes both integer and float sources null; row-source constructors validate their respective non-null source before publication |
| JNI blank_result use-after-free (1, text-only) | Failed renders stop before validation; result-free resets the result and ResultGuard owns its output. The reported path does not model this C ABI state transition |
| Panasonic bytes pointer | Report changes `pana_encoding` from non-5 at the caller to 5 inside the callee; that member is unchanged between them. Encoding-5 callers pass a real byte array |
| Fuji q_bases null / common_info stack free | Report changes the lossless flag across calls; lossless keeps stack common_info and no q_bases, lossy allocates its own tables and common_info |
| Phase One black tables (4) and CR3 allocation sizeof | Actual allocation byte counts match the paired short rows / INT64 representation; type-oriented sizeof diagnostics do not demonstrate underallocation |
| Leaf pixel null / leak (2) | Report changes filters after choosing borrowed raw storage versus an owned row; the production flag is invariant through that loop |
| Kodak buffer (2), Sony ARW2 pix, median neighbors (2), highlight hsat, spline row pointers | Loop/path analysis limits. Actual loops initialize the consumed elements; app RAW parameters keep Sony special modes off. Spline assigns each row after checked allocation. No uninitialized production-input path was reproduced |
| normalize_model orig (11) | Constant alias lists start with a canonical @ entry before aliases; the report loses this table ordering. The prior race fix keeps orig local |
| Remaining dead stores | No observed state or numeric result depends on the discarded assignment; retained to avoid unrelated third-party algorithm churn |

LibRaw's member malloc/calloc throw on allocation failure; they are not plain
C allocation calls that return null to these decoder bodies. Callbacks in the
app provide cancellation and do not mutate decoder geometry/format flags.
These dispositions apply to the current application configuration. They are
not a proof for arbitrary external users modifying LibRaw internals/callbacks.

Lint's existing warnings are mostly KTX/style suggestions, guarded newer API
constants, process singletons using applicationContext and update suggestions.
No speculative UI/dependency rewrite was made solely to make warning counts
zero. Full reports remain evidence, including the residual warnings.

## Signing and Phone Boundary

Published certificate SHA-256:
`CD:C5:E3:D0:AC:6F:C9:0B:53:E8:8C:57:E0:40:93:36:72:5A:E3:C2:6F:D1:9D:1A:7E:58:51:70:94:5F:6D:E3`.
The private key remains in GitHub Secrets. No device protection is disabled,
and no uninstall or data erasure is part of this work.

The original phone message and device/OEM version are not available. Android
signing and Play Protect guidance distinguish signing identity from device
reputation checks. A correctly signed APK alone does not prove that an unknown
installer warning has disappeared. Preserve this as an explicit unverified
item until the original text/screenshot and a phone result are supplied.

- https://developer.android.com/studio/publish/app-signing
- https://support.google.com/googleplay/answer/2812853
- https://developers.google.com/android/play-protect/warning-dev-guidance

The previous chat's generic risk flag did not provide a precise triggering
action. This continuation stays within authorized defensive source review,
normal release signing and standard installation; it makes no claim to have
identified or circumvented that flag.
