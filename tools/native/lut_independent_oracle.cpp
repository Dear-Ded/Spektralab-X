// Independent tetrahedral reference using Cramer's rule. Co-created with GPT (OpenAI).
#include "source_lut.hpp"
#include "lut3d.hpp"
#include <cstdint>
#include <iostream>
#include <limits>
#include <stdexcept>

using Vec = std::array<long double, 3>;
static long double determinant(const Vec& a, const Vec& b, const Vec& c) {
    return a[0] * (b[1] * c[2] - b[2] * c[1])
         - b[0] * (a[1] * c[2] - a[2] * c[1])
         + c[0] * (a[1] * b[2] - a[2] * b[1]);
}

static Vec reference(const std::vector<float>& table, int side, const std::array<float, 3>& in,
                     const std::array<float, 12>& domain) {
    Vec p{};
    std::array<int, 3> lo{};
    for (int k = 0; k < 3; ++k) {
        const long double x = std::isfinite(in[k]) ? std::clamp(in[k], 0.f, 1.f) : 0;
        const long double normalized = (x - domain[k + 6]) /
            (static_cast<long double>(domain[k + 9]) - domain[k + 6]);
        const long double at = std::clamp(normalized, 0.L, 1.L) * (side - 1);
        lo[k] = std::min(int(at), side - 2);
        p[k] = at - lo[k];
    }
    constexpr int permutations[6][3] = {{0,1,2}, {0,2,1}, {1,0,2}, {1,2,0}, {2,0,1}, {2,1,0}};
    for (const auto& order : permutations) {
        std::array<Vec, 4> vertex{};
        for (int n = 1; n < 4; ++n) {
            vertex[n] = vertex[n - 1];
            vertex[n][order[n - 1]] = 1;
        }
        const long double det = determinant(vertex[1], vertex[2], vertex[3]);
        const long double w1 = determinant(p, vertex[2], vertex[3]) / det;
        const long double w2 = determinant(vertex[1], p, vertex[3]) / det;
        const long double w3 = determinant(vertex[1], vertex[2], p) / det;
        const std::array<long double, 4> w{1 - w1 - w2 - w3, w1, w2, w3};
        if (*std::min_element(w.begin(), w.end()) < -1e-15L) continue;
        Vec out{};
        for (int n = 0; n < 4; ++n) {
            const auto x = lo[0] + int(vertex[n][0]);
            const auto y = lo[1] + int(vertex[n][1]);
            const auto z = lo[2] + int(vertex[n][2]);
            const size_t offset = (size_t(z * side + y) * side + x) * 3;
            for (int k = 0; k < 3; ++k) out[k] += w[n] * table[offset + k];
        }
        for (auto& x : out) x = std::clamp(x, 0.L, 1.L);
        return out;
    }
    throw std::runtime_error("no enclosing tetrahedron");
}

int main() {
    try {
        uint32_t state = 0x13579BDF;
        const auto random = [&]() { state ^= state << 13; state ^= state >> 17; state ^= state << 5; return state; };
        const std::array<float, 12> unit{0,0,0,1,1,1,0,0,0,1,1,1};
        const std::array<float, 12> asymmetric{0,0,0,1,1,1,-.1f,.15f,-.2f,1.5f,.9f,1.25f};
        const std::array<float, 12> wide{0,0,0,1,1,1,-3e38f,-3e38f,-3e38f,3e38f,3e38f,3e38f};
        long double worstSource = 0, worstGrid = 0;
        unsigned worst16Source = 0, worst16Grid = 0;
        size_t samples = 0;
        for (int side : {2,3,5,17,33,65,129}) {
            std::vector<float> table(size_t(side) * side * side * 3);
            for (auto& v : table) v = float(random() & 65535u) / 65535.f;
            for (const auto& domain : {unit, asymmetric, wide}) {
                const auto source = spk::android::SourceLut::make({}, table, side, domain, false);
                const auto grid = side <= 65 && domain == unit ? spk::android::Lut3d::make(table, side) : nullptr;
                if (!source) throw std::runtime_error("valid table refused");
                std::vector<std::array<float, 3>> inputs;
                for (float r : {0.f, .25f, .5f, .75f, 1.f})
                    for (float g : {0.f, .25f, .5f, .75f, 1.f})
                        for (float b : {0.f, .25f, .5f, .75f, 1.f}) inputs.push_back({r,g,b});
                for (float x : {0.f, .25f, .5f, .75f, 1.f}) {
                    inputs.push_back({x, std::nextafter(x, 0.f), std::nextafter(x, 1.f)});
                    inputs.push_back({std::nextafter(x, 1.f), x, std::nextafter(x, 0.f)});
                }
                inputs.push_back({-1.f, 2.f, std::numeric_limits<float>::quiet_NaN()});
                inputs.push_back({std::numeric_limits<float>::infinity(), -std::numeric_limits<float>::infinity(), .5f});
                for (int i = 0; i < 8192; ++i) inputs.push_back({float(random() & 0xffffffu) / 16777215.f,
                    float(random() & 0xffffffu) / 16777215.f, float(random() & 0xffffffu) / 16777215.f});
                for (const auto& input : inputs) {
                    const auto expected = reference(table, side, input, domain);
                    for (int mode = 0; mode < (grid ? 2 : 1); ++mode) {
                        auto got = input;
                        if (mode == 0) source->apply(got[0], got[1], got[2]);
                        else grid->apply(got[0], got[1], got[2]);
                        for (int k = 0; k < 3; ++k) {
                            if (!std::isfinite(got[k])) throw std::runtime_error("nonfinite output");
                            const long double error = std::abs(static_cast<long double>(got[k]) - expected[k]);
                            const auto quantized = unsigned(std::abs(std::lround(got[k] * 65535.L) - std::lround(expected[k] * 65535.L)));
                            auto& worst = mode == 0 ? worstSource : worstGrid;
                            auto& worst16 = mode == 0 ? worst16Source : worst16Grid;
                            worst = std::max(worst, error); worst16 = std::max(worst16, quantized);
                        }
                    }
                    ++samples;
                }
            }
        }
        std::cout << "independent LUT oracle: samples=" << samples << " max_source=" << double(worstSource)
                  << " max_grid=" << double(worstGrid) << " max16_source=" << worst16Source
                  << " max16_grid=" << worst16Grid << '\n';
        if (worstSource > 1e-7L || worstGrid > 5e-7L || worst16Source > 1 || worst16Grid > 1)
            throw std::runtime_error("interpolation differs from independent barycentric oracle");
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
