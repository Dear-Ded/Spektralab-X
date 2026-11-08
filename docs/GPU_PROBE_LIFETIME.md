# Storage probe lifetime correction

Co-created with GPT (OpenAI), 2026-11-08. See NOTICE for project terms.

## Behavior

Previously, a successful queue submission followed by a fence timeout returned
from `Probe::test` and immediately freed its buffers, then destroyed its fence,
command pool, descriptors, pipeline and device. A timeout does not establish
completion. This was a pending-resource lifetime defect.

`app/src/main/cpp/storage_probe.cpp` now owns all three candidate buffers until
device cleanup. It tracks submission separately from the candidate verdict:

- Submission success marks work pending. Fence success or fence-reported device
  loss settles it. Timeout and other wait failures leave it pending.
- Submission-reported device loss also enters pending cleanup. Vulkan treats
  that return like a successful submission for command-buffer lifetime; it
  does not make submission equivalent to a completed wait.
- `close_device()` waits for the private device to become idle when necessary,
  then releases child objects before the device and instance.
- If idle still returns neither success nor device loss, cleanup returns false
  and retains every referenced object and its parent instance. A later close
  can retry. An unresolved destructor intentionally leaves this exceptional
  Vulkan object graph until process exit rather than freeing in-use resources.
- Only actual `VK_ERROR_DEVICE_LOST` sets the `lost` output. The two-second
  diagnostic deadline is no longer described as a universal vendor watchdog.

Source anchors: `close_device` line 141, `teardown` line 168,
`submit_candidate` line 349, owned buffers/pending state line 387.

## Validation

The fake function table invokes production submission/cleanup methods and
asserts that no destruction or unmapping occurs while work is pending. It
also checks complete cleanup, idempotent close, actual-loss classification,
and parent-instance retention. No fake driver is installed in the app.

Nine sequences passed on aoidc, Ubuntu GCC 11.4.0, 2026-11-08 09:21:57 UTC:
normal completion; timeout then completion; timeout then device loss; other
wait failure then completion; wait-reported loss; submit failure;
submit-reported loss; unresolved cleanup followed by successful retry; and
unresolved cleanup through destruction.

```sh
UP=/data/work/gpt-engine-impl-20261108/upstream \
BUILD_DIR=/data/work/gpt-probe-lifetime-20261108/build \
bash /data/work/gpt-probe-lifetime-20261108/tools/native/run_probe_lifetime.sh

LP_NUM_THREADS=2 \
/data/work/gpt-probe-lifetime-20261108/build/storage_probe_lifetime --smoke
```

The optional smoke uses the actual loader and a single 4096-byte candidate.
It returned `verified=4096`. The human-readable probe log rounds MiB down and
therefore prints `verified 0 MiB` for this deliberately tiny case; the test
checks the byte return value. No GPU hang, capacity stress or device-loss
injection was performed. Full command trace, output and source SHA-256 values
are in [validation/storage-probe-lifetime.txt](validation/storage-probe-lifetime.txt).

Test files: `tools/native/storage_probe_lifetime.cpp` and
`tools/native/run_probe_lifetime.sh`. The default test requires no physical
GPU; `--smoke` requires a working Vulkan loader/device. The test includes the
production implementation and uses a narrow friend accessor, so its state
transitions are not a separately reimplemented model.

## Limits

Cleanup remains synchronous. A slow driver may make `vkDeviceWaitIdle` outlast
the diagnostic deadline. Isolating optional probes from normal startup is a
separate lifecycle/product change. The exceptional unresolved-wait path retains
resources until process exit, which can consume substantial memory for a real
candidate; it does not claim that leaking resources is normal recovery.

This change does not alter candidate sizes, Android GPU selection, probe cache
policy, shaders, engine output, or the upstream CPU Vulkan limit override.
Android NDK compilation and physical-device timeout behavior are not established
by this host test. Parent-task builds may provide separate Android evidence.

## Primary specification evidence

- [Khronos fence waits](https://docs.vulkan.org/refpages/latest/refpages/source/vkWaitForFences.html): timeout does not signal completion.
- [Khronos buffer destruction](https://docs.vulkan.org/refpages/latest/refpages/source/vkDestroyBuffer.html): VUID-vkDestroyBuffer-buffer-00922 requires commands referencing the buffer to have completed.
- [Khronos lost-device rules](https://raw.githubusercontent.com/KhronosGroup/Vulkan-Docs/main/chapters/devsandqueues.adoc): inspected lines 3479-3509 on 2026-11-08; children remain valid and require explicit destruction, and device-loss return values have the corresponding successful command's lifetime meaning.
- [Khronos synchronization](https://raw.githubusercontent.com/KhronosGroup/Vulkan-Docs/main/chapters/synchronization.adoc): inspected fence wait and device-idle definitions on 2026-11-08.

## FIR patch 0019 review

No new input/output alias or normal asynchronous scratch-reuse defect was found
in the inspected non-consuming row-buffer path. `Blur::fir` takes its input
Image by value, retaining its original buffer even if the output reference is
the caller's input object. Source, weights and accumulator references remain
live across all bands; horizontal filtering finishes before explicit `flush`
allows the next vertical write to reuse scratch. Consume-input execution remains
on its old path. The new shader preserves vertical-then-horizontal operation and
tap order; its vertical coordinate uses the full source and its horizontal
coordinate uses the full width.

An accumulator pointer equal to the output Image object is not a supported
alias: allocation overwrites that object before reading it. The existing full
temporary path already has this limitation, and current production mixture
calls pass a separate `next` output and `acc` input. This is not a new regression.

The bounded pool marker added by the parent task is useful: without persistent
classification, a freed 8 MiB scratch block could later serve a 12-byte ordinary
upload because the existing pool permits up to 64 MiB slack. The audit metric
`reuse_max_taken_for_request` is explicitly stored beside the largest reused
capacity in upstream `vulkan_gpu.cpp:1004`; observed 18855936/12 really means a
large buffer served a tiny request. It is a memory/performance issue, not a
descriptor-byte correctness error after patch 0010. Keep bounded blocks for
exact-capacity requests even after their first release.
