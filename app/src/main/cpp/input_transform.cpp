#include "input_transform.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace spk::android {
namespace upstream {
// Trc, RgbSpace, srgb_space, display_p3_space, linear_prophoto_space,
// invert3 and mul3, verbatim.
#include "upstream_input_math.inc"
}  // namespace upstream

namespace {
const upstream::RgbSpace& space_of(bool p3) {
    static const upstream::RgbSpace srgb = upstream::srgb_space(), display_p3 = upstream::display_p3_space();
    return p3 ? display_p3 : srgb;
}
}  // namespace

InputTransform::InputTransform(const std::string& space) : p3_(space == "Display P3") {
    const upstream::RgbSpace& s = space_of(p3_);
    // encoded_to_linear_prophoto's matrix and its per-code curve tables.
    m_ = upstream::mul3(upstream::invert3(upstream::linear_prophoto_space().to_xyz_d50), s.to_xyz_d50);
    for (int c = 0; c < 3; ++c)
        for (int i = 0; i < 256; ++i) lut_[c][i] = float(s.trc[c].decode(double(i) / 255));
}

float InputTransform::decode(float v, int c) const { return float(space_of(p3_).trc[c].decode(v)); }

void InputTransform::to_prophoto(float rgb[3]) const {
    const double l[3] = {rgb[0], rgb[1], rgb[2]};
    for (int r = 0; r < 3; ++r) rgb[r] = float(m_[r * 3] * l[0] + m_[r * 3 + 1] * l[1] + m_[r * 3 + 2] * l[2]);
}

}  // namespace spk::android
