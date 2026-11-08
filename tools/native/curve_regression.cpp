// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
#include "tone_curve.hpp"

#include <bit>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <random>

using Points = std::vector<std::array<double, 2>>;

// Frozen cdc7274 arithmetic: deliberately rebuilds the slopes at each sample.
double reference(const Points& p, double x) {
    const size_t n = p.size();
    if (x <= p.front()[0]) return p.front()[1];
    if (x >= p.back()[0]) return p.back()[1];
    std::vector<double> d(n - 1), m(n);
    for (size_t i = 0; i + 1 < n; ++i) d[i] = (p[i + 1][1] - p[i][1]) / std::max(p[i + 1][0] - p[i][0], 1e-6);
    m[0] = d[0]; m[n - 1] = d[n - 2];
    for (size_t i = 1; i + 1 < n; ++i) m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
    for (size_t i = 0; i + 1 < n; ++i)
        if (d[i] != 0) {
            const double a = m[i] / d[i], b = m[i + 1] / d[i], s = a * a + b * b;
            if (s > 9) { const double t = 3 / std::sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]; }
        }
    size_t i = 0;
    while (i < n - 2 && x > p[i + 1][0]) ++i;
    const double h = std::max(p[i + 1][0] - p[i][0], 1e-6), t = (x - p[i][0]) / h, t2 = t * t, t3 = t2 * t;
    return std::clamp((2 * t3 - 3 * t2 + 1) * p[i][1] + (t3 - 2 * t2 + t) * h * m[i] +
                      (-2 * t3 + 3 * t2) * p[i + 1][1] + (t3 - t2) * h * m[i + 1], 0.0, 1.0);
}

int main() {
    std::mt19937 rng(0x71faff);
    size_t curves = 0, samples = 0;
    auto check = [&](const Points& points) {
        const spk::android::ToneCurve curve(points);
        auto sample = [&](double x) {
            const double before = reference(points, x), after = curve.at(x);
            if (std::bit_cast<uint64_t>(before) != std::bit_cast<uint64_t>(after) ||
                std::bit_cast<uint32_t>(float(before)) != std::bit_cast<uint32_t>(float(after))) {
                std::fprintf(stderr, "curve changed: n=%zu x=%.17g\n", points.size(), x); std::abort();
            }
            ++samples;
        };
        for (int x = 0; x < 256; ++x) sample(double(x) / 255);
        for (const auto& point : points) {
            sample(point[0]); sample(std::nextafter(point[0], -1)); sample(std::nextafter(point[0], 2));
        }
        ++curves;
    };
    check({{0, 0}, {1, 1}});
    check({{0.1, -0.0}, {0.10000001, 1}, {0.10000002, 0}, {0.9, 1}});
    for (size_t n = 2; n <= 256; ++n) {
        for (int pattern = 0; pattern < 7; ++pattern) {
            Points points;
            for (size_t i = 0; i < n; ++i) {
                const double x = double(float(i) / float(n - 1));
                double y = 0;
                switch (pattern) {
                    case 0: y = x; break;
                    case 1: y = 1 - x; break;
                    case 2: y = double(i % 2); break;
                    case 3: y = 0.5; break;
                    case 4: y = i + 1 == n ? 1 : 0; break;
                    case 5: y = double(float(rng() % 65536) / 65535); break;
                    case 6: y = i == n / 2 ? 0.5000001 : 0.5; break;
                }
                points.push_back({x, y});
            }
            check(points);
        }
    }
    std::printf("curves: %zu curves, %zu double/float samples byte-identical\n", curves, samples);
}
