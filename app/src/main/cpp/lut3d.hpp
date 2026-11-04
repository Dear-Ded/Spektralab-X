// lut3d.hpp -- a user colour table for Layer 2 (layer2.hpp ColourTable).
//
// The table arrives already baked by the styles line (CubeLut.kt, LutBake):
// side^3 RGB entries over encoded ProPhoto 0..1, red changing fastest, then
// green, blue slowest. This file only interpolates: tetrahedral, the same six
// cases as Lut3.sample in CubeLut.kt, so a preview and the Kotlin tests agree.
// Header-only, so the build needs no new source file.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

#include "layer2.hpp"

namespace spk::android {

class Lut3d final : public ColourTable {
public:
    static constexpr int kMaxSide = 65;

    // Null when `side` is out of 2..kMaxSide or `values` has the wrong length.
    static std::shared_ptr<const Lut3d> make(std::vector<float> values, int side) {
        if (side < 2 || side > kMaxSide) return nullptr;
        if (values.size() != size_t(side) * side * side * 3) return nullptr;
        for (float& x : values) x = std::isfinite(x) ? std::min(1.0f, std::max(0.0f, x)) : 0.0f;
        return std::shared_ptr<const Lut3d>(new Lut3d(std::move(values), side));
    }

    void apply(float& r, float& g, float& b) const override {
        const int n1 = side_ - 1;
        const float fr = clamp01(r) * n1, fg = clamp01(g) * n1, fb = clamp01(b) * n1;
        const int r0 = std::min(int(fr), n1 - 1), g0 = std::min(int(fg), n1 - 1), b0 = std::min(int(fb), n1 - 1);
        const float dr = fr - r0, dg = fg - g0, db = fb - b0;
        const size_t c000 = at(r0, g0, b0), c111 = at(r0 + 1, g0 + 1, b0 + 1);
        size_t c1, c2;
        float w0, w1, w2, w3;
        if (dr >= dg && dg >= db) {
            c1 = at(r0 + 1, g0, b0); c2 = at(r0 + 1, g0 + 1, b0); w0 = 1 - dr; w1 = dr - dg; w2 = dg - db; w3 = db;
        } else if (dr >= db && db >= dg) {
            c1 = at(r0 + 1, g0, b0); c2 = at(r0 + 1, g0, b0 + 1); w0 = 1 - dr; w1 = dr - db; w2 = db - dg; w3 = dg;
        } else if (db >= dr && dr >= dg) {
            c1 = at(r0, g0, b0 + 1); c2 = at(r0 + 1, g0, b0 + 1); w0 = 1 - db; w1 = db - dr; w2 = dr - dg; w3 = dg;
        } else if (dg >= dr && dr >= db) {
            c1 = at(r0, g0 + 1, b0); c2 = at(r0 + 1, g0 + 1, b0); w0 = 1 - dg; w1 = dg - dr; w2 = dr - db; w3 = db;
        } else if (dg >= db && db >= dr) {
            c1 = at(r0, g0 + 1, b0); c2 = at(r0, g0 + 1, b0 + 1); w0 = 1 - dg; w1 = dg - db; w2 = db - dr; w3 = dr;
        } else {
            c1 = at(r0, g0, b0 + 1); c2 = at(r0, g0 + 1, b0 + 1); w0 = 1 - db; w1 = db - dg; w2 = dg - dr; w3 = dr;
        }
        const float* d = v_.data();
        r = w0 * d[c000] + w1 * d[c1] + w2 * d[c2] + w3 * d[c111];
        g = w0 * d[c000 + 1] + w1 * d[c1 + 1] + w2 * d[c2 + 1] + w3 * d[c111 + 1];
        b = w0 * d[c000 + 2] + w1 * d[c1 + 2] + w2 * d[c2 + 2] + w3 * d[c111 + 2];
    }

private:
    Lut3d(std::vector<float> v, int side) : v_(std::move(v)), side_(side) {}
    size_t at(int r, int g, int b) const { return ((size_t(b) * side_ + g) * side_ + r) * 3; }
    static float clamp01(float x) { return std::isfinite(x) ? std::min(1.0f, std::max(0.0f, x)) : 0.0f; }

    std::vector<float> v_;
    int side_;
};

}  // namespace spk::android
