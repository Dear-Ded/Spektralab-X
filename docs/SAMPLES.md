# Sample images

The comparison images in the README (`docs/images/samples/`) are real engine
renders. Nothing in them was retouched after the engine.

## Sources

All RAW files come from [raw.pixls.us](https://raw.pixls.us/) and are released
under CC0 1.0.

| Scene | Camera | File on raw.pixls.us |
|---|---|---|
| `lake` | Leica Q (Typ 116) | `Leica - Q (Typ 116) - 14bit (3:2).DNG` |
| `fruit` | Canon EOS R5 | `Canon - EOS R5 - RAW, 1.6 crop, dual-pixel (3:2).CR3` |
| `skyline` | Google Pixel 2 XL | `Google - Pixel 2 XL - 16bit (4:3).dng` |
| `dunes` | Ricoh GR III | `Ricoh - GR III - 14bit (3:2).DNG` |
| `temple` | Fujifilm X100F | `Fujifilm - X100F - 14bit 14bit compressed (3:2).RAF` |

Colour-chart shots were left out on purpose: they show calibration, not how a
film looks on a real scene.

## What each image shows

- **Before** (left half of `compare_*.jpg`): the neutral decode. LibRaw output
  in linear ProPhoto RGB, converted to sRGB with a plain matrix and the sRGB
  curve, exposed so the median luminance sits at middle grey. No tone curve,
  no film.
- **After**: the same decoded pixels opened with the parameters the Android app
  sends for a RAW (`input_color_space: "ProPhoto RGB"`, `auto_exposure: true`,
  `product_defaults: true`) and rendered at the engine's `full` tier. Only the
  film and paper differ between looks:

| Look | `film_stock` | `print_stock` | `scan_film` |
|---|---|---|---|
| Kodak Portra 400 | `kodak_portra_400` | `kodak_portra_endura` | false |
| Kodak Ektar 100 | `kodak_ektar_100` | `kodak_portra_endura` | false |
| Kodak Gold 200 | `kodak_gold_200` | `kodak_portra_endura` | false |
| CineStill 800T | `cinestill_800t` | `kodak_2383` | false |
| Fujifilm Velvia 100 | `fujifilm_velvia_100` | — (slide, scanned directly) | true |
| Kodak Tri-X 400 | `kodak_tri_x_400` | `ilford_multigrade_iv_rc` | false |

Images are rendered at 1600 px on the long edge, after an area downscale of the
decoded RAW, so grain is coarser relative to the frame than in a full-size
export.

## Reproducing

The tooling lives in [`tools/samples/`](../tools/samples):

- `spk_render.cpp` renders one image through the engine's C API.
- `samples.sh` renders every scene in every look.
- `compose.py` lays out the comparison images (Pillow, Noto Sans CJK).

Build the upstream engine for desktop Linux (`scripts/fetch-upstream.sh`, then
apply `patches/` and configure upstream's CMake with the Vulkan backend), then:

```bash
g++ -O2 -std=c++20 -fopenmp tools/samples/spk_render.cpp \
  -I"$UP/engine/third_party/stb" -I"$UP/engine/include" -I"$UP/engine/src" \
  -I"$UP/build/deps/LibRaw-0.22.2" -o spk_render \
  "$BUILD"/engine/libspk_image_io.a "$BUILD"/engine/libspektralab_engine.a \
  "$BUILD"/engine/libspektralab_core.a "$BUILD"/engine/libspk_libraw.a \
  "$BUILD"/engine/libspk_volk.a -ldl -lpthread
```

```bash
R="$BUILD/engine/resources" T=./spk_render bash tools/samples/samples.sh
```

```bash
python3 tools/samples/compose.py compare:lake:ektar100 compare:fruit:velvia100 \
  compare:skyline:ektar100 films:dunes:portra400,ektar100,gold200,cinestill800t,velvia100,trix400 \
  detail:temple:none,portra400,cinestill800t:0.55,0.02,0.95,0.42
```

`samples.sh` expects a `raw/` directory with the files above, renamed with
spaces replaced by underscores. Any Vulkan 1.2 device works; the published set
was rendered on Mesa lavapipe (CPU), so results do not depend on a GPU vendor.

## RAW regression set

[`tools/samples/manifest.tsv`](../tools/samples/manifest.tsv) lists 33 CC0
files from raw.pixls.us with their SHA-256: every Xiaomi file the site has, and
Canon (CR2, CR3, CRAW), Nikon (lossy and lossless NEF), Sony (including all six
A7 V / ILCE-7M5 samples), Fujifilm X-Trans (compressed and uncompressed),
Leica, Ricoh, Apple and Google Pixel. Some of them exercise one engine patch
each: `leica-q-ol3warp` and `apple-iphone-6s-plus-ol3vig` carry DNG
OpcodeList3 (patch 0009), the Xiaomi and Pixel DNGs carry an OpcodeList2
GainMap (patch 0006), the A7 V files need the ARW6 decoder (LibRaw patch 0019).

Not covered: raw.pixls.us has no Xiaomi phone newer than the POCO M3
(M2010J19CG), so the Xiaomi 10–18 series, the 13 Ultra of upstream #48 and
UltraRAW are missing. A sample offered under CC0 goes into the manifest with
its SHA-256 and raw.pixls.us id (or a URL the script can fetch).

[`tools/samples/expected.tsv`](../tools/samples/expected.tsv) holds, per file
and decode mode (`compatible`, `headroom`), what `spk_decode` printed: size,
SHA-256 of the float frame, per-channel mean and maximum, non-finite count and
LibRaw's decoder name. The hash is exact only for the compiler and CPU that
recorded it (the cloud box, GCC 14, x86-64); elsewhere a mean within 0.5 % is
reported as `close` and passes.

One command on a Linux box with an engine build (`scripts/parity.sh` leaves one
in its work directory) downloads each file, checks it, decodes it in both modes,
deletes it again and compares:

```bash
UP=<patched upstream tree> B=<build dir> bash tools/samples/regress.sh <workdir>
```

`record` instead of a comparison rewrites `expected.tsv` (after a deliberate
decode change; say why in the commit). `ONLY="xiaomi sony-a7v"` limits the run,
`SAMPLES_DIR=` reuses files already on the machine, `KEEP=1` keeps the
downloads.
