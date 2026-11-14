# Alpha 8 Actual-device Acceptance

Co-created with GPT (OpenAI), 2026-11-14. Times below are Asia/Shanghai.

## Accepted Package

The user reconnected USB. The corrected Alpha 8 candidate was installed in
place at 22:23:38 and tested through 22:55 on the Xiaomi 13 Ultra / 2304FPN6DC,
Android 16 / API 36, HyperOS OS3.0.308.0.WMACNXM, Adreno 740, 1440 x 3200.
The application remains in Chinese at the end of this session.

- Source: `b1c61bdde739022fd6d6b60b49d8c57b1822dbdb`.
- Version: `0.2.00-alpha-8`, code `128`, package `com.dearded.spektralab`.
- APK: 38,780,914 bytes; SHA-256
  `a806e456cbcdb16f67449a4bbe7396637d1bf83f2b7b3140a3864cd34c2214a3`.
- Published certificate SHA-256:
  `cdc5e3d0ac6fc90b53e88c57e0409336725ae3c26fd19d1a7e585170945f6de3`.
- Final signed Actions build: `43603540709`, run 280, successful at 22:20:44.
- Local deliverable:
  `D:/AGI/GPT/releases/Spektralab-X/0.2.00-alpha-8-candidate-r2/`.

This candidate is accepted for the cases below. It is not a published Alpha 8
release. Main and the published Alpha 7 remain unchanged.

## Actual-phone Results

| Case | Observed Result | Evidence |
| --- | --- | --- |
| A8-D01 | Both updates used `adb install -r`. First-install time remains November 4, 16:15:44. The previous Bayer DNG editor restored, and the two original library items, five-star rating and picked flag remained present. | `a8-00`, `a8-04`, `a8-33`, `a8-43`, package before/final records |
| A8-L01 | Immediately after the update, Settings and editor were Chinese. Expanding Language offered Chinese and English. No preference or app data was cleared. | `a8-05`, `a8-06` |
| A8-L02 | A fresh 1600 x 1000 PNG at +0.50 EV stayed open with the same edit while Settings and editor switched to English. Its supplied filename remained unchanged. | `a8-08` through `a8-10` |
| A8-L03 | Home/return retained English and the edit. After selecting the PNG through the system file picker and setting +0.75 EV, an actual force-stop/new process restored English, the same source and +0.75 EV. PID 25704 became 27046. | `a8-11`, `a8-15` through `a8-18`, process record |
| A8-L04 | Switching back to Chinese and deliberately restarting the process retained Chinese, the PNG and +0.75 EV. PID 27046 became 27541. | `a8-19` through `a8-21` |
| A8-F01 | Fresh PNG/DNG decoded and edited. JPEG, RGB16 TIFF and HEIF exported; HEIF reimported at 1600 x 1000. All three PNG exports had MediaStore pending=0 and correct indexed dimensions. Independent cloud decode/metadata checks passed. RAW JPEG was 1024 x 768. | `a8-07`, `a8-25` through `a8-30`, MediaStore rows, cloud export verification |
| A8-F02 | Repeated MediaStore VIEW intents were delivered to the current top editor and opened the requested valid source. Invalid JPEG produced a decoder error; dismissing it retained the previous HEIF. PID 27541 still had one Activity. | `a8-28`, `a8-30` through `a8-32`, Activity/memory records |
| A8-Q01 | The two fresh sources were added to the library, given the same film style, and exported as a two-frame automatic roll. Both JPEGs published with pending=0. After PID 27541 became 2288, the queue still showed all 19 saved items, and the library retained four frames, including the original ratings and two new edits. | `a8-35`, `a8-37` through `a8-43`, MediaStore rows |
| A8-P01 | The actual 3072 px built-in synthetic benchmark ran once and displayed results in Settings. First preview 534 ms; style switch 315 ms; export render 1429 ms. Thermal headroom 0.15 to 0.15; peak RSS growth could not be read. | `a8-22` through `a8-24`, memory/thermal captures |

The initial external-open restart used a temporary ADB-granted MediaStore read
permission. After force-stop, English persisted but the source was no longer
readable and was not restored (`a8-12` through `a8-14`). This is not counted as
successful photo restoration. The subsequent system-picker path supplied
persistent access and passed the combined source/edit/language restart cases.
The test does not promise restoration after a provider revokes read access.
The phone's server-side Activity dump retained the original root launch
intent, so it does not establish the Activity object's current `getIntent()`.
That replacement is verified by the cloud `ExternalOpenActivityTest`.

## Startup Failure Found and Fixed

The original candidate from source `c34657b` and SHA-256
`702914c627851263a93464943eb6d95e387cae269a18ebd6e62434987cb30a32`
passed cloud tests but failed actual Activity startup. Crashes were recorded
at 21:58:38, 21:59:41 and 22:17:34, before the corrected APK was installed.
`MainActivity.attachBaseContext` called the locale wrapper, which requested
the incoming context's theme before the Activity was fully attached.
`ContextImpl.getTheme()` then dereferenced null ApplicationInfo metadata.

