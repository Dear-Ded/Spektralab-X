// Co-created with GPT (OpenAI), 2026-11-10. Synthetic fixtures only.
#include "bitmap_f16.hpp"
#include "input_transform.hpp"

#include <array>
#include <iostream>
#include <set>
#include <vector>

using namespace spk::android;

static void require(bool ok, const char* message) {
    if (!ok) throw std::runtime_error(message);
}

static double reference_half(uint16_t code) {
    const int sign = code & 0x8000 ? -1 : 1;
    const int exponent = (code >> 10) & 31, mantissa = code & 1023;
    if (exponent == 31) return mantissa ? NAN : sign * INFINITY;
    return sign * (exponent ? std::ldexp(1.0 + mantissa / 1024.0, exponent - 15)
                                  : std::ldexp(double(mantissa), -24));
}

static std::array<uint8_t, 8> pixel(uint16_t r, uint16_t g, uint16_t b, uint16_t a) {
    const uint16_t values[] = {r, g, b, a};
    std::array<uint8_t, 8> out;
    std::memcpy(out.data(), values, sizeof values);
    return out;
}

int main() {
    try {
        for (uint32_t code = 0; code <= 65535; ++code) {
            const double expected = reference_half(uint16_t(code));
            const float actual = bitmap_half_to_float(uint16_t(code));
            if (std::isnan(expected)) require(std::isnan(actual), "half NaN classification");
            else {
                require(double(actual) == expected, "independent exhaustive half reference");
                if (expected == 0) require(std::signbit(actual) == std::signbit(expected), "signed zero");
            }
            const auto p = pixel(uint16_t(code), 0x3800, 0x3c00, 0x3c00);
            float rgb[3];
            const bool accepted = decode_f16_sdr_pixel_v1(p.data(), F16AlphaV1::straight, rgb);
            const bool expected_acceptance = std::isfinite(expected) && expected >= 0 && expected <= 1;
            require(accepted == expected_acceptance, "exhaustive V1 SDR domain");
            if (accepted) require(double(rgb[0]) == expected, "accepted half remains exact");
        }

        const auto p = pixel(0x3400, 0x3000, 0x3800, 0x3800);
        float rgb[3];
        require(decode_f16_sdr_pixel_v1(p.data(), F16AlphaV1::premultiplied, rgb), "premultiplied pixel");
        require(rgb[0] == 0.5f && rgb[1] == 0.25f && rgb[2] == 1.0f, "undo alpha before transfer");
        require(decode_f16_sdr_pixel_v1(p.data(), F16AlphaV1::straight, rgb) && rgb[0] == 0.25f,
                "straight alpha is not divided");
        const auto zero = pixel(0, 0, 0, 0);
        require(decode_f16_sdr_pixel_v1(zero.data(), F16AlphaV1::premultiplied, rgb) && rgb[0] == 0,
                "zero alpha does not divide by zero");
        for (uint16_t alpha : {uint16_t(0xbc00), uint16_t(0x3c01), uint16_t(0x7c00), uint16_t(0x7e00)}) {
            const auto invalid = pixel(0x3400, 0x3400, 0x3400, alpha);
            require(!decode_f16_sdr_pixel_v1(invalid.data(), F16AlphaV1::straight, rgb), "reject invalid alpha");
            require(decode_f16_sdr_pixel_v1(invalid.data(), F16AlphaV1::opaque, rgb), "opaque ignores stored alpha");
        }
        const auto invalid_premul = pixel(0x3c00, 0, 0, 0x3800);
        require(!decode_f16_sdr_pixel_v1(invalid_premul.data(), F16AlphaV1::premultiplied, rgb),
                "reject out-of-domain unpremultiplied code");

        constexpr uint32_t width = 17, height = 5, stride = width * 8 + 13;
        const F16LayoutV1 layout(width, height, stride, 2);
        require(layout.rgb_values == width * height * 3 && layout.storage_bytes == stride * height,
                "padded row allocation contract");
        std::vector<uint8_t> bytes(stride * height + 2, 0xa5);
        for (uint32_t y = 0; y < height; ++y) for (uint32_t x = 0; x < width; ++x) {
            const auto value = pixel(uint16_t(0x3800 + x + y), 0x3400, 0x3a00, 0x3c00);
            std::memcpy(bytes.data() + 1 + y * stride + x * 8, value.data(), 8);
        }
        const auto before = bytes;
        for (uint32_t y = 0; y < height; ++y) for (uint32_t x = 0; x < width; ++x) {
            require(decode_f16_sdr_pixel_v1(bytes.data() + 1 + y * stride + x * 8, layout.alpha, rgb),
                    "unaligned padded-row pixel");
            require(double(rgb[0]) == reference_half(uint16_t(0x3800 + x + y)), "row address");
        }
        require(bytes == before, "input and padding remain unchanged");

        for (const std::string space : {"sRGB", "Display P3"}) {
            const InputTransform xf(space);
            std::set<float> high_precision;
            std::set<uint8_t> quantized;
            for (uint16_t code = 0x3800; code <= 0x3900; ++code) {
                const auto value = pixel(code, 0x3400, 0x3a00, 0x3c00);
                require(decode_f16_sdr_pixel_v1(value.data(), F16AlphaV1::opaque, rgb), "precision fixture");
                const double v = reference_half(code);
                const double expected = v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4);
                for (int c = 0; c < 3; ++c) rgb[c] = xf.decode(rgb[c], c);
                require(std::abs(double(rgb[0]) - expected) < 2e-7, "independent SDR transfer reference");
                xf.to_prophoto(rgb);
                high_precision.insert(rgb[0]);
                quantized.insert(uint8_t(std::lround(v * 255)));
            }
            require(high_precision.size() == 257 && quantized.size() < 40, "adjacent half codes reach engine precision");
        }

        int rejected = 0;
        for (const auto dims : {std::array<uint32_t, 3>{0, 1, 8}, {1, 0, 8}, {2, 1, 15},
                                {std::numeric_limits<uint32_t>::max(), 1, 8}}) {
            try { F16LayoutV1 bad(dims[0], dims[1], dims[2], 0); }
            catch (const std::invalid_argument&) { ++rejected; }
        }
        for (int alpha : {-1, 3}) {
            try { F16LayoutV1 bad(1, 1, 8, alpha); }
            catch (const std::invalid_argument&) { ++rejected; }
        }
        require(rejected == 6, "invalid layout and alpha rejection");
        std::cout << "PASS: 65536 half/domain references; alpha; padded/unaligned rows; sRGB/P3 precision; invalid layouts\n";
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
