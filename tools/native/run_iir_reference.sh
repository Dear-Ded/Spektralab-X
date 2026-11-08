#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-08. See NOTICE for project terms.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
UP="${UP:?patched upstream tree}"
build="${BUILD_DIR:-$(mktemp -d)}"
mkdir -p "$build"
"${CXX:-c++}" -std=c++20 -O2 -ffp-contract=off -ffunction-sections -fdata-sections \
    -I"$UP/engine/src/core" "$repo/tools/native/iir_boundary_reference.cpp" \
    "$UP/engine/src/core/numeric.cpp" -Wl,--gc-sections -o "$build/iir_boundary_reference"
"$build/iir_boundary_reference"
