#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing host build directory}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
e="$b/engine"
src="$repo/app/src/main/cpp"
python3 - "$up/engine/host/output.cpp" "$build/upstream_output_math.inc" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text()
markers = [("static inline float spow", "bool OutputTransform::fetch"),
           ("static inline void cam16ucs_into", "template <typename F>")]
parts = []
for start, end in markers:
    a, z = text.find(start), text.find(end)
    assert 0 <= a < z, "upstream output markers changed"
    parts.append(text[a:z])
pathlib.Path(sys.argv[2]).write_text("\n".join(parts))
PY
flags=(-std=c++20 -O2 -pthread)
[[ "${STRICT:-1}" == 0 ]] || flags+=(-ffp-contract=off)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -fsanitize=address,undefined -fno-omit-frame-pointer)
"${CXX:-c++}" "${flags[@]}" -I"$src" -I"$build" -I"$up/engine/include" \
    -I"$up/engine/src" -I"$up/engine/src/core" \
    "$repo/tools/native/nofilm_float.cpp" "$src/output_transform.cpp" "$src/raw_development.cpp" "$src/layer2.cpp" \
    -Wl,--start-group "$e/libspektralab_engine.a" "$e/libspektralab_core.a" \
    "$e/libspk_volk.a" -Wl,--end-group -ldl -o "$build/nofilm_float"
export SPEKTRAFILM_VULKAN_STRICT_LIMITS=1
"$build/nofilm_float" "$e/resources"
