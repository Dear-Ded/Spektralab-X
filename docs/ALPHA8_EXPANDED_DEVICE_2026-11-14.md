# Alpha 8 Expanded Actual-device Testing

Co-created with GPT (OpenAI), November 14-15, 2026. Testing began November 14
at 23:10 and continued after midnight on November 15. Times use Asia/Shanghai.

This extends, rather than replaces, [the initial Alpha 8 acceptance](ALPHA8_DEVICE_ACCEPTANCE_2026-11-14.md).
The same Xiaomi 13 Ultra / Android 16 / Adreno 740 was connected over USB.
Only public synthetic images were edited; the original library entries and
ratings were retained. All builds and executable host tests ran in the cloud.

## Reproduced Defect and Correction

The installed source `b1c61bd` reproduced the previous grid-selection failure.
A separate DOWN, held for 900 ms, then UP entered the loupe without leaving a
selection. An 1800 ms slightly moving gesture also left no selection. Cloud
Compose pointer tests independently failed on both the first and second rows.
These tests inject touch events, rather than invoking accessibility actions.

Tracing established that the grid recognized the long press. Two defects were
present: a child's normal click handled release before its parent drag detector
and could toggle the newly selected cell off; hit testing used item offsets
without subtracting the grid's top and side content padding. Near a cell's
lower edge this could select the next row instead.

The first correction, source `87f6c1b` / signed run 281, fixed stationary and
near-bottom presses and horizontal selection on the phone. Actual selection
batch export produced two valid JPEGs. Chinese/English, a real process restart,
the persistent DNG and its Vision3 edit also passed. However, diagonal dragging
across rows still selected only its starting frame. Cloud tests using small
movement steps did not reproduce this remaining failure.

Disabling manual scrolling after selection (`6a3e724`, signed run 282) passed
eight cloud tests but did not fix the phone. That experiment was withdrawn.
Temporary coordinate tracing (`f913635`, signed run 283) then showed the diagonal
gesture being canceled immediately after long-press recognition, before any drag
callback; horizontal movement continued normally. The trace contained only
coordinates and grid indices and remains local.

A cloud pointer test with a single first movement across the scroll touch slop
fails against `f913635`, selecting only the starting cell. This establishes a
reproducible regression rather than relying on the earlier small-step tests.
The internal vertical scroll recognizer can consume that first large movement
before the parent's Main-pass drag detector receives it.

Final corrected source `c4a45e576a1dd694371542adc663252b5ae56d1d` waits for a
long press, then handles movement and release in the Initial pass. It retains
the padding correction, ordinary scrolling before a long press, short clicks,
accessibility long-click, two-finger column zoom and programmatic edge scrolling.
All temporary trace logging and the unsuccessful scroll-disable experiment
are removed. Eleven focused cloud tests pass, including both directions with
a first large movement, selection recomposition, release/next click, normal
scrolling after release and a second finger ending selection while zooming.
The failing single-movement baseline and successful eleven-test XML/logs are
retained in the public evidence.

Signed run 284 (`43617721123`) completed successfully on November 15 at 00:56
local time: Release 222 / Debug 366 tests, zero failures/errors/skips; lint zero
errors, 111 warnings and six hints. RAW, Vulkan/FIR, native adaptation, imported
LUT and package gates passed. Publishing was skipped on the research branch.

The final 38780914-byte APK has SHA-256
`08597164a18553b73c21654c58803492545df65951fd20bb01554c0cbdad04c8`.
Its certificate matches the published certificate
`cdc5e3d0ac6fc90b53e88c57e0409336725ae3c26fd19d1a7e585170945f6de3`.
AOIDC independently passed checksum, archive CRC, package/version, ARM64,
ELF/ZIP 16 KiB alignment, engine asset stamp, all 56 SPIR-V checks and absence
of the temporary trace tag from every DEX, exit 0. The local APK hash also
matches both SHA256SUMS and the independent CI package manifest.

