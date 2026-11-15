# Manufacturer profile research tools

Co-created with GPT (OpenAI), 2026-11-15. Executable checks run on existing
cloud hosts; local use in this project is limited to editing and Git.

These tools inspect external profile files and exercise a DCP HSV table
component. They do not add NP3, PF2/PF3, DCP, or arbitrary ICC import to the app.
They do not contain, generate, or distribute branded camera looks.

## Reproduce On A Cloud Host

Use Python 3.10+ (stdlib only), ExifTool 12.40+, and a Node runtime with native
TypeScript stripping. The recorded run used officially hash-verified Node
26.13.1, without npm dependencies or installing agent integrations.

```sh
python3 tools/vendor_color/fetch_sources.py /external/profile-cache sources.json
node tools/vendor_color/reference_np3.mjs \
  /external/profile-cache/ssssota/nikon-flexible-color-picture-control \
  /external/np3-reference
node --test \
  /external/profile-cache/ssssota/nikon-flexible-color-picture-control/test/samples.test.ts
python3 tools/vendor_color/validate_profiles.py \
  --corpus /external/profile-cache \
  --np3-expected /external/np3-reference/expected.json \
  --output /external/results
```

`fetch_sources.py` checks pinned Git blob identities, keeps external data
outside the repository and fails if any requested source is unavailable.
Existing cached files are hash-checked. Network failures must be resolved or
reported as missing coverage, never counted as passing tests. For bulk
acquisition a pinned GitHub source archive can populate the same cache before
running the fetcher; the fetcher still verifies individual Git blob hashes.
Canon's eight official downloads are mutable URLs; their acquired SHA-256
identities are recorded rather than implying an immutable release.

```sh
python3 tools/vendor_color/inspect_profiles.py /external/profile.dcp
python3 tools/vendor_color/inspect_profiles.py --full /external/style.NP3
```

All offset values in JSON are decimal byte offsets. DCP entries record type,
count, IFD entry and payload offsets, raw payload hash, and decoded values.
Default output truncates numeric previews at 18 elements; `--full` includes
all numbers. Unknown payload ranges are reported with hashes. These are
read-only inspections: no profile bytes are normalized or reserialized.

NP3 support is restricted to the observed Flexible Color `0310` variant.
Names in the decoded dictionary preserve the reference's `sharpning` and
`midRangeSharpning` spellings for exact comparisons. The high nibble of each
grading hue byte and unexplained framing bytes retain unknown status. Tone
curve mode suppresses five tone sliders, matching the pinned external decoder.
Other NP3 variants require new evidence and are rejected.

`sample_hsv_table` interpolates table parameters, not RGB pixels. Its contract
uses degrees for hue, bounded S/V, saturation-fast table order, cyclic hue,
and trilinear interpolation. Encoding 1 changes the V lookup coordinate via
the sRGB transfer when V has multiple slices. Applying an encoded value
scale, hue shift, saturation scale, RGB conversion, dual-illuminant blending,
white balance, matrices, exposure and tone curves remain the caller's job.
Do not put this table into the app's post-film CUBE slot.

The 8,192-point independent analytic comparison checks interpolation and
indexing, including cross terms, cyclic hue and encoded coordinates. It does
not establish parity with an Adobe, Nikon, Canon, or RawTherapee full renderer.
ExifTool independently checks decoded DCP metadata and complete numeric
arrays, with explicitly requested binary tags and minor-warning suppression
to avoid mistaking a large-array placeholder for decoded numbers.

`reference_dcp_component.py` compiles a hash-pinned interpolation block from
dcamprof's GPL-3.0 DNG reference. It compares the Python sampler with a separate
C implementation on synthetic and selected actual DCP tables. Its harness
provides input HSV values and exposes interpolated parameters before RGB
application or clipping; full camera rendering is outside this experiment.

```sh
python3 tools/vendor_color/reference_dcp_component.py \
  --reference /external/profile-cache/Beep6581/dcamprof/src/dngref.c \
  --output /external/results/c-reference.json \
  '/external/profile-cache/RawTherapee/RawTherapee/rtdata/dcpprofiles/CANON EOS 250D.dcp' \
  '/external/profile-cache/RawTherapee/RawTherapee/rtdata/dcpprofiles/Canon EOS 100D.dcp' \
  '/external/profile-cache/RawTherapee/RawTherapee/rtdata/dcpprofiles/Canon EOS 450D.dcp' \
  '/external/profile-cache/RawTherapee/RawTherapee/rtdata/dcpprofiles/Sony SLT-A55V.dcp'
```

`inspect_canon_reference.py` optionally loads a hash-pinned external analysis
codec, independently traverses its deobfuscated TLVs and checks record bounds,
end markers and checksums where present. That external codec has no declared
license and is not bundled or an application dependency. The report contains
offsets, record hashes and a few identifying/control fields, not decoded LUT
or curve payloads. It establishes container structure, not Canon rendering.
Obtain the external `pfstyle.py` at the report's exact source commit in a
separate research directory, review it before execution, and pass its path:

```sh
python3 tools/vendor_color/inspect_canon_reference.py \
  --reference /external/analysis-only/pfstyle.py \
  --output /external/results/canon-tlv.json \
  /external/profile-cache/canon-official/*.pf2
```

See `THIRD_PARTY_NOTICES.md` and the dated research report for provenance,
licenses, findings and integration gates. Do not infer the rights to embed a
profile from its download availability or its repository's root license.
