# IIR correction through the film pipeline, 2026-11-12

Co-created with GPT (OpenAI). This follows the default-off correction in
[0052](ENGINE_IIR_BOUNDARY_2026-11-12.md). It adds reproducible diagnostics and
evidence, with no production formula, default, material data or release change.

Both cloud hosts passed ten complete synthetic film/print scenarios, 140 stage
pairs, and independent checks of 1,209,390 output values at actual corrected-IIR
dispatches. Each host's 12,689,040-byte default stage stream exactly matches its
frozen pre-0052 build. A separate CPU gate passed 120 parameter cases and 1331
assertions per host. It establishes a real limit: CineStill back-reflection can
exceed the experiment's sigma-256 ceiling at ordinary full-resolution sizes.
The experiment remains opt-in; these results do not authorize a default switch.

## Actual stages and independent checks

`tools/native/iir_pipeline.cpp` links the actual engine/core/volk archives. It
uses `-fno-access-control` to observe existing Pipeline stages without adding
production test hooks. Each stage walk is independently checked against fresh
public `run_film` and `run_print` calls: negative and final output must agree
byte for byte. Both paths use the forwarding GPU observer and real Vulkan
dispatches. The observer adds synchronization/readbacks; this is neither a
timing nor a memory-budget benchmark.

The fourteen snapshots are prefix, scale/expose, boost, overscan, film blurs,
log/curves, retained film log exposure, couplers, grain, print spectral, glare,
linear, scan finish and output. The log-exposure snapshot is a retained buffer,
not an extra production stage. All snapshots must be finite.

The observer captures source, coefficients, active channels, weights and prior
accumulator before each checked corrected dispatch, including in-place writes.
Its reference explicitly repeats the right endpoint through the original
recurrence, keeps long-double recursive states and writes each forward value
to float32. It does not use the terminal conversion matrix. Every line and
channel of each observed dispatch is compared. On the first active line per
dispatch/channel, padding `ceil(64*sigma)+128` is compared with
`ceil(128*sigma)+128`. The reconstructed sigma controls padding only; production
hi/lo recurrence coefficients are used directly. Sharing those coefficients
means this checks boundary execution, not the YVV Gaussian approximation itself.

The gates remain `1e-5*S` for GPU error and `1e-7*S` for tail convergence.
Here `S` is at least one and the largest absolute source value for that
dispatch/channel; for accumulation it also includes
`abs(old_accumulator)+abs(weight*source)`. Inactive pass-through channels are
included in the output count. Public-path duplicate renders and intentional
range-rejection runs execute real shaders but do not repeat the numerical oracle.

Both hosts checked 58 dispatches, represented by 174 channel rows. The maximum
absolute oracle error was `3.0517578125e-5`, in the CineStill 4mm halation fixture
at signal scale `293.63815307617188`. Across all rows the maximum `error/S` was
`1.0644281317285164e-7`. The maximum checked sigma was `27.49636010999997`;
all sampled doubled-tail convergence differences were zero after float32 output.
The earlier isolated-line sigma-256 evidence is separate from these actual
pipeline tests. The summary's dispatch totals also include duplicate renders
and partial work before the expected range failures; they are not oracle counts.

## Scenarios and visible propagation

Inputs are public synthetic positive linear RGB: a ramp, asymmetric bright
borders and an HDR centre spot. Automatic exposure and input CCTF decoding are
disabled. Output is initialized ProPhoto RGB with its output CCTF enabled.
The film/paper pair is actual initialized Portra 400 or CineStill 800T with
Portra Endura. The 35mm cases use 193 by 65 pixels; other cases use 97 by 65.
Small 4mm frames and selected blur overrides deliberately exercise spatial
stages on small buffers; they are diagnostic settings, not claims about app
defaults or all UI slider ranges.

| Fixture | First changed snapshot | Final max absolute change, AOIDC / IBX |
| --- | --- | ---: |
| All relevant spatial effects disabled | none | `0 / 0` |
| Portra, 35mm | film couplers | `0.000923932 / 0.000923395` |
| Portra, 4mm, lens/scanner/unsharp overrides | film blurs | `0.091643810 / 0.091643929` |
| CineStill, 35mm, actual no-AH preset | film blurs | `0.005969226 / 0.005969554` |
| CineStill, 4mm | film blurs | `0.112205982 / 0.112205744` |
| Sublayer grain plus glare | film couplers | `0.101911843 / 0.101911187` |
| Simple grain plus glare | film couplers | `0.099848807 / 0.099891365` |
| Quarter-resolution metadata, blur overrides | film blurs | `0.095036209 / 0.095036209` |
| Scan negative | film couplers | `0.002068222 / 0.002068311` |
| Digital intermediate | film couplers | `0.006966770 / 0.006966949` |

These are old-versus-corrected output differences, not error against real film,
Delta E, percentages, or a full spectral reference. Border changes can propagate
into the interior through wide spatial kernels and nonlinear later stages.
The CSV includes RMSE, changed-value counts, an eight-pixel border maximum and
an interior maximum at every snapshot. The no-IIR control is byte-identical at
all fourteen snapshots; all ten prefixes also remain byte-identical.