Installed with `adb install -r` on November 15 at 00:59:41. The original first
install remains November 4 at 16:15:44. Stable-grid cross-row dragging succeeded
in both directions, followed by six alternating repetitions after a real
process restart: eight accepted cross-row gestures total. A stationary press
near the lower cell edge selected the correct single frame; tapping cleared
selection, and the next short tap opened its loupe. Horizontal selection again
selected both public fixtures, and batch export published pending=0 JPEGs at
1024 x 768 and 1600 x 1000. Both hashes match the earlier independently decoded
selection exports exactly. The original four library entries, two edits and
the original picked/five-star marks remain present.

One gesture injected immediately after starting navigation did not form a
selection. Its private evidence is retained, and it is not counted as a pass.
Later tests waited for the actual grid layout before injection; their stable
coordinates reproduce the old failing path. The timing-dependent first attempt
does not establish a separate cause and is a follow-up navigation/input case.

English process restart 9444 -> 11811 and Chinese restart 11811 -> 12315 each
restored the persistent DNG, Vision3 edit and selected language. Activity launch
times were 1043 ms after installation, then 608 ms and 404 ms on these restarts;
they are not photo first-preview measurements. The original "Original size"
setting was confirmed, and the phone was returned to the Chinese editor. After
restart the queue showed 29 saved and one canceled in its newest 30 records;
all new outputs are separate from that bounded history. Local delivery is
`D:/AGI/GPT/releases/Spektralab-X/0.2.00-alpha-8-candidate-r6/` with `VERIFIED.txt`.

## Additional Actual-phone Evidence

| Exercise | Result |
| --- | --- |
| Loupe paging | 20 alternating swipes between the new DNG and PNG. Checks at 5/10/15/20 show the expected filename and 1/2 or 2/2 position. |
| Style changes | 12 additional switches between CineStill 800T and Vision3 250D, returning to Vision3. No extra Activity observed. |
| Home and display sleep | Home/return and sleep/wake retained the current DNG, style and Chinese interface in the same process. |
| Repeated export | JPEG, RGB16 TIFF and HEIF twice each from the styled 1024 x 768 DNG. All six published pending=0; each same-format pair was byte-identical. |
| Independent decode | Cloud Pillow, ExifTool and libheif decoded all six at 1024 x 768. Each TIFF fully decompressed to 4718592 bytes with 16/16/16 samples. HEIF pixel, IFD0 and ExifIFD dimensions agree. |
| HEIF reopen | The second HEIF reopened on the actual phone at 1024 x 768; a subsequent VIEW opened the requested PNG in the same Activity. |
| Large input | Public 6000 x 4000 sRGB PNG decoded, applied Vision3, and used a 3547 x 2364 working image. This is a highly compressible synthetic chart, not a camera RAW test. |
| Working size comparison | Original setting was "Original size". Temporarily selecting 2048 and 3072 produced 2048 x 1365 and 3072 x 2048 on the same large chart. Both rendered Vision3. Original size was restored before replacing the APK. |
| Large high-quality export | JPEG decoded at 3547 x 2364, 4103591 bytes, Display P3 ICC, 4:4:4 sampling. MediaStore pending=0 and EXIF dimensions agree. |
| Background publication | Normal large JPEG published while MIUI launcher remained top resumed: 3547 x 2364, 1512671 bytes, Display P3, 4:2:0 sampling, pending=0. Observed 6538 ms after going Home; includes ADB/polling overhead. |
| Queue | After six repeated exports the queue showed 25 saved; after the large high-quality export it showed 26. The next normal large export adds a further saved output. No history clearing. |
| Actual selection export on replacement | Horizontal drag selected the two edited fixtures. Their JPEGs published pending=0 at 1024 x 768 and 1600 x 1000, independently decoded with correct EXIF dimensions and Display P3 ICC. Queue reached 29 saved and survived the new process. |
| Cancellation and retry | A later large high-quality job was canceled while active. Saved count remained 30; the canceled item remained retryable. Retry generated a new 3547 x 2364 JPEG and raised the saved count to 31, with one canceled item retained in 32 history entries. The owned MediaStore query contained no pending residue. |
| Finished-history limit | The next actual process showed 29 saved plus one canceled in 30 finished records. `ProcessingQueue.persist()` intentionally retains the newest 30 finished records, plus every active or unresolved-gallery job. The two large JPEGs remained in MediaStore pending=0 and both matched the earlier high-quality export's SHA-256. |
| Final corrected package | Eight stable-grid cross-row drags, both directions, plus correct stationary selection and click behavior. Horizontal selection batch published two more JPEGs matching the previously cloud-decoded outputs byte-for-byte. English and Chinese each survived a real process restart with the same persistent DNG and edit. |

