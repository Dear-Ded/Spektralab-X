# External Window Payload and Pool Capacity

Co-created with GPT (OpenAI), 2026-11-11. Synthetic-only source validation.
No photographs, private filenames or image measurements are included.

The warm-pool probe first releases six 1 MiB GPU allocations, then processes a
4692-byte RGB window. The baseline fails because FIR scratch or its constants
borrow oversized pool buffers. The candidate requires exact logical capacity
for window scratch and independently owned uploads for window data/constants.
It compares pooled capacity taken with capacity requested, and compares every
output byte with the ordinary production FIR/linear-combination sequence.

The host probe includes spans, both row-coverage arrays and fixed maximum
input/output arrays per worker. A 1282-byte payload budget admits two of four
requested worker slots. An insufficient budget fails before claiming output;
the same output can then be retried with sufficient budget. Forward, reverse
and shuffled halo bands preserve every float byte.

Both hosts pass 56 budget/warm-pool checks, 901 point-chain cases and 2251 FIR
print-run cases. Each parity matrix includes 1/4 workers and all three orders,
the strict 128 MiB storage-buffer limit, and GPU cancellation/recovery. The
point suite compares 102764160 float values and the print suite 75470400 values.
Each host fully rebuilt the native engine with the new Blur signatures. The
final AOIDC runs use the combined 0001..0045 source tree; the final IBX runs use
0001..0042 plus 0044 and 0045. ASN decoder validation is recorded separately.
AOIDC's strict replay applies all 45 patches with zero fuzz and verifies the
96 affected source files against its tested integrated tree.

AOIDC uses GCC 11 / llvmpipe LLVM 15; IBX uses GCC 14 / llvmpipe LLVM 19. AOIDC
also passes 94 external-plane failure contracts normally and under ASan/UBSan,
and the budget probe with ASan/UBSan. Only the probe/header executor is
instrumented for the latter: its linked engine libraries are ordinary builds.
NDK r27.2 ARM64/API29 syntax compilation covers final Blur, Pipeline and the
budget probe. The `*-final` files identify the final source and run results;
earlier build, baseline and 51-check probe logs document the reproduction.

Metadata-only rejection checks also cover the RGB kernels' uint32 element
indices: three channels must fit, not just the pixel count. No huge allocation
is needed to reach those validation boundaries.

The source hashes identify the actual files and native archives used; each
parity log also records its executable and archive hashes. Wall times include
compilation and are not a phone performance claim. Persistent uploads may trade
allocation overhead for predictable capacity.

This is a host payload budget, not a process RSS or Vulkan physical-memory
bound. Thread stacks, allocator overhead, tables, driver staging, allocation
alignment, idle pools and unrelated live application buffers remain outside it.
The GPU mutex serializes this executor only; callers still need exclusive GPU
ownership. The external helpers remain outside production Pipeline.
