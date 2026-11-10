# Bitmap input and shared source permissions

Co-created with GPT (OpenAI), 2026-11-10.

## Confirmed source defects

The light-table film-thumbnail route copied decoded pixels to ARGB_8888, then
treated every color space other than the named Display P3 instance as sRGB.
An actual Adobe RGB PNG decoded with Android ImageDecoder retains its profile;
copying its bitmap configuration does not convert its colors. Custom P3 transfer
functions and linear sRGB were also passed to the wrong native input transform.
`FilmThumbs.prepareSource` now draws through Skia into a named sRGB or Display P3
bitmap. It preserves the shared thumbnail, bounds the working dimensions and
owns temporary software storage. The cache key changes to `lut-v3`, so existing
incorrectly interpreted film thumbnails are regenerated. RAW film thumbnails
still use their embedded camera JPEG and remain approximations of RAW development.
The developing thumbnail keeps ownership of input/output bitmaps until the next
owner takes them, so cancellation during a dispatcher hop also releases storage.

The processing queue released completed jobs' persisted URI permissions without
checking the light table or current editor. Those permissions belong to the app;
another successful `takePersistableUriPermission` does not create a separate
reference-counted grant. Successful export could therefore leave an indexed
photo unreadable after its temporary permission expired. Queue cleanup now keeps
permissions used by the table/editor while dropping its own obsolete tracking
entry. Removing a frame from the table also respects the current editor's URI.
An unrelated editor URI and completed jobs alone do not retain unused grants.
Removal loads the existing index before mutating it, including when another
owner's short-circuit permission check has not yet initialized FrameStore. The
regression verifies that unrelated frames and their on-disk index survive.

The three owners also share a monitor for granting, publishing and releasing
URI ownership. Batch imports gather metadata/EXIF before entering it, then take
permissions and publish frames together. Queue source securing and editor grant
changes use that same monitor. Queue insertion publishes its source need under
the monitor; frame removal reads current queued jobs inside it, rather than using
a stale snapshot captured before the IO task runs. Native rendering and source
file copying remain outside this critical section.

## Input contract

The main photo decoder now derives the native input label from the final bitmap,
including after resizing and F16-to-ARGB_8888 configuration conversion. It no
longer uses a string set while requesting the decoder target. Existing supported
pixels remain unchanged. An unexpected gamut or transfer function produces an
explicit error, with base/gain-map storage released; it is never silently called
sRGB. This checks an API contract, rather than claiming an observed failure of
the device's decoder. Higher-precision SDR and HDR input work remains described
in [Android input precision](ANDROID_INPUT_PRECISION.md).

These changes do not modify RAW matrices, RAW default development, film inputs,
or exposure interpretation. New RAW imports remain neutral and scene-linear.

## Regression coverage

The synthetic fixtures contain no user photographs. `PhotoInputColorTest` uses
native Android graphics for supported byte preservation, F16 conversion/resizing,
owned gain-map lifetime, rejected Adobe RGB/linear sRGB metadata and actual
sRGB/P3 JPEG decoding. `FilmThumbnailColorTest` uses raw bitmap bytes and an
independent analytic `ColorSpace.connect` reference for Adobe RGB, custom P3
transfer and linear sRGB conversion; supported same-size inputs must remain
byte-identical. An actual PNG decoder fixture reproduces the unsupported-space
input that the old thumbnail path misinterpreted.

`ProcessingQueueGrantTest` invokes actual queue cleanup and FrameStore removal
with the resolver's persisted-permission implementation. It covers table/editor
ownership, pending and retryable jobs, completed/removed jobs and final release
after ownership changes. The import progress callback reproduces queue cleanup
between metadata collection and frame publication. Additional cases enqueue a
new source or finish an old job after creating the live owner provider. These
are source-level Android framework tests, not
physical-device or OEM content-provider acceptance.

The final AOIDC run passed all 30 selected Debug tests: 6 photo-input color,
6 film-thumbnail color, 12 shared-permission, 3 geometry and 3 output-tag tests,
with zero failures, errors or skips. Release Kotlin compilation and the existing
release JVM tests also passed. The ten changed production/test Kotlin files on
the cloud runner were checked against the local source by SHA-256. No APK was
assembled or installed, and no version was changed as part of these repairs.

[Public validation records](../tools/native/validation/android-bitmap-uri-20261110/README.md)
include the final logs/XML, the previous permission implementation's four failing
tests and the import handoff race's failing intermediate run. These records
contain only synthetic fixtures and source/build evidence. They do not replace
physical-device acceptance or implement the remaining F16/HDR input plan.

## Primary references

- [AOSP ImageDecoder default color space](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/libs/hwui/hwui/ImageDecoder.cpp#48)
- [AOSP Bitmap.copy color-space contract](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/graphics/java/android/graphics/Bitmap.java#698)
- [ContentResolver persisted URI permissions](https://developer.android.com/reference/android/content/ContentResolver#takePersistableUriPermission(android.net.Uri,int))
