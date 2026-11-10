# Bounded no-film float readback

Co-created with GPT (OpenAI), 2026-11-09.

## Change and scope

Patch 0034 adds an internal synchronous RGB-float row sink to the ungraded
no-film view. Android's `exportJpeg444` consumes up to 32 output rows at a time,
applies the existing Layer2/output transform, then appends RGBA8 to the existing
anonymous spool. Encoding begins only after the complete render and frame check
succeed. The adapter returns dimensions, not owned pixel storage.

For a 4096 x 3072 image, the readback float payload changes from 150,994,944
bytes (144 MiB) to at most 1,572,864 bytes (1.5 MiB). Fresh readback staging can
also stay small. Existing backend caches may retain earlier allocations.
The source image, GPU image, decode buffers and JPEG coefficient storage are
separate allocations. This is not a complete tiled or out-of-core RAW pipeline.

The existing full-vector preview/cache path, film/DI rendering, TIFF and other
Bitmap-based exports remain on their existing paths. No render-intent default,
RAW tone curve, saturation, codec quality or resolution policy changes here.

## Pixel and failure contracts

- Every strip retains full-frame width, height and absolute y coordinates.
  Vignetting, masks and texture placement use the original frame geometry.
  Mask/dust placement is prepared once and shared by the row packers.
- Float values stay signed and can exceed one before Layer2. The original
  operation order, quantization and output color transform are retained.
- `FloatFrameCheck` uses the old whole-frame sampling grid across strips. It
  rejects non-finite sampled values, malformed/incomplete streams and a flat
  completed frame. Flat individual strips are valid until the whole frame is
  checked. It remains a sampled corruption check, not an all-pixel validator.
- The sink borrows its float pointer only during the call. It runs with the
  session lock held and must not re-enter that session. False or an exception
  fails the render. Cancellation is polled between completed GPU operations and
  during CPU packing; in-flight GPU/codec operations are not preempted.
- The spool is sealed only after the completed frame is accepted. Failure closes
  its anonymous backing file. This export does not replace the editor's cached
  `last` or `last_float` result.

`spk::view::Request` is an internal C++ type whose layout changed. Rebuild the
engine and every consumer together. The public C `spk_result` ABI is unchanged.

## Reproduction and evidence

```bash
UP=/path/to/patched/upstream B=/path/to/host-build \
  JPEG_DIR=/path/to/jpeg-prefix MATH_DIR=/path/to/generated/math \
  BUILD_DIR=/path/to/nofilm-rows bash tools/native/run_nofilm_rows.sh

BUILD_DIR=/path/to/frame-check bash tools/native/run_float_frame_check.sh
SANITIZE=1 BUILD_DIR=/path/to/frame-check-asan bash tools/native/run_float_frame_check.sh

python3 tools/native/measure_nofilm_rows.py /path/to/nofilm_rows \
  /path/to/host-build/engine/resources synthetic /path/to/memory-results
```

The memory probe compares complete JPEG files. Its synthetic JPEG omits an ICC
profile to isolate the two readback paths; it is not the Android export API.
Production JNI continues to require the selected ICC profile.

Two-cloud source/host checks cover float-byte and packed-byte parity, full-frame
grading geometry, odd and tiny sizes, crop/angle/turn/flip/resize, failure,
cancellation and recovery. Failure injection covers first, middle and final
strips after data was appended to a real anonymous spool, including descriptor
cleanup and recovery pixel equality. The standalone frame validator also runs with
ASan/UBSan. Android ARM64 native compilation verifies the JNI integration; this
does not execute the application on a phone.

Memory must be reported by stage as well as for the whole process. The public
synthetic comparison found about 61 MiB lower RSS at the same completed-spool
checkpoint. Comparing the old live full-float checkpoint against the new
completed-spool checkpoint gives about 204 MiB, but those are different stages,
not an equivalent-time or whole-process-peak comparison.

The two-cloud runs share a large high-water mark during CPU Vulkan engine
creation, before any input image was generated or opened. Source inspection
and a no-image strict-limits control isolate the optional CPU-device storage
range probe: it deliberately writes and reads buffers beyond the advertised
128 MiB range to verify usable addressing. This branch is CPU-device-only;
the observed startup peak is not a measurement of Android GPU/RAW memory.
The no-image control and source trace are in
[engine initialization evidence](../tools/native/validation/engine-init-20261109/).
Reducing the export buffers does not lower that common whole-process peak.
Do not use
`getrusage` alone to attribute that peak to the final render: the measurement
helper polls the executable's own `/proc` VmRSS/VmHWM and the probe records
initialization checkpoints. Synthetic validation records are in
[the evidence directory](../tools/native/validation/nofilm-rows-20261109/).
Private camera samples, renders and their detailed measurements are excluded.

## Remaining work

Investigate engine-creation allocations separately, verify runtime behavior and
memory on Android GPUs, and classify each film algorithm before implementing
full-pipeline tiling. The source-backed route and licensing boundaries are in
[the external pipeline research](RAW_PIPELINE_ADAPTATION_RESEARCH_2026-11-09.md).
