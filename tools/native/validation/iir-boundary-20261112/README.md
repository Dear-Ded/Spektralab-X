# IIR boundary evidence, 2026-11-12

Co-created with GPT (OpenAI). Public synthetic inputs only; no private photographs,
handoffs, credentials, executable binaries or release packages are included.

`result.json` summarizes the completed runs. Each host directory contains its
actual runner log, zero exit marker, toolchain/shader hashes, Gaussian CSV and
JSON, full alias comparisons and frozen-default hashes. AOIDC additionally
records the 52-patch zero-fuzz replay and all 99 matching affected source files.

Both hosts freshly built the current Vulkan engine/resources and a separate
pre-0052 engine/resources from the 51-patch tree. GCC 11 / glslang 11.8 / LLVM
15.0.7 and GCC 14 / glslang 15.2 / LLVM 19.1.7 independently passed 574 Gaussian
and 640 direct-alias cases for automatic and forced-split selections.
Automatic mode selected Dekker split product on both hosts; fused FMA was not
accepted. The same fresh probe rejects the old boundary on a short ramp.

The four legacy streams per host contain 645,498 float32 values (2,581,992 bytes)
and share the recorded SHA-256, also equal across these hosts. Corrected output
is checked against an independent explicit-tail recurrence, not frozen as a
claim of cross-GPU bit identity. Alias output compares 16,057,920 float32 values
per run with zero differing bytes.

The compiled probe archive SHA-256 is
`a1ef27b85deedee69135d01a7c05bd4d91f2b684a503c88420d8ff430af9d335`.
Downloaded evidence archives were checked before extraction:

- AOIDC: `8c831ea35bffbe7b07fdacbdea2b2eda9a07e3bc58f9015560f6bd3a0c5faf6a`.
- IBX: `44d33ff1f2e1bbea9396c306ee4c055923f0440733cd53ebf6b7e356c1203141`.

Android proof is limited to API-26 ARM64 objects for blur/backend and Vulkan-1.0
SPIR-V validation. No full native relink, device execution, full-film parity,
real-film calibration, bounded whole-pipeline memory or APK is asserted.
Reproduction and algorithm limits: [implementation report](../../../../docs/ENGINE_IIR_BOUNDARY_2026-11-12.md).
