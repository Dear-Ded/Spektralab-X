# Security review: Alpha 8

Co-created with GPT (OpenAI), 2026-11-15. Scope: Android entry/import/update,
local persistence, JNI ownership/text, native patch application and release
integrity. Severity counts refer to confirmed new findings: Critical 0,
High 0, Medium 1, Low 0. This is not a penetration test or a proof of safety.

## Confirmed finding

### Medium: skipped ZIP entries bypass inflated-byte limits (fixed)

- File: `app/src/main/java/com/dearded/spektralab/SpkLook.kt`, `SpkPackage.read`.
- CWE-409 / OWASP A04, resource exhaustion from untrusted imported content.
- Known retained entries were individually bounded, but `ZipInputStream.nextEntry`
  drains an unread previous entry. Unknown names, duplicate names and directory
  payloads could therefore consume unbounded decompression work relative to the
  intended expanded-package ceiling. The existing 16 MiB compressed-file and
  16-entry limits still applied; this was a CPU/work boundary, not ZIP traversal
  or arbitrary code execution.
- Recommendation implemented: explicitly drain every entry through one bounded
  reader. All expanded bytes share the 17 MiB sum of existing look/LUT/thumbnail
  budgets; retained entries also preserve their individual limits. Skipped bytes
  are not retained in memory. No entry-controlled filesystem extraction exists.
- Cloud baseline: five tests, four expected-rejection tests fail on the old code;
  the small unknown-entry compatibility test passes. Baseline XML is retained.
- Final acceptance and CI results are linked from the release report.

## Boundary review

The exported MainActivity routes photo/style content through bounded style,
LUT and XMP readers; XMP declarations containing DOCTYPE/ENTITY are refused.
The share FileProvider and update receiver are not exported, backup is disabled,
and photo processing remains local. The planned RemoteBackend is disabled.
The updater uses normal TLS and repository releases, compares SHA-256, then
rechecks package/version/non-debuggable flag/current signing certificate before
installation. No trust-all TLS or script bridge was identified in app sources.
Release credentials remain in the existing GitHub Secrets; source pattern
checks print only status/locations and do not expose matched values.

All 112 resolved shipped Maven components returned no registered OSV advisory
at 2026-11-14T17:59:08Z (2026-11-15 local). This point-in-time service query does
not scan native dependencies and does not certify the dependencies bug-free.
Native analyzer findings, source identities and residual triage are recorded
separately in the release report. Fresh CI applies LibRaw patches with zero fuzz;
the Android CMake path now enforces the same zero-fuzz requirement.

Residual coverage: one device's synthetic inputs, no exhaustive real-camera
corpus or Adreno numerical parity, no long-duration battery test. New final
changes are cloud-tested while the phone is disconnected. Failed/unsupported
checks remain evidence and are never relabelled as successful validation.
