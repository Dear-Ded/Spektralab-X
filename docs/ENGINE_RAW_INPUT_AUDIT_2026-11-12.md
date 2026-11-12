# RAW input colour audit, 2026-11-12

Co-created with GPT (OpenAI). This checkpoint adds inspection and comparison
tools around the current production decoder. It does not change RAW arithmetic,
the scene-linear default, physical-film input, app version or release artefacts.
Private camera files, derived images and measurements are not in this report.

## Tools and interpretation

`tools/native/raw_color_audit.cpp` opens the source through CameraLibRaw,
inspects the Android precision plan, then calls `decode_raw_precision` with
camera WB and no display development. It writes `PREFIX.f32` (packed RGB,
little-endian float32, linear ProPhoto D50) and `PREFIX.json` with the actual
route, source/decoded geometry, inset-crop metadata, black/white levels,
applied WB, camera-to-output matrix, profile curve and timing information.
The optional numeric budget is the production half-size budget: zero keeps
full resolution, one forces eligible Bayer sources through the same binning
policy used by small Android previews. LinearRaw is not Bayer-binned.

The separate inspection instance computes calibration metadata; the pixels
always come from the production dispatcher and its own decoder instance.
The tool has no Vulkan dependency. Windows uses wide-character arguments and
LibRaw's wide source path, including non-ASCII paths. The existing decoder-only
CMake project now has an opt-in `SPK_BUILD_RAW_COLOR_AUDIT` target and the
Windows API definitions required by its image-writer translation unit.

Do not interpret `black == 0` alone as a missing black subtraction.
`black_per_channel` includes the common offset; the separate repeated
`black_pattern` must also be considered. `postprocess_maximum` records the
actual LibRaw processing result. An inset crop is recorded, not newly applied.
`camera_to_output` is the observed post-WB matrix, not a copy of ColorMatrix.

`tools/native/raw_color_compare.py` reads those output files without modifying
them. It applies valid JPEG EXIF orientation, converts the JPEG's embedded RGB
ICC profile through Pillow/LittleCMS to 8-bit sRGB, converts the linear RAW
from ProPhoto D50 to linear sRGB D65, and registers the images with SIFT/RANSAC.
It rejects weak or spatially concentrated matches. The three-panel preview is
RAW through the output transform, RAW with an explicitly reported comparison
exposure, and the ICC-managed camera JPEG. Exposure fitting affects only the
comparison, never the decoder or saved source float data.

The JPEG is a vendor-developed image. The reported chromaticity distance is
a diagnostic, not Delta E or an accuracy score. The 8-bit destination can clip
out-of-sRGB-gamut colours. These tools do not certify a complete ICC workflow,
recover clipped sensor data, or establish real-film fidelity.

## Reproduction

Use the fixed upstream pin with all current engine overlays and LibRaw 0.22.2
with all 17 current LibRaw patches. The production inputs inspected here use
the source state after `4847666`. Do not substitute the older fake host cache
or mix an old CameraLibRaw layout with a new consumer.

Build a native host tool, with caller-supplied source directories:

```bash
cmake -S tools/native/float_dng_deflate_host -B /outside-repo/audit-host \
  -DSPK_UPSTREAM="$UP" -DSPEKTRALAB_LIBRAW_SOURCE="$LIBRAW" \
  -DSPK_BUILD_RAW_COLOR_AUDIT=ON -DCMAKE_BUILD_TYPE=Release
cmake --build /outside-repo/audit-host --target raw_color_audit -j4
SPEKTRALAB_RAW_THREADS=4 python3 tools/native/raw_color_audit_regression.py \
  /outside-repo/audit-tests --probe /outside-repo/audit-host/raw_color_audit
```

