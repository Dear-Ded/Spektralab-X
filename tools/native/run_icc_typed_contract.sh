#!/usr/bin/env bash
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
build="${BUILD_DIR:?isolated output directory}"
up="${UP:?patched upstream directory}"
icc="${PROPHOTO_ICC:?profile emitted by tools/jvm/run_icc_profile.sh}"
mkdir -p "$build"
python3 - "$up/engine/host/output.cpp" "$build/upstream_output_math.inc" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text()
start = text.index('static inline float spow(')
end = text.index('bool OutputTransform::fetch', start)
pathlib.Path(sys.argv[2]).write_text(text[start:end])
PY
"${CXX:-c++}" -std=c++20 -O2 -ffp-contract=off -I"$build" \
    "$repo/tools/native/icc_typed_contract.cpp" -o "$build/icc_typed_contract"
"$build/icc_typed_contract" > "$build/cctf.csv"
python3 "$repo/tools/native/icc_typed_contract.py" --icc "$icc" \
    --samples "$build/cctf.csv" ${LCMS_LIBRARY:+--lcms "$LCMS_LIBRARY"}
