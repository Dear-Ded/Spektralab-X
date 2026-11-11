# DNG ProfileDynamicRange evidence

Co-created with GPT (OpenAI), 2026-11-11. All fixtures are synthetic.

`fixtures.log` and `fixtures/results.json` retain the 45-case parser/decode
comparison. The baseline is a separately compiled pre-0049 engine image-I/O
and pre-0023 LibRaw archive, not the new parser with its metadata hidden.
Every decoded pixel hash matches. Run with:

```sh
python3 tools/native/dng_render_metadata_fixtures.py /tmp/dng-fixtures \
  --probe /path/to/new/dng_render_metadata \
  --baseline-probe /path/to/old/dng_render_metadata
```

The isolated CMake project is `tools/native/dng_render_metadata_build`;
set `RAW_UPSTREAM`, `RAW_REPO`, and `SPEKTRALAB_LIBRAW_SOURCE` explicitly.
The old probe uses `-DSPK_DNG_RENDER_METADATA=0` at compile time because its
header has no new fields. Its actual image-I/O and LibRaw remain old.

`development-before.log` is the expected old failure. `development-after.log`
and `development-sanitized.log` pass the nine production development groups,
with both HDR rejection and the unchanged neutral/SDR paths checked.
Reproduce through `tools/native/run_raw_development.sh`, using `SANITIZE=1`
for the instrumented adapter run.

`engine-replay.log` and `engine-replay-files.txt` record strict application
of 49 patches to pinned a46627d; all 97 affected files compare equal to the
built source. Source hashes bind production, test and patch inputs, with text
CRLF normalized to LF to match Git checkouts on both hosts.

`android-build.log`, configure log, time, ELF headers, exports and SHA record
a fresh NDK27.2 ARM64/API29 native-library build, 186/186 targets. This is
not an APK, Android runtime test or phone acceptance. Build time's maximum
RSS is the reported child resource statistic, not aggregate process-group
or application rendering memory. No private images or metrics are included.
