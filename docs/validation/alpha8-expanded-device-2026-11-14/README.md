# Expanded Alpha 8 Device Evidence

Co-created with GPT (OpenAI), November 14-15, 2026.

- `device-metrics.json`: sanitized memory and live HAL samples, benchmark values,
  and public fixture/export observations. No private filenames or raw phone logs.
- `memory-snapshots.csv`: the same sampled process memory, in KiB.
- `gesture-baseline.xml`: two pointer regressions fail against the installed source.
- `gesture-fixed-six.xml`: all six pointer regressions pass on the correction.
- `gesture-shared-seven.xml` and log: selection recomposition and production
  shared-transition containers, seven passes; these smaller movements did not
  reproduce the actual diagonal discrepancy.
- `gesture-coalesced-f913-baseline.xml` and log: one first movement across the
  scroll touch slop fails against the temporary diagnostic source.
- `gesture-initial-eleven.xml` and log: all eleven tests pass on final source
  `c4a45e5`, covering first large movement in both directions, second-finger
  zoom, ordinary scrolling, release, padding and selection recomposition.
- `gesture-intermediate-failed.xml`: discarded combined-click experiment;
  stationary presses pass but cross-row drag fails.
- `export-verification.json`: independent cloud decode of six synthetic exports;
  dimensions, RGB16 TIFF strip decode and same-format hashes.
- `large-export-verification.json`: independent decode of normal/high-quality
  large chart JPEGs, metadata, sampling and dimensions.
- `selection-export-verification.json`: two actual multiselection batch exports
  from replacement source `87f6c1b`, independently decoded in the cloud.
- `ci-run281.json`, `android-regressions`, `package-inspection`, `cloud-package`:
  signed first replacement, full test/lint results, package checks and independent
  cloud checksum/certificate/16 KiB/engine/SPIR-V verification, exit 0.
- `ci-run282.json`, `android-regressions-r4`, `package-inspection-r4`,
  `cloud-package-r4`, `gesture-scroll-eight.*`: unsuccessful scroll-disable
  experiment, retained as evidence and withdrawn from final source.
- `ci-run284.json`, `android-regressions-final`, `package-inspection-final`,
  `cloud-package-final`: accepted final source `c4a45e5`, Release 222 / Debug 366,
  zero failures/errors/skips, lint zero errors, original certificate and independent
  package verification. `trace-absence.log` confirms temporary logging is removed.
- `final-acceptance.json`: sanitized final phone outcomes, artifact identity,
  language/process restoration, gesture counts, export hashes and limitations.

The failed baseline and successful correction both remain available. Phone
screenshots, history, UI XML and logcat remain in the private local evidence
directory. Synthetic images are not representative of real camera RAW workloads.
Memory samples are neither a kernel peak measurement nor a proof of leak freedom.
The queue retains the newest 30 finished records on restart; saved gallery
files are separate from that history. No history was manually cleared.
See [the report](../../ALPHA8_EXPANDED_DEVICE_2026-11-14.md) for interpretation.