The first background export's initial query returned no row yet; the queue and
subsequent MediaStore query confirmed its successful publication. Only the
second background run explicitly observed publication while the launcher was
still foreground. Do not assign the first run that stronger guarantee.

The rapid three-click queue attempt actually admitted one large job, which
completed and raised saved count from 29 to 30. It is not evidence of three
queued jobs. A separately admitted following job was the one canceled/retried.
No history was manually cleared. Restart applies the existing 30-record
persistence limit; it is not a lifetime export counter. This distinguishes
requested taps from admitted jobs, and retained history from saved image files.

## Measurements

| 3072 px Built-in Synthetic Benchmark | Earlier Acceptance | Extension Run 2 | Extension Run 3 |
| --- | ---: | ---: | ---: |
| First preview, ms | 534 | 295 | 168 |
| Style switch, ms | 315 | 290 | 294 |
| Export render, ms | 1429 | 1347 | 1370 |
| Thermal headroom before/after | 0.15 / 0.15 | 0.14 / 0.14 | unavailable / 0.14 |
| Peak RSS growth | unavailable | unavailable | unavailable |

These are actual displayed measurements, not UI fixture values. The changing
first-preview time does not establish a performance improvement between builds:
all three precede the gesture fix, and process/cache state differs. Every run
recommended live preview off and a 3072 px working edge. Export rendering here
does not save a file to the gallery.

The repeated-export click-to-publication observations were 2648, 2693, 2706,
2630, 2624 and 2599 ms. These include input, transport and MediaStore polling;
they must not be compared as pure codec or native-render times.

- Loupe snapshots: PSS 212942 KiB before, then 206634/206659/206851/205581
  KiB at 5/10/15/20 swipes. RSS ended at 258076 KiB. One Activity throughout.
- Large-input open: sampled maximum PSS 500077 KiB / RSS 606184 KiB, settling
  to PSS 353641 / RSS 459832 KiB in the twelve-snapshot sequence.
- Large high-quality export: sampled maximum PSS 575030 KiB / RSS 681712 KiB.
  A finite sampling sequence can miss the true peak.
- The later cancellation/retry sequence ended at PSS 622504 KiB / RSS 765156
  KiB, one Activity: the largest values among all 49 snapshots collected.
  This is the maximum sampled process footprint, not an isolated job's
  peak or a kernel peak measurement.
- After returning to the persistent DNG: PSS 336325 KiB / RSS 442728 KiB,
  one Activity. This and the short paging series do not prove absence of leaks.
- Working-edge snapshots on the same large chart: 2048 neutral PSS 301823 KiB,
  2048 styled 302691 KiB / RSS 386260 KiB; 3072 styled PSS 361914 KiB / RSS
  446104 KiB. These sequential same-process snapshots show memory sensitivity,
  but are not isolated benchmarks: cache, swap and garbage collection differ.
- Paging HWUI interval: 1332 frames, four deadline-janky (0.30%); legacy 4.80%.
  CPU P50/P90/P95/P99 5/7/8/12 ms; GPU 3/3/4/6 ms.
