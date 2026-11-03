// input_transform.hpp -- a phone photo into the engine's input space, as upstream does.
//
// Upstream's desktop host never asks the engine to decode a display-encoded
// file. Its image_io.cpp (`encoded_to_linear_prophoto`) takes the profile's
// curve off and its D50 colorants through XYZ into linear ProPhoto, and opens
// the engine on that. Handing the codes to the engine as "sRGB" or "Display
// P3" instead adapts their D65 white with CAT02 rather than the profile's
// Bradford colorants, which moved every print of a JPEG by a mean of
// 0.2-0.36 ΔE00 against the host (docs/PARITY.md).
//
// The colour spaces and the curve are upstream's own code (engine/host/
// image_io.*), copied in at configure time (CMakeLists.txt); the arithmetic
// below is the host's, in the host's precision.
#pragma once

#include <array>
#include <cstdint>
#include <string>

namespace spk::android {

class InputTransform {
public:
    // `space` is "Display P3" or "sRGB"; anything else is sRGB, the host's
    // assumption for an untagged file.
    explicit InputTransform(const std::string& space);

    // An 8-bit code of channel `c`, decoded by the profile's curve.
    float decode(uint8_t code, int c) const { return lut_[c][code]; }
    // A value in 0..1 (an unpremultiplied code), decoded.
    float decode(float v, int c) const;
    // Linear in the photo's primaries -> linear ProPhoto, in place.
    void to_prophoto(float rgb[3]) const;

private:
    bool p3_ = false;
    std::array<std::array<float, 256>, 3> lut_{};
    std::array<double, 9> m_{};
};

}  // namespace spk::android
