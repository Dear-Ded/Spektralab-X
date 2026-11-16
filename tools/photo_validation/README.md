# Public real-photo regression

Co-created with GPT (OpenAI). Execute on cloud only; keep generated evidence and
original photographs outside Git. Requires Python 3, Pillow, numpy and LittleCMS
for the optional independent input-colour check. No application dependency added.

`sources.json` defines twelve real photographs. `prepare.py` verifies Commons
licences, retains original metadata and bytes, applies EXIF orientation, converts
embedded ICC profiles to sRGB and explicitly records untagged-sRGB assumptions.
Commons originals are checked against the server's SHA-1. Every original and
normalised input also gets a local SHA-256 in `PUBLIC_IMAGES.json`.

```sh
python3 tools/photo_validation/prepare.py --out "$EVIDENCE/photos" --edge 256
```

Build `tools/native/run_builtin_schemes.sh` with the same pinned upstream,
generated math, resources and host archives used by the application audit. Export
all 87 Kotlin recipes using `SPK_BUILTIN_EVIDENCE_DIR` and
`BuiltinStyleFidelityTest`. Invoke the resulting probe once per normalised P6:

```sh
"$BUILD_DIR/builtin_scheme_probe" "$RESOURCES" "$FIXTURES" \
  "$EVIDENCE/results/portrait_chawla" "$EVIDENCE/photos/inputs/portrait_chawla.ppm"
python3 tools/photo_validation/report.py --photos "$EVIDENCE/photos" \
  --fixtures "$FIXTURES" --results "$EVIDENCE/results" --out "$EVIDENCE/report"
```

Each photograph runs every complete recipe through the production JNI preview
and ordinary session paths, in sRGB and Display P3. Assertions cover export-frame
validation, exact pixel equality at equal dimensions, retained input and parent
parameters, alpha, bitmap locks and released sessions. Output includes RGB8
statistics and eight selected sRGB proofs. Contact sheets are tagged sRGB; P3
code values are never displayed as sRGB. `--allow-partial` is explicitly labelled
and only aggregates successful per-photo `.exit` files.

The probe's optional `SPK_RECIPE_FILTER` isolates comma-separated recipe IDs for
diagnosis. It also writes a neutral source proof. Filtered results are not a full
87-recipe pass and must be stored separately. No filter is used for the complete
matrix. A source larger than 384 pixels uses the actual thumbnail dimensions
for the matching reference, rather than the original bitmap dimensions.

`input_probe.cpp` writes production linear-ProPhoto input samples. Compile it
with the actual `input_transform.cpp` and generated `upstream_input_math.inc`.
`input_oracle.py` compares those floats with LittleCMS and the Kotlin-generated
ProPhoto ICC. It preserves two explicit source-profile conditions:

- `lcms-default`: the independently generated default sRGB profile. Its D50
  colorants differ slightly from the published legacy sRGB/app host contract.
- `published-d50`: the published D50 colorants are supplied to LittleCMS so that
  independent arithmetic is compared under the application's input convention.

Do not discard the first condition or raise a failed budget silently. This
comparison verifies RGB input conversion, not the visual accuracy of a film.

`candidates.py` extends the separate ten-style research generator to all twelve
photos. It preserves the existing .75 interpolation budget, records failed
cases and their RGB8 stimuli, and writes explicitly mathematical previews.
It does not represent native or device execution; those checks run separately
against the generated packages and production interpolator.

Real photographs are regression stimuli, not official film or camera matching
references. Equal preview/main pixels do not prove pleasing exposure, faithful
skin or exact brand colour. Review the contact sheets separately. Clipping
statistics are descriptive; a globally small count can still conceal a poor
face or local highlight. Originals include portraits for internal testing only;
licence availability does not imply endorsement or permission to advertise with
the subjects' likenesses.
