#include "float_frame_check.hpp"

#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>

using spk::android::FloatFrameCheck;

static void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}

static std::string check(const std::vector<float>& rgb, uint32_t w, uint32_t h,
                         uint32_t strip, size_t stride) {
    FloatFrameCheck frame;
    for (uint32_t y = 0; y < h; y += strip) {
        if (!frame.append(rgb.data() + size_t(y) * stride * 3, stride, w, h,
                          y, std::min(strip, h - y))) break;
    }
    return frame.finish();
}

int main() {
    try {
        constexpr uint32_t w = 773, h = 1031;
        constexpr size_t stride = w + 7;
        std::vector<float> rgb(stride * h * 3, std::numeric_limits<float>::quiet_NaN());
        for (uint32_t y = 0; y < h; ++y)
            for (uint32_t x = 0; x < w; ++x)
                for (int c = 0; c < 3; ++c)
                    rgb[(size_t(y) * stride + x) * 3 + c] =
                        float(int((x * 17 + y * 29 + c * 31) % 1021) - 200) / 300;
        for (uint32_t strip : {1u, 7u, 32u, 127u, h})
            require(check(rgb, w, h, strip, stride).empty(), "signed/HDR or stride regression");

        // A sampled pixel after several strip boundaries must still fail.
        const size_t sample = (size_t(132) * stride + 18) * 3;
        const float saved = rgb[sample];
        for (float invalid : {std::numeric_limits<float>::quiet_NaN(),
                              std::numeric_limits<float>::infinity()}) {
            rgb[sample] = invalid;
            for (uint32_t strip : {1u, 7u, 32u, 127u, h})
                require(check(rgb, w, h, strip, stride) == "non-finite float pixels",
                        "sample grid changed across strips");
        }
        rgb[sample] = saved;

        std::fill(rgb.begin(), rgb.end(), 2.5f);
        rgb[(size_t(1028) * stride + 18) * 3] = 3.5f;
        require(check(rgb, w, h, 32, stride).empty(), "flat strips rejected before the whole frame");
        rgb[(size_t(1028) * stride + 18) * 3] = 2.5f;
        for (uint32_t strip : {1u, 7u, 32u, 127u, h})
            require(check(rgb, w, h, strip, stride) == "the float frame is one solid colour",
                    "flat frame not rejected");
        FloatFrameCheck missing;
        require(missing.finish() == "no float pixels", "empty frame accepted");
        require(missing.append(rgb.data(), stride, w, h, 0, 32), "first strip rejected");
        require(missing.finish() == "incomplete float frame", "partial frame accepted");
        require(!missing.append(rgb.data(), stride, w, h, 33, 32), "gap accepted");
        require(missing.finish() == "invalid float strips", "strip error not retained");
        FloatFrameCheck resized;
        require(resized.append(rgb.data(), stride, w, h, 0, 32), "first strip rejected");
        require(!resized.append(rgb.data(), stride, w - 1, h, 32, 32), "resized strip accepted");
        FloatFrameCheck overflow;
        require(!overflow.append(rgb.data(), std::numeric_limits<size_t>::max(), w, h, 0, 32),
                "stride overflow accepted");
        std::cout << "float frame check: full/strip grid, HDR, NaN, flat, incomplete and invalid strips PASS\n";
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
