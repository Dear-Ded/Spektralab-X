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
