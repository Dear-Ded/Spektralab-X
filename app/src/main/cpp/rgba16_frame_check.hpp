#pragma once
// Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>

namespace spk::android {

// Engine RGBA16 output is always opaque. Check every alpha before Bitmap
// packing replaces it with 255, hiding incomplete writes/readback from export.
inline std::string check_rgba16_frame(const uint16_t* rgba, uint32_t width,
                                      uint32_t height, size_t stride_px) {
    if (!rgba || !width || !height) return "no pixels";
    if (stride_px < width ||
        stride_px > std::numeric_limits<size_t>::max() / sizeof(uint16_t) / 4 / height)
        return "invalid RGBA16 frame layout";
    for (uint32_t y = 0; y < height; ++y) {
        const uint16_t* row = rgba + size_t(y) * stride_px * 4;
        for (uint32_t x = 0; x < width; ++x) {
            if (row[size_t(x) * 4 + 3] != 65535)
                return "invalid RGBA16 alpha at (" + std::to_string(x) + ", " +
                    std::to_string(y) + "): " + std::to_string(row[size_t(x) * 4 + 3]);
        }
    }
    const uint32_t sx = std::max(1u, width / 256), sy = std::max(1u, height / 256);
    uint16_t lo[3] = {65535, 65535, 65535}, hi[3] = {0, 0, 0};
    for (uint64_t y = 0; y < height; y += sy) {
        const uint16_t* row = rgba + size_t(y) * stride_px * 4;
        for (uint64_t x = 0; x < width; x += sx)
            for (unsigned c = 0; c < 3; ++c) {
                const uint16_t v = row[size_t(x) * 4 + c];
                lo[c] = std::min(lo[c], v);
                hi[c] = std::max(hi[c], v);
            }
    }
    constexpr uint16_t flat = 16;
    if (hi[0] <= flat && hi[1] <= flat && hi[2] <= flat) return "the frame is completely black";
    if (lo[0] >= 65535 - flat && lo[1] >= 65535 - flat && lo[2] >= 65535 - flat)
        return "the frame is completely white";
    if (hi[0] - lo[0] <= flat && hi[1] - lo[1] <= flat && hi[2] - lo[2] <= flat)
        return "the frame is one solid colour (" + std::to_string(lo[0]) + ", " +
            std::to_string(lo[1]) + ", " + std::to_string(lo[2]) + ")";
    return {};
}

} // namespace spk::android
