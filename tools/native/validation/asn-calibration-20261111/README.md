# ASN candidate finite-calibration gate validation

Co-created with GPT (OpenAI), 2026-11-11. Public synthetic evidence only.
No phone photographs, private filenames, scene measurements, credentials,
packages or release artifacts are included. This is source-only validation.

The validated pre-integration candidate patch SHA256 is
`d669e972a5393e07e5bdce521a14966c7c0707e26459ba751803d087c1380f4e`.
The integrated `patches/0043-dng-asn-calibration-signature.patch` SHA256 is
`6f8a15dbc56bcbc00d272fd2a87dc518d166bde26323bfcde108f89d5bc0a896`.
The exact compiled candidate header has SHA256
`e2cd1801dd53a1b82b7eb3e6c1482e41c14833ee07085699e5128f7b4e0bae92`.
Both cloud source hash files confirm that header and the two test sources.

The original 80 matrix cases retain their scope: three-channel synthetic
ASN DNGs with valid finite nonidentity CC and a usable FM, single/dual
illuminants, five signature combinations, default/custom WB, compatible16
and float-headroom decode. SDK arithmetic tolerance remains 1e-5. The oracle
uses the reported default-WB temperature; it does not independently certify
NeutralToXY/CCT inference, full SDK rendering or photographic color accuracy.

| Check | AOIDC | IBX |
| --- | --- | --- |
| Baseline matrix contracts | 40/80 | 40/80 |
| Final candidate matrix contracts | 80/80 | 80/80 |
| Candidate maximum matrix error | 8.391295946985622e-8 | 8.391295946985622e-8 |
| Unit-CC output byte-identical pairs | 40/40 | 40/40 |
| No-FM ASN output byte-identical pairs | 40/40 | 40/40 |
| Boundary decode result/byte-identical rows | 64/64 | 64/64 |
| Direct explicit-WB gate inputs equal to baseline | 16/16 | 16/16 |
| Earlier ungated candidate differing boundary rows | 24/64 | 24/64 |

The final finite-calibration candidate also reran the existing WhiteXY suite
on AOIDC: 25 fixtures, 98 decodes and 37 matrix checks pass, maximum error
8.946387675123901e-8. Its 82 successful non-ASN output files equal the old
baseline byte for byte. The four ASN `neutral-control` output files belong
to the newly corrected nonidentity-CC branch and are excluded from that
compatibility count. This final WhiteXY rerun was not repeated on IBX.

Boundary inputs cover NaN/Inf CC with matching/mismatching signatures,
1e-8 off-diagonal CC with mismatching/one-sided signatures, an overlong
signature, and missing ASN. Each of the 16 inputs has four decode rows
(two policies x default/custom). The direct gate result repeats in those
four rows; it is 16 distinct gate checks, not 64 independent gate inputs.
No-FM and boundary JSON records are byte-identical across cloud hosts.

The final candidate preserves the original near-unit/missing-CC branch.
The extension requires all original CC values finite, nonidentity CC under
the existing 1e-7 threshold, ASN, bounded valid signatures, a valid WB and
successful FM preparation. Signature mismatch substitutes identity only
inside that extension. Non-finite CC cannot become eligible merely because
signature selection replaced it with identity. Near-unit CC keeps the old
stored values even when signatures differ; changing that old behavior to
the SDK signature rule would be a separate scoped change.

The malformed file controls intentionally encode CC as TIFF DOUBLE to
carry NaN/Inf or retain sub-rational precision. They are robustness inputs,
not a claim that non-finite CC is valid DNG metadata. LibRaw disables its
camera-matrix path while opening non-finite-CC files, so decode equivalence
alone does not isolate the new interpolation gate. The direct C++ probe
therefore opens a valid synthetic fixture and then injects the CC value
into LibRaw's exposed metadata before supplying explicit WB. It confirms
the injected values are non-finite.

For single-illuminant mismatch+NaN and explicit WB, both hosts report:

| Implementation | interpolate_ok | finite_cc | Kelvin |
| --- | --- | --- | --- |
| Original baseline | 0 | 0 | 0 |
| Earlier ungated candidate | 1 | 0 | 6500 |
| Final candidate | 0 | 0 | 0 |

Sixteen near-unit decode rows had diagnostic-matrix differences in the
ungated candidate; their float pixel outputs happened to remain equal.
Eight more rows differ through the isolated NaN/Inf explicit-WB gate.
The final candidate restores all of those results. These are synthetic
compatibility regressions, with no attribution to any real-camera grayness.

Both clouds independently compile `raw_decoder.cpp` and the gate probe
against the candidate include directory, reuse the existing metadata-patched
LibRaw archive, and link the unchanged image-IO archive. Flags are C++20,
O2, ffp-contract=off, OpenMP/pthread, SPEKTRALAB_RAW_THREADS=2. AOIDC uses
the existing patched upstream headers at 1..41 (this RAW code is unchanged
by 42); IBX uses the independent 1..42 source tree. Existing baseline and
earlier ungated probe binaries are retained for negative controls.

Reproduce with each pair of independently compiled decode/gate probes:

```sh
python3 dng_asn_signature_audit.py OLD --probe OLD_PROBE --gate-probe OLD_GATE
python3 dng_asn_signature_audit.py NEW --probe NEW_PROBE --gate-probe NEW_GATE \
  --require-pass --compare-baseline OLD
```

The earlier ungated candidate must fail the final boundary comparison.
Compile `tools/native/dng_asn_calibration_gate.cpp` with the same candidate
include precedence and LibRaw archive as the decode probe. Complete JSON
records and test-source hashes appear under each cloud directory; the test
sources live in `tools/native/`. This filtered evidence omits duplicate
sources, patches, binaries and pixel files. Packaging, Android execution
and release are outside this work.

Primary SDK reference remains AOSP DNG SDK revision
`de700ad461e35af50b28b861943a0b0753b10929`, `source/dng_color_spec.cpp`:
lines 134-162 select CC only for matching signatures, lines 179/215 compose
AB*CC*CM, and lines 589-595 define the FM transform. Decoded source SHA256:
`6fb9ab59dcbdae9cb27a893c67810416ba7b2651a537673caa7d728d16d7ec0a`.
The limited near-unit compatibility exception above is retained deliberately.
