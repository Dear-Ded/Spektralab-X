# Alpha 8 audit and release

Co-created with GPT (OpenAI), 2026-11-15, Asia/Shanghai.
Published: `0.2.00-alpha-8`, Android version code 128.

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
The expanded release inventory covers 662 files / 4,363,797 bytes, including
the Windows wrapper, R8 rules, symbol exports, signing identity, sample tables
and root configuration in addition to the 651-file intermediate source set.
All byte hashes match and 222 syntax checks pass. The first complete
cloud suite passes 225 Release / 371 Debug cases with no failures/errors/skips,
and lint reports zero errors / 100 warnings / 6 hints in the offline cloud
environment. The final fixes add five package-boundary tests and two navigation
tests. All 17 final targeted cases pass, including language and Settings UI.
The final complete AOIDC suite passes **230 Release / 378 Debug** cases with
zero failures, errors or skips. Final offline lint reports **0 errors / 100
warnings / 6 hints**. The production GitHub suite also passes **230 Release /
378 Debug**, with zero failures, errors or skips; its online lint reports
**0 errors / 111 warnings / 6 hints**. Raw XML and job results are retained in
`validation/alpha8-release-2026-11-15/production-ci/`.

## Published Package

The public Alpha 8 prerelease was published at **2026-11-15 02:23:33 +08:00**
(2026-11-14 18:23:33 UTC). Android run **285 / 43625578747** completes all four
jobs successfully: APK, RAW storage, Vulkan FIR rows and release. Screenshot
run **93 / 43625578680** also succeeds. Both use source
`430d7f3069d322a0d7964aeac6a95c9e168f4465`; application code is commit
`0f9d6656c4562d3293941a2a3485f212c8031282`, followed only by audit evidence.
The Git archive of the release commit independently matches all 662 inventoried
file bytes. Its SHA-256 is
`292b07c7f926c030fe99a05c43bbf8e3f210c03a485a72f9b0c51579e6f02063`.

| Package identity | Verified value |
| --- | --- |
| File | `Spektralab-X-0.2.00-alpha-8-arm64-v8a.apk` |
| Bytes | 38,780,914 |
| SHA-256 | `bafa2eedb2c0ec4edee284e4d11632acbc43994eb6b976f306577d49dfceb129` |
| Package/version | `com.dearded.spektralab`, `0.2.00-alpha-8`, code 128 |
| Original signing certificate SHA-256 | `cdc5e3d0ac6fc90b53e88c57e0409336725ae3c26fd19d1a7e585170945f6de3` |
| Engine stamp | `ae0291630fc4f07708f735bffd3ed6a2` |
| Native package gate | Two ARM64 libraries, ZIP/ELF 16 KiB alignment, RELRO, non-executable stack, no writable executable LOAD |
| Engine/JNI gate | 113 staged assets, 56 SPIR-V modules, all 39 declared JNI methods present |

The APK downloaded from the public release matches the Actions APK, published
SHA256SUMS and release asset digest exactly. GitHub provenance verification
passes with an exact workflow, source commit and main-ref policy, rejecting
self-hosted runners. The verified certificate identifies
`.github/workflows/android.yml@refs/heads/main`, source digest `430d7f3...`,
`github-hosted`, and run 43625578747. The complete verification JSON is retained;
no signing private key was downloaded or created on this machine.

The published build includes Chinese as the default and a persistent English
switch in Settings, bilingual search, startup and import lifecycle fixes,
HEIF primary-image dimensions, native cancellation ownership, and the accepted
cross-row long-press selection fix. The later memory/queue refinements are
explicitly separated from the installed candidate's device observations.

Production Vulkan FIR comparison passes **368 cases / 74,180,082 float32
values byte-identical** on llvmpipe. Pipeline recovery passes **42 checks**;
metering/order statistics pass **1,460 production vectors**. RAW regression
includes **1,515 CRX cases**, **16,235 Phase One bitstream checks** and **44
Olympus checks**. Imported LUT interpolation compares 16,384 pixels with zero
float/16-bit difference under ASan/UBSan. These synthetic cloud results do not
establish Adreno equivalence. The 320 dp English Settings and queue screenshots
were visually checked with the successful screenshot run.

## Independent Download Verification

AOIDC independently checks the public download's checksum, ZIP CRC, original
certificate, release package/version, non-debuggable ARM64 package, ZIP/ELF
16 KiB alignment and recomputed engine stamp. All 56 packaged shaders pass
`spirv-val --target-env vulkan1.0`; every DEX is free of the temporary grid
trace tag. A separate package inspection passes DEX integrity, ELF structure,
all 39 JNI declarations/exports, permitted dynamic dependencies and all 113
asset byte comparisons against independently generated resources.

The first asset comparison correctly rejects AOIDC's glslang 11.8.0 output:
all 56 non-shader resources already match, but its 56 shaders and derived stamp
differ from production glslang 15.1.0. Generator versions, decoration ordering
and added access decorations differ in the retained affine-shader disassembly.
Instead of relaxing the byte comparisons, AOIDC builds glslang **15.1.0** from
the upstream release archive (SHA-256
`4bdcd8cdb330313f0d4deed7be527b0ac1c115ff272e492853a6e98add61b4bc`)
and regenerates assets from the audited, pinned source. All **113 / 113** assets
then match the public APK byte-for-byte, with the same stamp. The initial
compiler setup attempt rejects CMake 3.22.1; an isolated CMake 3.31.6 completes
the build. These unsuccessful attempts remain explicit evidence. The final
independent verification scripts both exit zero, with assertions unchanged.

Evidence is retained in `validation/alpha8-release-2026-11-15/independent-verification/`,
alongside production XML/provenance in `production-ci/` and the canonical Git
byte verification in `aoidc/git-source-verification.json`. The 662-file source
inventory SHA-256 is
`f43fddfdd5c7747f13a11fb4a46690b93d08bdcb971accb898bfd12cfd6f0e80`.
All compilation and executable testing took place in cloud environments.
Private phone logs, UI trees, screenshots, histories and local handoffs were
not uploaded. Documentation commits after publication do not change the APK's
verified source digest.

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

## Final Native Diagnostics

All **118 / 118** actual ARM64 compilation units complete successfully after
refreshing the current overlay. Plists and text each contain **48** warnings;
JNI has **zero**. There are no remaining bitwise-shift diagnostics. All 48
correspond to previously reviewed categories from the
[Alpha 7 triage](ALPHA7_AUDIT_2026-11-14.md#static-diagnostics-triage):

| Diagnostics | Disposition for this application configuration |
| --- | --- |
| 18 dead stores | No observed state/numeric effect; avoid unrelated third-party algorithm edits. |
| 11 normalize_model nullability paths | Alias tables establish a canonical entry first; analysis loses the constant table ordering. |
| 6 null dereferences | Two OutputTransform paths assume both validated row sources null; Panasonic/Fuji/Leaf paths vary invariant decoder flags; spline rows are checked allocations. |
| 5 allocation-size warnings | Actual short-pair row/INT64 byte allocations match the consuming representations. |
| 2 malloc warnings | Fuji lossless stack state cannot follow the lossy free branch; Leaf borrowed/raw storage follows invariant filters. |
| 4 undefined-operand / 2 uninitialized warnings | Existing Sony/Kodak/postprocessing loop paths; prior inspection found assignments/production mode guards on consumed elements. No new input path was reproduced in this audit. |

These are residual diagnostics, not 48 newly confirmed defects and not a claim
that every hypothetical path is impossible for other LibRaw consumers. Full
source identities, reports and unsuccessful attempts are retained under
`validation/alpha8-release-2026-11-15/aoidc/`.

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
