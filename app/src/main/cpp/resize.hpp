#pragma once

#include <cstdint>
#include <vector>

namespace spk::android {

// Area-average downscale of a packed RGB float image, preserving the two-pass
// float arithmetic. Up to eight workers cache four horizontal rows each instead
// of a full nw * h intermediate. `dst` is resized to nw * nh * 3; aliasing is OK.
// Nonzero dimensions, nw <= w, nh <= h and src.size() == w * h * 3 are required.
void resize_area_rgb(const std::vector<float>& src, uint32_t w, uint32_t h,
                     uint32_t nw, uint32_t nh, std::vector<float>& dst);

}  // namespace spk::android
