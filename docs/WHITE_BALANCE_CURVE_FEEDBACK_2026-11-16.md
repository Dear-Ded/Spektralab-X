# White balance picker and precision curves

Co-created with GPT (OpenAI), 2026-11-16. Engineering record; not release copy.

## User-visible contract

- Adjust > Colour provides a white balance picker. Tap or drag over a neutral
  patch, inspect the temperature/tint values, then confirm or cancel.
- Confirmation changes only the existing finished-print temperature/tint fields.
  It uses the ordinary update/history/save/export path. RAW decoder Kelvin/tint,
  other adjustments, and file formats retain their current contracts.
- Curves use a 280--380 dp plot, with the existing 24 dp handle gutter. Fine drag
  is the default and maps one unit of finger travel to one quarter unit of
  point travel. Direct drag remains available.
- Selecting or releasing a point preserves its selection for the existing
  precision rulers and numeric entry. Input/output values use 0.1 percent steps.
  Endpoints keep their input coordinate; neighbours keep their separation.

## Sampling and arithmetic

The picker owns a frozen snapshot of the retained display render, bounded to
512 pixels on the long edge. It has the same crop, rotation and film rendering
as that cache, without a UI paper frame. The source is the working ProPhoto
codes immediately before Layer 2 white balance, including RAW standard
development and an enabled original colour-table chain. It excludes subsequent
temperature/tint, tone curves, HSL, masks, output-space conversion and screen
quantisation. The displayed reference is an sRGB view of this sampling stage.
Consequently it may differ from the finished photo while the picker is open.

Each sample averages a 7 x 7 neighbourhood of these working codes; edge
neighbourhoods are clipped to the actual image, without repeated edge pixels.
The average is in the encoded domain because the existing finished-print WB
multiplies those codes. This tool does not replace physical RAW illuminant
estimation or camera-space white balance.

The native gains remain R*(1+.0018*T), G*(1-.0012*Tint), B*(1-.0018*T).
The inverse solves T=(B-R)/(.0018*(R+B)), then solves the green gain against
the corrected red/blue average. Look palette strength is included before the
stored values are calculated. Zero strength refuses a sample. Out-of-range
solutions use the existing +/-100 limits and explicitly report that limit.

Negative/nonfinite samples, near-black channels, and substantially clipped
patches refuse correction. These are documented engineering guards, not claims
of scene understanding. A coloured object cannot become a valid neutral
reference merely because the user clicks it.

Reference preparation and confirmation both check the source identity and
complete accepted edit. A stale sample cannot change a replacement photo or
overwrite intervening edits. Cancellation does not call update().

## Verification

Focused cloud checks: six solver/snapshot/geometry tests, four picker interaction
tests, and thirteen curve interaction tests pass. The latter retain cancellation,
channel-switch, disable/dispose, resize, parent scrolling and endpoint coverage;
new cases verify quarter-speed displacement and selection without an edit.

The actual native JNI integration checks ten combinations across no-film, film,
intermediate, original LUT chains and two display outputs. It verifies that the
picker excludes finish sliders, includes the LUT, keeps a bounded finite
snapshot, and does not change retained pixels or repack results. These checks
pass. Screenshot verification, complete packaging and device acceptance are
recorded separately when complete; focused tests are not device acceptance.

## Meter optimisation retained in this change set

Patch 0061 shares each trimmed logarithm between balanced and central metering,
without another sample buffer or changing addition order, rank definitions,
Gaussian weights or protection bounds. Baseline/candidate output is byte-equal
on 1,460 production/oracle vectors under GCC and Clang, each with normal and
strict contraction policies. ASan/UBSan/LSan passes the same vectors and the
baseline/candidate CSV comparison.

Seven alternating paired measurements per pattern, after equal warmup, show
15.50--20.08 percent lower meter-stage time under GCC and 10.92--14.62 percent
under Clang across six 65,536-sample patterns. This is a measurement of the
meter stage, not complete image processing or phone latency. Exposure values
are unchanged. Snow/sunset exposure intent remains a separate product issue.

## External research and scope boundary

Research agents inspected original papers and mature project sources. Two
relevant reference distinctions are retained, without copying their code:

- RawTherapee distinguishes an ordinary rendered-image Neutral profile from a
  RAW auto-matched-curve default. This is not evidence that a generic automatic
  exposure setting should override existing user edits.
  https://github.com/RawTherapee/RawTherapee/blob/64c2d90a3248c7ad41842d9162368858b61aac6a/rtgui/options.h#L43-L52
- darktable's exposure defaults use manual mode; its automatic percentile
  mode has explicit RAW restrictions and target levels.
  https://github.com/darktable-org/darktable/blob/b407398a1b9d03d552f59d7227bd3eff66045495/src/iop/exposure.c#L347-L372
  https://docs.darktable.org/usermanual/development/en/module-reference/processing-modules/exposure/

Changing a global brightness statistic alone cannot establish scene intent.
This patch therefore does not silently rewrite existing 87-style exposure
recipes or claim to fix the snow/sunset aesthetic issue.
