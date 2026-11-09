# Custom RAW SDR development v1 validation

Co-created with GPT (OpenAI), 2026-11-09. Synthetic logs contain no user images or
scene text. Neutral remains the default, including new imports. This optional
`standard_v1` is a custom SDR display intent, not complete DCP, Adobe SDK, Lightroom
or camera JPEG matching. Its AE switch only has effect when standard is selected.

## Algorithm and reference boundary

The source remains scene-linear ProPhoto. Statistics scans the complete source
once: finite RGB and Y > 2^-24, Y=.2880402R+.7118741G+.0000857B, 4096 uniform log2
bins [-24,+24] with endpoint clamping, 1%-99% count-trimmed bin-center log mean.
The frozen result is reused across preview, strips, export and settings changes.
Auto EV targets .1845 and is limited to +/-4 EV. User RAW EV is separately bounded
to +/-8 EV and applied before tone. Selected-profile baseline/offset are applied
once; auto normalization accounts for their presence. This AE is a versioned
product policy, not an Adobe algorithm.

Valid embedded tone points take precedence and are evaluated with independently
implemented shape-preserving cubic Hermite interpolation (PCHIP). File endpoints
are preserved. If absent, the zero-skew log-logistic fallback is
f(x)=x^1.5/(a+x^1.5), a=.1845^1.5*(1/.1845-1). Extrema are mapped through the curve
and the middle channel is interpolated using its original fractional position.
It is not a saturation multiplier. Correct engine ProPhoto toe is used at the
encoded boundary: e<16/512 -> e/16, otherwise e^1.8; inverse x<1/512 ->16x,
otherwise x^(1/1.8). Negative values are clipped only by enabled SDR development;
original caches stay signed/HDR, and neutral is a bitwise no-op. Non-finite pixels
pass unchanged to existing caller handling.

Primary fixed references:

- darktable `0c23f000db413843f385e402d53a796b71d4cba0`,
  `src/iop/sigmoid.c`, mathematical log-logistic curve and gray anchor.
  https://github.com/darktable-org/darktable/blob/0c23f000db413843f385e402d53a796b71d4cba0/src/iop/sigmoid.c
  GPL-3.0-or-later. This helper is an independent implementation of the elementary
  zero-black/zero-skew form; it does not port the entire darktable module or its
  hue-energy/primaries machinery. The application is already GPLv3.
- Adobe SDK fixed reference `de700ad461e35af50b28b861943a0b0753b10929`,
  `source/dng_render.cpp` profile/default selection and `dng_reference.cpp`
  `RefBaselineRGBTone` extrema/intermediate-channel semantics.
  https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/source/
  Custom Adobe SDK license; no SDK implementation, spline solver or ACR3 table is
  imported into this helper. It is a reference, not a product dependency.

ProfileLookTable and HueSatMap are not implemented by this helper. It is not a
complete DCP renderer. `DefaultBlackRender=None` adds no black adjustment. Auto or
absent black rendering emits `black_auto_not_applied`; no SDK ShadowScale/black
behavior is invented. Invalid bounded metadata and unsupported profile selection
are rejected for standard mode. PCHIP is not claimed byte-identical to SDK spline.

## Executed coverage

`ibx-gcc14.log`: standalone helper, GCC14.2 with C++20/O2/Wall/Wextra/Werror and
ffp-contract=off, plus an independently compiled ASan+UBSan executable. Eight
groups pass: exact neutral/non-finite bits; baseline/offset once and pre-tone HDR
recovery; literal toe values (.0016 encoded +2EV ->.0064); monotone finite fallback
and gray/hue anchors; profile control/end points/local extrema; full-frame stride
and empty statistics; immutable shared-plan thread/strip equality; metadata errors
and scan cancellation. The initial pure-gamma assumption was independently caught
and corrected before final validation; literal toe fixtures prevent a self-fulfilling
encoder oracle.

`aoidc-output.log` and `ibx-output.log`: actual engine-derived OutputTransform and
Layer2, independently compiled on GCC11 and GCC14. Matching `*-output-source.log`
files record compiler versions, every compiled adapter/helper/test/runner input,
generated upstream output math and linked engine/core/volk archive SHA256. The
eleven source/runner files and generated math match across both hosts; host-built
static archive hashes differ as expected. Each passes 96 cases:
2 target spaces x 2 transforms x 4 geometries x 3 plans x 2 Layer2 configurations.
Each case checks both RGBA8 and RGBA16 whole versus out-of-order rows of 1/3/32/65,
and RGBA8 borrowed strips at those sizes, including full-frame spatial masks.
The plan is also applied manually once to a source copy before the old no-plan
packing function: this verifies ordering before Layer2 and rejects double tone.
Neutral reproduces the no-plan result. Input source stays unchanged. Negative RAW
EV restores overrange highlights through the actual output transform. Active LUT
cancellation crosses the 500k multiworker threshold for both bit depths; all joined
workers return, and a fresh request reproduces uncancelled bytes. Pre-cancelled row
packing preserves scratch storage. The static engine/dependency libraries are not
claimed sanitizer-instrumented by the standalone helper run.

Reproduce the helper with `tools/native/run_raw_development.sh` and BUILD_DIR.
Reproduce integration with `run_raw_development_output.sh` and UP, B, MATH_DIR,
BUILD_DIR pointing to the matching built upstream/resource/math snapshots.

## Limits

These logs establish fixed-plan row/full consistency at the same image geometry,
not equality between different preview resolutions or resize/tone orders. Existing
preview matrix clipping and export CAM16 gamut compression can legitimately differ;
this test covers both separately and does not claim preview/export byte equality.
The before image remains neutral. Standard mode's complete-source metering has an
explicit decode/scan memory and latency cost; these tests are not a memory budget
or phone performance result. RAW source decode/metadata integration, JNI/Kotlin
persistence, engine rebuild layout, full Android/NDK checks and actual-device visual
acceptance are separate root integration gates. Private actual-file prototypes are
not committed here and camera originals are not Lightroom exports.
