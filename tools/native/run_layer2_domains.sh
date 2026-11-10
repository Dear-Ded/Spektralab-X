#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-10. See NOTICE.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
src="$repo/app/src/main/cpp"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer)
"${CXX:-c++}" "${flags[@]}" -I"$src" -c "$src/layer2.cpp" -o "$build/layer2.o"
for domain in exposure highlight; do
    "${CXX:-c++}" "${flags[@]}" -I"$src" "$repo/tools/native/layer2_${domain}_domain.cpp" \
        "$build/layer2.o" -o "$build/layer2_${domain}"
done
"$build/layer2_exposure" --require-linear
"$build/layer2_highlight" --require-monotonic
