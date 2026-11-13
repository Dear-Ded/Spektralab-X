# External colour look validation, 2026-11-13

Co-created with GPT (OpenAI). Public synthetic tables only. All compilation and
executable tests ran on AOIDC Linux. Windows handled sources, archives and Git.
See [research, implementation, tradeoffs and limits](../../../../docs/EXPERT_COLOR_RESEARCH_2026-11-13.md).

- `aoidc/jvm-summary.json` and `jvm/`: final 23 classes, 179 tests, zero failures,
  errors or skips. `gradle-final.exit=0` covers these tests and the x86_64 Debug APK.
- `aoidc/evidence/quality.csv`: 81,920 synthetic colours, five source sizes,
  four groups. Working encoded ProPhoto MAE/max and mathematical clipped sRGB8
  code differences, not Delta E or natural-photo/device acceptance.
- `aoidc/evidence/bake-cost.csv`: JDK21 single-bake cost, one warmup then best
  of three, plus single float-table payload sizes. Not a phone benchmark.
- `aoidc/native-*.log`: GCC11/Clang14 plain and ASan/UBSan, each 16,384 actual
  Kotlin/native outputs, exact float and 16-bit-code parity. Leak checks enabled.
  Only the production LUT header/interpolator is exercised, not full JNI/GPU.
- `aoidc/native-fixture.sha256` and `native-fixture-parity.log`: the fixture used
  for all four native runs also matches the final Kotlin test output. The large
  generated binary fixture is deliberately outside Git.
- `aoidc/before/`: alpha-6 production sources fail both new numerical tests.
  A nonzero exit is required for this negative control, not a final regression.
- `aoidc/failed-stress/`: the initial alternating-129-grid stress used an
  excessively narrow 2e-6 tolerance. The final limit is one 16-bit working-space
  code because Float source coordinates are amplified by the narrow bands.
  Ordinary vertices retain 2e-6; native parity remains exact in all measured cases.
- `aoidc/gradle.log`: earlier six-test iteration passed. It is historical;
  use `gradle-final.log`, final XML and the summary for the final seven-test class.
- `aoidc/source.sha256`, `source-verified-final.log` and
  `gradle-source-verified.log`: ten exact inputs verified in both cloud trees.
- `aoidc/toolchain.log`, `apk.sha256` and `run-final.sh`: tools, test APK identity
  and the before/final runner. No APK was published or installed on a device.

New `SPK2` caches round-trip exactly, while old `SPKL` pixel tables stay frozen.
Native ProPhoto source tables retain their original samples. Original source
tables and colour-space tags remain available for style sharing.

The downloaded archive has 49 unique paths, 15,850 bytes, SHA-256
`c70ca13c00245563258701b58007e28d7901ec2a7e1778bd62c533496ed88ddc`.
It was hash-checked and checked for duplicates, traversal and links before
extraction. The archive and generated large LUT stay in the local/cloud research
directories. No private images, derivatives, handoffs or credentials are included.
