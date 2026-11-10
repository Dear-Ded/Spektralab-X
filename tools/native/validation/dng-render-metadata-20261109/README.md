# Bounded DNG Rendering Metadata

Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.

Engine patch 0035 and LibRaw patch 0022 retain rendering intent without
changing the decoded scene-linear RGB. This is metadata plumbing for an
explicit developed view; it does not itself fix a source-linear image's look.

Both GCC 11 (AOIDC production image-I/O build) and GCC 14 (IBX independent
LibRaw/image-I/O-only build) pass 26 synthetic DNG cases. AOIDC also links a
probe against the previous archives and compares every synthetic decoded
pixel hash. Every hash is unchanged. Synthetic result JSON and source hashes
are retained here. Both clouds' six changed production source hashes match.
Private real-file parity evidence is kept outside the repository.

The tests exercise missing tags, valid signed exposure/offsets, Auto/None
black rendering, 256-point capacity, over-capacity and huge counts, odd and
short curves, wrong types, NaN/Inf/out-of-range/non-increasing coordinates,
zero rational denominators, unvisited extra-profile offsets, selected RAW
SubIFD fallback/override, and mixed or invalid selected-profile overrides.
Invalid explicit values remain distinguishable from missing defaults.

The parser stores at most 256 FLOAT x/y pairs (2 KiB) in each of LibRaw's
existing bounded IFD slots. It allocates no curve-dependent heap memory,
walks no additional TIFF directories and never follows ExtraCameraProfiles
offsets. Profile tags use selected RAW IFD then IFD0 selection. Their source
must match the selected main profile's CM/FM source; mixed sources are marked
unsupported rather than silently combined. Extra profiles are marked
unsupported without attempting selection. Existing neutral decoding remains
available even when developed rendering metadata is unsupported or invalid.

The curve check retains SDK-compatible bounded values: finite coordinates in
[0,1], at least two points, strictly increasing x. It does not enforce exact
(0,0)/(1,1) endpoints or claim complete DNG SDK conformance. In particular,
an existing camera curve with a final y slightly below 1 is preserved, not
silently repaired. The separate developed renderer must document its
interpolation and fallback policy. Curve budget overflow is explicit invalid
metadata, not an automatic standard-curve substitution.

Primary references were rechecked against Adobe's fixed SDK at
`de700ad461e35af50b28b861943a0b0753b10929`:
`source/dng_tag_codes.h` (50933,50940,51109,51110) and
`source/dng_shared.cpp:1165,1190,1233,3017` (SRATIONAL exposure offset, LONG
black-render enum, FLOAT curve pairs and extra profiles). SDK source is from
`android.googlesource.com/platform/external/dng_sdk`. The existing fixed DNG
1.7.1 specification record has SHA-256
`abdecfd8e104e8b86cc054d3a40b677bb2ebe87131bfde080a1df3ee16eb2f8d`.
Web retrieval was attempted again; local/cloud fixed primary source was used
when the search tool returned no content. No Adobe tone table was copied.

Reproduce AOIDC with `tools/native/run_dng_render_metadata.sh`, existing
`UP`, `B`, `BUILD_DIR`, and optional `--baseline-probe`. The isolated IBX
build uses `tools/native/dng_render_metadata_build/CMakeLists.txt`, with
`RAW_UPSTREAM`, `RAW_REPO` and `SPEKTRALAB_LIBRAW_SOURCE` explicitly set,
then runs the same Python fixtures. The official pinned LibRaw archive was
rebuilt with the existing overlay series. Its CRLF `crx.cpp` was normalized
to LF before the pre-existing CRX patch; no algorithm was changed for that
line-ending repair. New patches replay with GNU patch `--fuzz=0` and compare
byte for byte to the built source.

All consumers must rebuild after the internal LibRaw and RawMetadata layout
changes. Neither public C API nor Android serialization is modified by these
two patches. Physical-film auto-exposure is a separate engine contract:
current Edit defaults already request balanced auto-exposure, while the
neutral no-film view deliberately disables it. Profile tone must not be
silently inserted into that existing scene-linear film input.
