# Alpha 7 published release evidence

Co-created with GPT (OpenAI), 2026-11-14. APK bytes are distributed through
GitHub Releases, not committed here.

- `release.json` and `SHA256SUMS`: public release identity and asset digest.
- `actions-jobs.json`: successful production run 43541103433 at source
  `5b6090c444c9660d0edf62842cbaa51cd3bc345b`.
- `screenshots-jobs.json`: successful screenshot run 43541103393.
- `attestation-verification.json`: verified APK provenance with exact source
  digest, main ref, signer workflow, repository and hosted-runner constraints.
- `package-inspection/`: actual CI ZIP/DEX/ELF/JNI/SPIR-V/assets and signature
  checks. All assets are compared against that build's staged inputs.
- `android-regressions/`: final 222 Release and 341 Debug test XMLs and lint.
- `raw-storage/` and `fir-rows/`: sanitizer and real Vulkan numeric/lifetime
  regression results from the production run.
- `cloud-verification/`: independent AOIDC verification of the public release
  download, including apksigner/aapt/ZIP/ELF/stamp and 56 spirv-val checks.
- `VERIFIED.txt`: concise verification record and phone-testing limitation.

These are controlled cloud and package checks. The unknown phone installation
warning remains unverified; there is no claim of phone acceptance or warning
clearance. Earlier failed attempts remain in the separate audit evidence.
