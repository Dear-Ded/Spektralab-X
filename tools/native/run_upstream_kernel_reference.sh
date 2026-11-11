#!/usr/bin/env bash
# Independent upstream double oracles, pinned and verified before compilation.
# Co-created with GPT (OpenAI), 2026-11-11. No production math is replaced.
set -euo pipefail
up="${UP:?patched upstream tree}"
b="${B:?host engine build}"
build="${BUILD_DIR:?isolated output directory}"
pin=223205fddbbc01c583977497d9f27c79a49ca8d0
mkdir -p "$build/source"
for kind in di_encode headroom; do
    source="$build/source/gpu_vulkan_$kind.cpp"
    if [[ ! -f "$source" ]]; then
        curl -fsSL --max-time 45 "https://raw.githubusercontent.com/JamesQiu2005/SpektraLab/$pin/engine/tests/gpu_vulkan_$kind.cpp" -o "$source"
    fi
    case "$kind" in
        di_encode) expected=ce251cd447889494f36e21f9892476e97d3bf969db22c3c0fe8c60e1ed6a711f ;;
        headroom) expected=5b327a5c25d47c72d7a01a7367fbc21f3bff087a40d1477f3e16c94be13cbbeb ;;
    esac
    printf '%s  %s\n' "$expected" "$source" | sha256sum -c -
    "${CXX:-g++}" -std=c++20 -O2 -ffp-contract=off -pthread -I"$up/engine/src" -I"$up/engine/tests" \
        "$source" -Wl,--start-group "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" \
        "$b/engine/libspk_volk.a" -Wl,--end-group -ldl -o "$build/$kind"
    SPEKTRAFILM_VULKAN_STRICT_LIMITS=1 "$build/$kind" "$b/engine/resources"
done
