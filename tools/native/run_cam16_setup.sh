#!/usr/bin/env bash
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-09.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing upstream build directory}"
baseline="${BASE_CAM16:?frozen pre-0026 cam16.cpp}"
build="${BUILD_DIR:-$(mktemp -d)}"
constants="${CONSTANTS_BLOB:-$up/engine/resources/spektrafilm_constants.bin}"
core="$b/engine/libspektralab_core.a"
cxx="${CXX:-c++}"
mkdir -p "$build"
[[ -f "$core" && -f "$constants" ]] || {
    echo "requires built spektralab_core and constants blob" >&2; exit 2;
}
sed 's/\r$//' "$baseline" > "$build/baseline_cam16.cpp"
expected=dcdd8f4188e330c855c57978980fcf5b0d200fd585a595d6ceadb61ed76d65cd
actual="$(sha256sum "$build/baseline_cam16.cpp" | cut -d ' ' -f1)"
[[ "$actual" == "$expected" ]] || {
    echo "baseline SHA256 mismatch: expected=$expected actual=$actual" >&2; exit 2;
}
printf 'baseline_lf_sha256=%s\n' "$actual"
sha256sum "$up/engine/src/core/cam16.cpp" "$core" "$constants"
"$cxx" --version | head -1
includes=(-I"$up/engine/src" -I"$up/engine/src/core")
renames=(-Dcam16_setup=baseline_cam16_setup -Dxyz_to_cam16ucs=baseline_xyz_to_cam16ucs
         -Dcam16ucs_to_xyz=baseline_cam16ucs_to_xyz -Dcam16_setup_for=baseline_cam16_setup_for
         -Dreinhard_knee=baseline_reinhard_knee)
for mode in strict default; do
    flags=(-std=c++20 -O3 -DNDEBUG -fPIC -pthread)
    [[ "$mode" != strict ]] || flags+=(-ffp-contract=off)
    printf '\nmode=production_source_%s flags=' "$mode"
    printf '%s ' "${flags[@]}"
    printf '\n'
    "$cxx" "${flags[@]}" "${includes[@]}" "${renames[@]}" \
        -c "$build/baseline_cam16.cpp" -o "$build/baseline-$mode.o"
    "$cxx" "${flags[@]}" "${includes[@]}" \
        -c "$up/engine/src/core/cam16.cpp" -o "$build/production-$mode.o"
    # Explicit production TU supplies CAM16; all other operations come from
    # the existing production archive. Its CAM16 member is not extracted.
    "$cxx" "${flags[@]}" "${includes[@]}" "$repo/tools/native/cam16_setup.cpp" \
        "$build/baseline-$mode.o" "$build/production-$mode.o" "$core" \
        -o "$build/cam16-$mode"
    "$build/cam16-$mode" "$constants"
done
# Also exercise the actual CMake-built CAM16 object. The archive's own flags
# apply here; compiling this test with STRICT would not change those flags.
printf '\nmode=production_archive flags=existing_CMake_build\n'
"$cxx" -std=c++20 -O3 -DNDEBUG -pthread "${includes[@]}" \
    "$repo/tools/native/cam16_setup.cpp" "$build/baseline-default.o" "$core" \
    -o "$build/cam16-archive"
"$build/cam16-archive" "$constants"
