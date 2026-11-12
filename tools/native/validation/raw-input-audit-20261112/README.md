# RAW input audit evidence

Generated from public synthetic fixtures only, 2026-11-12. Co-created with
GPT (OpenAI). See `docs/ENGINE_RAW_INPUT_AUDIT_2026-11-12.md` for commands,
coverage, the retained integer LinearRaw normalization gap and limitations.

- `windows.json`: eight float orientations and four integer geometry/black
  cases through the current production dispatcher, executed on Windows.
- `aoidc.json`: the same cases executed on native Linux with the current
  decoder. The integer compatible-policy check is not a complete DNG claim.
- `cross-platform.json`: the 12 payload SHA-256 comparisons, all byte-equal.
- `comparison.json`: local known-geometry/exposure/ICC and wrong-pair checks.
- `source.sha256`: the relevant local source hashes.
- `toolchain.txt`: the cloud compiler versions, executable dependency list,
  executable hash, and production-source hashes used in the build.

No private camera paths, photos, crops, profiles extracted from private images,
or private measurements are included. The full float payloads and generated
fixture images remain outside the repository. IBX ran initial smoke tests only;
its later cooldown prevented final-suite execution and evidence download.
