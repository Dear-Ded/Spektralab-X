#!/usr/bin/env bash
# fetch-upstream.sh -- the SpektraLab sources this app builds, pinned.
#
#   scripts/fetch-upstream.sh [dest]        (default: <repo>/upstream)
#
# - SpektraLab, branch Windows_Linux at UPSTREAM_COMMIT (codeload tarball).
# - LibRaw 0.22.2, zip checked against the SHA-256 upstream's setup-libraw.sh pins.
# - Khronos Vulkan-Headers, tag vulkan-sdk-1.4.341.0, checked against upstream's
#   commit pin.
# Both dependencies land in <dest>/build/deps, where upstream's CMake expects them.
set -euo pipefail

UPSTREAM_COMMIT=a46627d250545a394ca21522f8a28b0fb89e4c42
LIBRAW_VERSION=0.22.2
LIBRAW_SHA256=02275a04cf0d1477ab9fd7ee231ddea59e7b14497458da6e3ba957517d6655c7
VK_TAG=vulkan-sdk-1.4.341.0
VK_COMMIT=b5c8f996196ba4aa6d8f97e52b5d3b6e70f7e4e2

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dest="${1:-$here/../upstream}"
mkdir -p "$dest"
dest="$(cd "$dest" && pwd)"

if [[ -f "$dest/.spk-commit" && "$(cat "$dest/.spk-commit")" == "$UPSTREAM_COMMIT" ]]; then
  echo "upstream $UPSTREAM_COMMIT already present in $dest" >&2
else
  tmp="$(mktemp -d)"
  curl -fsSL --retry 3 -o "$tmp/src.tar.gz" \
    "https://codeload.github.com/JamesQiu2005/SpektraLab/tar.gz/$UPSTREAM_COMMIT"
  find "$dest" -mindepth 1 -maxdepth 1 ! -name build -exec rm -rf {} +
  tar -xzf "$tmp/src.tar.gz" --strip-components=1 -C "$dest"
  rm -rf "$tmp"
  echo "$UPSTREAM_COMMIT" > "$dest/.spk-commit"
  echo "upstream $UPSTREAM_COMMIT extracted to $dest" >&2
fi

deps="$dest/build/deps"
mkdir -p "$deps"

if [[ ! -f "$deps/LibRaw-$LIBRAW_VERSION/libraw/libraw.h" ]]; then
  zip="$deps/LibRaw-$LIBRAW_VERSION.zip"
  curl -fsSL --retry 3 -o "$zip" "https://codeload.github.com/LibRaw/LibRaw/zip/refs/tags/$LIBRAW_VERSION"
  actual="$(sha256sum "$zip" | cut -d' ' -f1)"
  if [[ "$actual" != "$LIBRAW_SHA256" ]]; then
    echo "LibRaw archive checksum mismatch ($actual)" >&2
    exit 1
  fi
  (cd "$deps" && unzip -q "$zip")
  echo "LibRaw $LIBRAW_VERSION verified and extracted" >&2
fi

if [[ ! -f "$deps/Vulkan-Headers/include/vulkan/vulkan_core.h" ]]; then
  rm -rf "$deps/Vulkan-Headers"
  git -c advice.detachedHead=false clone -q --depth 1 --branch "$VK_TAG" \
    https://github.com/KhronosGroup/Vulkan-Headers.git "$deps/Vulkan-Headers"
  got="$(git -C "$deps/Vulkan-Headers" rev-parse HEAD)"
  if [[ "$got" != "$VK_COMMIT" ]]; then
    echo "Vulkan-Headers $VK_TAG is $got, expected $VK_COMMIT" >&2
    exit 1
  fi
  echo "Vulkan-Headers $VK_TAG verified" >&2
fi

echo "$dest"
