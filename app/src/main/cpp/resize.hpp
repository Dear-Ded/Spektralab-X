#pragma once

#include <cstdint>
#include <cstddef>
#include <functional>
#include <vector>

namespace spk::android {

void fit_rgb_size(uint32_t w, uint32_t h, uint32_t max_edge, uint64_t max_pixels,
                  uint32_t& nw, uint32_t& nh);

// Area-average downscale of a packed RGB float image, preserving the two-pass
// float arithmetic. Up to eight workers cache four horizontal rows each instead
// of a full nw * h intermediate. `dst` is resized to nw * nh * 3; aliasing is OK.
// Nonzero dimensions, nw <= w, nh <= h and src.size() == w * h * 3 are required.
void resize_area_rgb(const std::vector<float>& src, uint32_t w, uint32_t h,
                     uint32_t nw, uint32_t nh, std::vector<float>& dst);

// Co-created with GPT (OpenAI). Converts borrowed native-endian RGB16 one row
// at a time using float(double(code) / 65535.0), then the same area arithmetic.
// No full-size float source is allocated. Cancellation (or a throwing poll)
// returns false after workers join and leaves dst unchanged. Polls are serial.
bool resize_area_rgb16(const uint8_t* src, size_t bytes, uint32_t w, uint32_t h,
                       uint32_t nw, uint32_t nh, std::vector<float>& dst,
                       const std::function<bool()>& cancelled = {});

}  // namespace spk::android
