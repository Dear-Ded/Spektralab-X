# Native adaptation regressions

Co-created with GPT (OpenAI, AI assistant), 2026-11-08.

Run on Linux with a C++20 compiler. No Vulkan device is needed for these unit
regressions. `UP` must name the pinned upstream tree with this repository's
patches applied, as produced by `scripts/fetch-upstream.sh`.

```bash
UP="$PWD/upstream" bash tools/native/run.sh --large
UP="$PWD/upstream" STRICT=0 bash tools/native/run.sh --large
UP="$PWD/upstream" SANITIZE=1 bash tools/native/run.sh
```

The strict/default runs test both disabled and compiler-default floating-point
contraction. They do not enable fast math. `BUILD_DIR` selects a persistent output
directory; otherwise a fresh temporary directory is used and printed. Omitting
`UP` runs the app-side numerical tests only.

- `resize_reference.cpp` preserves the former two-pass arithmetic from eada743,
  run serially. The candidate is the production `resize.cpp`, with up to eight
  workers. Compare bytes for 350 shape/pattern combinations, or 355 with
  `--large` (4097 x 3073 to 2049 x 1537). Include negative/HDR values, signed
  zero, subnormals, thin images, identity resizing, aliasing and non-integral
  area boundaries. This reference is an oracle, not another production path.
- `engine_adaptation.cpp` checks the Android gainmap equation against a double
  natural-log oracle, including gamma 0.5/1/2/3 and interpolation before gamma.
  It also injects allocation failures into the actual `LookState` implementation
  and checks that failed updates retain the previous LUT/look.
- `setup_cache_lifetime.cpp` links the actual patched `setup_cache.cpp` and
  `blob.cpp`, with small deterministic stand-ins for expensive LUT builders.
  Check LRU rotation, eviction, cache destruction, 4000 concurrent reads and
  distinct gamut knees whose old 64-byte keys collided. This checks ownership
  and keying, not the spectral integrator.
- `curve_regression.cpp` freezes the cdc7274 per-sample slope preparation and
  compares 1,148,285 double/float samples with the prepared production curve.
- `filter_radius.cpp` links the actual `Blur::fir_radius` and numerical kernel,
  checks 3,600 rounding-boundary cases and refuses allocations during demand
  queries. It also checks the nonpositive-sigma identity path.

The output tests additionally need a host engine build, zlib and the generated
`upstream_output_math.inc` from `tools/parity/build.sh`. They use the actual
output transform. Compare 162 combinations of size, stride, look, local masks,
dust, sRGB/P3, display/export and exact mode across five strip heights, then
compare complete TIFF bytes and inject a failing row provider:

```bash
UP=/path/to/upstream B=/path/to/build MATH_DIR=/path/to/parity/sources \
  bash tools/native/run_output.sh
UP=/path/to/upstream B=/path/to/build MATH_DIR=/path/to/parity/sources \
  STRICT=0 bash tools/native/run_output.sh
UP=/path/to/upstream B=/path/to/build MATH_DIR=/path/to/parity/sources \
  SANITIZE=1 bash tools/native/run_output.sh
```

Keep `BUILD_DIR` to run `output_rows RESOURCES --memory-full` and
`output_rows RESOURCES --memory-rows` in separate processes. These include
reading encoded output for a digest, and do not measure Android App PSS.

Two research harnesses investigate upstream algorithm limits separately from
byte-preserving production regressions:

```bash
UP=/path/to/upstream B=/path/to/build bash tools/native/run_blur_study.sh
UP=/path/to/upstream B=/path/to/build bash tools/native/run_blur_study.sh --kernel
UP=/path/to/upstream bash tools/native/run_iir_reference.sh
```

`blur_boundary.cpp` exercises the actual Vulkan blur and reports reversal and
constant-extension diagnostics, plus interior sample counts (`nan` if empty).
Its kernel mode measures a central impulse against a normalized discrete
Gaussian; it does not grade photographs. `iir_boundary_reference.cpp` is a CPU
candidate for the Triggs/Sdika boundary conversion, tested against explicit
long-double tail continuation. Carrying state across five chunk sizes preserves
double scan bytes. This does not implement GPU streaming or change the product
boundary rule. See [the algorithm study](../../docs/ENGINE_ALGORITHM_RESEARCH.md).

For isolated RSS measurements, run these in separate processes after building:

```bash
BUILD_DIR=build-native bash tools/native/run.sh
build-native/engine_adaptation --memory-reference
build-native/engine_adaptation --memory-cache
```

These report peak RSS and an output digest for the same generated input. The
reference is serial and the candidate may be parallel; their elapsed times do
not establish a phone speedup or an old/new production throughput comparison.

`render_regression.py` compares actual film rendering against a completed
baseline parity study. Pass its directory containing `manifest.json`, `inputs/`
and per-case `.rgb` files. The baseline manifest records the input hashes and
complete parameters. Use the same Vulkan driver/device, a fresh candidate
binary and a fresh output directory:

```bash
python3 tools/native/render_regression.py \
  --binary /path/to/candidate/spk_android \
  --resources /path/to/build/engine/resources \
  --baseline /path/to/baseline/renders --out /path/to/candidate/renders \
  --raw-root /path/to/cc0/raw/fixtures
```

This script uses `tools/parity/host_parity.py` and its NumPy dependency for the
shared Layer 2 fixture. It fails on a changed byte in the engine print, sRGB/P3
exports or sRGB/P3 views. It records hashes and logs for every case. Stochastic
effects are compared only with deterministic fresh-process seeds; this is not
a general assertion that arbitrary random renders must match.

Linux native parity, APK compilation and execution on physical Android GPUs
are separate verification steps. See [the adaptation record](../../docs/ANDROID_ENGINE_ADAPTATION.md).
The completed 2026-11-08 run's per-output hashes and byte comparisons are in
[the render evidence](../../docs/validation/android-engine-render-2026-11-08.json).
