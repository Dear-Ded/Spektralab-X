// Original CUBE evaluation at the Layer 2 boundary. Co-created with GPT (OpenAI).
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <memory>
#include <vector>

#include "layer2.hpp"

namespace spk::android {

class SourceLut final : public ColourTable {
public:
    static constexpr int kMaxSide = 129;
    static constexpr size_t kMaxShaper = 65536;
    // domains: 1D min RGB, 1D max RGB, 3D min RGB, 3D max RGB.
    static std::shared_ptr<const SourceLut> make(std::vector<float> shaper, std::vector<float> table,
                                                int side, std::array<float, 12> domains, bool srgb) {
        if ((!shaper.empty() && (shaper.size() % 3 || shaper.size() < 6 || shaper.size() > kMaxShaper * 3)) ||
            (side != 0 && (side < 2 || side > kMaxSide)) ||
            table.size() != size_t(side) * side * side * 3 || (shaper.empty() && table.empty())) return nullptr;
        for (float v : shaper) if (!std::isfinite(v)) return nullptr;
        for (float v : table) if (!std::isfinite(v)) return nullptr;
        for (int k = 0; k < 12; ++k) if (!std::isfinite(domains[k])) return nullptr;
        for (int k : {0, 1, 2, 6, 7, 8}) if (domains[k + 3] <= domains[k]) return nullptr;
        return std::shared_ptr<const SourceLut>(new SourceLut(std::move(shaper), std::move(table), side, domains, srgb));
    }

    void apply(float& r, float& g, float& b) const override {
        std::array<double, 3> c{clamp(r), clamp(g), clamp(b)};
        if (srgb_) {
            for (double& x : c) x = x < .03125 ? x / 16 : std::pow(x, 1.8);
            c = mul(kToSrgb, c);
            for (double& x : c) { x = clamp(x); x = x <= .0031308 ? 12.92 * x : 1.055 * std::pow(x, 1 / 2.4) - .055; }
        }
        if (!shaper_.empty()) {
            const size_t n = shaper_.size() / 3 - 1;
            for (int k = 0; k < 3; ++k) {
                const double p = position(c[k], k) * n;
                const size_t lo = std::min(size_t(p), n - 1);
                const double f = p - lo;
                c[k] = shaper_[3 * lo + k] * (1 - f) + shaper_[3 * (lo + 1) + k] * f;
            }
        }
        if (side_) {
            std::array<size_t, 3> lo{};
            std::array<double, 3> f{};
            for (int k = 0; k < 3; ++k) {
                const double p = position(c[k], 6 + k) * (side_ - 1);
                lo[k] = std::min(size_t(p), size_t(side_ - 2));
                f[k] = p - lo[k];
            }
            std::array<int, 3> order{0, 1, 2};
            if (f[order[0]] < f[order[1]]) std::swap(order[0], order[1]);
            if (f[order[1]] < f[order[2]]) std::swap(order[1], order[2]);
            if (f[order[0]] < f[order[1]]) std::swap(order[0], order[1]);
            const double w[4] = {1 - f[order[0]], f[order[0]] - f[order[1]], f[order[1]] - f[order[2]], f[order[2]]};
            c = {};
            for (int vertex = 0; vertex < 4; ++vertex) {
                const size_t at = ((lo[2] * side_ + lo[1]) * side_ + lo[0]) * 3;
                for (int k = 0; k < 3; ++k) c[k] += w[vertex] * table_[at + k];
                if (vertex < 3) ++lo[order[vertex]];
            }
        }
        if (srgb_) {
            for (double& x : c) { x = clamp(x); x = x <= .04045 ? x / 12.92 : std::pow((x + .055) / 1.055, 2.4); }
            c = mul(kToPro, c);
            for (double& x : c) { x = clamp(x); x = x < .001953125 ? x * 16 : std::pow(x, 1 / 1.8); }
        }
        r = float(clamp(c[0])); g = float(clamp(c[1])); b = float(clamp(c[2]));
    }

private:
    SourceLut(std::vector<float> shaper, std::vector<float> table, int side, std::array<float, 12> domains, bool srgb)
        : shaper_(std::move(shaper)), table_(std::move(table)), side_(side), domains_(domains), srgb_(srgb) {}
    static double clamp(double x) { return std::isfinite(x) ? std::clamp(x, 0.0, 1.0) : 0.0; }
    double position(double x, int k) const { return clamp((x - domains_[k]) / (double(domains_[k + 3]) - domains_[k])); }
    static std::array<double, 3> mul(const std::array<double, 9>& m, const std::array<double, 3>& c) {
        return {m[0] * c[0] + m[1] * c[1] + m[2] * c[2], m[3] * c[0] + m[4] * c[1] + m[5] * c[2],
                m[6] * c[0] + m[7] * c[1] + m[8] * c[2]};
    }
    static constexpr std::array<double, 9> kToSrgb{2.0340758, -.7273341, -.3067418, -.2288131, 1.2317301,
        -.0029169, -.0085698, -.1532866, 1.1618564};
    static constexpr std::array<double, 9> kToPro{.5293459, .3300728, .1405813, .0983743, .8734610,
        .0281646, .0168832, .1176725, .8654443};
    const std::vector<float> shaper_, table_;
    const int side_;
    const std::array<float, 12> domains_;
    const bool srgb_;
};

}  // namespace spk::android