For Linux-to-Windows MinGW cross compilation, additionally provide the patched
upstream `engine/cmake/toolchain-mingw-w64.cmake`,
`-DSPEKTRALAB_STATIC_RUNTIME=ON`, and point both `ZLIB_LIBRARY_RELEASE` and
`ZLIB_LIBRARY_DEBUG` to the target's static `libz.a`. FindZLIB otherwise picks
`libz.dll.a`; a final `-static` cannot replace an explicit import archive.
The tested compiler was the Ubuntu MinGW **posix** thread variant. The tested
executable imports only KERNEL32, msvcrt and WS2_32. It is an internal diagnostic
executable; it is not an Android package or an app release.

Run a local private audit and comparison with output outside the checkout:

```powershell
& 'C:/spk-tools/raw_color_audit.exe' 'C:/photos/input.dng' 'C:/spk-private/audit'
python tools/native/raw_color_compare.py 'C:/spk-private/audit' `
  'C:/photos/camera.jpg' 'C:/spk-private/comparison'
```

Create the output parent directory first. The comparison needs NumPy, Pillow
with LittleCMS and OpenCV with SIFT. It requires an embedded input RGB profile;
an untagged JPEG needs an explicitly chosen profile before this comparison.

## Verification and a retained limitation

AOIDC built the Windows executable from the current production sources with
MinGW GCC 10 and the native Linux tool with GCC 11. Both ran eight float32
LinearRaw orientation cases against the independent historical float/matrix
oracle, and four integer Bayer/LinearRaw full/budget-one cases. Windows and
Linux produced byte-identical float payloads for all 12 cases.

- Maximum float-oracle absolute error: `1.1920928955078125e-7`.
- Maximum integer compatible-policy absolute error: `7.881786360408594e-6`.
- Source rational float black/white values, repeated integer black metadata,
  Bayer binning and LinearRaw geometry were checked.
- The local synthetic comparison recovered a known homography with maximum
  corner error `0.0189102` pixels and a known exposure with error
  `5.2283e-6` EV. It verified tagged-sRGB identity, EXIF orientation 6, dotted
  output prefixes, unchanged source floats and rejection of an unrelated JPEG.
- IBX independently compiled the production adapter and ran the initial
  rotated integer Bayer full/half smoke tests. It then entered platform
  `machine_cooldown`, with recovery given as November 12, 2026 05:43:15 UTC.
  IBX did **not** run the final 12-case suite. OpenCV was unavailable on AOIDC;
  the image-comparison suite ran locally, not on either cloud.

The first independent integer LinearRaw check failed against full DNG
normalization. This is retained as a finding, not relabelled as an accuracy
pass: with sample 1024, per-channel black 64 and white 4095, the inherited
three-channel integer compatible path subtracts the black but retains common
maximum 4095. The normalization reference uses `(1024-64)/(4095-64)`.
The observed maximum gap is `0.0037299514970185466`. The regression explicitly
records both compatibility agreement and this reference gap; its summary sets
`complete_dng_normalization_claimed=false`. The corresponding Bayer fixture
uses postprocess maximum 4031. No production arithmetic was changed to hide
or compensate for this difference.

The existing default ASN matrix preparation also retains its McCamy CCT
approximation. Agreement with that formula does not prove agreement with the
DNG SDK's Robertson temperature implementation or a full reference render.

Public evidence is under `tools/native/validation/raw-input-audit-20261112`.
It contains only synthetic results, source hashes and toolchain provenance.
Neither a new Android build nor phone execution was needed by these tool-only
changes; host binning checks are not phone display/memory/performance acceptance.

## Next acceptance boundaries

Investigate integer LinearRaw's nonzero black normalization at the actual
LibRaw scaling boundary, retain frozen Bayer/default-output controls, and
choose an explicit compatibility policy before changing the adapter.
Evaluate exact DNG temperature interpolation separately from exposure/look
development. Camera-JPEG appearance alone must not select a matrix or saturation.

Real colour and film claims still need controlled captures and a specified
film/processing/output reference. Lock RAW input, film stock, exposure, processing
and scan/print target independently; then evaluate tone, colour and spatial
effects against those references. Keep Android device acceptance separate from
all host numerical checks.
