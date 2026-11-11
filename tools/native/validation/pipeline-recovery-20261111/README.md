# Pipeline Failure Recovery

Synthetic source-only probes against the production Pipeline translation unit.
The runner compiles one replacement pipeline.cpp object and links the existing
engine archives without changing them. Test-only private access is enabled by
`-fno-access-control`; production code has no fault injection hooks.

The cancellation probe runs the production striped executor and print_output
stage with cancellation after reading a band at row 0 or row 9. It then runs the
real Scene Latitude GPU kernel on a differently sized portrait frame, comparing
every float32 byte with a fresh Pipeline. The original code fails this comparison.

The progress/mask probe exercises cancelled film entry points and rejected print
entry points, including a reused Progress whose done flag was previously true.

The publication probe uses a test GPU forwarding real Vulkan allocations and
transfers. The three DI math dispatches are stubbed as successful to isolate the
Pipeline publication contract. It injects a failure at the final phase flush:
flush 4 for ordinary print, flush 7 for striped print with one full-height band.
It asserts the original caller output handle and dimensions survive, done is
false, and progress/mask state is released. This probe does not validate DI math.
`PUBLICATION_ONLY=1` isolates this failure from the earlier cancellation probe.

AOIDC uses GCC 11 and Vulkan llvmpipe; IBX uses GCC 14 and Vulkan llvmpipe.
ASan/UBSan instruments the replacement production translation unit and probe;
the existing engine archives are not instrumented. Leak detection is disabled
for the external Vulkan driver. These are host checks, not Android device tests.

| Probe | AOIDC | IBX |
| --- | --- | --- |
| Original striped cancellation | FAIL: next Scene Latitude bytes differ | Same failure |
| Original final phase flush | FAIL: replaced caller output | Same failure |
| Recovery candidate | PASS: 42 checks | PASS: 42 checks |
| Candidate ASan/UBSan | Not run | PASS: 42 checks |

The last report-append-before-output ordering adjustment was rechecked normally
on AOIDC. IBX's normal and sanitizer logs precede that ordering-only adjustment.
Stored IBX logs omit the transport wrapper's status for reading the log file;
the FAIL/PASS lines are the probe's output. The observed baseline probe exit was
1 and candidate probe exits were 0.

## Final 0044 + 0045 Combination

On IBX, the final recovery patch (including report append before output commit)
was applied to the complete 1..42 + 0045 source tree. A full CMake incremental
build relinked the engine archive successfully. Against this complete build,
normal and ASan/UBSan recovery probes each passed all 42 checks.

The existing `run_vulkan_fusion.sh` regression also passed 494 cases comparing
1,490,286 float32 values, including real film-to-print rendering in six output
spaces for paper and DI (12 render configurations). This checks fused versus
unfused parity within the combined build; it is not a stored before/after image
comparison against another release. The 640 nonfinite reference values are
reported by that existing regression's adversarial kernel inputs.

See `ibx-combined-{build,normal,sanitized,fusion}.log`. All final combined commands
completed with exit status 0. No Android device execution is implied.
