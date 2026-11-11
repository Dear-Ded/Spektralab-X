# Signed-input limits of the interaction LUT

GPT (OpenAI), 2026-11-11. Source/cloud research only; no application renderer,
RAW default, version, APK or release change. This extends the
[default-pair gate](LUT_INTERACTION_GATE_2026-11-11.md).

## Decision

Keep the LUT as a restricted prototype. A larger uniform lattice does not solve
the inherited spectral upsampler's very steep signed-domain extension. Do not
enable it for arbitrary RAW or silently clip negative input to pass the test.
The exact full renderer remains the reference, including its limitations.

Both Linux hosts independently reproduced this result with the same 49-overlay
engine, Portra 400 x Supra Endura, fixed 0 EV, no spatial effects, and the actual
UNORM16 working boundary and P3 output transform. These are synthetic inputs,
not an explanation of the user's Xiaomi photographs or a Lightroom match.

| Lattice | Trilinear maximum DE00 | Tetrahedral maximum DE00 |
| --- | ---: | ---: |
| 33^3 | 37.104880 | 44.385459 |
| 65^3 | 14.962652 | 21.706464 |

These maxima use the same 10,240 in-domain held-out inputs as the original gate;
four other inputs exercise actual spectral fallback. Common SDR group results
still pass the original mean <= 3 gate. Passing that mean is not approval of
the signed stress domain.

## Actual-engine witness

The probe reads the real `spk_tc_b` GPU outputs with the actual adapted matrix;
it does not infer the selector from a different CPU color model. At fixed R/B,
two consecutive float32 green values produce:

| Quantity | Before | After |
| --- | --- | --- |
| Linear ProPhoto R | 0.5552405119 | 0.5552405119 |
| Linear ProPhoto G | -0.2234972715 | -0.2234972566 |
| Linear ProPhoto B | -0.2419789582 | -0.2419789582 |
| G float32 bits | 3194281080 | 3194281079 |
| Actual GPU tc | (1, 0) | (1, 1) |
| Final decoded P3 RGB | (0, 0.02312636003, 0.2130467445) | (0, 0.1157631204, 0.01304093748) |

The exact green step is 1.4901161193847656e-8. Complete reference output changes
by **DE00 51.7994535**, independently on both hosts. This is a sampled abrupt
change, not proof of a strict discontinuity in the real-arithmetic formula:
epsilon guards and clamps can create a continuous transition narrower than one
float32 step. Individual downstream contributions are not isolated by this run.

The inherited projection is, with epsilon=1e-10:

```text
(X,Y,Z) = adapted_matrix * linear_RGB
B = X+Y+Z
x = X/max(B,epsilon); y = Y/max(B,epsilon)
u = clamp((1-x)^2,0,1)
v = clamp(y/max(1-x,epsilon),0,1)
```

In the witness region Z is negative, B is positive, and x>1. The protected
denominator selects opposite clamped v edges as Y crosses zero. Negative RGB
alone is not the criterion: valid wide-gamut conversions can also contain
negative RGB, and input should remain signed through the existing pipeline.

## Why a denser local test can mislead

The experiment also bakes a 17^3 subgrid inside each of four previously failing
33^3 cells, then tests the old anchor, its six float32 neighbors and 257 off-grid
points. These are diagnostic regression cells, not a fresh acceptance set.

In the worst original cell, the old anchor's tetrahedral error drops to
0.015191. Yet a separate 65-point, consecutive-float32 Y-crossing sequence in
that same cell still has maximum approximation error **46.898121**. Improving
the original failing point does not establish that its neighborhood is safe.

A separate classification checks positive XYZ at every original cell vertex,
with a rounding margin. The 33^3 signed/positive-cell cohort contains 1,268
points and has tetrahedral maximum 1.492557; the 654 signed/unsafe-cell points
reach 44.385459. This locates one boundary risk; it is not a general DE00 bound,
a physically realizable SPD test, or a shipping support/fallback classifier.

## Source provenance

The guard was not introduced by this Android/Windows port. The checked sources
are deliberately distinguished:

| Implementation | Fixed revision / actual locations | Denominator behavior |
| --- | --- | --- |
| Actual 49-overlay SpektraLab Vulkan | Base `a46627d250545a394ca21522f8a28b0fb89e4c42`; `engine/src/shaders/vulkan/spk_tc_b.comp:30-38` | Positive epsilon for B and 1-x |
| Same engine, original Metal | `engine/src/shaders/nodes.metal:121-129` | Same protection |
| Author spektrafilm Python | `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc`; `src/spektrafilm/utils/spectral_upsampling.py:42-54,119-147` | `np.fmax` protection |
| Hanatos/vkdt historical reference | `9b25fdd974baa0c65bc07d0cbb904da624742970`; `src/pipe/modules/shared/upsample.glsl:2-6,15-20` | Direct division by B and 1-x |
| Hanatos/vkdt checked newer revision | `ea3deef7e4636936f8da746e3fba93762589d949`; same file | Direct division |

For B>0, the direct expression reduces to Y/(Y+Z); it has its own singular
surface at Y+Z=0. Removing guards is not a general stability proof. Vkdt also
uses a different coefficient lookup, so this table does not establish whole
renderer equivalence or the provenance of the exact distributed coefficient
file. The Python `fused_tc_b` fork imported by upstream parity tests is not
revision-pinned in the checked tree; all-stage Python parity remains unverified.

Primary source locations:

