// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
#pragma once

#include <cmath>
#include <cstdint>

namespace spk::android {

// Android Gainmap's SDR-base reconstruction at full HDR weight. Texture codes
// are interpolated before gamma: interpolating nonlinear log boosts is different.
inline float gainmap_bilinear(uint8_t a, uint8_t b, uint8_t c, uint8_t d, float tx, float ty) {
    return ((a / 255.0f) * (1 - tx) + (b / 255.0f) * tx) * (1 - ty) +
           ((c / 255.0f) * (1 - tx) + (d / 255.0f) * tx) * ty;
}

struct GainmapChannel {
    float log_min = 0, log_max = 0, gamma = 1, epsilon_sdr = 0, epsilon_hdr = 0;

    static bool make(float ratio_min, float ratio_max, float gamma,
                     float epsilon_sdr, float epsilon_hdr, GainmapChannel& out) {
        if (!std::isfinite(ratio_min) || !(ratio_min > 0) ||
            !std::isfinite(ratio_max) || !(ratio_max > 0) ||
            !std::isfinite(gamma) || !(gamma > 0) ||
            !std::isfinite(epsilon_sdr) || epsilon_sdr < 0 ||
            !std::isfinite(epsilon_hdr) || epsilon_hdr < 0) return false;
        out = {std::log2(ratio_min), std::log2(ratio_max), gamma, epsilon_sdr, epsilon_hdr};
        return true;
    }

    float recover(float base_linear, float gain_sample) const {
        const float weight = gamma == 1.0f ? gain_sample : std::pow(gain_sample, gamma);
        const float log_boost = log_min * (1 - weight) + log_max * weight;
        return (base_linear + epsilon_sdr) * std::exp2(log_boost) - epsilon_hdr;
    }
};

}  // namespace spk::android
