# Complete-pipeline IIR evidence, 2026-11-12

Co-created with GPT (OpenAI). Only public synthetic diagnostics are included;
no private photographs, handoffs, credentials, executables or float image
payloads are published.

Each host directory retains the actual zero exit marker, runner/current/frozen
logs, all 140 stage comparisons, 174 observed dispatch/channel oracle rows,
initialized scenario metadata, the 120-case parameter-domain report with 1331
assertions, source/archive hashes and raw-payload sizes/hashes. `result.json`
provides a generated compact index. All source hashes were checked against
the repository files after evidence retrieval.

Both hosts reuse their independently built 0052 and frozen 0051 Vulkan
archives from the previous boundary study. The current diagnostic executable
was compiled on each host. Automatic arithmetic selected Dekker split on both;
neither cloud validates successful fused-FMA or mobile GPU execution.

The complete default snapshot stream is 12,689,040 bytes per host, byte-equal
to that host's pre-0052 stream:

- AOIDC SHA-256: `2ba2b47b6c4419ed54842ed90b140f1671079029b95f8b727d81f3691a00d6b1`.
- IBX SHA-256: `f2022a7dc2e3fa1628d0e0ceeb2d8aebd101d27e1f19bce05e5e17fa47249f78`.

Cross-host bit identity is not asserted. Differences between environments
are present in both whole-stage hashes and some old-versus-new metrics.
Corrected dispatches are instead checked against an independent numerical
reference on each host, using the unchanged declared tolerance.

The synchronized tool archive SHA-256 is
`723b4227af2b6a45c01e2ea91407a4791e76b853c9a436dbb9257abcb00b778f`.
Downloaded evidence archives were hash-checked before extraction:

- AOIDC: `8b5a38cf55e396e7f8b1ad61f36cfaa0b6d61c3dfd5bf5fd4a2925ac09eb0957`.
- IBX: `6be63e633cb3ed28369afc9dea3f629ae40eaa6718f45885bce47c9e945c2e84`.

The tool's maximum absolute oracle error is `3.0517578125e-5` at an HDR
intermediate signal scale near 294; maximum error divided by the per-row
scale is `1.0644281317285164e-7`. The checked pipeline sigmas reach about
27.4964. All sampled doubled-tail convergence differences are zero. The
standalone boundary study's sigma-256 tests remain separate evidence.

The CPU domain report records actual CineStill back-reflection cutovers at
7902/7903 pixels for 35mm and 8127/8128 for 36mm at halation amount one.
At amount two a 1600-pixel preview can be within domain while full 6000
exceeds it. Actual 6000x2 and 8000x2 public Pipeline calls independently
reject without publishing output or silently falling back. These are
thin range fixtures, not complete high-resolution photographs.

Commands, fixture settings, interpretation and remaining acceptance gates:
[pipeline report](../../../../docs/ENGINE_IIR_PIPELINE_2026-11-12.md).
