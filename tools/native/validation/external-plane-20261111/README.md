# External-plane publication evidence, 2026-11-11

Co-created with GPT (OpenAI). Upstream pin:
`a46627d250545a394ca21522f8a28b0fb89e4c42`, engine patches 0001-0042.
This source-only follow-up does not build an APK or change the version.
Only synthetic data is included. Private camera files and their derived metrics
are excluded. See [the report](../../../../docs/ENGINE_EXTERNAL_PLANE_PUBLICATION_2026-11-11.md).

Both hosts run the same checked-in publication and GPU harnesses:

- `contract.log`: 94 checks, including unwritten/incomplete reads, overlapping
  and concurrent writes, publication ownership, cancelled I/O, EOF, short and
  interrupted I/O, reservation/write failure, and descriptor cleanup. Thread
  creation is forced to fail at each of three background launch positions;
  existing workers and the caller still finish and publish matching bytes.
- `sanitized.log`: the same 94 checks with ASan/UBSan, no uninstrumented engine
  archive linked. Linux interposition covers glibc fortified pread too.
- `planner.log`: the previous 89 geometry/budget/halo/IIR contracts still pass.
- `point.log` and `print.log`: independent whole-frame references versus banded
  execution, plus GPU cancellation at 8/10 checkpoints and three run positions
  each, followed by recovery with byte-identical output. Existing numeric
  matrices cover 901/2251 scenes; strict 128 MiB storage range remains enforced.
- `header-sha256.txt`: exact tested upstream headers. `source-sha256.txt` at this
  directory root binds checked-in source inputs, normalizing CRLF to LF.

AOIDC: GCC 11.4, llvmpipe LLVM 15.0.7; current existing engine archives with
recompiled header-only helpers/harnesses. `before.log` reproduces the unsealed
read bug against 0041. `arm64.log` records NDK r27.2 ARM64/API 29 header syntax
compilation with warnings treated as errors.

IBX: GCC 14.2, llvmpipe LLVM 19.1.7; fresh pinned upstream archive, strict replay
of all 42 patches, and a fresh current engine build. `replay-final.json` compares
all 96 touched source files with a second zero-fuzz replay; `engine-replay.log`
records application. This does not revalidate LibRaw patch replay. Vulkan
headers are supplied from the host's existing dependency tree.

`primary-source-sha256.txt` records downloaded official POSIX and Vulkan
documents used to resolve preallocation/completion and budget semantics. Their
URLs and concrete design consequences are in the report. No copied full
specifications are distributed here.

Comparisons are within each host, not across drivers. These are CPU Vulkan
devices, not Android phone GPUs. The helpers remain outside production
Pipeline; full-process memory bounds, decoder/codec/page-cache accounting,
IIR carried state and phone appearance/performance remain separate work.
