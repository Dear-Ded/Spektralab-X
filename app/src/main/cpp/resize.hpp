#pragma once

#include <cstdint>
#include <vector>

namespace spk::android {

// Area-average downscale of a packed RGB float image. Both passes run on all
// cores. `dst` is resized to nw * nh * 3.
void resize_area_rgb(const std::vector<float>& src, uint32_t w, uint32_t h,
                     uint32_t nw, uint32_t nh, std::vector<float>& dst);

}  // namespace spk::android