The real corrected dispatches include lens blur, halation, DIR couplers, grain,
glare, scanner blur and unsharp. The grain fixtures request final grain blur
3.5; this does not establish every dye-cloud or microstructure parameter branch.
Grain uses the `exact` sampler. Fresh Pipelines have the same explicit RNG state
and draw history, which is also necessary to freeze print glare: selecting
`exact` alone only fixes the film grain seed. No shader RNG policy was changed.

The default stream matches the old engine within each host. Cross-host SHA-256
values differ, as do some corrected stage differences. This study does not
establish cross-driver bit identity or assign those differences to a single
cause. Both environments independently satisfy the numerical and frozen gates.

## Full-resolution parameter limit

The subagent's `iir_pipeline_domain.cpp` was reviewed and executed on both hosts.
It calls actual `init_params` (including digest), `set_source_shape`, physical
pitch and blur-component accessors without allocating images or creating a GPU.
It covers Portra/CineStill, 4/35/36mm, eight long edges from 1600 to 8192,
halation amounts 1/2 and selected 1600-pixel preview/full metadata pairs.
Glare sigma is evaluated from the actual node expression; it is not dispatched.
This is a parameter gate for the recorded lens/halation/DIR/unsharp/glare
settings, not complete render admission or an exhaustive grain/geometry domain.

For current long edge `L`, full long edge `F` and film long side `M` millimetres,
pitch is `1000*M/L` micrometres and preview ratio is `L/F`. Physical lens,
halation and DIR sigmas use the current pitch; unsharp and glare use `L/F`.
Scanner lens blur is already in pixels. Changing `F` alone must not change the
physical sigmas. The nonzero lens probe also checks this when the stock's
initialized lens blur is zero.

The actual CineStill preset has no antihalation layer, back-reflection enabled,
120-micrometre base thickness and spatial scale one. Its widest fitted component
has sigma `9.448921 * 120 * L/(1000*M) * sqrt(halation_amount)`.
With other initialized settings held fixed:

| Film side / amount | Below ceiling | Above ceiling |
| --- | --- | --- |
| 35mm / 1 | L=7902, sigma `255.9955671154` | L=7903, sigma `256.0279634160` |
| 36mm / 1 | L=8127, sigma `255.9712698900` | L=8128, sigma `256.0027662933` |
| 35mm / 2 | preview L=1600/F=6000, sigma `73.3044602221` | full L=6000, sigma `274.8917258330` |
| 36mm / 2 | preview L=1600/F=6000, sigma `71.2682252160` | full L=6000, sigma `267.2558445598` |

This is an experimental-support ceiling, not a proven physical/model failure
or a restriction of the unchanged default legacy kernel. Low-weight Gaussian
components still execute and count toward it. Halation amount zero does not
disable the separate scatter calculation; explicit `active=false` is used for
the no-spatial control.

Actual public `run_film` calls additionally test 6000 by 2 at amount 2 and
8000 by 2 at amount 1 with the initialized 35mm CineStill model. Both reject
with the `[3, 256]` diagnostic on each host. The caller's old output handle and
dimensions remain unchanged, progress is not marked done, the internal progress
pointer is cleared, and no legacy IIR dispatch silently substitutes. These
thin full-width images check range/failure behavior, not full 6000/8000-pixel
photograph rendering, memory use, or numerical accuracy beyond sigma 256.

## Reproduction and next boundary

Use matching engine/resources builds through 0052 and a separate frozen build
through 0051, C++20, a Vulkan loader and initialized profile resources:

```bash
UP=/path/to/52-patched-upstream B=/path/to/52-build \
BASELINE_UP=/path/to/51-patched-upstream BASELINE_B=/path/to/51-build \
BUILD_DIR=/outside-repo/new-results bash tools/native/run_iir_pipeline.sh
```

The runner uses automatic arithmetic selection with strict limits. AOIDC used
GCC 11.4 / glslang 11.8 / llvmpipe LLVM 15.0.7; IBX used GCC 14.2 / glslang 15.2 /
llvmpipe LLVM 19.1.7. Both selected Dekker split after rejecting the fused-FMA
residual. This run reuses the verified 0052/0051 archives from the boundary
study; it does not claim two new engine rebuilds. Tool and archive hashes,
raw logs, CSV, domain JSON and zero exit markers are retained in
[the public evidence](../tools/native/validation/iir-pipeline-20261112/README.md).

Before a default change, the next acceptance gates are numerically justified
support for larger actual radii, a versioned mode that invalidates caches and
survives saved edits, and actual Adreno/Mali execution. Independent Python
spectral-stage comparison, signed-domain behavior, physical PSF calibration,
and complete streamed memory accounting remain distinct work. This study adds
no APK, Android relink, phone run, or controlled film/paper measurement.

Subsequent work adds a separate difference-state v2 experiment through sigma
1024, retaining this study's v1 behavior and frozen streams. See
[large-radius validation](ENGINE_IIR_LARGE_RADIUS_2026-11-12.md); the sigma-256
cutovers above remain the contract for v1.
