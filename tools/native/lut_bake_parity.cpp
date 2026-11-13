// Synthetic Kotlin bake -> production native interpolator parity.
// Co-created with GPT (OpenAI), 2026-11-13. See NOTICE.
#include "lut3d.hpp"
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>

int main(int argc, char** argv) {
    try {
        if (argc != 2) throw std::runtime_error("usage: lut_bake_parity native-parity.bin");
        std::ifstream input(argv[1], std::ios::binary);
        const auto read = [&](auto& value) {
            if (!input.read(reinterpret_cast<char*>(&value), sizeof value))
                throw std::runtime_error("short fixture");
        };
        uint32_t side = 0, count = 0;
        read(side); read(count);
        if (side != 65 || count != 16384) throw std::runtime_error("unexpected fixture shape");
        std::vector<float> data(size_t(side) * side * side * 3);
        for (auto& value : data) read(value);
        const auto lut = spk::android::Lut3d::make(std::move(data), int(side));
        if (!lut) throw std::runtime_error("invalid production LUT");
        float worst = 0;
        unsigned max16 = 0;
        for (uint32_t n = 0; n < count; ++n) {
            float actual[3], expected[3];
            read(actual); read(expected);
            lut->apply(actual[0], actual[1], actual[2]);
            for (int k = 0; k < 3; ++k) {
                if (!std::isfinite(actual[k])) throw std::runtime_error("nonfinite native result");
                worst = std::max(worst, std::abs(actual[k] - expected[k]));
                const int a = int(std::lround(actual[k] * 65535));
                const int b = int(std::lround(expected[k] * 65535));
                max16 = std::max(max16, unsigned(std::abs(a - b)));
            }
        }
        if (input.peek() != std::char_traits<char>::eof()) throw std::runtime_error("trailing fixture data");
        std::cout << "native LUT parity: pixels=" << count << " max_float=" << worst
                  << " max16=" << max16 << '\n';
        if (worst > 1e-6f || max16 > 1) throw std::runtime_error("Kotlin/native interpolation mismatch");
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
