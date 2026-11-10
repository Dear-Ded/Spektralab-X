# Reference illuminant cache regression

Co-created with GPT (OpenAI), 2026-11-10. Synthetic inputs only.

The Hanatos LUT depends on the film reference illuminant through the spectral
window normalization, adaptation surface center, and input compression white
axis. Overlay 0036 includes that illuminant in the existing LUT cache key.
It does not change built-in stock settings or enable the adaptation surface.

`setup_cache_illuminant.cpp` uses a synthetic three-channel film sensitivity
and three synthetic linear RGB samples with the actual LUT builder and baked
public spectral constants. It verifies unchanged-illuminant reuse, exact
cached/fresh D65 LUT and exposure equality after switching from D55, and reuse
of the original entry after switching back to D55.

AOIDC normal and AddressSanitizer/UndefinedBehaviorSanitizer runs passed.
The probe and cache translation unit are instrumented in the sanitizer run;
the existing static core archive is reused and was not rebuilt or instrumented.
The same final probe compiled against the old cache fails with a cached/fresh
maximum LUT difference of 4.42726. Fixed difference is exactly zero.

Reproduce against the tree with overlays applied:

```sh
UP=/path/to/upstream B=/path/to/host-build bash tools/native/run_setup_cache_illuminant.sh
UP=/path/to/upstream B=/path/to/host-build SANITIZE=1 bash tools/native/run_setup_cache_illuminant.sh
```

`source.sha256` names the independently compiled source files; `baseline.cpp`
in that manifest is the pre-0036 cache source in the isolated build, not a
vendored source file here. Reconstruct it by applying overlays 0001..0035 to
the pinned upstream. `baseline.log` is the expected failing run.
No image, RAW file, camera rendering, or private image metric was used.

All 0001..0036 overlays were also replayed from a clean pinned archive with
no rejects. Normal and sanitizer cache probes passed against that clean tree,
and the Android native library built with it. The dependency download cache
was reused; no working source checkout was replaced by the replay.
