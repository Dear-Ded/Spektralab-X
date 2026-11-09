# Synthetic output-gamut review

Co-created with GPT (OpenAI), 2026-11-09. No user images, image-derived values,
or private paths are included. This bounded review does not change production
parameters or establish a preferred photographic rendering intent.

## Result

The existing export path agrees with an independently evaluated core-double
CAM16 reference. No matrix orientation, XYZ scaling, Cmax table layout, or
additional duplicate target-compression defect was found for sRGB / Display P3.
The production default remains unchanged.

| Target | Samples | Float/reference max linear RGB error | Actual OutputTransform/reference max encoded error |
| --- | ---: | ---: | ---: |
| sRGB | 739 | 4.4244e-6 | 4.0977e-6 |
| Display P3 | 739 | 4.1709e-6 | 3.4341e-6 |

For both spaces, the API's 22 double setup constants and two matrices exactly
match an independent `cam16_setup_for` call; all 46,080 Cmax entries match its
double table after the API's float conversion. The sampled table interpolation
versus direct double boundary bisection differs by at most 0.8445 CAM16-UCS
chroma units for sRGB and 0.05136 for P3. This is table discretization, not a
float/API layout mismatch; the independent reference uses the same declared
table grid when checking production output.

`GamutCompressSpec::knee={0,1,6}` means the chroma mapping is
`d' = d / (1 + d^6)^(1/6)`, where `d=Mp/Cmax`. There is no positive-width
uncompressed interval. At d=0.8, d'=0.76955294; at d=1, d'=0.89089872.
Thus finite chroma can decrease inside the RGB cube by design.

For a synthetic target-linear pure blue `(0,0,1)`, sRGB has d=1.00736 and the
production result is approximately `(0.02400,0.03337,0.64370)`. The independent
double result agrees to 7.5e-7. The P3 pure-blue result is approximately
`(0.02828,0.03645,0.64538)`, agreeing to 3.4e-7. This experiment identifies the
numerical cause; it does not declare the resulting creative appearance correct.

## Reference and scope

`probe.cpp` calls the actually built adapter and generated upstream float math,
and independently calls `core/cam16.cpp`'s public double forward/inverse and
`reinhard_knee`. It does not reproduce the float forward/inverse as its oracle.
It also rebuilds setup from the baked `Colour` data, compares the actual API
setup/table, and evaluates direct Cmax bisection on a separate 35-point grid.
Each target's 739 points comprise a 9x9x9 linear target-RGB grid plus ten named
black/gray/white, blue, negative/out-of-gamut and HDR cases. Full
`OutputTransform::apply` is checked through the inverse source/target matrix
and the actual piecewise ProPhoto transfer.

The adapter fetches export CAM16 with lightness compression disabled and
display with the gamut algorithm off. Its destination transform is applied
once. Physical-film generation separately has its established ProPhoto working
gamut/lightness stage; that distinct working-space stage is not a second
application of the same final sRGB/P3 transform. The view request returns
encoded ProPhoto with `export_intent=false`, before the adapter's destination
conversion. This review does not remove or redefine the working-space stage.

The API `k` ordering, XYZ Y=1 to CAM16 Y=100 scaling, circular hue wrapping,
row-major 64x720 table layout, and endpoint lightness clamp agree across
`core/cam16.cpp`, `core/params.hpp`, `pipeline/engine.cpp`, `host/output.cpp` and
the generated float math. Source and linked binary identities are recorded in
`source.sha256`; its `cam16_boundary_probe.cpp` entry is byte-identical to the
public `probe.cpp`. Compiler identity is in `compiler.txt`.

## Candidate-only threshold experiment

The probe also evaluates knee `{0.8,1,6}` without changing production. This is
only a CAM16 late-knee experiment, not an ACES implementation or a selected
product default. It preserves the knee input exactly for **d <= 0.8**: 263
sRGB and 268 P3 samples in this set. It is not an identity for all RGB-cube
colors, since cube-boundary colors can have d near or slightly above one.

In that interval, the double RGB result agrees with a plain RGB->XYZ->RGB
matrix roundtrip within 4.9e-15. The production float variant differs from that
roundtrip by at most 2.9e-6. The baked sRGB forward/inverse matrix roundtrip
itself has up to 5.405e-5 residual; P3's is below 4.5e-16. The first draft of
this fixture incorrectly required the complete sRGB RGB roundtrip to be
identity to 1e-10. Splitting the no-knee matrix roundtrip from the CAM16 change
resolved that test assumption, without relaxing the CAM16 double comparison.

HDR is included as a reference-equivalence check. Export lightness compression
is intentionally disabled, so HDR values can remain above one before final
packing; the threshold candidate is not an HDR tone-mapping solution.

## Reproduce

Use the repository's pinned/patched upstream, an existing host build, and the
generated `upstream_output_math.inc` from the regular output-math extraction.
No image input is accepted by the probe.

```sh
UP=/path/to/patched-upstream B=/path/to/build-host \
MATH_DIR=/path/to/generated-math BUILD_DIR=/path/to/independent-output \
bash tools/native/validation/output-gamut-review-20261109/run.sh
```

The recorded execution used GCC 11, C++20, O2, disabled FP contraction, OpenMP,
and strict Vulkan resource limits. It is a CPU-output numeric check on a cloud
host, not Android GPU performance or phone display/color-quality acceptance.
`aoidc-gcc11.log` contains only synthetic inputs and exact observed results.
