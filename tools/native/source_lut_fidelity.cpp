// Direct source-chain oracle and measured cost. Co-created with GPT (OpenAI).
#include "source_lut.hpp"
#include "lut3d.hpp"
#include <chrono>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>

int main(int argc, char** argv) {
    try {
        if (argc < 2 || argc > 3) throw std::runtime_error("source_lut_fidelity source.bin [baked.bin]");
        std::ifstream in(argv[1], std::ios::binary);
        const auto read = [&](auto& v) { if (!in.read(reinterpret_cast<char*>(&v), sizeof v)) throw std::runtime_error("short source fixture"); };
        uint32_t side, n, space, count;
        read(side); read(n); read(space); read(count);
        if (side != 2 || n != 257 || space > 1 || count != 16384) throw std::runtime_error("fixture shape");
        std::array<float, 12> domains;
        for (auto& v : domains) read(v);
        std::vector<float> shaper(size_t(n) * 3), table(size_t(side) * side * side * 3);
        for (auto& v : shaper) read(v);
        for (auto& v : table) read(v);
        const auto lut = spk::android::SourceLut::make(shaper, table, int(side), domains, space == 1);
        if (!lut) throw std::runtime_error("source refused");
        std::vector<std::array<float, 3>> pixels(count);
        double sum = 0; float worst = 0; unsigned max16 = 0;
        for (auto& p : pixels) {
            for (auto& v : p) read(v);
            std::array<float, 3> expected;
            for (auto& v : expected) read(v);
            auto got = p;
            lut->apply(got[0], got[1], got[2]);
            for (int k = 0; k < 3; ++k) {
                if (!std::isfinite(got[k])) throw std::runtime_error("nonfinite output");
                const float error = std::abs(got[k] - expected[k]);
                sum += error; worst = std::max(worst, error);
                max16 = std::max(max16, unsigned(std::abs(int(std::lround(got[k] * 65535)) - int(std::lround(expected[k] * 65535)))));
            }
        }
        if (in.peek() != std::char_traits<char>::eof()) throw std::runtime_error("trailing source bytes");
        std::cout << "source fidelity: pixels=" << count << " mean_float=" << sum / (3 * count)
                  << " max_float=" << worst << " max16=" << max16 << '\n';
        if (worst > 2e-6f || max16 > 1) throw std::runtime_error("source-chain mismatch");
        auto invalid = domains; invalid[3] = invalid[0];
        if (spk::android::SourceLut::make(shaper, table, 2, invalid, true) ||
            spk::android::SourceLut::make({}, {}, 0, domains, true) ||
            spk::android::SourceLut::make(shaper, table, 130, domains, true)) throw std::runtime_error("invalid source accepted");
        shaper[0] = std::numeric_limits<float>::quiet_NaN();
        if (spk::android::SourceLut::make(shaper, table, 2, domains, true)) throw std::runtime_error("nonfinite source accepted");
        {
            // A 129-side alternating stripe disappears on a 65-side bake.
            constexpr size_t side129 = 129;
            std::vector<float> stripes(side129 * side129 * side129 * 3);
            for (size_t i = 0; i < stripes.size(); ++i) stripes[i] = ((i / 3) % side129) % 2 ? .9f : .1f;
            const std::array<float, 12> unit{0, 0, 0, 1, 1, 1, 0, 0, 0, 1, 1, 1};
            const auto dense = spk::android::SourceLut::make({}, std::move(stripes), 129, unit, false);
            if (!dense) throw std::runtime_error("129-side source refused");
            for (int i = 0; i < 129; ++i) {
                float r = i / 128.0f, g = .371f, b = .719f;
                dense->apply(r, g, b);
                if (std::abs(r - (i % 2 ? .9f : .1f)) > 1e-6f) throw std::runtime_error("129-side source detail lost");
            }
            std::cout << "source detail: 129 alternating native-grid samples preserved\n";
        }
        if (argc == 3) {
            std::ifstream baked(argv[2], std::ios::binary);
            uint32_t bs, bc;
            baked.read(reinterpret_cast<char*>(&bs), 4); baked.read(reinterpret_cast<char*>(&bc), 4);
            if (bs != 65 || bc != count) throw std::runtime_error("baked fixture shape");
            std::vector<float> data(size_t(bs) * bs * bs * 3);
            if (!baked.read(reinterpret_cast<char*>(data.data()), std::streamsize(data.size() * 4))) throw std::runtime_error("short baked fixture");
            const auto grid = spk::android::Lut3d::make(std::move(data), 65);
            for (const auto& renderer : {std::static_pointer_cast<const spk::android::ColourTable>(grid),
                                         std::static_pointer_cast<const spk::android::ColourTable>(lut)}) {
                double checksum = 0;
                const auto start = std::chrono::steady_clock::now();
                for (int repeat = 0; repeat < 64; ++repeat) for (auto p : pixels) {
                    renderer->apply(p[0], p[1], p[2]); checksum += p[0] + p[1] + p[2];
                }
                const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
                std::cout << "cost: mode=" << (renderer.get() == grid.get() ? "baked65" : "source")
                          << " pixels=" << 64 * count << " single_thread_ms=" << ms << " checksum=" << checksum << '\n';
            }
        }
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
