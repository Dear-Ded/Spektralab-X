# LUT experiment evidence, 2026-11-11

`final` contains the accepted full run and complete held-out CSV. `full` is the earlier identical-value run before adding an actual separate out-of-domain fallback render; its metrics contain the same interpolation values. `smoke.log` records the preceding 9-cube smoke. `final/metrics.json` was regenerated locally with the final metric tool to add raw worst-case records; its tiny final-digit differences from cloud stdout are host math-library rounding.

The sampler's PRNG seed is **539365649 (0x20261111)**. The initial contract metadata accidentally wrote 539100945; this metadata is corrected before final delivery. Sample values and the actual PRNG initialization were always 0x20261111.

Primary file SHA256:

| File/version | SHA256 |
|---|---|
| OCIO baking HTML v2.4.2 | bfd53535c4722d9b043f98925dd9fd3f999710039fb29bdc62c71aba54eb3e8c |
| OCIO Lut3DOpCPU.cpp v2.4.2 | 405733918dbe15ac54a486c275bf6f68b699b241f357431a7ce8db7afa80224f |
| colour interpolation.py v0.4.6 | acad01a09f774a228e736d2e70109461ca0f36b52012352ec00cb7412b453cc8 |
| colour delta_e.py v0.4.6 | 51322a12650b1a48f977ed061c6927910d85e276399e2512194ebbc42a22487d |

Staging check transcript and hashes are in `lut-staging.log`; all three cmp commands returned zero. Cloud `.so` or LUT binaries are not included or installed.
