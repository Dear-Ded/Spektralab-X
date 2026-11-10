# Film tone and stock selection corrections

Co-created with GPT (OpenAI), 2026-11-10. Source changes only.

## Bound film tone weights after exposure

The first tone correction in `5be4b79` bounded shadow/highlight weights only
for no-film float output. Integer film codes can also leave the SDR domain
after valid exposure or contrast adjustments. Applying +3 exposure and -100
highlights could then reverse the gray ramp and produce negative bright pixels,
which become black at output packing.

Global and local adjustment weights are now bounded for both paths. This bounds
the weighting coordinate, not the pixel values, so highlight headroom remains
available to later controls. Zero-tone output and the old exposure arithmetic
remain unchanged. Existing film edits with previously out-of-domain tone
weights intentionally change; in-domain behavior remains the same.

The film exposure control still follows the upstream pseudo-linear convention.
It is not redefined as a new physical exposure model by this repair. The
no-film float path retains its previously corrected piecewise ProPhoto exposure.
Default RAW development remains neutral and physical film input stays linear.

## Apply the selected paper

New-photo fixed stock selection previously inherited the no-paper state from
the initial neutral edit. Selecting a negative and a fixed paper could still
render its digital intermediate; a positive could still render a direct scan.
The paper name alone was insufficient to activate printing.

A fixed pair now selects a compatible paper and disables direct scan and the
digital intermediate, just as manual paper selection does. If no compatible
paper can be resolved, the film-selection fallback remains in effect. In
particular, positive films with no target print must keep their safe direct
scan when inheriting an incompatible monochrome paper.

Choosing a film by itself retains the explicit no-paper intent. Neither the
neutral new-photo default nor ordinary film selection is silently redirected
to printing.

## Remember print versus direct scan

The remembered stock settings stored film, paper, no-film and digital
intermediate, but omitted direct scan. Changing only direct scan also did not
trigger a settings write. After restart, a positive film's print selection
could therefore become a direct scan.

Direct scan now participates in both the change check and persisted settings.
Restoring a remembered combination uses the complete saved intent. Existing
settings without this field retain their prior inferred behavior; a choice
that was never stored cannot be reconstructed retrospectively.

## Verification

[Synthetic evidence](../tools/native/validation/film-tone-stocks-20261110/)
includes the same property probe failing against `5be4b79`, passing normal
and sanitizer builds, full parsed-mask checks, output-row comparisons and
Android compilation. Nine JVM tests cover new neutral inputs, fixed negative
and positive printing, real positive `targetPrint=null` fallback, removed
stocks, ordinary no-paper selection, and restored scan/print intent.

No version, release, tag or APK assembly is part of this change. These source
and host tests do not establish physical Android driver or screen behavior.
No user image, original filename or per-image measurement is included.
