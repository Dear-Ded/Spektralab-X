// Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
#include "rgba16_frame_check.hpp"
#include <chrono>
#include <iostream>
#include <stdexcept>
#include <vector>

using spk::android::check_rgba16_frame;
static void require(bool ok, const char* why) {
    if (!ok) throw std::runtime_error(why);
}
static void fill(std::vector<uint16_t>& frame, uint32_t w, uint32_t h, size_t stride) {
    for (uint32_t y = 0; y < h; ++y) for (uint32_t x = 0; x < w; ++x) {
        auto* p = frame.data() + (size_t(y) * stride + x) * 4;
        p[0] = uint16_t(x * 37 + y * 19);
        p[1] = uint16_t(x * 13 + y * 29);
        p[2] = uint16_t(x * 7 + y * 31);
        p[3] = 65535;
    }
}
int main() try {
    constexpr uint32_t w = 773, h = 1031;
    constexpr size_t stride = w + 7;
    std::vector<uint16_t> frame(stride * h * 4, 0);
    fill(frame, w, h, stride);
    require(check_rgba16_frame(frame.data(), w, h, stride).empty(), "padded frame rejected");
    // The old RGB sampling grid skips this pixel, and Bitmap packing hides its alpha.
    frame[(size_t(1) * stride + 1) * 4 + 3] = 0;
    require(check_rgba16_frame(frame.data(), w, h, stride) ==
        "invalid RGBA16 alpha at (1, 1): 0", "unsampled incomplete pixel accepted");
    fill(frame, w, h, stride);
    frame[(size_t(h - 1) * stride + w - 1) * 4 + 3] = 12345;
    require(check_rgba16_frame(frame.data(), w, h, stride) ==
        "invalid RGBA16 alpha at (772, 1030): 12345", "last pixel corruption accepted");
    fill(frame, w, h, stride);
    for (uint32_t y = 15; y < h; ++y)
        std::fill_n(frame.data() + size_t(y) * stride * 4, size_t(w) * 4, 0);
    require(check_rgba16_frame(frame.data(), w, h, stride).find("alpha at (0, 15)") != std::string::npos,
        "narrow rendered strip concealed incomplete rows");
    // Opaque black rows are valid content; this check cannot prove RGB correctness.
    for (uint32_t y = 15; y < h; ++y) for (uint32_t x = 0; x < w; ++x)
        frame[(size_t(y) * stride + x) * 4 + 3] = 65535;
    require(check_rgba16_frame(frame.data(), w, h, stride).empty(), "dark content rejected");
    for (uint16_t colour : {uint16_t(0), uint16_t(65535), uint16_t(12345)}) {
        std::fill(frame.begin(), frame.end(), colour);
        for (uint32_t y = 0; y < h; ++y) for (uint32_t x = 0; x < w; ++x)
            frame[(size_t(y) * stride + x) * 4 + 3] = 65535;
        require(!check_rgba16_frame(frame.data(), w, h, stride).empty(), "flat rejection regressed");
    }
    require(check_rgba16_frame(nullptr, w, h, stride) == "no pixels", "null accepted");
    require(check_rgba16_frame(frame.data(), w, h, w - 1) == "invalid RGBA16 frame layout",
        "short stride accepted");
    require(check_rgba16_frame(frame.data(), w, h, std::numeric_limits<size_t>::max()) ==
        "invalid RGBA16 frame layout", "overflow accepted");
    for (unsigned c = 0; c < 3; ++c) {
        std::vector<uint16_t> boundary(8, 1000);
        boundary[3] = boundary[7] = 65535;
        boundary[4 + c] = 1016;
        require(!check_rgba16_frame(boundary.data(), 2, 1, 2).empty(), "flat tolerance boundary changed");
        boundary[4 + c] = 1017;
        require(check_rgba16_frame(boundary.data(), 2, 1, 2).empty(), "non-flat tolerance boundary rejected");
    }
    constexpr uint32_t pattern_w = 1024, pattern_h = 1024;
    std::vector<uint16_t> pattern(size_t(pattern_w) * pattern_h * 4, 65535);
    for (unsigned mode = 0; mode < 5; ++mode) {
        for (uint32_t y = 0; y < pattern_h; ++y) for (uint32_t x = 0; x < pattern_w; ++x) {
            const bool bright = mode == 0 ? x == 1 && y == 1 :
                mode == 1 ? x == pattern_w - 1 && y == pattern_h - 1 :
                mode == 2 ? y % 4 == 1 : x % 4 == (mode == 3 ? 0u : 1u);
            auto* p = pattern.data() + (size_t(y) * pattern_w + x) * 4;
            p[0] = p[1] = p[2] = bright ? 65535 : 0;
        }
        require(check_rgba16_frame(pattern.data(), pattern_w, pattern_h, pattern_w).empty(),
            "non-flat pattern was mistaken for sampled black or white");
    }
    pattern.back() = 0;
    require(check_rgba16_frame(pattern.data(), pattern_w, pattern_h, pattern_w).find(
        "invalid RGBA16 alpha at (1023, 1023)") != std::string::npos,
        "alpha after proven RGB variation was skipped");
    constexpr uint32_t big_w = 6709, big_h = 4475;
    std::vector<uint16_t> big(size_t(big_w) * big_h * 4);
    fill(big, big_w, big_h, big_w);
    const auto start = std::chrono::steady_clock::now();
    require(check_rgba16_frame(big.data(), big_w, big_h, big_w).empty(), "30 MP frame rejected");
    const auto ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    std::cout << "PASS rgba16 frame: stride, incomplete pixel/rows, opaque dark content, flat, layout, 5 non-flat patterns; "
              << "30MP_check_ms=" << ms << '\n';
} catch (const std::exception& e) {
    std::cerr << e.what() << '\n';
    return 1;
}
