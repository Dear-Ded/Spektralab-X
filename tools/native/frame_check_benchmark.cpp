// Co-created with GPT (OpenAI), 2026-11-13. Synthetic validator timings, not device benchmarks.
#include "float_frame_check.hpp"
#include "rgba16_frame_check.hpp"
#include <chrono>
#include <iostream>
#include <vector>

template <class Check> static void measure(const char* format, const char* pattern, Check check) {
    double best = INFINITY;
    std::string error;
    for (int trial = 0; trial < 3; ++trial) {
        const auto start = std::chrono::steady_clock::now();
        error = check();
        best = std::min(best, std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - start).count());
    }
    std::cout << format << ',' << pattern << ',' << best << ',' << (error.empty() ? "accepted" : "rejected") << '\n';
}

int main() {
    constexpr uint32_t w = 6709, h = 4475;
    std::cout << "format,pattern,best_of_3_ms,result\n";
    for (bool flat : {false, true}) {
        const char* name = flat ? "flat_30MP" : "gradient_30MP";
        {
            std::vector<uint16_t> rgba(size_t(w) * h * 4, 65535);
            for (size_t p = 0; p < size_t(w) * h; ++p)
                for (unsigned c = 0; c < 3; ++c) rgba[p * 4 + c] = flat ? 12345 : uint16_t(p * 17 + c * 31);
            measure("rgba16", name, [&] { return spk::android::check_rgba16_frame(rgba.data(), w, h, w); });
        }
        {
            std::vector<float> rgb(size_t(w) * h * 3, .5f);
            if (!flat) for (size_t i = 0; i < rgb.size(); ++i) rgb[i] = float(i % 1021) / 1020;
            measure("float", name, [&] {
                spk::android::FloatFrameCheck check;
                check.append(rgb.data(), w, w, h, 0, h);
                return check.finish();
            });
        }
    }
}
