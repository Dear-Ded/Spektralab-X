#!/usr/bin/env bash
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
set -euo pipefail
: "${LIBRAW_SOURCE:?Set LIBRAW_SOURCE to the patched LibRaw 0.22.2 source}"
: "${BUILD_DIR:?Set BUILD_DIR to a dedicated probe directory}"
HERE=$(cd "$(dirname "$0")" && pwd)
for mode in old new; do
    enabled=OFF
    if [[ "$mode" == new ]]; then enabled=ON; fi
    cmake -S "$HERE/libraw-deflate-project" -B "$BUILD_DIR/$mode" \
        -DCMAKE_BUILD_TYPE=Release -DLIBRAW_SOURCE="$LIBRAW_SOURCE" -DWITH_ZLIB="$enabled"
    cmake --build "$BUILD_DIR/$mode" -j "${JOBS:-4}"
done
python3 "$HERE/libraw_deflate_fixtures.py" "$BUILD_DIR/fixtures" \
    --baseline "$BUILD_DIR/old/libraw_deflate" --candidate "$BUILD_DIR/new/libraw_deflate"
