# Original photo style lab

Co-created with GPT (OpenAI), 2026-11-15. Project LICENSE and NOTICE apply.

This offline research tool generates ten original finishing looks as bilingual
v2 `.spklook` candidates. It does not change app defaults, RAW development, film
models or built-in styles. See `docs/COLOR_STYLE_RESEARCH_2026-11-15.md`.

## Cloud execution

Use an isolated cloud workspace with Python 3.10+, NumPy and Pillow. Compilation
and executable tests for this project run on cloud only. Public inputs only.

```sh
OPENBLAS_NUM_THREADS=1 python3 -m unittest discover -s tools/style_lab -v
OPENBLAS_NUM_THREADS=1 python3 tools/style_lab/build.py \
  --out /work/style-output --photos /work/public-images
SPK_STYLE_CANDIDATES_DIR=/work/style-output ./gradlew :app:testDebugUnitTest \
  --tests com.dearded.spektralab.StyleLabCandidateTest
BUILD_DIR=/work/style-native SANITIZE=1 tools/native/run_lut_bake_parity.sh \
  /work/style-output/clear_portrait.bin
```

Repeat the native harness for all ten `.bin` fixtures. Fixtures contain tables
and 16,384 public synthetic samples, never photographs. The expected results
come from the independent sorted-simplex oracle, not the native six branches.
The large candidate integration test is excluded from normal Gradle runs;
setting `SPK_STYLE_CANDIDATES_DIR` includes it and tracks the directory as a
test input. A configured missing directory fails instead of silently skipping.
`metrics.json` compares the packaged six-decimal Float32 65-cube with the
continuous algorithm, including off-grid samples; it also includes a 33-cube
comparison and hard RGB clipping as a boundary-stress baseline. Oklab distance
is not DeltaE2000 and is not proof of artistic or camera color accuracy.

`PUBLIC_IMAGES.json` records source URLs, hashes, credits and licensing of four
public-domain / CC0 test images. Images come from the pinned scikit-image
v0.25.2 data paths; source licensing is documented in its `_fetchers.py` and API
documentation. Public JPEG/PNG values are treated as sRGB; these are visual
tests, not chart-calibrated camera tests. No private phone data is needed.

## Algorithm

1. Decode the app's encoded ProPhoto toe/gamma and convert to signed linear
   sRGB D65 using the existing app matrix. Keep negative components here.
2. Convert to Oklab; apply endpoint-anchored contrast, continuous periodic hue
   weights, separate chroma/density adjustments and optional split toning.
3. Attenuate selected changes near a broad skin-like hue/chroma range. This is
   only a color proxy; foliage, wood and skin can overlap. No face detector.
4. Compress along a linear ProPhoto RGB ray from a neutral anchor with a C1
   knee at 85% of the cube boundary. Preserve RGB channel ratios around that
   anchor, allowing measurable Oklab hue/L changes. Constant-Oklab-hue boundary
   searches were rejected: disconnected intervals can cause jumps between
   neighboring colors even when every individual result is inside the cube.
5. Encode and sample a 65-cube, red fastest. Export `scope=filter` and
   `lut.space=prophoto`; do not route through the narrower sRGB LUT baker.

The deepest 65-cube cell is identity, with a smooth transition to full effect
by encoded ProPhoto max RGB .12. This limits under-resolved near-black chroma
and avoids introducing tint into almost-black noise. Neither this encoded-RGB
toe blend nor finite LUT interpolation promises constant perceptual hue/L.
For color looks, negative-LMS extremes of the ProPhoto cube retain the input;
a smooth confidence weight fades in the look over min(LMS)/max(LMS) 0..0.12.
This avoids amplifying the signed cube-root singularity near non-physical
primaries, without clipping to sRGB. The two monochrome looks use cube-root
linear Rec.709/D65 luminance instead, so wide colors still become monochrome
above the deliberately preserved darkest toe. Colour candidates also fade their
effect smoothly over source Oklab lightness .90..1 to retain bright colour
detail near the white corner; monochrome candidates keep their achromatic map.
This v2 change addresses seven over-budget cases found in twelve additional
public real photographs. The .75 photo-error budget remains unchanged. Neither
path is a camera profile.

Generation fails if public-photo maximum interpolation error exceeds 0.75 or
uniform ProPhoto stress maximum exceeds 5, in 100*Oklab distance. These are
engineering candidate budgets, not perceptual just-noticeable thresholds.
Rare extreme colors remain less accurate than ordinary photo colors.

This is an SDR finishing algorithm, not an ACES implementation. No spatial
effects, bloom, grain, local subject masks, HDR recovery or exposure/WB
normalization can be encoded in these three-input tables. The LUT takes the
current finished image, including any active film/paper processing. To inspect
the digital look alone, select the app's existing no-film option yourself.

## Import and review

Import the individual `.spklook` files in either `zh/` or `en/` through the
existing settings style-import entry. Both languages contain identical color
tables; importing both creates duplicate looks with different names. The
current format does not dynamically translate imported user style names.
The app's existing intensity control blends encoded RGB, not perceptual L/h.
Do not extract `lut.cube` and import it as a generic sRGB cube.

Contact sheets use the delivered table and production interpolation contract
at full strength, then explicitly clip to sRGB for display. They are JPEG
previews, not wide-gamut export references. Candidates remain optional until
broader camera/lighting/skin-tone photographs and connected-phone tests pass.
