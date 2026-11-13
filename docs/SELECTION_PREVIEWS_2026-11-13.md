# Accurate selection previews, 2026-11-13

Co-created with GPT (OpenAI). This research-branch change improves the pictures
inside the existing bottom selection cards. Card sizes, navigation, typography,
selection markers and the main photo's processing defaults stay as specified.

## Behavior

Previously every style using the same film borrowed one film-only bitmap. Its
own paper, exposure, finish, imported LUT and saved strength could therefore be
absent from the card. Film cards were generated once after opening a photo and
could retain the earlier paper or adjustments after subsequent edits.

Each composed card now requests the edit its action would apply:

- Styles use `atStrength(...).applyTo(...)`, including filter-only imports,
  physical recipes, LUT strength and the current photo's retained masks.
- Film choices use `withFilm(...)` and retain the current finish and compatible
  paper. Film "none" retains that finish; style "none" clears it.
- Recent edits use the same pasted recipe for both their picture and their tap.
- Paper "none" previews its direct scan or digital intermediate with the current
  finish. The other paper choices retain their existing text cards.

`openPreviewV1` owns a separate small native session on the source's retained
384 px linear input. The ordinary output, LUT, look and render APIs process this
session. It inherits RAW metadata and already prepared complete-source exposure
statistics. It does not modify the editor's parameters, look or retained render.
Borders, film edges and date imprints are omitted from these color previews.

## Scheduling And Lifetime

Lazy rows request their composed cards, including Compose's normal prefetch,
instead of developing the entire film catalogue. A serial coroutine mutex
coalesces identical cache misses. Work waits for a short quiet interval, slider
release, the main render, exports and acceptable thermal headroom.

The cache key includes the committed source generation, complete candidate edit
and actual output target. A successful reopen increments that generation even
when the URI and source metadata are unchanged. New recipes immediately clear
the old image in composition, and cancelled requests cannot publish over them.
An allocated Bitmap is reclaimed if a cancellable dispatcher return discards it.

The LRU has a 12 MiB bitmap budget and a minimum 256 KiB charge per entry,
limiting it to at most 48 entries for tiny sources. Eviction drops references
rather than recycling a Bitmap that Compose may still draw. This is a cache
bound, not a bound on application RSS, native LUT copies or the GPU pool.

## Verification

All builds and executable tests ran on the Linux cloud host. Windows was used
for source editing, archive handling, hashes and Git.

| Check | Result |
| --- | --- |
| Release JVM suite | 24 classes, 182 tests, no failures/errors/skips |
| Selected Debug/Compose suites | 5 classes, 21 tests, no failures/errors/skips |
| Production JNI, GCC 11 | 8 exact comparisons, ownership/state assertions pass |
| Production JNI, Clang 14 with ASan/UBSan | Same checks pass |
| Android x86_64 Debug APK | Full build succeeds; JNI symbol present in packaged library |
| Android ARM64 native library | Recompiled and linked; JNI symbol present |
| Input provenance | 185 local/Gradle-tree input hashes match |

Three new recipe tests and six new Compose tests cover full candidate identity,
strength, no-film/DI semantics, source replacement, clearing an already displayed
old image and refusing a superseded asynchronous result. The screenshot uses
synthetic presentation fixtures and does not claim to render the native engine.

The native integration compares no-film finish, no-film LUT, film/print LUT and
DI LUT in both sRGB and Display P3. Every 384 x 256 output matches the ordinary
main render pipeline byte for byte **at the same input resolution**. It also
checks that parent parameters, source pixels and cached repacks remain intact,
that refused RAW previews free their sessions, and that an owned preview remains
usable after its parent is released.

ASan/UBSan instrument the JNI translation unit, adaptation helpers and test.
The engine/LibRaw support archives remain Release builds. LSan is disabled for
this host Vulkan integration. This does not resolve the separately documented
older GCC 11 pipeline-recovery sanitizer diagnostic.

The native test's first setup lacked the Android platform shim in the cloud
checkout; its next attempt used JSON boolean conversion for a string literal.
Both harness errors were corrected. Original failure logs remain under
`attempts/`, and final normal/sanitized exit codes are zero.

Raw results, hashes and images are in
[`tools/native/validation/selector-previews-20261113`](../tools/native/validation/selector-previews-20261113/README.md).

## Limits

These are 384 px selection previews. Film grain, spatial effects, metering and
very tight crops can differ from a full-size render. Exact comparisons above
establish the shared pipeline at equal input size, not full-size pixel parity.

A RAW candidate needing unprepared complete-source development statistics
remains a text placeholder until the source is reopened with those statistics;
the thumbnail path does not silently meter the small copy instead. Failed
optional previews also keep the existing placeholder.

There is no new release or phone installation, and no claim about phone frame
rate, peak memory, device display color accuracy or natural-photo visual ratings.

The four synthetic native outputs below are, in reading order: no-film finish,
no-film finish plus LUT, film/print plus LUT, and digital intermediate plus LUT.
All four PNG tiles contain actual encoded sRGB output from the native test.

![Native selection pipeline on a synthetic color chart](../tools/native/validation/selector-previews-20261113/aoidc/native-selection-srgb.png)
