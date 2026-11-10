# Film tone and stock-state validation

Co-created with GPT (OpenAI), 2026-11-10. Synthetic public evidence only.

Production changes and compatibility boundaries are documented in
[film and stock-state fixes](../../../../docs/RAW_FILM_STATE_FIXES_2026-11-10.md).

- `baseline-highlights.log`: the final property probe built against application
  source at `5be4b792933549be4f70be4b3bf0c359a33aa846` exits 1, detecting two
  ramp reversals, one negative highlight and six shadow-domain violations.
- `domains-normal.log` and `domains-sanitizer.log`: both integer and float
  highlight properties pass, including global/local equality, nonnegative
  monotonic bright ramps and shadow controls leaving exposed white alone.
  The existing dense signed/toe exposure sweep also passes. The two probes
  and Layer2 translation unit are instrumented by ASan and UBSan.
- `local-tone-sanitized.log`: 524 cases through the actual mask parser and
  full-coverage blending pass, together with recovered-channel checks and
  36,873 ordinary-domain byte comparisons. The probe and Layer2 are instrumented.
- `native.log`: the final native entrypoint, including its domain runner and
  large-image checks, passes. Its upstream core/archive dependencies are reused.
- `output.log`: 162 integer output cases across five strip heights agree in
  RGB16 and complete TIFF bytes, including provider failure recovery.
- `raw-output.log`: 96 development/output cases pass for sRGB/P3, display/file
  intent, 8/16-bit packing and whole/row/borrowed conversion. The exposed
  highlight ramp remains monotonic.
- `android-build-clean.log`: release Kotlin, release JVM tests and ARM64 native
  compilation pass. This is an executed build, not just reused task results;
  no APK assembly task runs. Only trailing log whitespace is normalized.
- `new-photo-stocks-junit.xml`: nine stock-selection tests pass with no
  failures, errors or skips. They exercise the same policy helpers used by
  fixed selection and restart restoration, including wire output flags.

`source.sha256` binds the production, policy, test and entrypoint files to the
local tree. `compiler.txt` identifies the AOIDC compiler. These results do not
claim second-host execution, fully instrumented engine archives, an Android
JNI runtime, or a physical device test. The other cloud host remained under
a host-enforced cooldown during this run.

```sh
bash tools/native/run_layer2_domains.sh
SANITIZE=1 bash tools/native/run_layer2_domains.sh
UP=/path/to/upstream bash tools/native/run.sh --large
UP=/path/to/upstream B=/path/to/host-build \
  MATH_DIR=/path/to/output-math bash tools/native/run_output.sh
```

Personal source data and image-derived measurements are excluded.
