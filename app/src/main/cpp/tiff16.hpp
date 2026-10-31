#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace spk::android {

// Uncompressed little-endian RGB16 classic TIFF, top-left orientation, with
// `icc` embedded as tag 34675 when non-empty. `rgba` is the engine's RGBA16
// result; alpha is dropped. Overwrites `path`.
bool write_tiff16(const std::string& path, const uint16_t* rgba, uint32_t width, uint32_t height,
                  uint32_t row_stride_px, const std::vector<uint8_t>& icc, std::string& error);

}  // namespace spk::android
