#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>

namespace spk::android {

// Inspect every valid pixel, retaining only extrema across ordered strips.
// Check before grading: signed/HDR values can still recover useful detail.
class FloatFrameCheck {
public:
    bool append(const float* rgb, size_t stride_px, uint32_t width, uint32_t height,
                uint32_t y0, uint32_t count) {
        if (!error_.empty()) return false;
        if (!rgb || !width || !height || !count || stride_px < width ||
            y0 != next_y_ || y0 > height || count > height - y0 ||
            stride_px > std::numeric_limits<size_t>::max() / sizeof(float) / 3 / count ||
            (width_ && (width != width_ || height != height_))) {
            error_ = "invalid float strips";
            return false;
        }
        width_ = width;
        height_ = height;
        constexpr float flat = 16.0f / 65535.0f;
        const uint64_t end = uint64_t(y0) + count;
        for (uint64_t y = y0; y < end; ++y) {
            const float* row = rgb + size_t(y - y0) * stride_px * 3;
            uint32_t x = 0;
            for (; !varied_ && x < width; ++x) {
                for (int c = 0; c < 3; ++c) {
                    const float value = row[size_t(x) * 3 + c];
                    if (!std::isfinite(value)) {
                        error_ = "non-finite float pixels";
                        return false;
                    }
                    lo_[c] = std::min(lo_[c], value);
                    hi_[c] = std::max(hi_[c], value);
                }
                varied_ = hi_[0] - lo_[0] > flat || hi_[1] - lo_[1] > flat || hi_[2] - lo_[2] > flat;
            }
            // Variation is permanent, but every remaining component must still be finite.
            for (; x < width; ++x) {
                for (int c = 0; c < 3; ++c) {
                    if (!std::isfinite(row[size_t(x) * 3 + c])) {
                        error_ = "non-finite float pixels";
                        return false;
                    }
                }
            }
        }
        next_y_ += count;
        return true;
    }

    std::string finish() const {
        if (!error_.empty()) return error_;
        if (!width_ || !height_) return "no float pixels";
        if (next_y_ != height_) return "incomplete float frame";
        return varied_ ? std::string{} : "the float frame is one solid colour";
    }

private:
    uint32_t width_ = 0, height_ = 0, next_y_ = 0;
    float lo_[3] = {INFINITY, INFINITY, INFINITY};
    float hi_[3] = {-INFINITY, -INFINITY, -INFINITY};
    bool varied_ = false;
    std::string error_;
};

} // namespace spk::android
