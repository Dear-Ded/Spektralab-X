# Synthetic film dispatch and session evidence

2026-11-12. Co-created with GPT (OpenAI). See
`docs/ENGINE_FEEDBACK_DISPATCH_2026-11-12.md` for scope and limitations.

- `aoidc/`: final normal/forced-split logs, 170-row comparison CSVs, session
  tier CSVs/capabilities, tool/compiler/source/archive provenance.
- `ibx/`: the corresponding independent LLVM 19 software Vulkan run.
- `windows/`: normal/forced-split comparison CSVs, session tier CSVs and
  capabilities from actual AMD Radeon 760M execution. Automatic mode accepted
  native FMA; forced mode used Dekker split. Console logs were observed during
  execution but are not reconstructed into purported raw log files.

Each dispatch CSV has 170 successful full-frame comparisons totalling
9,596,670 float32 pairs, with zero differing bits. Each session tier CSV has
seven records: live, preview, full, stable, repeat, edited, restored. Glare is
disabled only for the final four records. Stable/repeat/restored hashes match
within a run; edited differs and keeps the cached negative.

The runner/tool archive SHA256 was
`d6e9d26dd2f2acbb82e5f4e9c4b27397493b1e4cd36d994d49de5042ff2d425b`.
AOIDC public evidence archive SHA256:
`0ae8f92dcbd82d6412366d0b3e75a96c6e76ffda63ba18008e39bc4bca46545f`.
IBX public evidence archive SHA256:
`e4d46fcd700ed91aa836e94c683dd1a2510601a1bbed367798701b56289cd9bd`.

Windows executable SHA256 values, outside Git:

- dispatch: `af7f79df68f124d4459d865e513afd610cce756cabad57c7de404d21fab0719f`
- session: `ce978c7645a57c2f3eaf58dd0aac890cf73a91f194f9bee0137ca6f080452bc5`

No image, float payload, executable, private input measurement or handoff is
included. These are synthetic equivalence checks, not an independent film
accuracy oracle, mobile performance benchmark or proof that Issue #17 is fixed.
