# Alpha 8 Goal Acceptance Audit

Co-created with GPT (OpenAI), 2026-11-14.

## Current Evidence

The full goal includes preserving existing phone data while updating the app,
Chinese/English Settings, language persistence across process restarts,
import/edit/export and lifecycle testing on the actual phone, cloud validation,
and delivery of the verified package, results and handoff records.

At 15:42 +08:00 on November 14, 2026, the local Windows present USB inventory
did not enumerate an Android/ADB interface. The user's latest direct device
report says the phone is disconnected. No ADB command was invoked during this
audit. The USB inventory does not establish any wireless ADB connection.

The current application source matches the verified source
`c34657bde1f0318b27c6f7c7efb9a947b99226b6`; subsequent changes are documentation.
The locally delivered APK was hashed again and matches
`702914c627851263a93464943eb6d95e387cae269a18ebd6e62434987cb30a32`.
It is version `0.2.00-alpha-8`, code `128`, 38,780,914 bytes, signed with the
published certificate. It has not been installed on the phone.

| Goal Requirement | Evidence Inspected | Acceptance Status |
| --- | --- | --- |
| Chinese default, including an English system configuration | `LocalizationTest.defaultsToChineseEvenOnEnglishSystemAndUnknownPreference`; final CI XML | Automated check passes; initial phone upgrade default pending |
| Settings switches Chinese and English, retaining the edit | Real Settings composition at 320dp, `LocalizationUiTest.settingSwitchesBothWaysAndRetainsTheOpenEdit`; final CI XML and screenshots | Automated check passes; candidate phone acceptance pending |
| Language preference persists and reloads | Explicit successful preference transaction and `LocalizationTest.selectionIsDurableAndReloadsAfterInMemoryLanguageChanges` | Automated reload passes; a real process restart on the phone is unverified |
| Preserve phone data during installation update | Alpha 7 device report establishes the installed baseline; candidate uses the same package and certificate | Candidate installation and before/after data comparison pending |
| Import, edit and export on the phone | Alpha 7 PNG/DNG, JPEG/TIFF/HEIF, edit and batch evidence | Baseline passes; final candidate flow and corrected HEIF indexing pending |
| Background, return and process restart | Alpha 7 lifecycle observations; candidate state tests | Candidate lifecycle combined with both languages pending |
| Use cloud resources for builds and automated checks | Final Actions `43570672053`; Release 222 / Debug 354, RAW/Vulkan/native/LUT checks; independent AOIDC package and native checks | Complete for the current candidate |
| Deliver the package, evidence and handoff | Local APK/checksum/verification record, committed audit evidence, both private local handoff files | Delivered; final device result must be appended after execution |

The objective is not complete. The successful cloud checks and the Alpha 7
device observations do not prove candidate execution on the phone.

## Remaining Phone Cases

Execute against the delivered candidate when the phone again has an authorized
ADB connection. Record actual results under these case identifiers; all cases
below are currently unexecuted on Alpha 8.

| Case | Operation | Required Evidence |
| --- | --- | --- |
| A8-D01 | Record installed package/version and visible existing edits/library state; update in place to code 128 | Package identity before/after, successful update result, matching certificate, retained existing edit/library state |
| A8-L01 | Open Settings after the update, before choosing a language | Chinese default and available Chinese/English choices; do not clear app data to obtain this result |
| A8-L02 | Open a synthetic photo, make a recognizable edit, switch to English in Settings and return | English Settings/editor, same source and edit values, user-supplied names unchanged |
| A8-L03 | After edits are saved, go Home, return, then deliberately force-stop and launch again | English retained after both transitions, a new process for the second transition, same saved edit and source |
| A8-L04 | Switch back to Chinese and repeat the saved-state process restart | Chinese retained, Settings remains usable, saved edits and source retained |
| A8-F01 | Use fresh copies of the public synthetic PNG and Bayer DNG; import, adjust and export JPEG, TIFF and HEIF | Actual output dimensions and color metadata, successful MediaStore publication; HEIF IFD0 and ExifIFD dimensions agree with actual pixels and indexed dimensions; successful reimport |
| A8-F02 | Open files repeatedly through the external provider; attempt the deliberately invalid JPEG | Current intent/source wins, no duplicate top editor under the tested route, invalid-input error retains the previously open photo |
| A8-Q01 | Batch-export the two synthetic sources and revisit the queue/library after restart | Outputs published with `IS_PENDING=0`, queue/history consistent, pre-existing user items retained |
| A8-P01 | Run the actual performance test once and inspect Settings results | Actual first-preview/style/export measurements, memory/thermal observations and result screenshot; record a thermal abort accurately if encountered |

Use fresh synthetic file names for candidate export checks, or reset only the
current synthetic edit, so Alpha 7's saved crop/style does not silently change
the expected output dimensions. Personal photos and existing user edits are
not test fixtures. Private screenshots and device logs remain local.

An installation restriction or signature/version error must be recorded as a
failure, without uninstalling, clearing app data or changing device protection.
The previous rejected diagnostic-helper installation and ADB port-forward path
remain closed; none of these acceptance cases requires either operation.

## Evidence References

- [Code changes and cloud validation](ALPHA8_CODE_AUDIT_2026-11-14.md)
- [Final CI, package inspection and independent cloud results](validation/alpha8-code-audit-2026-11-14/README.md)
- [Installed Alpha 7 device baseline](REAL_DEVICE_2026-11-14.md)

The screenshot benchmark values are injected UI fixtures. They are not actual
phone performance measurements. HWUI frame statistics in the baseline report
are interface measurements and are not native photo-rendering FPS.
