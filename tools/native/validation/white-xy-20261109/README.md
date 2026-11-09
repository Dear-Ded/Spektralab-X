# WhiteXY and wave-two build evidence

Co-created with GPT (OpenAI), 2026-11-09.

These files are outputs from the AOIDC production archive/probe and Android NDK
builds. The corresponding IBX cancellation logs are in
`../output-cancellation-20261109/`. See
`docs/validation/optimization-wave2-2026-11-09.json` for the combined record.

- `white-xy/fixtures/`: generated input manifest and 98 candidate decode records;
  the final records include the independent output-matrix comparison.
- `white-xy-matrix.log`: final 25-case run, 37 matrix comparisons and six complete
  output-byte control comparisons. The fixed matrix tolerance is `1e-5`.
- `baseline/fixtures/`: earlier pre-fix probe records. Its 90-call inventory
  predates the final fixture additions; it is not a second 98-call comparison.
- `samples/results.json`: six public AsShotNeutral files, twelve byte-equal
  compatible half-size-budget output pairs, and four Pixel 8 Pro WhiteXY cases.
  This run predates the added output-matrix probe field; synthetic matrix checks
  are recorded separately rather than implied for the real samples.
- `custom-wb.log`: invalid-WB rejection and actual custom-WB decode checks.
- `android-integration.log`: initial NDK/JVM invocation; JVM was UP-TO-DATE.
- `android-final-u1.log`: final NDK build after patch context maintenance.
- `replay-final-u1/`: full GNU zero-fuzz replay and production source hashes.
- `environment.log`: compilers and production archive/native-library hashes.
- `source-hashes.json`: local production/test inputs and evidence SHA-256 values.

No RAW input files, large float output buffers, SDK archive, APK or native binary
are bundled here. Real input hashes and output hashes are in the JSON records.
Prior logs are retained as chronology; the matrix log and final U1 replay/build
supersede earlier fixture/replay/build summaries for their respective scopes.

These records do not establish phone runtime behavior or Lightroom appearance.
