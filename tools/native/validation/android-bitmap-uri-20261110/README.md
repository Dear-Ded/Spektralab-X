# Android bitmap and shared URI regression evidence

Co-created with GPT (OpenAI), 2026-11-10.

The fixtures are synthetic; this directory contains no user photographs or
private image measurements. The Kotlin source changes and tests are described in
[the repair report](../../../../docs/ANDROID_BITMAP_URI_FIXES_2026-11-10.md).
Only trailing whitespace was removed from the collected text logs.

## Final result

The AOIDC Linux runner used API 34 Robolectric native graphics. All 30 selected
Debug tests passed, with zero failures, errors or skips:

| Class | Tests |
| --- | ---: |
| PhotoInputColorTest | 6 |
| FilmThumbnailColorTest | 6 |
| ProcessingQueueGrantTest | 12 |
| PhotoGeometryTest | 3 |
| OutputColorTagTest | 3 |

[bitmap-uri-after.log](bitmap-uri-after.log) records the successful run, completed
at 2026-11-10 02:49:00 UTC in 1 minute 4 seconds. The final per-class reports are
in [junit](junit/). [release-after.log](release-after.log) records successful
release Kotlin compilation and the existing release JVM test suite, completed
at 02:51:35 UTC in 1 minute 44 seconds. No APK assembly, installation or release
was performed.

The executed commands were:

```sh
spk-build /data/work/gpt-engine-impl-20261108/repo \
  :app:testDebugUnitTest \
  --tests com.dearded.spektralab.PhotoInputColorTest \
  --tests com.dearded.spektralab.FilmThumbnailColorTest \
  --tests com.dearded.spektralab.ProcessingQueueGrantTest \
  --tests com.dearded.spektralab.PhotoGeometryTest \
  --tests com.dearded.spektralab.OutputColorTagTest

spk-build /data/work/gpt-engine-impl-20261108/repo \
  :app:testReleaseUnitTest :app:compileReleaseKotlin
```

[source.sha256](source.sha256) identifies all ten changed production/test Kotlin
files used by the final cloud runs. Each was compared with the local source
before committing. The unchanged geometry and output-tag tests are additional
regression coverage, not part of this ten-file source manifest.

## Reproduced failures before repair

[grants-before.log](grants-before.log) and
[TEST-grants-before.xml](TEST-grants-before.xml) record four failures out of nine
permission tests against the previous shared-ownership implementation. The
failures exercise grants shared with the light table/editor and eventual final
release.

[import-race-before.log](import-race-before.log) records the single import-race
test failing against the intermediate implementation before atomic grant
handoff was added. It invokes queue cleanup between metadata collection and
frame publication. Its intermediate XML was overwritten by a later run and is
not included. The final green report contains this test and both live-owner
provider cases in the full 12-test permission class.

## Scope

These runs exercise Android framework bitmap conversion, native Skia, actual
JPEG/PNG decoder fixtures and resolver permission ownership. They do not certify
physical-device display behavior, OEM content providers, Adreno performance or
whole-pipeline device memory. The existing eight-bit photo-input boundary and
the remaining high-precision/HDR input plan are unchanged. RAW default display
remains neutral and its film input remains scene-linear.
