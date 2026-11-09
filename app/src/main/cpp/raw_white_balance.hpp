// Co-created with GPT (OpenAI). Match the white-balance UI to DNG decoding.
#pragma once

#include "io/raw_camera.hpp"
#include "white_balance.hpp"

#include <array>
#include <cmath>
#include <string>

namespace spk::android {

inline bool raw_multipliers_for(io::detail::CameraLibRaw& raw, double temperature,
                                double tint, std::array<float, 4>& result,
                                std::string& error) {
    error.clear();
    if (!(temperature >= 1700 && temperature <= 50000) || !std::isfinite(tint)) {
        error = "invalid RAW temperature or tint";
        return false;
    }
    // The caller already knows the white. Use it to choose the calibration
    // rather than deriving an illuminant again from camera multipliers: a
    // dual-illuminant profile can have multiple inverse-neutral solutions.
    // The decoder must receive this same temperature together with result.
    raw.interpolate_dng_matrix(nullptr, temperature);
    std::array<float, 4> candidate{};
    if (!spkhost::multipliers_for(raw, temperature, tint, candidate, error)) return false;
    for (float value : candidate) {
        if (!(value > 0) || !std::isfinite(value)) {
            error = "invalid RAW white-balance multiplier";
            return false;
        }
    }
    result = candidate;
    return true;
}

}  // namespace spk::android
