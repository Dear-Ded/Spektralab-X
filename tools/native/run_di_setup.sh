#!/usr/bin/env bash
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-09.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing upstream build directory}"
baseline="${BASE_DI:?frozen pre-0027 digital_intermediate.cpp}"
build="${BUILD_DIR:-$(mktemp -d)}"
core="$b/engine/libspektralab_core.a"
cxx="${CXX:-c++}"
mkdir -p "$build"
[[ -f "$core" ]] || { echo "requires built spektralab_core" >&2; exit 2; }
sed 's/\r$//' "$baseline" > "$build/baseline_di.cpp"
expected=cb136c69faca30bc65f21b2e0dbc3b208a2d56c1375a9533210f9fbb53cd4bba
actual="$(sha256sum "$build/baseline_di.cpp" | cut -d ' ' -f1)"
[[ "$actual" == "$expected" ]] || {
    echo "baseline SHA256 mismatch: expected=$expected actual=$actual" >&2; exit 2;
}
printf 'baseline_lf_sha256=%s\n' "$actual"
sha256sum "$up/engine/src/core/digital_intermediate.cpp" "$core"
"$cxx" --version | head -1
resources="${RESOURCES_DIR:-$build/resources}"
if [[ -z "${RESOURCES_DIR:-}" ]]; then
    mkdir -p "$resources/profiles"
    cp "$up/engine/resources/spektrafilm_constants.bin" "$resources/"
    cp "$up/engine/resources/neutral_print_filters.json" "$resources/"
    cp "$up/engine/resources/profiles/"*.json "$resources/profiles/"
    cp "$up/engine/resources_product/profiles/"*.json "$resources/profiles/"
    cp "$repo/profiles-extra/"*.json "$resources/profiles/"
fi
includes=(-I"$up/engine/src" -I"$up/engine/src/core")
renames=(-Ddi_constants=baseline_di_constants -Ddi_live_offsets=baseline_di_live_offsets)
source="$repo/tools/native/di_setup.cpp"
for mode in strict default; do
    flags=(-std=c++20 -O3 -DNDEBUG -fPIC -pthread)
    [[ "$mode" != strict ]] || flags+=(-ffp-contract=off)
    printf '\nmode=production_source_%s\n' "$mode"
    "$cxx" "${flags[@]}" "${includes[@]}" "${renames[@]}" \
        "-DDI_SOURCE=\"$build/baseline_di.cpp\"" -DDI_CURVE_PROBE=baseline_di_curve_probe \
        -c "$source" -o "$build/baseline-$mode.o"
    "$cxx" "${flags[@]}" "${includes[@]}" \
        "-DDI_SOURCE=\"$up/engine/src/core/digital_intermediate.cpp\"" \
        -DDI_CURVE_PROBE=production_di_curve_probe -c "$source" -o "$build/production-$mode.o"
    "$cxx" "${flags[@]}" "${includes[@]}" "$source" \
        "$build/baseline-$mode.o" "$build/production-$mode.o" "$core" -o "$build/di-$mode"
    "$build/di-$mode" "$resources"
done
# Only the internal curve probe uses this renamed source object. The public DI
# functions in this third run are extracted from the actual CMake archive.
"$cxx" -std=c++20 -O3 -DNDEBUG -fPIC -pthread "${includes[@]}" \
    "-DDI_SOURCE=\"$up/engine/src/core/digital_intermediate.cpp\"" \
    -DDI_CURVE_PROBE=production_di_curve_probe \
    -Ddi_constants=probe_only_di_constants -Ddi_live_offsets=probe_only_di_live_offsets \
    -c "$source" -o "$build/production-boundary-only.o"
printf '\nmode=production_archive flags=existing_CMake_build boundary_probe=production_source_default\n'
"$cxx" -std=c++20 -O3 -DNDEBUG -pthread "${includes[@]}" "$source" \
    "$build/baseline-default.o" "$build/production-boundary-only.o" "$core" -o "$build/di-archive"
"$build/di-archive" "$resources"
