#!/usr/bin/env bash
# fma_check.sh -- what the engine does on a GPU whose fma is not fused
# (Adreno / Mali demote GLSL fma() to a multiply and an add), checked on
# lavapipe, which does the same (LLVM 19.1.7, measured 2026-11-03: the probe's
# residual comes back 0 where binary64 says 7.45e-11).
#
#   UP=<upstream tree> B=<upstream build dir> A=<spk_android> R=<engine resources> \
#   IN=<run dir with inputs/> OUT=<scratch dir> fma_check.sh
#
# 1. The startup gate (vulkan_gpu.cpp probe_math_mode) against upstream's own
#    fault-injected probes (engine/tests/math_gate_fault.comp, the fixtures
#    engine/CMakeLists.txt builds for its spk_math_gate_* tests): an unfused
#    residual (all zero), a mixed one, a broken split product, a missing split
#    probe, and STRICT_FMA. The first two must start on the Dekker split
#    product; the rest must refuse, and stay refused.
# 2. The picture: the same scenes through the real engine resources, once as
#    shipped and once with spk_math_probe.spv replaced by the unfused fixture.
#    On a device whose own probe already reports unfused (lavapipe) both arms
#    take the split product and must be bit-identical; on a GPU with a fused
#    fma this arm measures fused against split. Halation is on (the IIR blur,
#    one of the two kernels that use two_prod) and one case rotates the frame
#    (geometry resample, the other). Grain and glare are off (they are random).
# 3. What the gate protects against (below).
set -euo pipefail
UP="${UP:?upstream tree}"; B="${B:?upstream build dir}"; A="${A:?spk_android}"; R="${R:?engine resources}"
IN="${IN:?run dir holding inputs/}"; OUT="${OUT:?scratch dir}"
HERE="$(cd "$(dirname "$0")" && pwd)"
E="$B/engine"
mkdir -p "$OUT"

# --- 1. the gate -------------------------------------------------------------
openmp=""
grep -q 'SPEKTRALAB_LIBRAW_OPENMP:BOOL=ON' "$B/CMakeCache.txt" && openmp="-fopenmp"
c++ -std=c++20 -O2 $openmp -I"$UP/engine/src" -I"$UP/engine/include" "$UP/engine/tests/gpu_vulkan_math_gate.cpp" \
    -Wl,--start-group "$E/libspektralab_engine.a" "$E/libspektralab_core.a" "$E/libspk_volk.a" -Wl,--end-group \
    -lpthread -ldl -o "$OUT/math_gate"
fault="$UP/engine/tests/math_gate_fault.comp"
for kind in unfused mixed broken missing; do
    d="$OUT/gate/$kind"; mkdir -p "$d"
    mixed=0; [ "$kind" = mixed ] && mixed=1
    glslangValidator -V --target-env vulkan1.0 -DPAIR=0 -DMIXED=$mixed -o "$d/spk_math_probe.spv" "$fault" >/dev/null
    case "$kind" in
        broken) glslangValidator -V --target-env vulkan1.0 -DPAIR=1 -DMIXED=0 -o "$d/spk_split_probe.spv" "$fault" >/dev/null ;;
        missing) ;;
        *) cp "$R/vulkan/spk_split_probe.spv" "$d/" ;;
    esac
done
gate() {   # name env dir expect message
    local name="$1" env="$2" dir="$3" expect="$4" msg="$5" out
    if out=$(env $env "$OUT/math_gate" "$dir" "$expect" "$msg" 2>&1); then echo "GATE ok   $name: $out"
    else echo "GATE FAIL $name: $out"; fi
}
base="SPEKTRAFILM_VULKAN_FORCE_SPLIT=0 SPEKTRAFILM_VULKAN_STRICT_FMA=0"
# As shipped the gate picks whatever the device does. lavapipe (LLVM 19)
# demotes fma() to a multiply and an add, as Adreno / Mali do, so there it is
# the split product; a GPU with a fused fma reports "fused fma" instead.
gate shipped "$base" "$R/vulkan" pass "Vulkan probe passed"
gate unfused "$base" "$OUT/gate/unfused" pass "split product"
gate mixed "$base" "$OUT/gate/mixed" pass "split product"
gate broken "$base" "$OUT/gate/broken" fail "split-product probe"
gate missing "$base" "$OUT/gate/missing" fail "split-product probe"
gate strict "SPEKTRAFILM_VULKAN_FORCE_SPLIT=1 SPEKTRAFILM_VULKAN_STRICT_FMA=1" "$OUT/gate/unfused" fail "STRICT_FMA=1"
gate forced "SPEKTRAFILM_VULKAN_FORCE_SPLIT=1 SPEKTRAFILM_VULKAN_STRICT_FMA=0" "$R/vulkan" pass "forced split"

