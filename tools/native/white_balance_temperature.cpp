// Co-created with GPT (OpenAI). Independent Robertson table checkpoints.
#include "white_balance.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>

static bool checkpoint(double mired, double expected_u, double expected_v) {
    double x = 0, y = 0;
    spkhost::temperature_tint_to_xy(1e6 / mired, 0, x, y);
    const double denominator = 1.5 - x + 6 * y;
    const double u = 2 * x / denominator, v = 3 * y / denominator;
    if (std::abs(u - expected_u) > 1e-12 || std::abs(v - expected_v) > 1e-12) {
        std::fprintf(stderr, "Robertson %.0f mired: uv=(%.9f, %.9f), expected=(%.9f, %.9f)\n",
                     mired, u, v, expected_u, expected_v);
        return false;
    }
    double temperature = 0, tint = 0;
    spkhost::xy_to_temperature_tint(x, y, temperature, tint);
    return std::abs(temperature - 1e6 / mired) < 1e-8 && std::abs(tint) < 1e-8;
}

int main() {
    // Colour's published Robertson table, including Lindbloom's 325-mired
    // correction. Forward/inverse tests alone cannot detect a shared typo.
    bool ok = checkpoint(300, .24010, .34308);
    ok &= checkpoint(325, .24792, .34655);
    ok &= checkpoint(350, .25591, .34951);
    if (!ok) return 1;

    double worst_kelvin = 0, worst_tint = 0;
    unsigned cases = 0;
    for (double k : {2000., 2850., 3000., 3076.923076923077, 3200., 4000., 6500., 12000.}) {
        for (double tint : {-150., -25., 0., 25., 150.}) {
            double x = 0, y = 0, actual_k = 0, actual_tint = 0;
            spkhost::temperature_tint_to_xy(k, tint, x, y);
            spkhost::xy_to_temperature_tint(x, y, actual_k, actual_tint);
            if (!std::isfinite(actual_k) || !std::isfinite(actual_tint)) return 1;
            worst_kelvin = std::max(worst_kelvin, std::abs(actual_k - k));
            worst_tint = std::max(worst_tint, std::abs(actual_tint - tint));
            ++cases;
        }
    }
    // Interpolated isotherms are approximate; this bounds their round trip
    // across the actual Android sliders without requiring exact inverses.
    if (worst_kelvin > 4 || worst_tint > .1) {
        std::fprintf(stderr, "Robertson round trip: K error %.9g, tint error %.9g\n",
                     worst_kelvin, worst_tint);
        return 1;
    }
    std::printf("Robertson: corrected table checkpoints and %u round trips passed (K %.6g, tint %.6g)\n",
                cases, worst_kelvin, worst_tint);
}
