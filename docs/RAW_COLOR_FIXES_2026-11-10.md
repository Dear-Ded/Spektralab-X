# RAW exposure and desktop file-color adaptation

Co-created with GPT (OpenAI), 2026-11-10. No version or release change.

## Correct exposure in the actual working transfer

No-film float pixels are encoded ProPhoto RGB with exponent 1.8 and a linear
toe at linear 1/512, encoded 1/32. Global and mask exposure previously decoded
with exponent 2.2 and clipped negative channels before exposure. Thus an EV
slider did not apply the labeled linear gain, and mixed-toe colors could change
channel ratios. Exposure now applies `2^EV` in the actual piecewise transfer.
Both endpoints on the same segment use a precomputed gain; only toe crossings
need a power per channel. Temporary values and gains use double precision.

The no-film float shadow/highlight weights also extrapolated outside their
SDR domain. Raising exposure and lowering highlights could turn bright pixels
negative and produce black output. The weights are now bounded to `[0,1]`;
the pixels themselves retain signed/HDR headroom until the existing packing
boundary. Global and local masks use the same rules.

Integer film exposure retains its legacy arithmetic. The subsequent
[film and stock-state fixes](RAW_FILM_STATE_FIXES_2026-11-10.md) also bound the
integer film tone weights; the initial correction covered no-film float only.
No-film edits with nonzero EV or
out-of-range tone weights deliberately render differently after this correction.
The neutral RAW default and the scene-linear physical-film input are unchanged.

## Follow the Mac file intent

The upstream Mac fix from 2026-11-06 is
[d661b3b957adfd0fa90259d7c3b67cfe0d996165](https://github.com/JamesQiu2005/SpektraLab/commit/d661b3b957adfd0fa90259d7c3b67cfe0d996165).
Its `ColourManagement.swift` requests CAM16-UCS knee `[0.7,0.995,6]` with
lightness compression disabled. Android had only disabled lightness and
inherited the engine's `[0,1,6]` knee, compressing chroma even far inside the
target gamut. The adapter now explicitly requests the Mac values.

This changes sRGB/P3 file output and the matching JPEG color preview for RAW,
film and bitmap sources. It is a file-rendering correction, not a change to
RAW decoding, the editing display, a saturation slider, or a new tone curve.
The 0.995 limit is intentional and follows the source code's boundary margin.
The display transform remains matrix-only. Internal film working-space gamut
compression and all creative film/paper settings are unchanged.

Existing saved edits retain their controls; re-exporting uses the corrected
file intent and can change bytes/colors. No legacy-output selector is added.
The two earlier gamut-study folders retain their historical logs and now
identify the adopted Mac intent separately from their unadopted experiments.

## Reference-illuminant cache key

Overlay 0036 includes the film reference illuminant in the Hanatos LUT cache
key. Switching light sources on the same stock must not reuse a LUT built for
the old light. This does not change built-in stock defaults. Its regression
uses a synthetic film and compares cached LUTs and film exposures with an
independent fresh build, including a switch back to the first light.

## Verification and limits

`run_layer2_domains.sh` checks labeled EV, toe crossings, signed/HDR values,
global/local equality, channel ratios, neutral identity and legacy arithmetic.
A dense 270,369-case exposure sweep uses a separate decode/gain/encode oracle.
The old implementations fail the same focused exposure and highlight probes.

`run_nofilm_float.sh` retains its 8/16-bit precision assertions, geometry,
out-of-order TIFF rows and repack checks. Exposure references pre-expose the
linear scene independently before the GPU grade. For export, the GPU first
produces graded ProPhoto RGBA16; `mac_export_reference.hpp` evaluates the file
intent using core-double CAM16 and independent transfer/table interpolation.
SDR highlight/shadow GPU parity uses a bounded source because the upstream
shader has the same HDR-weight defect. Separate property and actual-output
tests cover HDR instead of treating that shader's extrapolation as an oracle.

`run_raw_development_output.sh` additionally verifies that a positive-exposure,
negative-highlight grayscale ramp remains monotonic through actual sRGB/P3
display/export output in both bit depths and whole/row packing.

Recorded compiler, sanitizer, integration logs and exact sources are in
[tone/export evidence](../tools/native/validation/raw-tone-export-20261110/)
and [illuminant-cache evidence](../tools/native/validation/setup-cache-illuminant-20261110/).
Sanitizer coverage is stated in those records; prebuilt engine archives are
not claimed fully instrumented. These tests do not establish mobile-driver
behavior or reproduce a camera ISP/Lightroom profile. Personal image sources,
derived image values and camera renderings are excluded from public evidence.
