#!/usr/bin/env bash
# stage-assets.sh -- the engine's run-time resources, as APK assets.
#
#   scripts/stage-assets.sh [upstream]      (default: <repo>/upstream)
#
# Mirrors upstream's spektralab_vulkan_resources target (engine/CMakeLists.txt):
# baked resources, product profiles over them, the DI view LUT and the Vulkan
# kernels compiled to SPIR-V. Output: app/src/main/assets/engine (gitignored).
# The app copies it to its files directory on first run, keyed by stamp.txt.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/.." && pwd)"
up="$(cd "${1:-$repo/upstream}" && pwd)"
engine="$up/engine"
out="$repo/app/src/main/assets/engine"
glslang="${GLSLANG:-$(command -v glslangValidator || true)}"
if [[ -z "$glslang" ]]; then
  echo "glslangValidator not found (install glslang-tools or set GLSLANG)" >&2
  exit 1
fi

rm -rf "$out"
mkdir -p "$out/vulkan" "$out/DI" "$out/licenses"
cp -R "$engine/resources/." "$out/"
cp -R "$engine/resources_product/profiles/." "$out/profiles/"
cp "$engine/resources_product/paper_grades.json" "$out/paper_grades.json"
cp "$engine/resources_product/neutral_print_filters.json" "$out/product-neutral-print-filters.json"
# engine.cpp reads only the ProPhoto view LUT.
cp "$up/modern_UI/Spektrafilm/Spektrafilm/Resources/DI/SpektraLab DI view, Cineon to ProPhoto RGB.cube" "$out/DI/"

cp "$up/license/LICENSE" "$out/licenses/LICENSE.txt"
cp "$up/license/SPEKTRAFILM_LICENSE.txt" "$out/licenses/SPEKTRAFILM_LICENSE.txt"
cp "$engine/resources_product/NOTICE.md" "$out/licenses/Product-profiles-NOTICE.md"
cp "$engine/resources_product/fonts/LICENSE" "$out/licenses/Imprint-fonts-LICENSE.txt"
cp "$engine/resources_product/fonts/DejaVu-LICENSE" "$out/licenses/Imprint-mono-font-DejaVu-LICENSE.txt"
cp "$engine/third_party/stb/LICENSE" "$out/licenses/stb-LICENSE.txt"
cp "$engine/third_party/volk/LICENSE.md" "$out/licenses/volk-LICENSE.md"
libraw="$up/build/deps/LibRaw-0.22.2"
cp "$libraw/LICENSE.LGPL" "$out/licenses/LibRaw-LICENSE.LGPL"
cp "$libraw/COPYRIGHT" "$out/licenses/LibRaw-COPYRIGHT.txt"

kernels="$(sed -n '/set(SPEKTRALAB_VULKAN_KERNELS/,/CACHE INTERNAL/p' "$engine/CMakeLists.txt" |
           grep -o 'spk_[a-z0-9_]*' | grep -v '^spk_$' | sort -u)"
count=0
for k in $kernels; do
  "$glslang" -V --target-env vulkan1.0 -I"$engine/src/shaders/vulkan" \
    -o "$out/vulkan/$k.spv" "$engine/src/shaders/vulkan/$k.comp" >/dev/null
  count=$((count + 1))
done
if [[ $count -lt 40 ]]; then
  echo "only $count Vulkan kernels found in engine/CMakeLists.txt; the list format changed" >&2
  exit 1
fi

# aapt2 skips dotfiles, so the stamp is a plain name.
# A hash of the staged tree, not the build time: identical inputs give an
# identical APK, and any change to the assets still re-installs them.
( cd "$out" && find . -type f ! -name stamp.txt -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -c1-32 ) > "$out/stamp.txt"
echo "staged $count kernels and resources in $out ($(du -sh "$out" | cut -f1))" >&2
