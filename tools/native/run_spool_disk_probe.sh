#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
flags=(-std=c++20 -O2 -Wall -Wextra -Werror)
[[ "${STRICT:-1}" == 0 ]] || flags+=(-ffp-contract=off)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -fsanitize=address,undefined -fno-omit-frame-pointer)
"${CXX:-c++}" "${flags[@]}" -I"$repo/app/src/main/cpp" "$repo/tools/native/spool_disk_probe.cpp" \
    -Wl,--wrap=fallocate -Wl,--wrap=fstatvfs -Wl,--wrap=fwrite -Wl,--wrap=fflush -o "$build/spool_disk_probe"
"$build/spool_disk_probe" "$build/output"
