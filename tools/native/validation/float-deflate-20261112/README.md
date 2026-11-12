# Float Deflate Evidence, 2026-11-12

Co-created with GPT (OpenAI). Baseline is `b7ec00e93972155e6f902bd96421ff0f20c0b03b`.
All repository evidence uses public code and synthetic pixels. Private photo
inspection is stored outside the repository and is not part of this matrix.

## Replay

With a verified patched upstream and zlib-enabled decoder/image I/O build:

```bash
mkdir -p /new/baseline
git show b7ec00e:app/src/main/cpp/float_dng_linear.hpp > /new/baseline/float_dng_linear.hpp
UP=/path/to/patched/upstream B=/path/to/decoder/build \
  BASELINE_INCLUDE=/new/baseline BUILD_DIR=/new/results MEMORY=1 \
  bash tools/native/run_float_dng_deflate.sh
UP=/path/to/patched/upstream B=/path/to/decoder/build \
  BASELINE_INCLUDE=/new/baseline BUILD_DIR=/new/sanitized \
  SANITIZE=1 ASAN_OPTIONS=detect_leaks=0 bash tools/native/run_float_dng_deflate.sh
```

Use a new baseline/results directory. For a fresh decoder-only build, replay
`patches/libraw/*.patch` in sorted order on a copy of the pinned pristine LibRaw
0.22.2 source, normalizing `src/decoders/crx.cpp` to LF first as the Android
CMake path does. Then configure:

```bash
cmake -S tools/native/float_dng_deflate_host -B /new/host \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-ffp-contract=off \
  -DSPK_UPSTREAM=/path/to/patched/upstream \
  -DSPEKTRALAB_LIBRAW_SOURCE=/new/patched-libraw -DSPEKTRALAB_LIBRAW_OPENMP=ON
cmake --build /new/host --parallel 4
```

This reuses the fixed upstream CMake decoder definition and does not fetch
dependencies or produce an APK. Use that build as `B` in the runner. The frozen
helper and candidate always link the same archive within each comparison.

## Evidence Layout

`aoidc/` and `ibx/` retain environment and source/archive fingerprints,
condensed validation summaries, ordinary RSS records and runner logs. AOIDC
also retains sanitizer and actual Android JNI compilation diagnostics; IBX
retains fresh decoder patch replay/configure/build logs and Python optimized
checks. Full generated fixtures and raw JSON remain in dedicated cloud
directories, not in Git.

| Host | Root | Upstream | Decoder build |
| --- | --- | --- | --- |
| AOIDC | `/data/work/gpt-float-deflate-20261112` | `/data/work/gpt-dng-dynamic-20261111/upstream` | `/data/work/gpt-adobe-20261111/host` |
| IBX | `/data/home/gpt-float-deflate-20261112` | `/data/home/gpt-lut-gate-20261111/upstream` | Fresh `host/` under this evidence root |

Memory runs use `final/fixtures/memory.json`; those processes run after fixture
generation exits. The sanitizer memory record is separate. Code is identified
by baseline Git SHA plus exact candidate/source hashes. Existing decoder
archives, zlib and drivers are not automatically instrumented by `SANITIZE=1`.
No software Vulkan run, ARM compilation or host comparison represents a phone
installation. See [the implementation report](../../../../docs/ENGINE_FLOAT_DEFLATE_2026-11-12.md).
