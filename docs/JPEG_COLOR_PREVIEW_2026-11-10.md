# JPEG color preview

Co-created with GPT (OpenAI), 2026-11-10. Source-only integration; no version,
release or default rendering-intent change.

The save menu offers separate sRGB and Display P3 JPEG color previews. This
shows the existing file-target gamut compression, unlike the editor's ordinary
matrix-and-clip display conversion. Selecting a preview does not change the
JPEG export preference. The current queue uses that preference only for JPEG;
TIFF and HEIF continue to use sRGB.

## Pixel and ownership contract

`renderProofBitmap` renders a fresh preview-tier result in encoded ProPhoto.
It reuses `to_bitmap`, including the applicable RAW development, Layer2 and
existing output quantization, with a local export-intent `OutputTransform`.
RAW development is still excluded from physical film input. The editor's
`out`, `last`, `last_float` and render revision are not replaced. Rendering
can still update the engine's internal negative, print and tier caches.

The bitmap holds codes in the requested **file target**. `tagOutput` assigns
exactly that color space before Compose sees it; it does not retag P3 codes as
sRGB on an sRGB screen. Android's color-managed drawing then maps the tagged
bitmap to the window. Unknown targets and tag failures are errors. The ordinary
`DisplayColor.tag` path is not used for file-target previews.

The engine-owned borrowed Cmax table also remains valid when fetching the other
target. The checked patched upstream declares engine-lifetime ownership in
`spk_engine.h`; `engine.cpp` only inserts missing target tables into
`cam16_cmax_f32`, without replacing or resizing existing vectors. This review
does not change the existing engine-lifetime requirement.

Inspection requests share EngineThread and the native cancellation generation.
Their own revision plus source identity and accepted edit checks prevent stale
publication. Opening another source, editing, closing the viewer or trimming
memory invalidates the request and cancels active inspection work. An old
request cannot clear a newer request's loading state or reopen a closed viewer.
Discarded fresh bitmaps are recycled. Cancellation does not display an error.

## Product boundary

The viewer identifies the target, actual preview dimensions and exclusions.
It is a **preview-size color inspection**, not a full-resolution final-file
proof. It does not include the JPEG codec, subsampling, paper frame or encoded
file metadata. Resolution-dependent grain, halation, texture and geometry can
differ from a full working-size export. It does not certify a phone panel,
third-party viewer or camera/Lightroom appearance match.

The file-target transform and compression knee remain unchanged. The synthetic
[output-intent exploration](../tools/native/validation/output-intent-20261110/README.md)
records why alternative knees and boundary policies remain candidates. Its
earlier full proof design is distinct from this smaller adapter bridge, which
delegates the final file-target-to-screen conversion to Android using a
correctly tagged bitmap.

## Validation scope

Existing double-cloud RAW output tests cover 96 cases per host, including both
targets and intents, development-before-Layer2 exactly once, whole-frame and
row/strip parity, unchanged sources and cancellation recovery. They establish
the reused pixel path; they do not execute the new JNI/UI entry point.

`OutputColorTagTest` exercises native Android bitmap graphics under Robolectric
SDK 34, without loading the engine. It compares raw `copyPixelsToBuffer` bytes
across sRGB/P3 tag changes and checks unknown-target state preservation. It does
not use color-converting `getPixels` as the byte oracle and does not test a
physical display. All three tests passed. Release JVM tests, Kotlin compilation
and ARM64 native compilation also passed; logs and relevant JUnit XML are in
[Android integration validation](../tools/native/validation/raw-color-inspection-20261110/README.md).

End-to-end phone tests are still required for cancel/reopen/edit races, both
screen gamut modes, and proof versus the decoded final JPEG at matching
dimensions. No real-image or physical-device result is implied by compilation,
host pixel-path tests or the bitmap-label test.
