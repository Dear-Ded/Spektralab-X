# Synthetic cloud environment evidence

2026-11-12. Co-created with GPT (OpenAI). See
`docs/ENGINE_FEEDBACK_ENVIRONMENT_2026-11-12.md` for interpretation.

`ibx/` contains the five-case native matrix, actual scope limits, partial
results from expected failures, Mesa device/dispatch limits and matching
kernel cgroup OOM records. `full-size-probed/` is the preceding seven-render
30 MP session/cache run. No private RAW, private measurements or image payloads
are present. CSV hashes compare only within each deterministic check; glare
and cross-driver image equality are not inferred from unrelated run hashes.

The downloaded native evidence archive SHA256 is
`bbd82cede369508c9b6304968eabee63faffd165670af9b5742531a745c49d08`.
Diagnostic source/archive provenance is preserved in `matrix/build-inputs.txt`.
The matrix completion status is zero because all cases were attempted; the
2 GiB OOM and strict capacity controls remain recorded as failed renders.

`aoidc/swiftshader-host/` contains the independent Google SwiftShader host
dispatch and 1537x989 session/cache checks. Both pass under strict limits and
forced split. This is a second driver implementation, not Android/Adreno or
large-image evidence. Its downloaded archive SHA256 is
`7bb0322430751bedc6f5c52013bc5501154acdb023a5e2bc594c5e0fe65b0509`.

`aoidc/android/` contains selected original text records of the API 37 AVD
preparations and final tests. `android-results-v6/cases.csv` is the final
default-transfer result: 30 MP codec passes, film smoke aborts (134), full film
export is skipped. `android-inline-off/` is the separate ring-off/1 MiB
staging control, also aborted during Vulkan initialization. Both crash stacks
are symbolized with the exact production JNI binary; the guest process logs
identify `Unsupported virtual memory feature`. Boot never completed and the
actual expanded Java heap was 576 MiB. These are not successful Android film
or phone-compatibility results.

The earlier v4 codec also passes, but its runner ends with a parse error;
v5 fails to load the newly added helper's C++ shared runtime. Both are retained
and superseded by v6's static C++ helper build. Java and native fixture
statistics match all three full-frame codec CSV rows (`fixture-java-vs-native.exit=0`).
The downloaded Android text archive SHA256 is
`9730e9e00c163143315f580efa5754e387bd969305cc6531c5732b6b97cd2556`.
Image payloads, dex and native binaries remain outside Git. Emulator startup
logs are retained on the cloud and excluded from this selected text bundle;
runtime properties, acceleration check and failures are included.

The final diagnostic-source archive SHA256 is
`7aafd63242879170d35146d1328166f4aa44febd210c3d115710fd19201c2d28`.
Local and cloud Android diagnostic source hashes were checked equal. All
owned test processes and the disposable AVD have ended; no follow-up job is
scheduled.
