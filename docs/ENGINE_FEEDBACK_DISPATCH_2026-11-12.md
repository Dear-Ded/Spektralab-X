# Film dispatch and session diagnostics

2026-11-12. Co-created with GPT (OpenAI). Production source baseline:
`db379cda92def55610940fc3de44ea92e19d257a` (alpha-4 engine, through overlay 0053).
This change adds host diagnostics and synthetic evidence, with no production
algorithm, default, application screen, version or release change.

## Scope

Issue #17 reports a Sony A7C II input that is complete while viewing the
original, but becomes a narrow strip or has purple blocks after film processing.
The public report identifies an Adreno 840 on a Xiaomi phone. This symptom does
not establish a decoder defect, driver defect or physically defective device.
The affected phone and its original failing frame have not been exercised here.

These diagnostics separate two missing checks:

1. Full film/print stages with normal versus deliberately small dispatch chunks.
2. The actual C ABI session path, including tier resize, cached negatives,
   RGBA16 packing/readback and ordinary print edits.

Private image inputs, derived images and measurements remain outside Git and
are not sent to cloud machines. All evidence under the accompanying validation
directory comes from public synthetic inputs.

## Dispatch comparison

`tools/native/feedback_dispatch.cpp` uses the initialized Ultramax 400 /
Ultra Endura pair, linear ProPhoto input, balanced auto exposure and +0.5 EV.
Five scenes cover sublayer grain, simple grain, a preview/full-size ratio of
seven, a 257x1 image, and a spatial-effects-off control. The largest actual
input is 257x129 or 769x33; the preview ratio specifies a larger source shape,
not an additional full-resolution render.

Each scene records fourteen complete float32 snapshots, including the live
log-exposure plane. A baseline uses the ordinary device workgroup limit.
Test hooks then cap X to one or seven workgroups. Every corresponding float
must have identical bits. Both baseline and chunked stage walks also compare
their negative and final image with public `run_film` / `run_print` calls.
All snapshots must be finite, and the GPU ownership audit must remain clear.

Exact grain and a fresh Pipeline with a pinned complete RNG history make
the comparison repeatable, including glare. There is no tolerance adjustment.
This compares equivalent scheduling of the existing algorithm; it is not an
independent colour/film oracle and does not certify every Vulkan kernel.

## Actual session comparison

`tools/native/feedback_session.cpp` generates a 1601x1031 synthetic image,
then invokes the app's production `resize_area_rgb` to make a 1537x989 input.
It opens a real `spk_session` with the same stock pair and requests `live`
(1024x658), `preview` and `full` (both 1537x989). This covers the native
session tier implementation rather than only calling Pipeline directly.

The first three renders retain the configured spatial effects. The cache
comparison then disables print glare, retaining the existing cached film
negative, so exact repeat and restore checks do not mistake random glare for
corruption. It requires a cached repeat to be identical, a print exposure edit
to change the result while reusing the negative, and restoration to reproduce
the original stable bytes. Seven render records include RGBA16 row pitch,
opaque-alpha validation and whole-row black/white counts. Those row counts
are diagnostics, not a production rule rejecting legitimate dark images.

The tool also accepts a local float32 RGB file and optional working edge:

```text
feedback_session RESOURCES INPUT_F32|--synthetic WIDTH HEIGHT OUTPUT_DIR EDGE
```

Local file outputs include sampled transfer-encoded ProPhoto RGB floats and
capabilities. They must stay with the private input. This does not invoke
Android JNI, Kotlin, display composition, MediaStore or a JPEG encoder.

## Results

| Environment | Automatic arithmetic selection | Forced split | Dispatch comparison per run | Session checks per run |
| --- | --- | --- | --- | --- |
| AOIDC, llvmpipe LLVM 15.0.7 | Dekker split; FMA residual probe rejected | Passed | 5 scenes, 170 frames, 9,596,670 float pairs; zero changed or nonfinite values | 3 tiers, 3 cache comparisons; passed |
| IBX, llvmpipe LLVM 19.1.7 | Dekker split; FMA residual probe rejected | Passed | Same counts; zero changed or nonfinite values | Same checks; passed |
| Windows, AMD Radeon 760M | Native fused FMA probe accepted | Passed | Same counts; zero changed or nonfinite values | Same checks; passed |

Each row includes a complete automatic run and a complete forced-split run.
The cloud runs therefore do not count as successful independent FMA paths.
Windows does exercise the accepted FMA selection as well as forced split.
Bit comparisons are within each driver/mode; cross-driver identical imaging
is not asserted. Neither the selected math probe nor these fixtures replaces
Adreno/Mali execution or a complete shader parity oracle.

The matching 0053 engine/core/volk archives and resources are reused from
previously recorded builds; this run compiles the new probes and app resize
source, not a new complete engine. Source and archive hashes are recorded in
`build-inputs.txt`. Windows uses the existing MinGW cross-built engine and
static probe executables. No new Android objects, JNI link, APK or phone test
is claimed.

## Reproduction and remaining isolation

```bash
UP=/path/to/upstream-through-0053 B=/path/to/matching-build \
BUILD_DIR=/outside-repo/new-results LP_NUM_THREADS=2 \
bash tools/native/run_feedback_dispatch.sh
```

Requires C++20, engine/core/volk archives and initialized Vulkan resources.
`RESOURCES` may override resources from the matching build. The dispatch tool
uses `-fno-access-control` and existing test hooks; production code needs no
new entry point. The runner resets experimental fusion/bounded-tail/IIR
selection in the dispatch process and runs automatic plus forced-split modes.
Do not combine old result directories with a new run.

Current evidence establishes small forced-chunk equivalence and ordinary
session behaviour on the listed environments. It does not reproduce or fix
Issue #17. Actual 2^24-thread boundaries, 30 MP synthetic session operation,
mobile thermal/memory conditions and Android consumers are outside this suite.

The next discriminating evidence is the same failing file, edit, app version
and working size on the affected Adreno 840, then on another device. Recording
the first differing native stage would distinguish a decode/session problem
from a device-specific shader result. Lowering working size and disabling
individual spatial effects are diagnostic comparisons; a successful smaller
render alone cannot identify a driver bug or justify changing default colour.

Raw records: `tools/native/validation/feedback-dispatch-20261112/`.
