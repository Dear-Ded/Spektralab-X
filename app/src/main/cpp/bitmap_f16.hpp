#pragma once

#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <stdexcept>

namespace spk::android {

// V1 is transfer-encoded SDR sRGB/P3. Values outside [0,1] require a separate
// extended-range/HDR contract; they must never be silently clipped into V1.
enum class F16AlphaV1 : int { opaque = 0, premultiplied = 1, straight = 2 };

inline float bitmap_half_to_float(uint16_t half) noexcept {
    const uint32_t sign = uint32_t(half & 0x8000u) << 16;
    uint32_t exponent = (half >> 10) & 31u;
    uint32_t mantissa = half & 1023u;
    uint32_t bits;
    if (exponent == 0) {
        if (mantissa == 0) bits = sign;
        else {
            exponent = 113;
            while ((mantissa & 1024u) == 0) { mantissa <<= 1; --exponent; }
            bits = sign | (exponent << 23) | ((mantissa & 1023u) << 13);
        }
    } else if (exponent == 31) {
        bits = sign | 0x7f800000u | (mantissa << 13);
    } else {
        bits = sign | ((exponent + 112u) << 23) | (mantissa << 13);
    }
    return std::bit_cast<float>(bits);
}

struct F16LayoutV1 {
    size_t rgb_values = 0;
    size_t storage_bytes = 0;
    F16AlphaV1 alpha;

    F16LayoutV1(uint32_t width, uint32_t height, uint32_t stride, int alpha_mode) {
        if (!width || !height || uint64_t(width) * 8 > stride)
            throw std::invalid_argument("invalid F16 bitmap dimensions or row stride");
        if (alpha_mode < 0 || alpha_mode > 2)
            throw std::invalid_argument("unsupported F16 V1 alpha mode");
        const uint64_t values = uint64_t(width) * height * 3;
        const uint64_t bytes = uint64_t(stride) * height;
        if (values > std::numeric_limits<size_t>::max() / sizeof(float) ||
            bytes > std::numeric_limits<size_t>::max())
            throw std::length_error("F16 bitmap payload exceeds addressable storage");
        rgb_values = size_t(values);
        storage_bytes = size_t(bytes);
        alpha = static_cast<F16AlphaV1>(alpha_mode);
    }
};

// Unaligned pixel reads are valid. No allocation or exceptions on worker threads.
inline bool decode_f16_sdr_pixel_v1(const uint8_t* pixel, F16AlphaV1 mode, float rgb[3]) noexcept {
    uint16_t codes[4];
    std::memcpy(codes, pixel, sizeof codes);
    float alpha = 1.0f;
    if (mode != F16AlphaV1::opaque) {
        alpha = bitmap_half_to_float(codes[3]);
        if (!std::isfinite(alpha) || alpha < 0.0f || alpha > 1.0f) return false;
    }
    for (int c = 0; c < 3; ++c) {
        float value = bitmap_half_to_float(codes[c]);
        if (!std::isfinite(value) || value < 0.0f || value > 1.0f) return false;
        if (mode == F16AlphaV1::premultiplied) value = alpha == 0.0f ? 0.0f : value / alpha;
        if (!std::isfinite(value) || value > 1.0f) return false;
        rgb[c] = value;
    }
    return true;
}

}  // namespace spk::android
