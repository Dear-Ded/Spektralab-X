# RAW tone and Mac file-intent regression

Co-created with GPT (OpenAI), 2026-11-10. Synthetic public evidence only.

The production changes, historical behavior differences and scope are in
[RAW color fixes](../../../../docs/RAW_COLOR_FIXES_2026-11-10.md).
`source.sha256` binds these runs to the compiled source, not to photographs.

## Results

- `domains-normal.log` and `domains-sanitizer.log`: exposure and highlight
  invariants pass, including 270,369 dense signed/HDR EV cases. ASan and UBSan
  instrument both probes and the Layer2 translation unit.
- `exposure-baseline.log` and `highlights-baseline.log`: the final probes built
  against application source at `307103eb79fc528f354d84a2f4460befe3962e26`
  each exit 1 as intended. Exposure fails 20 focused cases, two mixed-toe
  ratios and 262,176 dense cases; highlights show four reversed steps, five
  negative results and six HDR-shadow violations. Legacy checks remain zero.
- `float-output.log`: 120 output/grade combinations pass, maximum RGB16 error
  3 and RGB8 error 0; 780 TIFF rows and 60 cached repacks agree.
- `gamut-double.log`: 739 synthetic colors per target compare float CAM16
  against core double and the actual adapter against the adopted Mac intent.
  The historical default and threshold-0.8 study are retained separately.
- `raw-output.log`: 96 development/grade/output cases pass; 8/16-bit actual
  output keeps the +3 EV / -100 highlights grayscale ramp monotonic for both
  sRGB/P3 and display/export intent. Whole, borrowed and out-of-order rows
  agree, canceled conversions recover, and source storage stays immutable.
- `float-rows.log`: 12 geometry/row cases and 108 first/middle/last sink-failure
  recoveries pass, including shared mask/dust geometry and anonymous spool
  cleanup. This run uses `RAW=0` synthetic data, not camera decoding.
- `output-rows.log`: 162 integer output cases across five strip heights
  produce identical RGB16 and complete TIFF bytes; provider failure recovers.
- `base-native.log`: existing resize, gain map, brightness, local tone,
  curve/wire, matrix, cache lifetime and FIR checks pass.
- `android-build.log`: ARM64 native build, release Kotlin compilation and
  release JVM tasks pass against a clean 0001..0036 overlay replay. The Kotlin
  and JVM tasks were up-to-date because their sources did not change. No APK
  assembly or release task ran.

`compiler.txt` records the AOIDC host compiler. Native host engine archives
were reused and were not rebuilt with sanitizers. The independent cache-key
probe has its own [coverage record](../setup-cache-illuminant-20261110/).
The second cloud host was under a host-enforced cooldown; these new results
are not claimed as a second-host/compiler or mobile-runtime verification.

## Reproduce

```sh
bash tools/native/run_layer2_domains.sh
SANITIZE=1 bash tools/native/run_layer2_domains.sh
UP=/path/to/patched-upstream B=/path/to/host-build \
  bash tools/native/run_nofilm_float.sh
UP=/path/to/patched-upstream B=/path/to/host-build \
  MATH_DIR=/path/to/generated-output-math BUILD_DIR=/tmp/raw-output \
  bash tools/native/run_raw_development_output.sh
```

`run_nofilm_float.sh` generates the upstream output-math include. Other
runners can use that build directory as `MATH_DIR`. The changed high-dynamic
range tone cases intentionally use independent properties rather than the
upstream shader's known-invalid SDR-weight extrapolation as an oracle.
No user file, original filename, derived camera metric or image is included.
