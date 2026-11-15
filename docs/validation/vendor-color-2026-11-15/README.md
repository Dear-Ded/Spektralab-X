# Vendor profile research evidence

Recorded 2026-11-15. All executable checks ran on existing cloud hosts. The
canonical conclusions and limits are in `../../VENDOR_COLOR_RESEARCH_2026-11-15.md`.

## AOIDC

- `final/summary.json`: final 12 structural checks, NP3 comparisons, complete
  161-file DCP/ExifTool comparisons, lens inventory counts and tested sources.
- `final/dcp-inventory.json`: offsets, payload hashes and compact numeric
  previews. Comparison used all 20,662,584 numeric values, not these previews.
- `final/np3-comparison.json`: file fingerprints, sample types and unknown-byte
  accounting for 13 published and 96 reference-generated cases.
- `final/canon-inventory.json`: envelope inspection only. The deeper independent
  TLV observations are in IBX's separate report, not counted as RGB rendering.
- `final/lens-inventory.json` and `lens-corpus-digest.json`: measured entry counts
  and exact per-file identity comparison with IBX.
- `corpus-files.json`: 248 pinned Git blobs and eight official Canon downloads,
  URL/commit/size/SHA-256. Original external profile payloads are not included.
- `aoidc-c-reference.json`: independent compiled DNG interpolation block,
  40,960 samples across synthetic and selected actual profile tables.
- `np3-original-tests.log`: original MIT project's 28 tests, all passed.
- `environment.json` and `node-runtime.json`: cloud/runtime versions, verified
  official Node tarball SHA-256, and explicit scope exclusions.
- `initial-sources.json`, `followup-sources.json`, `format-sources.json`,
  `repo-pins.json`: primary/reference provenance and unavailable-source errors.
- `acquisition-errors-resolved.json`: first fetch's errors retained alongside
  the final 256 requested / zero failed result. Early partial inventories are
  not the final coverage and are deliberately not used as success evidence.

## IBX

- `ibx-summary.json` and `ibx-structural-tests.log`: final same six source file
  hashes, 12 structural tests, analytic comparison and lens corpus digest.
- `ibx-lens-inventory.json`: independently downloaded Lensfun inventory.
- `ibx-c-reference.json`: GCC 14.2 build of the same fixed DNG reference block,
  same 40,960 sample experiment as AOIDC's GCC 11.4 build.
- `ibx-canon-tlv.json`: eight official PF2 inputs, 109 bounded TLVs, three
  present/valid checksums, external reference identity and block hashes. No
  decoded curve/LUT payloads or external codec implementation are distributed.

Both hosts' source hashes were checked against the six local Python/JavaScript
files. The shared Lensfun XML corpus digest is
`505e733d8eff9d1e0e0b2b93767213cc02b977948c6ea0c518a6801e509cf2d9`.

File decoding, interpolation and independent implementation agreement are
different from camera JPEG parity. No private image, official renderer image
comparison, new device session, APK build or app-runtime integration is claimed.
