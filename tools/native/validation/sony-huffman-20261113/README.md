# Sony Huffman cache maintenance evidence

Co-created with GPT (OpenAI), 2026-11-13. See NOTICE.

AOIDC final.exit=0 and arm64.exit=0. CI-form ASan/UBSan tests compared 81,920
legal cases and accepted 24,576 controlled high-bit cases after the repair.
The old controlled high-bit case is an expected ASan failure, recorded separately.
These synthetic inputs prove only the cache guard, not a real camera failure.

Two synthetic DNG controls have byte-identical unpacked RAW and RGB16 output.
The file runner reused the existing complete host archive and replaced only
the two translation units that include the changed header. It did not rebuild
every host dependency. The ARM64 JNI, including the new LibRaw overlay, was fully
compiled and linked from an isolated build directory.

18 LibRaw overlays replayed with zero fuzz and matched the built source.
Only losslessjpeg.h differs from the host baseline source used for file controls.
The original test runner's directory/binary naming failure was fixed before
these final tests. Both pristine and already-patched input forms passed.

Public text archive SHA256:
`298b346ec0a78b16d5882ecd8513d1fa354a4862f6f9a2c0659573b7f8f67ac9`.

No private images, generated pixel payloads, APKs or native binaries are stored
here. No IBX run, complete Sony camera file suite or phone execution is claimed.
See the [report](../../../../docs/ENGINE_SONY_HUFFMAN_CACHE_2026-11-13.md) for
coverage, provenance and reproduction tool paths.
