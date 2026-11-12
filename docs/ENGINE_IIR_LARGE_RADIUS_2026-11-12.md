# Stable recursive state at larger radii, 2026-11-12

Co-created with GPT (OpenAI). Patch 0053 adds explicit
`SPK_IIR_BOUNDARY=triggs-v2` for Vulkan, retaining the default legacy algorithm
and the existing `triggs-v1` implementation/domain. The new experimental IIR
domain is sigma `[3,1024]` pixels. Smaller positive channels retain the existing
FIR routing. Film/paper data, Gaussian-fit coefficients, physical parameter
semantics, RAW scene-linear defaults and application/release versions are unchanged.

This addresses the large-radius acceptance gap found in
[the complete-pipeline study](ENGINE_IIR_PIPELINE_2026-11-12.md), rather than
merely lifting its sigma-256 guard. The same numerical gate now rejects the
previous direct-state shader and accepts the new difference-state shader.

## Why the state changes

The original recurrence is `y = g*x + a1*w1 + a2*w2 + a3*w3`. At large sigma
the three states are similar and the result combines large terms of opposite
sign. Splitting `a1/a2/a3` into float pairs also perturbs the very small
`dc=1-a1-a2-a3`. Both effects can amplify error despite float-pair arithmetic.
The two cloud measurements reproduce a numerical failure in the direct-state
shader at sigma 1024. They do not assign its entire error to one cause.

The new state holds `Y=w1`, `D=w1-w2`, `E=w1-2*w2+w3`. With
`h=1+a2+2*a3` and `j=1-a3`, an algebraically equivalent update is:

```text
third = g*x - dc*Y - h*D - j*E
E = E + third
D = D + E
Y = Y + D
```

The CPU computes `g,dc,h,j` from the original double coefficients in long double
before splitting. This avoids asking the GPU to recover the small `dc` by
subtracting three independently quantized large coefficients. All three GPU
coordinates retain both float words. The forward plane and final result remain
float32; accumulation still happens only after the reverse recurrence.

The terminal transform uses the same constant-extension model as 0052. If
`R` contains the old `M*A`, `M*A^2`, `M*A^3` rows and
`B=[[1,0,0],[1,-1,0],[1,-2,1]]`, the new mapping is `B*R*B` because `B^-1=B`.
The endpoint's forward DC is subtracted only from `Y`; reverse DC is added only
to the new reverse `Y`. Reverse differences use `y(n+1)-y(n+2)` and
`y(n+1)-2*y(n+2)+y(n+3)`, matching the reverse scan's state order. The original
source endpoint is captured before any in-place write.

An independent subagent checked the actual implementation and these identities.
Its high-precision algebra check is supporting review, not GPU evidence.
CPU generation still forms differences of the large terminal rows. Linux
long-double generation is exercised here; Android object compilation is a
separate check, and Windows binary64 `long double` execution is not established.
The explicit finite domain remains necessary.

## Numerical and pipeline gates

`iir_large_radius.cpp` calls the real shader, checks separate versus in-place
buffers, and requires the public `Blur::gaussian` v2 path to be byte-identical
to direct v2 dispatch. Its independent reference explicitly continues the
original direct recurrence, uses long-double recursive states, stores every
forward sample as float32 and does not use either terminal matrix. A one-sample
axis uses its settled constant response to avoid repeating a long tail for
every pixel; named sigma/constant checks compare that optimization against the
unoptimized tail byte for byte.

The 1040-case matrix contains named sigmas 3/96/256/257/275/384/512/768/1024;
lengths 1/2/3/17/513/8192 in both orientations; constants, ramps, impulses,
signed HDR and asymmetric channels; inactive channels and signed accumulation;
23 deterministic off-grid radii plus the actual CineStill component near
274.891726; and 257 by 259 launch-boundary images. The last 50 cases were added
after the initial named-grid experiment. All tolerances were retained:
`1e-5*S`, with `1e-6*S` for constants, and `1e-7*S` for doubled-tail convergence,
where `S=max(1,max(abs(input)))`. Alias output must be byte-identical.

