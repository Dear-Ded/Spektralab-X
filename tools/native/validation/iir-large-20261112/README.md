# Difference-state IIR evidence, 2026-11-12

Co-created with GPT (OpenAI). Public synthetic inputs only. Float image payloads,
executables, private photographs, handoffs and credentials are excluded.

Both hosts built new engine/core/resources through patch 0053 from their
verified 0052 source trees, applying 0053 with zero fuzz. `verified.exit=0`
records the final runner, including all numerical and frozen-output gates.
`result.json` is a generated index; raw logs/CSV/summaries remain authoritative.
Every downloaded source hash was compared against the repository tool/patch.

| Gate, per host | Result |
| --- | --- |
| v2 large-radius matrix, automatic / forced split | 1040 / 1040 passed |
| v2 mixed FIR/IIR and reversal matrix, automatic / forced split | 574 / 574 passed |
| Original direct-state large-radius negative control | 1040 completed, 17 numerical failures |
| v1 complete pipeline | 10 fixtures, 140 stage pairs, 1,209,390 oracle values |
| v2 complete pipeline | 10 fixtures, 140 stage pairs, 1,614,606 oracle values |
| v2 accepted large-width CineStill film and print | 6000x2 amount2; 8000x2 amount1 |
| v2 over-domain transactional rejections | 6000x2 amount32; 8000x2 amount16 |
| Default and v1 complete-stage compatibility | byte-equal to each host's frozen 0052 streams |

Both automatic probes rejected fused-FMA residual and chose Dekker split.
The forced mode confirms that path, not an additional successful FMA path.
Large-radius v2 error maximum is `4.76837158203125e-7`, error/S maximum
`1.335144055701676e-7`, doubled-tail convergence maximum
`5.6843418860808015e-14`, and alias byte difference zero. The worst large-radius
row is the accumulated 257x259 asymmetric image with sigmas `{1024,512,0}`.
The old shader's maximum error is `0.00018796324729919434` under the same gate.
It is tested directly beyond v1's product admission domain for diagnosis.

The large-radius auto/split float payload is 18,940,368 bytes per run with
SHA-256 `8db53f650b4b524e0aaf3fc094f1e559a93359f6a8555fa9e1148f996f6ee434`
on both hosts. This observation is not a general cross-driver bit-identity
promise. Full pipeline streams do differ between hosts; each is checked only
against its own frozen counterpart. Pipeline streams contain 12,689,040 bytes
each. Their default/v1 hashes are unchanged from the previous study.

V2 pipeline oracle count includes all lines in the ten small fixtures plus
405,216 values from the first line per dispatch/channel in the large-width
fixtures. Maximum checked sigma is about 274.891726. Absolute oracle error
maximum is `3.0517578125e-5` at an HDR intermediate scale near 294. Maximum
scaled error is `1.1112287778917034e-7` on AOIDC and
`1.0689096095829029e-7` on IBX. These are not real-film or full spectral errors.

AOIDC additionally records NDK 27.2 API26 ARM64 object compilation of actual
`blur.cpp` and `vulkan_gpu.cpp`; the new SPIR-V passes Vulkan-1.0 validation.
For full replay, the previously verified pinned 52-overlay tree was copied,
all 52 overlays were reversed with zero fuzz, then all 53 reapplied with zero
fuzz. All 101 affected files match the tested source byte for byte. The
complete patch hashes were checked against this repository. This reuses the
previously verified pin, rather than claiming a fresh upstream download.

Final synchronized tool archive SHA-256:
`40ccb8a63ee6ebd38ae840fbefec12c78f96c638a5425025756931b2ea4040eb`.
Evidence archive hashes verified before extraction:

- AOIDC: `374e2e3c000e48d8e09d7517eed7c383ba325c2cc57870e183c9ee7017fef749`.
- IBX: `b74ccbbd80655b7b8d83a06be4434fa1433a1ad6405ae2d9e42d7483a234f43e`.

Reproduction, equations, fixture controls and scope:
[large-radius report](../../../../docs/ENGINE_IIR_LARGE_RADIUS_2026-11-12.md).
No Android relink, APK, phone execution, full-height large-photo memory test,
physical PSF calibration or default-mode switch is asserted.
