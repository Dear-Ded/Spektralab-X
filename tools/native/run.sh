#!/usr/bin/env bash
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
cxx="${CXX:-c++}"
flags=(-std=c++20 -O2 -pthread)
# A separate build must verify the compiler's normal contraction policy as well.
[[ "${STRICT:-1}" == 0 ]] || flags+=(-ffp-contract=off)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -fsanitize=address,undefined -fno-omit-frame-pointer)
app="$repo/app/src/main/cpp"
"$cxx" "${flags[@]}" -I"$app" "$repo/tools/native/engine_adaptation.cpp" \
    "$repo/tools/native/resize_reference.cpp" "$app/resize.cpp" "$app/layer2.cpp" -o "$build/engine_adaptation"
"$build/engine_adaptation" "$@"
"$cxx" "${flags[@]}" -I"$app" "$repo/tools/native/curve_regression.cpp" -o "$build/curve_regression"
"$build/curve_regression"
if [[ -n "${UP:-}" ]]; then
    core="$UP/engine/src/core"
    "$cxx" "${flags[@]}" -I"$core" -I"$UP/engine/third_party" \
        "$repo/tools/native/setup_cache_lifetime.cpp" "$core/setup_cache.cpp" "$core/blob.cpp" \
        -o "$build/setup_cache_lifetime"
    "$build/setup_cache_lifetime"
    "$cxx" "${flags[@]}" -ffunction-sections -fdata-sections -I"$UP/engine/src" -I"$core" \
        "$repo/tools/native/filter_radius.cpp" "$UP/engine/src/pipeline/blur.cpp" "$core/numeric.cpp" \
        -Wl,--gc-sections -o "$build/filter_radius"
    "$build/filter_radius"
fi
printf 'native tests passed (%s)\n' "$build"
