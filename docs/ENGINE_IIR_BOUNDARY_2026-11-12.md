# IIR constant-extension boundary experiment

Co-created with GPT (OpenAI), 2026-11-12. Upstream remains pinned to
`a46627d250545a394ca21522f8a28b0fb89e4c42` plus the ordered engine overlays.

Patch 0052 adds an explicit Vulkan experiment for the recursive Gaussian
filter's terminal states. The existing shader and default selection remain
unchanged. RAW stays scene-linear by default; film/paper profiles, spectral
tables, the FIR/IIR threshold, application version and release state are unchanged.

## What changed

The legacy reverse scan starts its three states from the last forward sample.
This discards the decaying forward tail beyond the image boundary. It produces
direction-dependent errors even with the existing double-float arithmetic.
The earlier actual-GPU observations and mathematical reference remain in
[the algorithm research](ENGINE_ALGORITHM_RESEARCH.md).

The new `spk_iir_boundary_df_acc` shader uses a Triggs/Sdika state conversion
for a constant extension of the input. With `g=b[0]`, `a=b[1..3]` and
`k=g/(1-a1-a2-a3)`, it starts the forward states at `left*k`. Before any write
it saves that pass's original right endpoint, including during the second,
in-place pass. It subtracts `right*k` from the three complete forward states,
applies the three rows `M*A`, `M*A^2`, `M*A^3`, then adds `right*k^2`.

The CPU prepares the conversion in long double and uploads high/low float
pairs. State arithmetic retains both words. The forward intermediate plane is
still float32, and the reverse scan reads that plane. Merely copying the
previous all-double reference into the GPU test would miss this storage
boundary. Forward states are never reconstructed from the rounded plane.

The original horizontal/vertical order, mixed FIR/IIR dispatch, inactive
channels, final accumulation and 256-line launch grouping remain in place.
This is a boundary correction that deliberately changes the experimental image;
it is not an equivalent scheduling optimization or a new film model.

## Explicit Scope

Only `SPK_IIR_BOUNDARY=triggs-v1` selects the new kernel, and only in a Vulkan
build. Unset, `off` and unknown values select the legacy shader. Active IIR
sigmas must be finite and within `[3,256]`; an unsupported sigma is rejected
before replacing the caller's output. FIR channels retain their existing path.

This is a process-level research switch, not an editor setting. Use a fresh
engine/session when comparing complete renders. Changing the environment does
not invalidate already computed image caches or record an algorithm version
in a sidecar. Product integration needs that explicit state before making this
a saved editing option or a new default. Metal has not been ported.

## Numerical Evidence

`iir_boundary_gpu.cpp` calls the actual `Blur::gaussian` and Vulkan shader.
Its independent oracle never uses the terminal conversion matrix: it explicitly
continues the original recurrence with repeated right input, writing each
forward output to float32 while retaining high-precision recursive states.
The far reverse states use `k*float32(right*k)`. Padding of `ceil(64*sigma)+128`
is compared with `ceil(128*sigma)+128`; nonconvergence fails the test.
The coefficient generator is intentionally shared with production, so this
checks the boundary implementation, not the accuracy of the YVV Gaussian fit.

Each run covers 574 corrected cases: lengths 1/2/3/17/513, both axes, sigmas
3/6/24/96/256, zero and nonbinary constants, ramps, left/right/centre impulses,
steps, signed HDR and asymmetric channel/spatial patterns. Mixed-channel cases
include 0/1.5/2.999, all five IIR sigmas, nonzero accumulators and negative/positive
weights. A 257 by 259 case crosses the launch grouping boundary.

On both Linux clouds, Mesa llvmpipe LLVM 15.0.7 and LLVM 19.1.7, the automatic
math mode and forced split-product runs passed. Across these synthetic cases:

| Measure | Maximum absolute difference |
| --- | ---: |
| Corrected GPU versus explicit-tail oracle | `2.384185791015625e-6` |
| Oracle at the two padding lengths | `0` after float32 output |
| Corrected line versus flipped/reversed line | `1.4901161193847656e-6` |
| Legacy GPU versus the same oracle | `2.2910477519035339` |

