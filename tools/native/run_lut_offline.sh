#!/usr/bin/env bash
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?matching source}"
b="${B:?matching fully rebuilt engine}"
out="${OUT:?isolated output directory}"
mkdir -p "$out"
python3 - "$up" "$out" <<'PY'
import pathlib, sys
up, out = map(pathlib.Path, sys.argv[1:])
def part(path, start, end):
    text = (up / path).read_text()
    a, b = text.index(start), text.index(end)
    assert a < b
    return text[a:b]
(out / 'upstream_output_math.inc').write_text(
    part('engine/host/output.cpp', 'static inline float spow', 'bool OutputTransform::fetch') + '\n' +
    part('engine/host/output.cpp', 'static inline void cam16ucs_into', 'template <typename F>'))
(out / 'upstream_input_math.inc').write_text(
    part('engine/host/image_io.hpp', 'struct Trc {', 'bool parse_icc(') + '\n' +
    part('engine/host/image_io.cpp', 'double Trc::decode', 'static uint32_t be32'))
PY
app="$repo/app/src/main/cpp"
"${CXX:-g++}" -std=c++20 -O2 -ffp-contract=off -pthread -I"$up/engine/src" -I"$up/engine/src/core" \
  -I"$app" -I"$out" -I"$up/engine/include" "$repo/tools/native/lut_offline.cpp" \
  "$app/input_transform.cpp" "$app/output_transform.cpp" "$app/raw_development.cpp" "$app/layer2.cpp" -Wl,--start-group \
  "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" "$b/engine/libspk_volk.a" \
  -Wl,--end-group -ldl -lz -o "$out/lut_offline"
sha256sum "$out/lut_offline" "$b/engine/libspektralab_engine.a"
LP_NUM_THREADS="${LP_NUM_THREADS:-4}" SPEKTRAFILM_VULKAN_STRICT_LIMITS=1 \
  /usr/bin/time -v -o "$out/run.time" "$out/lut_offline" "$b/engine/resources/vulkan" "$b/engine/resources" "$out" "${MODE:-smoke}"
