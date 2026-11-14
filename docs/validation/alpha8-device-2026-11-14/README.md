# Alpha 8 Connected-device Evidence

Co-created with GPT (OpenAI), 2026-11-14.

Accepted source: `b1c61bdde739022fd6d6b60b49d8c57b1822dbdb`.
Accepted APK SHA-256: `a806e456cbcdb16f67449a4bbe7396637d1bf83f2b7b3140a3864cd34c2214a3`.
The preceding SHA-256 `702914c6...30a32` is rejected for actual startup failure.

- `ci-run280.json`: final Actions `43603540709`, all required jobs successful;
  release skipped. Completed 2026-11-14 22:20:44 +08:00.
- `android-regressions`: all final Release 222 and Debug 355 test results,
  zero failures/errors/skips; lint zero errors, 111 warnings, six hints.
- `startup-fix-cloud`: AOIDC 12 focused startup, locale, Settings composition
  and external-open tests; final Gradle exit 0.
- `package-inspection`: final CI package inspection, published certificate,
  exact APK digest, DEX, ARM64 libraries, shaders, assets and alignment.
- `cloud-package`: independent AOIDC checksum, CRC, certificate, package/version,
  ARM64 ELF/ZIP 16 KiB, engine stamp and 56 SPIR-V checks; exit 0.
- `cloud-exports`: only public synthetic exports, independently inspected with
  ExifTool, libheif and complete RGB16 TIFF strip decompression. HEIF IFD0 and
  ExifIFD dimensions agree with its decoded pixels and the phone's index.
- `device-metrics.json`: sanitized actual-phone observations transcribed from
  the locally retained captures; it is not a raw device log.

Raw device UI/screenshots, logs and queue history remain local:
`D:/AGI/GPT/research/spektralab-20261114/i18n/alpha8-device-evidence`.
No raw phone log, history screenshot or personal photo is included here.
Full case results, measurements, provider-permission boundaries and remaining
coverage limits are in [the acceptance report](../../ALPHA8_DEVICE_ACCEPTANCE_2026-11-14.md).
