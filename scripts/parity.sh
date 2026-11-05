#!/usr/bin/env bash
# parity.sh -- the colour parity check against upstream's own Linux host, in one go.
#
#   scripts/parity.sh [workdir]        (default: <repo>/build/parity)
#
# Builds upstream's spektralab-host and this app's colour path (tools/parity/
# spk_android.cpp with app/src/main/cpp's output_transform, input_transform,
# layer2 and resize) against the same pinned, patched engine, renders the
# catalogue in tools/parity/host_parity.py through both, grades it and exits
# non-zero when a pass bar fails. docs/PARITY.md says what each case is and
# why the bars are where they are.
#
# Needs: Linux x64, a C++20 compiler, CMake >= 3.20, NASM, glslangValidator,
# Python 3.12+ with numpy, curl, git, unzip, and a Vulkan device (lavapipe is
# enough: apt install mesa-vulkan-drivers libvulkan1). Meson and Ninja come from
# a venv in the work directory. About 1.5 GB of disk, 40-60 minutes on 4 cores.
#
# Environment:
#   THREADS=2     lavapipe threads and build jobs (keep it low on a shared box)
#   ONLY="a b"    render only cases whose names contain one of these
#   FMA=1         also run tools/parity/fma_check.sh (the fma probe, about 2 min)
#   SAMPLES_DIR=  a directory already holding the CC0 RAWs (names with _ for
#                 spaces); files whose SHA-256 matches are linked, not downloaded
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
W="${1:-$repo/build/parity}"
THREADS="${THREADS:-2}"
mkdir -p "$W"
W="$(cd "$W" && pwd)"
UP="$W/upstream"
log() { echo "== $*" >&2; }

for tool in c++ cmake nasm glslangValidator python3 curl git unzip; do
    command -v "$tool" >/dev/null || { echo "parity.sh: $tool is missing" >&2; exit 2; }
done
python3 -c 'import numpy' 2>/dev/null || { echo "parity.sh: python3 needs numpy" >&2; exit 2; }

# --- 1. upstream at the pinned commit, with patches/*.patch -------------------
log "upstream"
bash "$repo/scripts/fetch-upstream.sh" "$UP" >/dev/null

# --- 2. the host's codec dependencies (upstream's own pinned setup scripts) ---
if [[ ! -x "$W/venv/bin/meson" ]]; then
    log "meson / ninja venv"
    python3 -m venv "$W/venv"
    "$W/venv/bin/pip" install -q meson==1.9.2 'ninja>=1.13,<1.14'
fi
# The venv's python3 comes first on PATH from here on, and the harness needs numpy.
"$W/venv/bin/python3" -c 'import numpy' 2>/dev/null || "$W/venv/bin/pip" install -q numpy
export PATH="$W/venv/bin:$PATH"
D="$W/deps"
if [[ ! -f "$D/.done" ]]; then
    log "codecs (export, input, lens)"
    bash "$UP/engine/setup-codecs.sh" "$D/export-codecs" >/dev/null
    python3 "$UP/engine/setup-input-codecs.py" --sources "$D/input-codecs" --build "$W/b-input" \
        --install "$D/input-codecs/install-linux" --generator Ninja --jobs "$THREADS"
    python3 "$UP/engine/setup-lens-codecs.py" --sources "$D/lens-codecs" --build "$W/b-lens" \
        --install "$D/lens-codecs/install-linux" --generator Ninja --jobs "$THREADS"
    touch "$D/.done"
fi

# --- 3. spektralab-host, the reference ----------------------------------------
B="$W/build-host"
log "spektralab-host"
cmake -S "$UP" -B "$B" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DSPEKTRALAB_BUILD_NATIVE_RAW=ON -DSPEKTRALAB_BUILD_HOST=ON -DSPEKTRALAB_REQUIRE_VULKAN=ON \
    -DSPEKTRALAB_STATIC_RUNTIME=ON -DSPEKTRALAB_DEPS_DIR="$UP/build/deps" \
    -DSPEKTRALAB_CODECS_DIR="$D/export-codecs" \
    -DSPEKTRALAB_INPUT_CODEC_PREFIX="$D/input-codecs/install-linux" \
    -DSPEKTRALAB_LENS_PREFIX="$D/lens-codecs/install-linux" \
    -DBUILD_TESTING=OFF -DSPEKTRALAB_HOST_VERSION=parity >/dev/null
ninja -C "$B" -j "$THREADS" spektralab-host >/dev/null
R="$B/engine/resources"

# --- 4. this app's side --------------------------------------------------------
S="$W/src"
log "spk_android"
mkdir -p "$S"
cp "$repo"/tools/parity/{spk_android.cpp,host_parity.py,fma_check.sh,build.sh} "$S/"
for f in output_transform input_transform layer2 resize; do
    cp "$repo/app/src/main/cpp/$f.hpp" "$repo/app/src/main/cpp/$f.cpp" "$S/"
