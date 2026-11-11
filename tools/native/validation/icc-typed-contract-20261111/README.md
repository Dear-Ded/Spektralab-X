# Independent typed ICC evidence

Co-created with GPT (OpenAI, AI assistant), 2026-11-11.

These two synthetic-only results use the same real upstream CCTF samples and
the same production Kotlin-generated ROMM ICC. `aoidc-lcms-results.json` uses
Little CMS runtime 2.12; `windows-lcms-results.json` uses runtime 2.18. The small
C++ CCTF executable was compiled on AOIDC from the complete 1..48 patched tree.
The Windows run reads its CSV rather than compiling Windows engine code.

Both runs pass 4,118 in-domain samples per CCTF, 12,291 ROMM channel-curve
samples, and 32 actual CMM RGB-to-XYZ conversions. Hashes in the JSON bind the
CSV and ICC bytes. The production generator and output source hashes and
support limits are recorded in
[`docs/ICC_TYPED_CONTRACT_2026-11-11.md`](../../../../docs/ICC_TYPED_CONTRACT_2026-11-11.md).

Reproduce on a host with a C++20 compiler, Python 3, Little CMS 2 shared library,
the pinned patched upstream source, and the project's JVM test prerequisites:

```bash
BUILD_DIR=/tmp/icc-production bash tools/jvm/run_icc_profile.sh
UP=/path/to/patched/upstream BUILD_DIR=/tmp/icc-cmm \
  PROPHOTO_ICC=/tmp/icc-production/prophoto.icc \
  bash tools/native/run_icc_typed_contract.sh
```

Set `LCMS_LIBRARY` if the shared library is not discoverable by the platform.
This adds no Android runtime dependency. It validates existing SDR contracts;
negative/out-of-range observations do not claim extended-range ICC support.
No private samples, image measurements, APKs or device display results are
included. No production color algorithm was changed for this evidence.
