#!/usr/bin/env bash
# Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
set -euo pipefail
: "${UP:?candidate upstream required}" "${B:?candidate engine build required}"
: "${BUILD_DIR:?isolated evidence directory required}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$BUILD_DIR"
flags=(-std=c++20 -O2 -ffp-contract=off -pthread)
[[ "${SANITIZE:-0}" == 0 ]] || flags+=(-g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie)
compile() {
  local up=$1 b=$2 name=$3
  shift 3
  "${CXX:-c++}" "${flags[@]}" -DSPK_VULKAN_BACKEND=1 \
    -I"$up/engine/include" -I"$up/engine/src" -I"$up/engine/src/core" \
    -I"$up/engine/tests" -I"$b/engine/generated" "$@" \
    -I"$ROOT/app/src/main/cpp" \
    "$ROOT/tools/native/finite_output.cpp" -Wl,--start-group \
    "$b/engine/libspektralab_engine.a" "$b/engine/libspektralab_core.a" \
    "$b/engine/libspk_volk.a" -Wl,--end-group -ldl -o "$BUILD_DIR/$name"
}
if [[ -n "${BASELINE_UP:-}" || -n "${BASELINE_B:-}" ]]; then
  : "${BASELINE_UP:?pre-0054 upstream required}" "${BASELINE_B:?pre-0054 engine build required}"
  compile "$BASELINE_UP" "$BASELINE_B" baseline
fi
compile "$UP" "$B" candidate -DSPK_EXPECT_INTEGRITY=1
# The 30 MP case uses the existing measured range on the cloud CPU backend.
# The production helper never changes hardware/guest storage limits.
export SPEKTRAFILM_VULKAN_STRICT_LIMITS=0
if [[ -n "${BASELINE_UP:-}" ]]; then
  "$BUILD_DIR/baseline" "$BASELINE_B/engine/resources/vulkan" baseline \
    > "$BUILD_DIR/baseline.csv" 2> "$BUILD_DIR/baseline.log"
fi
"$BUILD_DIR/candidate" "$B/engine/resources/vulkan" candidate \
  > "$BUILD_DIR/candidate.csv" 2> "$BUILD_DIR/candidate.log"
if [[ -n "${BASELINE_UP:-}" ]]; then
  awk -F, '$1==0 {print}' "$BUILD_DIR/baseline.csv" > "$BUILD_DIR/healthy-before.csv"
  awk -F, '$1==0 {print}' "$BUILD_DIR/candidate.csv" > "$BUILD_DIR/healthy-after.csv"
  cmp "$BUILD_DIR/healthy-before.csv" "$BUILD_DIR/healthy-after.csv"
  cat "$BUILD_DIR/baseline.log"
  printf 'PASS unchanged healthy RGBA16, view and proof bytes\n'
fi
spirv-val --target-env vulkan1.0 "$B/engine/resources/vulkan/spk_validate_finite.spv"
# Inject an omitted workgroup into a derived shader, keeping production intact.
mkdir -p "$BUILD_DIR/dropped-workgroup"
cp "$B/engine/resources/vulkan/"*.spv "$BUILD_DIR/dropped-workgroup/"
sed '/void main() {/a\    if (gl_WorkGroupID.x == 1u) return;' \
  "$UP/engine/src/shaders/vulkan/spk_validate_finite.comp" > "$BUILD_DIR/dropped-workgroup.comp"
glslangValidator -V --target-env vulkan1.0 "$BUILD_DIR/dropped-workgroup.comp" \
  -o "$BUILD_DIR/dropped-workgroup/spk_validate_finite.spv" > "$BUILD_DIR/dropped-workgroup-build.log" 2>&1
spirv-val --target-env vulkan1.0 "$BUILD_DIR/dropped-workgroup/spk_validate_finite.spv"
set +e
"$BUILD_DIR/candidate" "$BUILD_DIR/dropped-workgroup" candidate \
  > "$BUILD_DIR/dropped-workgroup.csv" 2> "$BUILD_DIR/dropped-workgroup.log"
status=$?
set -e
if [[ $status != 1 ]] || ! grep -qF 'render integrity: incomplete GPU validation (3113/3114 groups)' \
    "$BUILD_DIR/dropped-workgroup.log"; then
  cat "$BUILD_DIR/dropped-workgroup.log" >&2
  printf 'dropped-workgroup negative control failed (exit %s)\n' "$status" >&2
  exit 1
fi
sha256sum "$ROOT/tools/native/finite_output.cpp" "$UP/engine/src/pipeline/finite_output.hpp" \
  "$UP/engine/src/shaders/vulkan/spk_validate_finite.comp" \
  "$B/engine/resources/vulkan/spk_validate_finite.spv" > "$BUILD_DIR/inputs.sha256"
cat "$BUILD_DIR/candidate.log"
cat "$BUILD_DIR/dropped-workgroup.log"
printf 'PASS finite-output fault rejection\n'
