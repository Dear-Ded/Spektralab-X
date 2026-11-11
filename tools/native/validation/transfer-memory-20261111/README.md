# Vulkan transfer allocation validation

Co-created with GPT (OpenAI), 2026-11-11. Project terms in NOTICE apply.
Source and cloud-native verification only; no Android runtime is certified.

The complete engine was rebuilt for the new Gpu virtual methods. An old archive
must not be linked with the new interface. AOIDC independently integrates all
48 patches; IBX independently rebuilt the final 0043 plus 0046/0047 combination.
The transfer probe uses the real Vulkan backend and the public C memory report.

Both inline-ring modes pass 24 checks per host. A 4 MiB persistent upload and
pooled scratch produce distinct upload/read staging allocations. Explicit trim
returns their actual allocation total while preserving compute buffers, pool
counts and the argument ring. A following 4 KiB read creates only 4 KiB read
staging and returns identical bytes. Concurrent ledger snapshots during repeated
trim/read operations succeed. Final handle release and pool trim clear the
tracked buffers. Public memory JSON preserves the existing logical total and
adds the separate backend partition.

The existing device-lost regression passes both ring modes: 16 entry points
refuse work with the latched diagnosis, and cleanup still returns two buffers
and 131072 bytes. NDK r27.2 ARM64/API29 compilation of changed consumers passes.
Two existing C-linkage return-type warnings remain, without a new compile error.

`results.log` records the parent's final 0001..0048 tests and matching source,
object and executable hashes. `replay.json` records zero-fuzz application of
48 patches and SHA256 equality for all 97 affected files. The ledger covers
tracked Vulkan allocations, not driver-private memory, RSS or concurrent
application allocations. Logical image totals overlap the backend totals and
must not be added. No new rendering arithmetic is introduced by 0046.

Reproduce with `tools/native/run_vulkan_transfer_memory.sh`, setting `UP` to
the patched source, `B` to its complete matching-ABI native build, and
`BUILD_DIR` to a separate probe directory. The runner executes both ring modes.