# --- 2. the picture ----------------------------------------------------------
# A resources copy whose probe reports an unfused fma, nothing else changed.
RU="$OUT/resources-unfused"
rm -rf "$RU"; cp -r "$R" "$RU"
cp "$OUT/gate/unfused/spk_math_probe.spv" "$RU/vulkan/spk_math_probe.spv"
OFF='"grain_active":false,"glare_active":false,"halation_active":true'
PH='"auto_exposure":true,"auto_exposure_method":"balanced"'
declare -A CASES=(
    [synth_portra]="f32 $IN/inputs/synth.f32 {\"auto_exposure\":false,\"film_stock\":\"kodak_portra_400\",\"print_stock\":\"kodak_supra_endura\",$OFF}"
    [fruit_cine800t]="f32 $IN/inputs/fruit.f32 {$PH,\"film_stock\":\"cinestill_800t\",\"print_stock\":\"kodak_2383\",$OFF}"
    [lake_velvia_rot]="f32 $IN/inputs/lake.f32 {$PH,\"film_stock\":\"fujifilm_velvia_100\",\"print_stock\":\"kodak_supra_endura\",\"scan_film\":true,\"geometry_rotation_deg\":7.5,$OFF}"
    [dunes_trix]="f32 $IN/inputs/dunes.f32 {$PH,\"film_stock\":\"kodak_tri_x_400\",\"print_stock\":\"ilford_multigrade_iv_rc\",\"c_filter_neutral\":0,\"m_filter_neutral\":0,\"y_filter_neutral\":68,$OFF}"
)
for name in "${!CASES[@]}"; do
    read -r kind input params <<<"${CASES[$name]}"
    for arm in fused split; do
        d="$OUT/pic/$name/$arm"; mkdir -p "$d"
        res="$R"; [ "$arm" = split ] && res="$RU"
        if ! SPEKTRAFILM_VULKAN_FORCE_SPLIT=0 "$A" "$res" "$kind" "$input" "$d" "$params" >"$d/log" 2>&1; then
            echo "RENDER FAIL $name/$arm: $(tail -c 300 "$d/log")"; continue 2
        fi
    done
done
python3 -I - "$OUT/pic" "$HERE" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[2])
import numpy as np
import host_parity as hp
root = Path(sys.argv[1])
for case in sorted(p for p in root.iterdir() if p.is_dir()):
    for f, space in (("print", None), ("export_srgb", "srgb"), ("export_p3", "p3"), ("view_srgb", "srgb")):
        a, _ = hp.read_rgb(case / "fused" / f"{f}.rgb")
        b, _ = hp.read_rgb(case / "split" / f"{f}.rgb")
        codes = np.abs(a - b) * 65535
        line = f"{case.name:<18} {f:<12} |d| max {codes.max():7.2f} mean {codes.mean():.4f} (16-bit codes)"
        if space:
            e = hp.ciede2000(hp.to_lab(a, space), hp.to_lab(b, space))
            line += f"  dE00 mean {e.mean():.4f} p99.9 {np.percentile(e, 99.9):.4f} max {e.max():.4f}"
        print(line)
PY
# --- 3. what the gate protects against ----------------------------------------
# A probe that lies: it returns the exact residual (from the split product), so
# the gate believes fma() is fused and the IIR / geometry kernels use it. On a
# device whose fma() is a multiply and an add (lavapipe, Adreno, Mali) that
# two_prod loses its low word: this arm is the precision a missing or wrong
# gate would silently cost, measured against the split-product arm above.
cat > "$OUT/lying_probe.comp" <<'GL'
#version 450
#extension GL_GOOGLE_include_directive : require
#include "spk_split_product.glsl"
layout(local_size_x = 1) in;
layout(set = 0, binding = 0, std430) readonly buffer Input { float value[]; } src;
layout(set = 0, binding = 1, std430) writeonly buffer Output { float value[]; } dst;
void main() { uint i = gl_GlobalInvocationID.x; dst.value[i] = spk_split_product(src.value[2u * i], src.value[2u * i + 1u]).y; }
GL
RL="$OUT/resources-lying"
rm -rf "$RL"; cp -r "$R" "$RL"
glslangValidator -V --target-env vulkan1.0 -I"$UP/engine/src/shaders/vulkan" \
    -o "$RL/vulkan/spk_math_probe.spv" "$OUT/lying_probe.comp" >/dev/null
for name in "${!CASES[@]}"; do
    read -r kind input params <<<"${CASES[$name]}"
    d="$OUT/pic/$name/lying"; mkdir -p "$d"
    "$A" "$RL" "$kind" "$input" "$d" "$params" >"$d/log" 2>&1 || echo "RENDER FAIL $name/lying: $(tail -c 300 "$d/log")"
done
python3 -I - "$OUT/pic" "$HERE" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[2])
import numpy as np
import host_parity as hp
for case in sorted(p for p in Path(sys.argv[1]).iterdir() if p.is_dir()):
    for f, space in (("print", None), ("export_srgb", "srgb"), ("view_srgb", "srgb")):
        a, _ = hp.read_rgb(case / "split" / f"{f}.rgb")
        b, _ = hp.read_rgb(case / "lying" / f"{f}.rgb")
        codes = np.abs(a - b) * 65535
        line = f"lying {case.name:<16} {f:<12} |d| max {codes.max():7.2f} mean {codes.mean():.4f} (16-bit codes)"
        if space:
            e = hp.ciede2000(hp.to_lab(a, space), hp.to_lab(b, space))
            line += f"  dE00 mean {e.mean():.4f} p99.9 {np.percentile(e, 99.9):.4f} max {e.max():.4f}"
        print(line)
PY
echo FMA_CHECK_DONE
