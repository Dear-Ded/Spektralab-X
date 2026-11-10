# RAW lifecycle helper validation

Co-created with GPT (OpenAI), 2026-11-10. Synthetic evidence only.

The four logs are verbatim successful standalone probe output from AOIDC and
IBX, with exit status zero. No private image, image path, or image metric is
included. `source.sha256` records the three identical files verified on both
clouds and the local workspace. This directory verifies the native helper;
it does not claim Android editor lifecycle or visual acceptance.

AOIDC used GCC 11.4.0 and the repository `run_raw_development.sh` with
`BUILD_DIR` for the normal run and `SANITIZE=1` for the second run. IBX used
GCC 14.2.0 with the same translation units and equivalent flags:

```sh
c++ -std=c++20 -O2 -Wall -Wextra -Werror -ffp-contract=off -pthread \
  -Iapp/src/main/cpp tools/native/raw_development.cpp \
  app/src/main/cpp/raw_development.cpp -o raw_development
./raw_development
```

The sanitizer build additionally used `-O1 -g -fsanitize=address,undefined
-fno-omit-frame-pointer`. Both sanitizer runs completed without diagnostics.

The ninth test group exercises the production preparation entry point:

- Neutral and physical-film requests bypass invalid/null source pixels and
  clear stale output statistics.
- Active standard development scans even with automatic exposure disabled,
  preserving statistics for a later AE toggle.
- Prepared statistics match the existing scanner field for field.
- Invalid source and cancellation preserve the previous output statistics.
- An all-black source returns ready with no available AE samples; readiness
  is not inferred from histogram availability.

The other eight groups preserve the earlier numerical, metadata, complete-frame
statistics, cancellation, and strip/thread parity coverage. No engine archive
was rebuilt and no real-image test was rerun for this helper change.
