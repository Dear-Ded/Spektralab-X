#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-11. See NOTICE. No APK build.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
up="${UP:?patched upstream tree}"
b="${B:?existing host engine build}"
build="${BUILD_DIR:?isolated output directory}"
icc="${ADOBE_ICC:?profile emitted by production Kotlin IccProfile.generateAdobeRgb}"
mkdir -p "$build"
python3 - "$up/engine/host/output.cpp" "$build/upstream_output_math.inc" <<'PY'
import pathlib,sys
s=pathlib.Path(sys.argv[1]).read_text()
parts=[]
for start,end in [('static inline float spow','bool OutputTransform::fetch'),('static inline void cam16ucs_into','template <typename F>')]:
    a,z=s.index(start),s.index(end)
    assert a<z
    parts.append(s[a:z])
pathlib.Path(sys.argv[2]).write_text('\n'.join(parts))
PY
app="$repo/app/src/main/cpp"
e="$b/engine"
"${CXX:-c++}" -std=c++20 -O2 -pthread -ffp-contract=off -I"$app" -I"$build" -I"$up/engine/include" -I"$up/engine/src" \
    "$repo/tools/native/adobe_tiff.cpp" "$app/output_transform.cpp" "$app/raw_development.cpp" "$app/layer2.cpp" "$app/tiff16.cpp" \
    -Wl,--start-group "$e/libspektralab_engine.a" "$e/libspektralab_core.a" "$e/libspk_volk.a" -Wl,--end-group -ldl -lz -o "$build/adobe_tiff"
"$build/adobe_tiff" "$e/resources" "$icc" "$build"
python3 "$repo/tools/native/adobe_tiff_cmm.py" --icc "$icc" --directory "$build" ${LCMS_LIBRARY:+--lcms "$LCMS_LIBRARY"}
