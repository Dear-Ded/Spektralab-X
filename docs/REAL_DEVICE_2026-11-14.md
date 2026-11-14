# Real-device validation, 2026-11-14

Status: baseline testing completed on the installed official Alpha 7. The phone
was disconnected by the user after capture. New source changes have not been
installed or verified on the phone. The English-language feature is still in progress.

## Device and package

- Xiaomi 13 Ultra / 2304FPN6DC, SM8550, arm64-v8a, Android 16 / API 36,
  HyperOS OS3.0.308.0.WMACNXM, physical screen 1440 x 3200.
- Adreno 740, Vulkan 1.3.128, verified single-buffer limit 134217728 bytes,
  application pixel cap 8388608. The Vulkan probe reports full shader parity pending.
- Installed version 0.2.00-alpha-7 / code 127. The pulled base APK is 38649694
  bytes, SHA-256 `c182b9af71513596219a0c89096cf0323566e52577a8ebbc29a2135090031e66`,
  matching the published release. No uninstall or data clearing was performed.
- Tests used a synthetic 1600 x 1000 PNG, a synthetic 1024 x 768 Bayer DNG,
  and a deliberately invalid JPEG. Personal photos were not uploaded.

## Observed Results

| Operation | Evidence and result |
| --- | --- |
| PNG import and film style | Image opened and rendered; Vision3 250D / 2393 style applied. |
| JPEG work export | 1600 x 1000, 407291 bytes; Display P3 ICC independently confirmed. |
| TIFF sRGB export | 1600 x 1000, 7896708 bytes, RGB 16/16/16 bits, sRGB ICC; all Deflate strips decompressed on the cloud to the expected 9600000 bytes. |
| HEIF export and reimport | 182838 bytes; Android reimport and independent libheif decode both produce 1600 x 1000. Incorrect IFD0 dimensions are described below. |
| Detail view | HEIF full image opened at 1:1 and returned to fit view. |
| Exposure and lifecycle | +0.50 EV retained after Home and return. Exposure and geometry edits restored after a deliberate force-stop and restart. |
| Crop, rotation, flip | 3:2 crop, 90-degree rotation and horizontal flip exported as 1000 x 1500 JPEG, 361393 bytes. |
| RAW development | Synthetic Bayer DNG decoded at 1024 x 768; neutral and experimental standard development controls exercised. Standard development JPEG: 1024 x 768, 49428 bytes. |
| System document picker | DNG selected through the application file picker and opened successfully. |
| Invalid input recovery | Invalid JPEG selected through that same picker displays an error. Dismissing it retains the original DNG in the same Activity. An earlier external-open test created a fresh Activity and cannot be used as evidence of lost state. |
| Light table | Two synthetic sources imported; five-star rating and picked flag retained when returning to the grid. The synthetic DNG has no embedded preview and its grid tile is a placeholder; RAW editor decoding was tested separately. |
| Roll batch export | Two JPEGs published with IS_PENDING=0: 1024 x 768 / 53502 bytes and 1000 x 1500 / 324346 bytes. |

Launch samples from `am start -W` were 433 and 529 ms for cold launches and
34-101 ms for sampled warm launches. These are Activity launch times, not image
decode, render, or export timings.

## Performance and Stability

- The isolated editing interval recorded 1056 HWUI frames, 18 janky frames
  (1.70%), CPU frame percentiles P50/P90/P95/P99 = 10/17/27/65 ms,
  GPU percentiles = 3/7/11/13 ms. The legacy jank count was 678 (64.20%).
- The longer final process interval recorded 42961 frames, 1233 janky frames
  (2.87%), P50/P90/P95/P99 = 11/17/18/36 ms. These are interface statistics,
  not native Vulkan photo-rendering FPS; different intervals are not merged.
- Memory snapshots included PSS/RSS of 358915/508064 KiB after HEIF,
  459782/599036 KiB during benchmark-related work, and 483822/642640 KiB
  at the final capture. The final snapshot contained four Activities from
  repeated external opens, so it is not a single-editor memory baseline.
- Final live HAL values: battery 30.9 C, skin 32.64 C, CPU 34.0-35.6 C,
  GPU 33.1-33.9 C; thermal status 0. Battery service reported 100%, USB power,
  30.9 C. Cached thermal entries include older, higher values and are not
  presented as current readings or measured test peaks.
- App PID 14925 log capture covers 13:54:01-14:25:45. No matching FATAL
  EXCEPTION, Fatal signal, ANR, VK_ERROR, or OutOfMemoryError was found in
  that capture. ApplicationExitInfo shows the deliberate 13:54:01 force-stop;
  no additional crash/ANR exit from this test interval was observed.
- The built-in 3072 px synthetic benchmark was invoked and its style-switch
  progress observed. Exact first-preview/style/export timings have not been
  retrieved. A settings screenshot named `benchmark-result` contains no
  numeric result and must not be treated as successful benchmark evidence.

## Confirmed Defect and Fix

HEIF pixels and its primary image are 1600 x 1000, but the borrowed JPEG APP1
carrier left IFD0 ImageWidth/ImageLength at 1 x 1. ExifImageWidth/Height were
already correct. MediaStore indexed the incorrect IFD0 pair as 1 x 1.
`ExifCopy.applyTo` now writes both dimension pairs from the final output size.
The cloud Robolectric regression `HeifExifTest.carrierDimensionsDescribeTheExport`
passed (1 test, zero failures/errors/skips; Gradle BUILD SUCCESSFUL).
Phone confirmation of the fixed APK remains pending.

## Evidence and Limits

Raw device evidence is retained locally under
`D:/AGI/GPT/research/spektralab-20261114/i18n/device-evidence` (35 named UI
captures plus screenshots, synthetic exports, process logs and metrics).
Queue/history screenshots and full device logs stay private. Independent
synthetic export checks ran on AOIDC using libheif, ExifTool and a full TIFF
strip decompression script. No local Gradle build or host test was run.

A temporary diagnostic fixture APK could not be installed: Android returned
INSTALL_FAILED_USER_RESTRICTED. It was not installed and no protection setting
was changed. Automatic approval separately rejected an ADB reverse-port command
with `blocked by policy`; that operation was stopped and its unused local
server was closed. No benchmark values were inferred from either failed path.

Follow-up localization, code corrections, cloud regressions and an official
signature Alpha 8 candidate are completed in
[the code audit](ALPHA8_CODE_AUDIT_2026-11-14.md). Candidate installation,
Chinese/English switching and persistence, and fixed HEIF metadata still need
a later connected-device verification.
