// Co-created with GPT (OpenAI), 2026-11-10. See NOTICE.
#pragma once

#include <cmath>

namespace spk::android {

// Engine ProPhoto uses a linear toe, including signed values. A gain can be
// applied directly when both endpoints stay on the same transfer segment.
// Only toe crossings need pow; gains are calculated once per look or mask.
inline float expose_encoded_prophoto(float encoded, double linear_gain, double encoded_gain) noexcept {
    const double value = encoded;
    constexpr double toe = 16.0 / 512.0;
    if (value < toe) {
        const double exposed = value * linear_gain;
        return float(exposed < toe ? exposed : std::pow(exposed / 16.0, 1.0 / 1.8));
    }
    const double exposed = value * encoded_gain;
    return float(exposed >= toe ? exposed : std::pow(value, 1.8) * linear_gain * 16.0);
}

}  // namespace spk::android