- [Fixed SpektraLab Vulkan shader](https://github.com/JamesQiu2005/SpektraLab/blob/a46627d250545a394ca21522f8a28b0fb89e4c42/engine/src/shaders/vulkan/spk_tc_b.comp)
- [Fixed spektrafilm Python reference](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm/utils/spectral_upsampling.py)
- [Historical Hanatos reference](https://github.com/hanatos/vkdt/blob/9b25fdd974baa0c65bc07d0cbb904da624742970/src/pipe/modules/shared/upsample.glsl)
- [Jakob and Hanika author publication](https://rgl.epfl.ch/publications/Jakob2019Spectral)

## Isolated causal intervention

Both hosts also ran a counterfactual with **only** the 1-x denominator changed:
when it is below -1e-10, preserve its negative sign; otherwise retain the old
expression. B protection, clamps, profiles, coefficient tables, all other
shaders and the pipeline binary remain unchanged. A separately recompiled
unmodified shader must first match the original SPIR-V byte-for-byte. Original
inputs are hash-checked after the run; 55 recorded inputs per host are intact.
The experiment uses a new, disjoint directory and never patches the app source.

The maximum adjacent reference DE00 over the same 65 Y-crossing inputs changes
from **51.7994535 to 0** at the tested P3 UNORM16 boundary. Actual tc-y now varies
near zero instead of selecting opposite edges. This isolates the denominator's
causal contribution to this particular witness.

At 33^3, all 7,307 positive-XYZ-cell reference RGB decimal triples are identical
before/after within each host; at 65^3 all 7,639 are identical. Recovering the
quantized P3 RGBA16 codes from these decimals also gives identical byte arrays
(58,456 and 61,112 bytes). The comparison requires each recovered channel to be
within 0.01 code of a UNORM16 value. This verifies the recorded output contract,
not unexported intermediate float bytes or byte identity between the two hosts.

The counterfactual still has tetrahedral maxima **34.588483** at 33^3 and
**19.680381** at 65^3. Its 65^3 trilinear maximum increases from 14.962652 to
15.943596. It changes 19 unsafe held-out reference outputs in each lattice
comparison. Thus it changes the rendering reference and is not a complete
signed-domain solution, a shipping fix, or permission to broaden LUT support.
B=0 and Y+Z=0 neighborhoods, physical input cohorts and author-model validation
remain separate work.

The three scripts under `tools/lut_experiment/` reproduce the intervention:

```bash
BASE=/path/to/experiment-root UP=/path/to/upstream B=/path/to/build \
  BIN=/path/to/signed/lut_offline BASELINE=/path/to/signed \
  METRICS_DIR="$PWD/tools/lut_experiment" EXPERIMENT=/path/to/new-directory \
  bash tools/lut_experiment/run_negative_denominator.sh
```

`EXPERIMENT` must not exist or overlap source/build/binary/baseline trees. These
scripts intentionally require the inspected source hash, original compiler
contract and matching binary/results; a different upstream needs a new review.
Compact counterfactual outputs are retained separately from the unchanged
reference under `tools/native/validation/lut-signed-20261111/counterfactual/`.

## Verification and next gate

AOIDC uses GCC 11.4 / llvmpipe LLVM 15; IBX uses GCC 14.2 / llvmpipe LLVM 19.
Each has four CPU Vulkan workers and strict 128 MiB per-storage-buffer limits.
This does not prove a 128 MiB process budget, Android driver behavior or speed.
The canonical full engine manifest SHA-256 remains
`1aa7cfe5b3fd1db7d9cf53bb9d6ee8c62e88944da7f579f949fce9aecf34bf8a`.

Both diagnostic runs match their prior host's 40,976 serialized 33^3 held-out
rows and the complete 431,244-byte float32 lattice exactly. AOIDC additionally
reran the original `MODE=gate`: all 74,750 CSV rows and lattice bytes match the
previous run. No performance conclusion is drawn from the diagnostic timings.

Reproduce using the same matching source/build as the original gate:

```bash
UP=/path/to/upstream B=/path/to/build OUT=/path/to/signed MODE=diagnostic \
  bash tools/native/run_lut_offline.sh
python3 tools/lut_experiment/signed_report.py /path/to/signed \
  --reference-gate /path/to/prior-gate --evidence-dir /path/to/compact \
  --output /path/to/compact/signed-report.json
```

Compact evidence is under
`tools/native/validation/lut-signed-20261111/`. Full global CSV/grids are
regenerated by the command; the compact records retain local inputs, actual
coordinates, reference/approximate outputs, distributions, source hashes and
logs. No private photo or unrelated machine file is included.

The portable `check_signed_evidence.py` recomputes all 13 local groups per host,
joins 1,121 samples to actual GPU coordinates by exact float32 input identity,
and checks the consecutive Y-crossing sequence. `--self-test` must reject six
in-memory corruptions per host: mismatched coordinates, a missing crossing row,
a skipped float neighbor, NaN, a forged summary and forged GPU coordinates.
Use `--counterfactual` only for the separately altered reference; it removes the
requirement to reproduce the original (1,0)->(1,1) jump while retaining all data
identity, adjacency and report checks. These consistency checks do not prove
the execution provenance or the omitted full global statistics independently.

Next decisions remain separate: further singular-surface controls can extend
the causal test, a cohort integrated from nonnegative SPDs can test a defined
physical model, and reduced spectral quadrature can test a different speed
approximation. None alone approves a new rendering model. Interaction still
needs a validated support domain, identity/publication contracts, lattice/GPU
tests and exact fallback. Full-resolution strip execution remains independent.
