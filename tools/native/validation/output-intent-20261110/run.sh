#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Existing core-double library, no image or GPU.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "${BUILD_DIR:?independent output directory}"
"${CXX:-c++}" -std=c++20 -O2 -Wall -Wextra -Werror -ffp-contract=off \
 -I"${UP:?patched upstream}/engine/src" "$here/probe.cpp" \
 "${B:?host build}/engine/libspektralab_core.a" -o "$BUILD_DIR/probe"
"$BUILD_DIR/probe" "$B/engine/resources/spektrafilm_constants.bin"