Both automatic probes rejected the device's fused-FMA residual and selected
the validated Dekker split product. The forced runs confirm that selection;
they do not establish successful fused-FMA shader execution on either cloud.
The images here are below the advertised storage-buffer range; the backend's
separate software-device range probe is not a mobile memory-limit result.

The largest corrected error occurs in an accumulated 3 by 257 asymmetric HDR
case with sigmas `{3,6,256}`. The sigma-256 line maximum is
`1.8477439880371094e-6`, on the 513 by 1 asymmetric HDR signal. Per-sigma line
maxima for 3/6/24/96 are respectively about `1.19e-7`, `1.19e-7`, `1.19e-7`,
`2.38e-7`. CSV records identify each witness. These are bounded synthetic errors,
not a photograph error percentage or a measured improvement in real-film fidelity.

The fixed gate is `1e-5*S` for GPU error and `1e-7*S` for oracle convergence,
where `S=max(1,max(abs(input)))`; constants have the stricter `1e-6*S` gate.
No gate was relaxed to obtain these results. The previous all-double CPU
reference's approximately `1e-11` result is a separate mathematical check.

## Reproduction And Limits

Build the engine and `spektralab_vulkan_resources` through patch 0052, and a
separate frozen engine through 0051. The public synthetic runner uses C++20,
the real engine/core/volk archives and a working Vulkan loader:

```bash
UP=/path/to/52-patched-upstream B=/path/to/52-build \
BASELINE_UP=/path/to/51-patched-upstream BASELINE_B=/path/to/51-build \
BUILD_DIR=/outside-repo/iir-results bash tools/native/run_iir_boundary_gpu.sh
```

The runner checks both arithmetic selections, compares default float payloads
with the frozen pre-0052 engine, and requires the old boundary to fail the new
independent numerical gate. It also runs a direct shader alias-control probe.
Results and exact verification details live under
`tools/native/validation/iir-boundary-20261112/`.

Each host/mode compared 2,581,992 bytes of default output with its frozen build;
all four default streams per host have SHA-256
`0f800b9efa47c2cc36e4972cfcb582a3c66928d2307907cecdfbd830d9069a73`.
Unset/off/unknown are also compared within all 574 cases. The old engine fails
the new gate on a 2 by 1 ramp at sigma 3, with error `0.466495938599`.
Thus the gate demonstrates the original defect rather than merely accepting
the new code.

The direct alias probe passed 640 cases per host/mode: both axes, very short
images and 17 by 19 / 257 by 259, five sigmas, active/inactive channel masks,
separate nonzero accumulators and zero/negative/positive weights. It compared
16,057,920 float32 values per run with zero differing bytes and no nonfinite
outputs. It uses the actual conversion helper as a coefficient contract; its
independence is in buffer ownership, not a second numerical oracle.

Android NDK 27.2.12479018 compiled `blur.cpp` and `vulkan_gpu.cpp` for ARM64 API
26, and `spirv-val --target-env vulkan1.0` accepted the new shader. All 52 engine
patches replayed with zero fuzz on the fixed upstream archive; all 99 affected
files match the tested source byte for byte, including the five changed files.
The unchanged legacy shader source also matched the replay; its SPIR-V matched
the frozen build on each host. Shader binaries differ across glslang versions.
This is object compilation and host Vulkan execution, not a full Android relink,
APK, installation or Adreno/Mali test.

Constant extension differs from FIR's reflection policy. This patch does not
solve the width discontinuity at sigma 3, re-fit halation PSFs, prove physical
film/paper fidelity, or validate signed spectral projection. The full source and
output planes remain resident; terminal-state correctness is not streamed IIR
or a complete memory bound. The subsequent
[complete synthetic pipeline study](ENGINE_IIR_PIPELINE_2026-11-12.md) passed
staged film/print comparisons and identified a full-resolution sigma ceiling.
Actual mobile execution, broader radius support and a versioned mode remain
acceptance gates before any default change.
