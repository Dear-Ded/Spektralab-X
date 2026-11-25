# Darkroom scene evidence v1

This is a local measurement of a rendered edit preview, not a model's description of a photograph.
The sampler uses the existing `AdviceStatistics` source and sample revisions. It does not decode the
original, infer subject classes, create an edit mask, or change the result schemas. The evidence is
not included in network requests at this stage.

## Frame and measurement

- Input is an engine-owned opaque sRGB or Display P3 preview. The engine's serial dispatcher holds
  it stable until measurement finishes. Coordinates refer to the post-orientation, post-crop content
  and not the editor's zoom, screen, letterbox or RAW sensor. If content occupies only part of the
  preview bitmap, pass its explicit half-open pixel bounds. The optional reference is in pixels
  relative to that content, with the origin at the top left.
- Android reads 8-bit non-premultiplied sRGB codes from either supported bitmap tag. The metric is
  encoded `Y' = (0.2126 R + 0.7152 G + 0.0722 B) / 255`, not linear light. Fractions at `Y' <= 0.02`
  and `Y' >= 0.98` describe the rendered result, not sensor clipping or recoverable highlight detail.
- `srgb8.area-grid4.v1` uses the same area-weighted grid as the existing broad-side light check,
  bounded to 128 samples on either axis. Tiles are listed left to right, then top to bottom. Each
  axis is divided into up to four spans; tile boundaries in sample cells are
  `floor(i * sample_count / tile_count)`. Values are mean encoded Y' over those cells.
- The optional rectangle uses exact preview pixels for area fraction, mean Y' and near-endpoint
  fractions, even when the main grid is reduced. Its neighborhood is the surrounding ring out to
  half a rectangle's width and height (rounded down, at least one pixel), clipped to the content
  and excluding the rectangle itself. The ring mean is absent if the ring is empty.
- If a legacy sample center is translucent, the whole sample remains unavailable. If translucency
  is only outside that grid, global values remain usable but spatial evidence and measured light
  direction are absent. Global quantiles and per-channel histograms keep their original semantics.

## Verification

`DarkroomSceneEvidenceTest` covers matched histograms, isolated corner light, mirrored and rotated
references, border exclusion, a one-pixel highlight, JPEG decode and Display P3 conversion. This
does not replace the real-engine RAW, permitted holdout and Android device checks in the roadmap.
