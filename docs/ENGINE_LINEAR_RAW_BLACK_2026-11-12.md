# Integer LinearRaw uniform black normalization

2026-11-12. Co-created with GPT (OpenAI). Source-only change, no version or
Android package. The scene-linear RAW default and film input remain unchanged.

## Defect and bounded correction

The audit in [RAW input inspection](ENGINE_RAW_INPUT_AUDIT_2026-11-12.md)
found a genuine scaling defect in the inherited integer three-channel DNG
route. Sample 1024, uniform RGB black 64, white 4095 produced 0.2344243526,
instead of `(1024-64)/(4095-64) = 0.2381543041`. This makes the input darker;
film density curves can amplify the effect. It is not a camera-JPEG look issue.

LibRaw 0.22.2 `adjust_bl()` extracts a common offset using all four `cblack`
slots, although this source has only three channels. The unused zero prevents
the RGB common black from reaching `color.black`. `subtract_black_internal()`
subtracts the RGB offsets, but lowers `maximum` only by `color.black`.
`scale_colors()` therefore divides by 4095 rather than 4031.

Patch 0051 installs a pre-subtract-black callback on CompatibleLibRaw. It fills
the unused fourth processing-black slot with the equal RGB offset, so the
existing LibRaw functions perform the subtraction and scaling consistently.
Both color3 and color4 backing buffers are accepted when TIFF samples is 3;
the tested packed DNG decoder uses color4 storage for three-channel data.
The initial color3-only candidate compiled but did not affect output and was
rejected by the numeric check before this final version.

The callback only handles integer DNG, three non-mosaic channels, equal nonzero
RGB offsets, no spatial black pattern, an unused fourth slot, valid positive
white-minus-black and no user black/saturation or no-auto-scale override.
Source `rawdata.color` is untouched. Nonuniform/spatial black, floating routes,
Bayer, non-DNG and headroom retain their existing policies. This is not complete
DNG normalization, arbitrary profile support or a change to the film model.

## Reproduction

Use the existing decoder-only CMake project with `SPK_BUILD_RAW_COLOR_AUDIT=ON`,
all 51 engine patches and the existing 17 LibRaw overlays. Build a frozen audit
with the same tool source and engine patches through 0050 for comparisons:

```bash
SPEKTRALAB_RAW_THREADS=4 python3 tools/native/raw_color_audit_regression.py \
  /outside-repo/black-basic --probe /path/to/current/raw_color_audit
SPEKTRALAB_RAW_THREADS=4 python3 tools/native/linear_raw_black_regression.py \
  /outside-repo/black-expanded --probe /path/to/current/raw_color_audit \
  --baseline /path/to/frozen/raw_color_audit
```

The extended check covers black 0/64/1024, white 4095/16383, all eight TIFF
orientations and full/budget-one requests, using a spatial RGB ramp with
below-black and white endpoints. Independent expectations use literal
white-minus-black arithmetic and orientation mapping, not decoder internals.
Twenty controls preserve zero-black LinearRaw and nonuniform-black/Bayer
pixels; the latter also compare all diagnostic fields except elapsed time.

## Actual verification

| Environment | Execution | Result and boundary |
| --- | --- | --- |
| AOIDC, GCC 11 | Fresh decoder-only LibRaw/image IO build, 12 basic cases, 48 extended oracle cases, 20 frozen controls | All passed. Maximum float error 1.1920928955078125e-7; uniform-constant integer error 6.747361123771167e-6; extended ramp error 3.0517578125e-5 |
| IBX, GCC 14 | Compile the final adapter and current diagnostic against its previously verified LibRaw/image IO archives, same 12/48/20 cases | All passed, same error maxima. Not a second full rebuild of every dependency; direct adapter object overrides the older archived adapter |
| Both clouds | Actual decoder cancellation enumeration, false/throw callbacks, retry, full/budget-one and camera/custom WB | Four settings each passed; 21 decoder checkpoints per setting. Existing gain-map worker checks also passed |
| AOIDC sanitizer | Direct adapter and cancellation probe built with ASan/UBSan | Custom-WB rotated LinearRaw cancellation/retry passed. Existing LibRaw/image IO archives not wholly instrumented; LeakSanitizer disabled |
| AOIDC MinGW GCC 10, executable run on local Windows | Fresh Windows diagnostic build and same 12/48/20 synthetic cases | All passed; diagnostic only, no Android package |
| Android NDK 27.2.12479018 | Compile final raw_decoder.cpp for aarch64 Android API 29, actual patched Android LibRaw headers | Object build passed. No phone execution, full native relink or APK claimed |
| Fresh fixed upstream archive | Replay patches 0001 through 0051 with zero fuzz | All applied; raw_decoder.cpp byte-identical to the tested adapter |

All 12 basic float-payload SHA-256 values matched across both cloud hosts and
local Windows. The new Windows diagnostic executable SHA-256 is
`128bb38ea6992d9de93a0c5d8e601c3d34e79edafd88ecd39293a06e1e0fe804`.
It is kept outside the repository as a local diagnostic, not a release artefact.

The original constant-case reference gap decreased from
0.0037299514970185466 to 0.000006747361123771167; the remaining error is within
the compatible 16-bit quantization contract. This does not establish a colour
accuracy percentage or real-film fidelity. Evidence is synthetic only, under
`tools/native/validation/linear-raw-black-20261112/`.

The earlier audit's 12-case result and retained gap remain historical records.
Its test script now expects the corrected uniform-black behavior, so reproduce
that earlier checkpoint from its original commit when testing the old adapter.
