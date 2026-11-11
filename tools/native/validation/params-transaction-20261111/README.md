# Rejected parameter rebuild transaction

Source-only synthetic verification, co-created with GPT (OpenAI), 2026-11-11.
Project GPL-3.0 section 7 terms apply; see NOTICE.

IBX independently rebuilt the complete native engine, core and Vulkan archives
in a new CMake build directory (Release, C++20, Vulkan required). The source
started from the existing bounded-budget tree, which lacked 0043; final 0043,
0046 and candidate 0047 were then applied with zero fuzz. The 0047 hunks had an
11-line offset caused by the 0046 memory-report addition. No old GPU objects
were linked into the new ABI. The final raw_camera.hpp hash matches final 0043.

The real v1 C ABI test uses a generated 29x19 RGB float image and baked engine
resources. It rejects output-space, print EDR without a calibrated paper table,
input-space, film-stock plus invalid output, and print-stock plus invalid output
builds. Each case verifies unchanged accepted parameters, usable old pipeline and
cached negative, ordinary live/rebuild recovery without a full restore, full
snapshot reapplication, and subsequent live/print/shoot output equality to a
fresh control. These are actual v1 API fields; no V2 revision state is claimed.

The old implementation failed 20 of 70 checks. Candidate 0047 passed all 70.
Twenty successful synthetic render outputs (88,160 bytes) also compared equal
byte for byte across the old and new executables. This equivalence covers the
tested successful configurations; it is not an exhaustive renderer guarantee.

The same full rebuild passed the 0046 transfer-memory probe in both inline-ring
modes, 24 checks each. A 4 MiB upload/read warm-up followed by a 4 KiB read retained
only the small read staging allocation; the probe also checks transferred bytes.

Reproduce with tools/native/run_params_transaction.sh, setting UP to the patched
upstream, B to a complete matching-ABI native build, and BUILD_DIR to an isolated
output directory. Run once against the old build and once against 0046+0047;
compare accepted-output.rgba16 using cmp. The transfer probe uses
tools/native/run_vulkan_transfer_memory.sh with the same build variables.

No Android device or JNI execution is claimed by this native host test. No
private image, filename, measurement, credential or local filesystem path is
included. No generated image or compiled artifact is distributed here.