The correction creates only a configuration context during Application and
Activity base attachment. The live Compose wrapper still uses the initialized
Activity theme and retains its context chain. A regression supplies an incoming
context whose theme is unavailable before attachment and checks both language
configurations. The original candidate is explicitly marked REJECTED in its
local verification record. It must not be substituted for the accepted APK.

AOIDC passed all 12 focused startup/language/Settings/external-open tests.
Two intermediate test assertions failed because Robolectric's Activity
resource shadow did not reflect the incoming override; the final regression
checks the configuration actually supplied during attachment. The abandoned
CI attempts were canceled. Final CI run 280 passes Release 222 and Debug 355
tests, with zero failures/errors/skips. Lint reports zero errors, 111 warnings
and six hints. RAW, Vulkan/FIR, native adaptation, LUT and package gates pass.

## Export Integrity

| Output | Bytes | Dimensions | Independently Confirmed |
| --- | --- | --- | --- |
| PNG edit to JPEG | 229713 | 1600 x 1000 | Display P3 ICC; both EXIF dimension pairs correct |
| PNG edit to TIFF | 464670 | 1600 x 1000 | RGB 16/16/16, sRGB ICC, Deflate strips fully decoded to 9600000 bytes |
| PNG edit to HEIF | 167417 | 1600 x 1000 | libheif actual decode, IFD0, ExifIFD and MediaStore dimensions all agree; sRGB |
| Neutral fresh DNG to JPEG | 53526 | 1024 x 768 | Published with pending=0 |
| Styled roll DNG to JPEG | 80742 | 1024 x 768 | Published with pending=0 |
| Styled roll PNG to JPEG | 407315 | 1600 x 1000 | Published with pending=0 |

Only public synthetic fixtures and their exports were transferred to AOIDC.
The accepted APK was also independently checked there: matching SHA-256,
archive CRC, published certificate, package/version, ARM64 ELF/ZIP 16 KiB
alignment, recomputed engine stamp and all 56 SPIR-V modules. Exit status is 0.

## Measured Performance and Stability

The benchmark uses a 3072 px synthetic image and renders its export without
saving it to the gallery. Its real results above must not be confused with
the injected 120/65/900 ms UI screenshot fixtures in the earlier code audit.
Its own recommendation was to disable live preview and use 3072 px as the
working size for this run. Peak RSS growth is unavailable, not zero.

- Benchmark-completion snapshot: PSS 404760 KiB, RSS 553992 KiB, one Activity.
- End-of-interval snapshot: PSS 418876 KiB, RSS 581716 KiB, one Activity.
- After the final restart/library visit: PSS 235624 KiB, RSS 371052 KiB,
  one Activity. These snapshots are not a measured memory peak or leak test.
- PID 27541 HWUI: 13556 frames; 143 deadline-janky frames (1.05%). CPU
  P50/P90/P95/P99: 10/18/21/46 ms. GPU: 5/13/14/15 ms. The separately reported
  legacy jank metric was 7200 frames (53.11%). These are interface metrics,
  not native photo-rendering FPS, and neither metric replaces the other.
- Final live HAL readings: battery 31.3 C, skin 33.067 C, CPU 34.8-37.1 C,
  GPU 34.3-35.5 C; thermal status 0. Battery service: USB charging, 100%,
  31.3 C. Older cached temperatures are not current readings or test peaks.
- Corrected-package cold Activity launches sampled 1082 ms initially, then
  350/365/362/481 ms. These are Activity launch times, not first-preview times.
- PID 27541 captured 22:32:40-22:54:22 and PID 2288 captured
  22:54:26-22:55:04. Neither capture contains FATAL EXCEPTION, Fatal signal,
  ANR in, VK_ERROR or OutOfMemoryError. ApplicationExitInfo after the corrected
  installation records the deliberate force-stops, without a new crash/ANR.

## Evidence and Limits

Public, sanitized machine evidence is under
[`validation/alpha8-device-2026-11-14`](validation/alpha8-device-2026-11-14/README.md).
Raw UI XML/screenshots, package records, queue/history screenshots and phone
logs remain local under
`D:/AGI/GPT/research/spektralab-20261114/i18n/alpha8-device-evidence`.
No phone logs, personal photos or history screenshots were uploaded.

The ADB long-press attempts did not establish grid multiselection: a stationary
900 ms gesture opened the loupe, and a slightly moving 1600 ms gesture did not
produce a selection. Multiselection and drag-selection are not accepted by
this session. The successful batch test used the two-frame automatic roll.
The native device probe still reports full shader parity pending; successful
Adreno import/render/export does not establish numerical parity of every shader.
No large real camera RAW, sustained thermal stress, AAB language splits or
exhaustive provider-permission lifecycle matrix was tested in this session.

No uninstall, application-data clearing or device-protection change occurred.
The earlier rejected diagnostic-helper installation and ADB reverse path were
not retried. No local Gradle build or executable host test was run.
