#!/usr/bin/env bash
# Co-created with GPT (OpenAI). Both original suites plus custom-WB mathematics.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
build="${BUILD_DIR:?dedicated custom-WB test directory}"
BUILD_DIR="$build/helper" bash "$repo/tools/native/run_float_dng_linear.sh"
BUILD_DIR="$build/dispatcher" bash "$repo/tools/native/run_raw_precision_input.sh"
baseline=()
if [[ -n "${BASELINE_FLOAT_PROBE:-}" ]]; then baseline=(--baseline-helper "$BASELINE_FLOAT_PROBE"); fi
python3 "$repo/tools/native/float_custom_wb_fixtures.py" "$build/custom" \
    --helper "$build/helper/float_dng_linear" --dispatcher "$build/dispatcher/raw_precision_input" "${baseline[@]}"