- Expanded pre-fix interval: 17913 frames, 177 deadline-janky (0.99%); legacy
  26.30%. CPU P50/P90/P95/P99 9/16/19/44 ms; GPU 4/10/12/15 ms.
  These are interface statistics, not native photo-render FPS.
- First replacement after cold restoration, PID 30863: PSS 253839 KiB, RSS
  389932 KiB, one Activity; 2449 HWUI frames, 18 deadline-janky (0.73%),
  legacy 13.43%, CPU P50/P90/P95/P99 8/10/12/28 ms. These intervals have
  different interactions and must not be presented as a controlled before/after
  performance improvement.
- The captured pre-fix PID 2288 log from 22:54:26 through 23:42:34 has no
  FATAL EXCEPTION, Fatal signal, ANR in, VK_ERROR or OutOfMemoryError match.
  This does not erase the rejected candidate's earlier startup crashes.
- Final corrected process: PSS 270246 KiB / RSS 402528 KiB, one Activity;
  3362 HWUI frames, 18 deadline-janky (0.54%), legacy 20.35%, CPU percentiles
  8/16/17/31 ms and GPU 4/10/12/14 ms. This is an interaction interval, not a
  controlled native-render benchmark or a comparison proving an FPS gain.
- Final corrected logs cover PID 9444 at 00:59:42-01:05:50, PID 11811 at
  01:06:14-01:06:31, and PID 12315 at 01:06:31-01:08:07 on November 15.
  None matches the fatal/ANR/Vulkan/OOM patterns above. Exit history contains
  the two deliberate force-stops and package updates, with no new crash/ANR;
  the earlier rejected candidate's three crashes remain in historical evidence.

Real-time HAL temperatures and thermal status are saved separately from cached
historical readings in the sanitized metrics. The old cached 70 C entries are
not temperatures measured during this extension. USB charging was connected;
this run is not a battery-drain test.
Across twelve live HAL snapshots, battery was 31.0-32.2 C, skin about 33.7-35.7 C,
CPU 34.8-57.6 C and GPU 35.1-43.0 C. All sampled thermal statuses were 0.

## Prioritized Improvements and Limits

1. P1: grid long press and drag selection. All three causes now have concrete
   corrections and cloud pointer coverage. The Initial-pass correction passed
   actual-phone acceptance on signed run 284, including both cross-row directions.
2. P2: use the 2048/3072 comparison to guide further device-specific size advice.
   The device benchmark repeatedly advises 3072/live-preview off; snapshots
   demonstrate memory sensitivity to size. The user's original setting was
   "Original size", restored after testing. A single phone is insufficient
   evidence for a global default change.
3. P2: benchmark memory reporting is unavailable on this device. External
   sampling now gives a usable range; an in-app portable sampler is separate
   work and must distinguish sampled maximum from kernel peak.
4. P3: the completed queue retains its static "Processing" heading and active
   notification hint above "All done". Completion is accurate, but the heading
   and hint can better reflect idle state. It is a UX observation, not evidence
   of a stuck or lost job.
5. P3: label the completed list as recent history and disclose its 30-record
   retention. The observed restart count change is consistent with the code,
   while the saved gallery images remain present and byte-identical.
6. P3: separately characterize input during navigation transitions. One early
   injected gesture formed no selection, while the settled-layout repeats pass.
   Do not present that first attempt as accepted or invent a cause from one sample.

Full numerical Adreno shader parity, a real large camera RAW, long sustained
thermal stress, battery drain, language-split AAB delivery, and the full provider
permission lifecycle remain outside this run. The 24 MP chart tests decoding,
device cap and export behavior; it does not cover sensor-specific RAW decoding.

Raw XML/screenshots/history/logs remain local at
`D:/AGI/GPT/research/spektralab-20261114/i18n/alpha8-expanded-device-evidence`.
Only sanitized metrics and public synthetic exports were transferred to cloud
resources. Public evidence is in
[validation/alpha8-expanded-device-2026-11-14](validation/alpha8-expanded-device-2026-11-14/README.md).
