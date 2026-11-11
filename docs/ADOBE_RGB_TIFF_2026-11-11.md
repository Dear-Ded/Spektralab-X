# Explicit Adobe RGB (1998) TIFF export

Co-created with GPT (OpenAI, AI assistant), 2026-11-11.

## Result and scope

The existing TIFF menu gains an explicit Adobe RGB (1998) target. The default
remains sRGB; old edits, new RAW neutral/linear display, input interpretation,
versions and JNI signatures are unchanged. ADOBE_RGB is appended to the enum
and persisted by name. Missing or unknown queue values still restore as sRGB.

This is source and cloud validation, with no APK, release, Android runtime
or physical-device acceptance. It does not resolve or certify the reported
Xiaomi appearance difference.

## A mismatch found by the real engine

The initial implementation passed local tests with supplied D50 metadata.
The full engine returned a CAT02-adapted ProPhoto-to-Adobe matrix instead.
That matrix did not describe the Bradford D50 colorants in the generated ICC:
independent TIFF-to-XYZ verification failed with maximum error 0.0107658986.

The final Adobe file intent uses M = inverse(Adobe_D50_PCS) * ROMM_D50_PCS,
matching the published colorants and the existing production ROMM definition:

```
Adobe D50                 ROMM D50
.60974 .20528 .14919       .7976749 .1351917 .0313534
.31111 .62567 .06322       .2880402 .7118741 .0000857
.01947 .06087 .74457       0        0        .8252100
```

Only exporting && target == "Adobe RGB (1998)" selects this matrix. It clips
target linear channels to [0,1] before the exact 256/563 encoding power,
without CAM16. Other file intents retain their original matrix and CAM16
arithmetic. The existing UNORM16 working-space grade boundary remains;
the final TIFF is unsigned SDR16, not signed or HDR storage.

IccProfile.generateAdobeRgb produces a deterministic ICC v4 matrix/TRC
profile: three type-0 parametric curves with exact 563/256 gamma, D50 PCS
colorant columns and Bradford chad. No Android named Adobe profile is used.
Its colorants are already adapted; the CMM does not apply a second adaptation.
The preceding transfer-only review could not establish full matrix agreement.

## Verification

AOIDC rebuilt all 167 host targets from the current 49-patch engine and
17-overlay LibRaw source, including actual Vulkan resources and PIC archives.
The host is CPU llvmpipe; this is not an Adreno performance result.

- Production Kotlin ICC passes 4,100 independent Java CMM cases. Existing ROMM
  ICC bytes remain SHA-256 04e9d00188531524a16ba754615edb6cb92cd2a7ff0025e84f3b81769b584f60.
  Adobe ICC is ff7671a6657533d5f9f462d33718b30b2bcc7a9f2d3e91a91c442596f81969ec.
- Actual engine, Android output adapter, grading and TIFF writer pass float/u16,
  graded/plain and strip heights 1/7/32/65. Four files and 4,420 pixels have
  identical whole-frame/row output. The reader inflates actual TIFF strips,
  undoes the predictor and checks the embedded production ICC bytes.
- Independent double matrix/power equations differ by at most 0.4993161
  UNORM16 code. Little CMS 2.12 maximum pixel XYZ error is 1.1880089e-5,
  below 4e-5. No near-zero error interval is needed for this fixture.
  Gamma-2.2 and original-engine CAT02 controls reject 1,529 and 2,214 pixels.
- Production JNI passes 16 film/no-film, target and grade combinations, covering
  file and descriptor writes, preview preservation and legacy compatibility.
  The actual Kotlin/JVM binding resolves all four target exports.
- Real-engine metadata/CAM16 runs retain identical 848,640 old-target sample
  bytes over 48 combinations. Both old and new SHA-256:
  1afa2e91264fdf6c404ec0b6c6e65ee80f1c0e59877c0d9e20bfd951702a6054.
- NDK27.2 ARM64/API29 rebuilds the changed native library. Full application
  Kotlin compilation and release JVM results are recorded with the evidence.

Native JNI old-target tests intentionally retain their prior opaque sRGB ICC
writer fixture for byte comparisons. Independent Adobe tests use the actual
Adobe profile and published PCS oracle; the JVM test uses production Adobe
and ROMM profiles. Opaque old-target tests do not prove P3 colorimetry.

## Reproduction and evidence

Build the Linux engine with tools/native/full_engine_host, setting SPK_UPSTREAM
and SPEKTRALAB_LIBRAW_SOURCE to matching patched sources. Use
-DCMAKE_CXX_FLAGS=-ffp-contract=off and one -j4 build on the four-core host.

Run tools/jvm/run_icc_profile.sh to emit production ICCs.
Set UP, B, BUILD_DIR and ADOBE_ICC for tools/native/run_adobe_tiff.sh.
For tools/native/run_jni_tiff.sh also set CODEC, MATH_DIR and VULKAN_HEADERS;
ADOBE_ICC adds the fourth native target, and JVM=1 tests actual Kotlin binding.
run_adobe_legacy.sh additionally takes BASELINE_CPP from the preceding Git
commit. The local metadata fixture remains a separate, explicitly weaker test.

Logs, original engine metadata, source hashes and results:
[adobe-tiff-20261111](../tools/native/validation/adobe-tiff-20261111/README.md).
No private photographs, binary dependencies or third-party PDFs are included.

## Primary references

The preceding [ICC research](ICC_TYPED_CONTRACT_2026-11-11.md) records retrieved
primary documents and their SHA-256, including Adobe RGB (1998) Color Image
Encoding 2005-05, ICC.1:2022, Little CMS and fixed AOSP ColorSpace.java.
Adobe sections 4.3.1, 4.3.6.1 and 4.3.7.3 define gamma, clipping and D50
colorants. This implementation derives matrix constants from those equations;
it does not copy an external engine or import a runtime color dependency.
