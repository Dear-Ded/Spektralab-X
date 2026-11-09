#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>

namespace spk::android {

// Uses the same full-frame sampling grid for a whole image or ordered strips.
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
        const uint32_t sx = std::max(1u, width / 256);
        const uint32_t sy = std::max(1u, height / 256);
        const uint64_t end = uint64_t(y0) + count;
        const uint64_t first = uint64_t(y0) + (sy - y0 % sy) % sy;
        for (uint64_t y = first; y < end; y += sy) {
            const float* row = rgb + size_t(y - y0) * stride_px * 3;
            for (uint64_t x = 0; x < width; x += sx) {
                for (int c = 0; c < 3; ++c) {
                    const float value = row[size_t(x) * 3 + c];
                    if (!std::isfinite(value)) {
                        error_ = "non-finite float pixels";
                        return false;
                    }
                    lo_[c] = std::min(lo_[c], value);
                    hi_[c] = std::max(hi_[c], value);
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
        constexpr float flat = 16.0f / 65535.0f;
        if (hi_[0] - lo_[0] <= flat && hi_[1] - lo_[1] <= flat && hi_[2] - lo_[2] <= flat)
            return "the float frame is one solid colour";
        return {};
    }

private:
    uint32_t width_ = 0, height_ = 0, next_y_ = 0;
    float lo_[3] = {INFINITY, INFINITY, INFINITY};
    float hi_[3] = {-INFINITY, -INFINITY, -INFINITY};
    std::string error_;
};

} // namespace spk::android