done
# layer2.cpp's masks and dust (header only).
cp "$repo/app/src/main/cpp/layer2_local.hpp" "$S/"
UP="$UP" B="$B" SRC="$S" bash "$S/build.sh" >/dev/null

# --- 5. the CC0 RAWs (docs/SAMPLES.md), checked against their SHA-256 ----------
RAW="$W/raw"
mkdir -p "$RAW"
log "samples"
sum() { sha256sum "$1" | cut -d' ' -f1; }
while read -r sha url; do
    name="$(basename "$url")"; file="$RAW/${name// /_}"
    [[ -f "$file" && "$(sum "$file")" == "$sha" ]] && continue
    # A verified copy already on this machine (SAMPLES_DIR) is linked, not fetched.
    # tools/samples/regress.sh names its copies <raw.pixls.us id>-<file>, since
    # several files share a name (two Nikon Z 7 samples here and there).
    id="${url#*/getfile.php/}"; id="${id%%/*}"
    linked=0
    for local_copy in "${SAMPLES_DIR:-}/$id-${name// /_}" "${SAMPLES_DIR:-}/${name// /_}"; do
        if [[ -n "${SAMPLES_DIR:-}" && -f "$local_copy" && "$(sum "$local_copy")" == "$sha" ]]; then
            ln -sf "$local_copy" "$file"; linked=1; break
        fi
    done
    (( linked )) && continue
    # raw.pixls.us sometimes ends a transfer early with no error; resume it.
    for attempt in 1 2 3 4 5; do
        curl -fsSL --retry 3 -C - -o "$file.part" "${url// /%20}" || true
        [[ -f "$file.part" && "$(sum "$file.part")" == "$sha" ]] && break
    done
    [[ "$(sum "$file.part")" == "$sha" ]] || { echo "checksum mismatch: $name" >&2; exit 1; }
    mv "$file.part" "$file"
done <<'LIST'
f7b2a6b6d7684536511cf2db2e2bfa46fae1ad7e52214093714f35e7ca60acbf https://raw.pixls.us/getfile.php/1797/nice/Leica - Q (Typ 116) - 14bit (3:2).DNG
10a1116bd00e92314b09382b403f76706b1eeab3ce33234a6c75fe25ed289756 https://raw.pixls.us/getfile.php/4691/nice/Canon - EOS R5 - RAW, 1.6 crop, dual-pixel (3:2).CR3
05513ee72f7cac4165534c9f64995412084f414a033c80c473c79fd94154292e https://raw.pixls.us/getfile.php/3115/nice/Ricoh - GR III - 14bit (3:2).DNG
5541093a369f0487fef92bcd1f5fef1be6e4e3cec2aa4d52ad38d2211f3309d7 https://raw.pixls.us/getfile.php/2206/nice/Google - Pixel 2 XL - 16bit (4:3).dng
e54a6c4264bf685214f538036fd9d39088e510b2bfedf288e568f0db1897880e https://raw.pixls.us/getfile.php/2789/nice/Nikon - Z 7 - 12bit 12bit compressed (3:2).NEF
bca8ad027b0ee0038107c01792ccb5c6b776512b77b584c6f93c6f2ced5f15a3 https://raw.pixls.us/getfile.php/1936/nice/Fujifilm - X100F - 14bit 14bit compressed (3:2).RAF
845fee4f09c832af3728778737f5ae9b99124debb821b6e2066b94aed1daa91c https://raw.pixls.us/getfile.php/915/nice/Apple - iPhone 6s Plus - 16bit (4:3).DNG
56faa7db3947c32c5e6e31bc0db027ebf95163533e043bd8c4f18166a129692c https://raw.pixls.us/getfile.php/1201/nice/Canon - EOS Rebel SL1 - RAW (3:2).CR2
LIST

# --- 6. render, grade, check ----------------------------------------------------
OUT="$W/run"
log "render (graded and pruned case by case)"
only=(); [[ -n "${ONLY:-}" ]] && only=(--only $ONLY)
(cd "$W" && nice python3 "$S/host_parity.py" render --host "$B/engine/host/spektralab-host" --resources "$R" \
    --android "$S/spk_android" --up "$UP" --raw "$RAW" --out "$OUT" --threads "$THREADS" --prune "${only[@]}")
log "grade"
status=0
python3 "$S/host_parity.py" grade "$OUT" --table "$OUT/table.md" --check || status=$?

if [[ "${FMA:-0}" == 1 ]]; then
    log "fma probe"
    (cd "$W" && UP="$UP" B="$B" A="$S/spk_android" R="$R" IN="$OUT" OUT="$W/fma" \
        LP_NUM_THREADS="$THREADS" bash "$S/fma_check.sh") | tee "$W/fma.txt"
    grep -q "GATE FAIL\|RENDER FAIL" "$W/fma.txt" && status=1
fi
echo "results: $OUT/grade.json, $OUT/table.md" >&2
exit "$status"