A separate 574-case public Gaussian regression includes small-FIR/large-IIR
channel mixtures, FIR-adjacent sigmas, reversal, off/unknown legacy selection,
accumulation and launch grouping. The public v2 path rejects sigma 1025 without
publishing output; v1 continues to reject sigma 257 with its original message.

Both hosts run automatic selection and forced split. Automatic selection on
llvmpipe LLVM 15/19 chooses Dekker split after rejecting the fused-FMA residual;
these are not successful fused-FMA hardware tests. The large-radius matrix's
maximum absolute v2 error is `4.76837158203125e-7`, maximum error/S is
`1.335144055701676e-7`, and alias difference is zero. The old direct-state shader
completes the same matrix with 17 numerical failures; the runner requires that
negative control rather than accepting a crash or incomplete test.
The required old-shader failure reproduces these cloud environments; different
GPU arithmetic could change that negative-control result.

The actual Pipeline is then checked in both v1 and v2 using the ten scenarios
and fourteen snapshots from the previous study. Public negative/final calls
must match their corresponding stage walk, all values must be finite, and the
no-spatial control must remain unchanged. On each host, both the default and
v1 complete stage streams must match the frozen 0052 payloads byte for byte.
This is per-host compatibility; cross-driver bit identity is not asserted.

V2 additionally completes actual CineStill `run_film` and `run_print` at
6000 by 2 / amount 2 and 8000 by 2 / amount 1, without legacy fallback. At those
full widths, it checks the first line per dispatch/channel against the tail
reference, in addition to all-line checks on the small fixtures. The combined
pipeline check covers 1,614,606 values: 1,209,390 in small fixtures and 405,216
sampled from the large-width fixtures. Checked sigma reaches about 274.891726.
Its maximum scaled error is `1.1112287778917034e-7`; maximum absolute error is
`3.0517578125e-5` at an HDR intermediate scale near 294. These absolute and
scaled maxima need not occur in the same row.

The same widths with diagnostic amounts 32/16 exceed sigma 1024 and must reject
without changing the caller's old output or leaving completion/progress state.
These amounts are native test overrides, not claims about UI ranges. Thin
images prove the high-radius call and failure contracts; they do not represent
full-height high-resolution photographs, memory bounds or phone performance.

## Reproduction and remaining limits

Build engine/resources through 0053. Keep the prior complete-pipeline result
directory through 0052 for frozen comparisons:

```bash
UP=/path/to/53-patched-upstream B=/path/to/53-build \
BASELINE_PIPELINE=/path/to/frozen-0052-pipeline/current \
BUILD_DIR=/outside-repo/new-results bash tools/native/run_iir_large_radius.sh
```

The runner needs C++20, Python 3, Vulkan, `spirv-val`, actual engine/core/volk
archives and initialized resources; `RESOURCES` can override the latter. It
exits nonzero on any positive-gate failure or missing negative-control failure.
Raw CSV, summaries, environment/build/source hashes and completion markers are
archived in [the evidence directory](../tools/native/validation/iir-large-20261112/README.md).

The actual blur/backend units compile with NDK 27.2 for API26 ARM64; this is
object compilation, not Android execution. For zero-fuzz full replay, the
previously verified pinned 52-patch tree was copied, its 52 patches reversed,
then all 53 reapplied. All 101 affected files match the tested source. This
reuses the previously verified pin and does not claim a fresh upstream download.

The mode is still a process diagnostic. It neither invalidates an existing
session's cached images nor saves an algorithm choice in sidecars. A versioned
product mode and actual Adreno/Mali execution remain prerequisites for changing
defaults. It also does not fix FIR/IIR threshold discontinuity, refit the
Gaussian/halation model, provide independent full spectral-stage parity, solve
signed projection, establish physical film fidelity, or stream the full image.
No APK, version bump, release or homepage change is part of this work.
