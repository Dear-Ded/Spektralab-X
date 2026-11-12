# Float LinearRaw Strip Adaptation, 2026-11-12

Co-created with GPT (OpenAI). Continues from released alpha-3 commit
`ed135c8c2bab72084ea529f66ec5e3539819a478`. Source changes only; no version
increment, new APK or release. Home screens and README files are unchanged.

## Confirmed Gap and Implementation

The precision-preserving Android float LinearRaw reader required one strip and
`RowsPerStrip == ImageLength`. Other valid strip layouts were classified as
unsupported and could enter the dispatcher's explicitly labelled integer
compatibility path. This was a format-admission limitation, not evidence that
all Xiaomi RAW files share this layout or that it caused the reported colour.

The reader now validates and reads up to 1024 uncompressed float32 RGB strips:

- Strip offsets, byte counts and RowsPerStrip accept their defined SHORT/LONG
  encodings. The final strip contains only the remaining rows. A single strip
  may specify more rows than the image, including `2^32 - 1`.
- Follow offsets in image order, even when strips are reversed, shuffled,
  separated by gaps or stored before metadata. Seek once at each strip boundary
  and keep one row buffer, rather than unpacking another complete float image.
- Validate exact byte counts, strip count, file bounds, metadata overlap and
  overlap between strips before allocating the output. Maintain the 4096-byte
  per-tag metadata limit and an explicit 1024-strip limit for SHORT arrays too.
- Preserve the open pixel stream, cancellation classification and transactional
  output publication. Failed reads and cancellation leave the caller's old
  result intact. Existing black/white normalization, matrix arithmetic, white
  balance, signed values and high-light headroom are unchanged.

The source layout follows Adobe TIFF 6.0, pages 19, 21, 27 and 39-40:
[specification mirror](https://download.osgeo.org/libtiff/doc/TIFF6.pdf).
In particular, strip offsets are independent file positions, and the final
strip is not padded with unused rows. No external decoder implementation is
copied into the helper.

## Validation

Both hosts compiled the same candidate header and test sources. Each compares
old and new helpers against the same host's LibRaw archive, keeping dependency
changes out of the strip-layout comparison. AOIDC uses the current patched
decoder build; IBX reuses the earlier float-stream decoder archive. This second
host validates the strip reader and calibration controls independently, not a
second full rebuild of every alpha-3 dependency.

| Check | Result |
| --- | --- |
| AOIDC GCC 11 and IBX GCC 14 | 300 byte-identical layout/control cases per host |
| Layout coverage | Eight orientations, default/custom WB, 1/7/16-row bands, exact/oversized single strips, forward/reverse/shuffled placement, SHORT/LONG fields |
| Independent matrix/orientation reference | Maximum historical absolute error `4.76837158203125e-7`, tolerance `2e-6` |
| Cancellation | 124 multi-strip boundaries per host; prior 88-boundary suite also passes |
| Invalid layout/sample rejection | 15 new malformed cases; caller output retained |
| I/O mutation | Eight short-read/path-replacement cases per host |
| Admission | 1024 strips accepted, 1025 rejected; original payload-limit checks unchanged |
| AOIDC ASan/UBSan | Same final matrix passes; linked decoder archive is not instrumented and leak checking is disabled |
| IBX Python `-O` | Explicit-check strip suite still passes |
| Android NDK r27.2, ARM64/API29 | Actual production `spektralab_jni.cpp` compiles to an AArch64 object with the candidate helper |

The suite also checks that integer inputs are not read or mutated by the float
dispatcher, non-finite samples fail explicitly, and distant maxima do not
rescale an unchanged pixel. All fixtures are synthetic; private photographs are
not included.

## Memory and Timing

For a synthetic 4096 x 3072 image, the alpha-3 single strip and new 64-row
reverse-ordered strips produce the same float output hash. Three alternating,
separate-process measurements per host record:

| Host | Alpha-3 single-strip peak RSS | New multi-strip peak RSS |
| --- | --- | --- |
| AOIDC | 153000-153172 KiB | 153056-153140 KiB |
| IBX | 152604-152624 KiB | 152584-152672 KiB |

This preserves the row-streaming residency while admitting more files; it is
not a new 149 MiB reduction relative to alpha-3. Decode times overlap and were
measured during other compiler work, so no performance improvement is claimed.

The first measurement spawned native probes from the large Python fixture
writer and inherited its high initial RSS across fork/exec, reporting 610752
KiB for every run. That measurement is discarded. The committed runner starts
the small measurement process only after the writer exits. It retains the
corrected values rather than presenting the inherited peak as engine usage.

## Remaining Engine Work

This change does not add compressed/tiled float input, float Bayer demosaicing,
multiple IFD/profile selection, arbitrary crop/opcode/calibration support or
complete HDR development. The output still occupies a complete float RGB
plane; total render memory is not bounded by the row buffer. RAW continues to
open with neutral/linear defaults.

Further work remains divided by numerical contract:

1. Expand precise RAW layouts only with an independent normalization/profile
   reference; then compare actual RAW/UltraRAW under colour management.
2. Extend bounded execution beyond the guarded print tail, preserving global
   grain coordinates, IIR recurrence state, statistics and failure publication.
3. Keep LUT interaction and projection-formula changes experimental until their
   worst-case errors are acceptable. The alpha-3 spectral reference stays fixed.

Evidence and reproduction commands are in
[the validation directory](../tools/native/validation/float-strips-20261112/README.md).
