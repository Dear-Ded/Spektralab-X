# Default-pair 33-cube interaction gate

GPT (OpenAI), 2026-11-11. This is an offline experiment, not an enabled preview
feature. The existing full spectral renderer and RAW linear default are intact.

## Decision

Keep a restricted color-approximation prototype as the next candidate. Both
Linux hosts pass the requested mean CIEDE2000 <= 3 independently for all four
common synthetic groups, with trilinear and tetrahedral interpolation. This does
not qualify the LUT for arbitrary RAW, arbitrary parameters or final exports.

AOIDC, 1,024 held-out inputs per group, 16-bit display output:

| Synthetic group | Trilinear mean | Tetrahedral mean | Tetrahedral maximum |
| --- | ---: | ---: | ---: |
| Encoded sRGB samples | 0.43760 | 0.29860 | 1.12859 |
| Neutral ramp | 0.43880 | 0.20635 | 0.87041 |
| Warm RGB range | 0.43319 | 0.30298 | 1.41018 |
| Dark RGB range | 0.01770 | 0.01551 | 0.13205 |

These are color proxies, not a natural-photo distribution, measured skin chart,
private Xiaomi photographs or a Lightroom comparison. The separate signed/wide
stress samples reach **44.38546** tetrahedral DE00 on AOIDC. Even inputs inside
the LUT's numerical box can therefore be unsuitable. Clamping negative channels
or pooling these errors into the common-group mean would conceal this failure.

A 1024 x 768 synthetic scene gives mean 0.31839, maximum 1.69054 for tetrahedral
interpolation. Removing configured spatial effects separately gives mean
1.19894, maximum 15.19534 on the same sampled scene. The latter is model deletion,
not interpolation error. Grain, halation, DIR diffusion, contrast masks and
other spatial effects cannot be claimed as reproduced by this three-input LUT.

## Matching the application

The older experiment used Portra Endura and direct linear P3 output. This gate
uses the current product default **Portra 400 x Supra Endura**, 36 mm, live edge
1024, print exposure compensation off. Exposure is fixed at 0 EV to isolate a
point function; per-image automatic metering is excluded.

Input is linear ProPhoto RGB. The 33^3 table contains encoded ProPhoto float32
values, then both candidates cross the production UNORM16 working boundary and
the production `OutputTransform` to Display P3. Quantized P3 is decoded before
D65 Lab/DE00 measurement. Eight-bit output has its own report. This is an SDR
viewing comparison, not a color difference formula for unbounded HDR.

The signed log shaper has domain [-0.25, 16], pivot 0.01 and zero position 0.25;
blue is the fastest lattice axis. The independent held-out seed is 0x20261112.
The training grid is excluded from error metrics. Four out-of-box points use
actual spectral fallback, checked against actual reference results. Point
permutation/reshape and repeat results must match byte-for-byte before the gate
runs. Nonfinite intermediates are rejected before display clipping.

An actual `spk_to_rgba16` GPU dispatch is compared against the probe's CPU working
quantization on all 10,244 held-out values. Production float/u16 P3 packers must
then agree byte-for-byte for both output depths. This establishes the tested
pixel boundary, not an identical timed JNI fetch path.

## Two-host evidence and limits

Both hosts independently build/use the same patched engine source. Canonical
SHA-256 of the complete `engine/` file-hash manifest, C locale path ordering:
`1aa7cfe5b3fd1db7d9cf53bb9d6ee8c62e88944da7f579f949fce9aecf34bf8a`.
There are 49 engine overlays; the upstream pin is unchanged. IBX builds with RAW
disabled because this experiment consumes synthetic float input, not a decoder.

| Host | Compiler / software Vulkan | Point spectral median | CPU LUT median |
| --- | --- | ---: | ---: |
| AOIDC | GCC 11.4 / llvmpipe LLVM 15.0.7 | 2349.56 ms | 376.23 ms |
| IBX | GCC 14.2 / llvmpipe LLVM 19.1.7 | 318.73 ms | 122.46 ms |

Three repetitions at 1024 x 768, four llvmpipe workers. The timing includes input
upload or CPU interpolation, display packing and P3 decoding for measurement;
it excludes construction, baking, decoding/JNI/UI and automatic metering. The
spectral probe reads float32 and simulates the equivalent working UNORM16
boundary on CPU. It is not the actual JNI GPU-to-u16 fetch timing. Software
drivers, compilers and host load differ; neither ratio predicts Android speed.
The full spatial end-to-end measurement is reported separately from kernel wall
time. The 128 MiB Vulkan storage-buffer limit is enabled; this is not a claim
that process RSS or all Vulkan allocations stay below 128 MiB.

`ciede2000_reference.py` independently checks the 34 author-supplied Sharma/Wu/
Dalal reference pairs (four-decimal rounding bound 5e-5), symmetry, nonfinite
rejection and 4,096 deterministic random Lab pairs against the existing metric.
`gate_report.py` recomputes errors from CSV and rejects stale metrics, nonfinite
values, invalid SDR outputs, unmatched held-out inputs and invalid fallbacks.

Reproduce on Linux with the matching patched engine:

```bash
UP=/path/to/upstream B=/path/to/build OUT=/path/to/results MODE=gate \
  bash tools/native/run_lut_offline.sh
python3 tools/native/lut_metrics.py /path/to/results/samples.csv /path/to/results/metrics.json
python3 tools/lut_experiment/gate_report.py /path/to/results /path/to/results/metrics.json \
  --output /path/to/results/gate-report.json
python3 tools/lut_experiment/ciede2000_reference.py --compare tools/native/lut_metrics.py
```

Compact logs, contracts, source hashes, errors, tails and timings are retained
under `tools/native/validation/lut-gate-20261111/`. No private photos are used.

## Requirements before interaction integration

Do not bake more stock pairs or wire the main UI based only on this result.
First define a conservative validated input/parameter contract with an exact
fallback, then test lattice identity, asymmetric RGB axes, texture centers and
storage quantization against this oracle. Hardware texture filtering of an
endpoint lattice needs `(u * (N - 1) + 0.5) / N`; manual buffer interpolation
using `u * (N - 1)` must not add that correction again.

LUT identity needs the actual point-function parameters, resource generation,
domain/shaper, size, interpolation, storage format and output contract. Pixel
publication separately needs source, edit revision, metering/gain and tier.
Changing film exposure inside the nonlinear pipeline is not automatically
equivalent to an output adjustment. Spatial stages must remain honestly absent
from a color approximation, with full spectral final output preserved.

Full-resolution strip rendering remains a separate task. This experiment does
not remove the current working-size limits, establish complete 128 MiB execution,
change the pinned upstream, or enable a camera viewfinder.
