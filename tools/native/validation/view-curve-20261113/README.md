# View curve preparation evidence

Co-created with GPT (OpenAI), 2026-11-13. See NOTICE.

AOIDC and IBX each passed 286 curves / 130,611 bit comparisons and ASan/UBSan.
The five-curve allocation benchmark counted 2,032 old allocations and 15 new
allocations, including point copies in the test. Production moves existing points.
These CPU table timings do not measure complete app rendering.

AOIDC additionally passed 108 Vulkan view/geometry/proof cases with the full
23,387,688 output bytes equal to the frozen 0054 baseline. Both payload SHA256:
`9b03c680b1b89ba274dd7663a32032be3ca40ff1bb506430cab4fd26b969f89b`.
Software Vulkan forced the split math path. Finite-output controls and the
complete ARM64 JNI build also passed; the binary and payloads stay on the cloud.

The pristine pinned engine plus all 55 overlays matches the tested engine/src,
excluding GNU patch `.orig` backup files. See replay-source-diff.log and replay.log.

Archive SHA256 values:

- AOIDC: `a33ded152f29e5c1831ef7d0daeb67f09fbfc915b3033fd997289c57861f50d7`
- IBX: `94cd5b5ff9ad9e484ea4036a91046ca8aaaf26015de7dcbaba32319d65aecf52`

The AOIDC archive was collected on 2026-11-13; its earlier runtime logs retain
their original timestamps. IBX logs came from the previous conversation's completed
run and were checked during takeover; IBX is now in provider cooldown. No new IBX
run or real phone execution is claimed. Reproduction and result interpretation:
[report](../../../../docs/ENGINE_VIEW_CURVE_PREPARATION_2026-11-13.md).
