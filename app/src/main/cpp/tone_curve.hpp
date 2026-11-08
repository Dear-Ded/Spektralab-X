// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace spk::android {

// Validated, strictly increasing control points; the caller retains them.
// Keep the sequential slope limiter and Hermite expression from upstream.
class ToneCurve {
public:
    explicit ToneCurve(const std::vector<std::array<double, 2>>& points) : p_(points), m_(points.size()) {
        const size_t n = p_.size();
        std::vector<double> d(n - 1);
        for (size_t i = 0; i + 1 < n; ++i)
            d[i] = (p_[i + 1][1] - p_[i][1]) / std::max(p_[i + 1][0] - p_[i][0], 1e-6);
        m_[0] = d[0]; m_[n - 1] = d[n - 2];
        for (size_t i = 1; i + 1 < n; ++i) m_[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
        for (size_t i = 0; i + 1 < n; ++i)
            if (d[i] != 0) {
                const double a = m_[i] / d[i], b = m_[i + 1] / d[i], s = a * a + b * b;
                if (s > 9) { const double t = 3 / std::sqrt(s); m_[i] = t * a * d[i]; m_[i + 1] = t * b * d[i]; }
            }
    }

    double at(double x) const {
        const size_t n = p_.size();
        if (x <= p_.front()[0]) return p_.front()[1];
        if (x >= p_.back()[0]) return p_.back()[1];
        size_t i = 0;
        while (i < n - 2 && x > p_[i + 1][0]) ++i;
        const double h = std::max(p_[i + 1][0] - p_[i][0], 1e-6), t = (x - p_[i][0]) / h, t2 = t * t, t3 = t2 * t;
        return std::clamp((2 * t3 - 3 * t2 + 1) * p_[i][1] + (t3 - 2 * t2 + t) * h * m_[i] +
                          (-2 * t3 + 3 * t2) * p_[i + 1][1] + (t3 - t2) * h * m_[i + 1], 0.0, 1.0);
    }

private:
    const std::vector<std::array<double, 2>>& p_;
    std::vector<double> m_;
};

}  // namespace spk::android
