// Versioned SDR RAW display development, outside the physical film input.
// Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "output_cancel.hpp"

namespace spk::android {

// A bounded copy of the selected RAW profile's display metadata. None of these
// exposure values has been baked into the scene-linear decoder output.
struct RawDevelopmentMetadata {
    double baseline_exposure = 0.0, baseline_exposure_offset = 0.0;
    bool baseline_exposure_present = false, baseline_exposure_invalid = false;
    bool baseline_exposure_offset_present = false, baseline_exposure_offset_invalid = false;
    uint32_t default_black_render = 0;
    bool default_black_render_present = false, default_black_render_invalid = false;
    std::vector<std::array<float, 2>> profile_tone_curve;
    bool profile_tone_curve_present = false, profile_tone_curve_invalid = false;
    bool extra_camera_profiles = false, profile_source_mismatch = false;
};

struct RawDevelopmentOptions {
    bool enabled = false, auto_exposure = false;
    float exposure_ev = 0.0f;  // RAW exposure, before tone mapping; finite [-8, 8].
};

struct RawDevelopmentStats {
    std::array<uint64_t, 4096> histogram{};
    uint64_t valid_pixels = 0, invalid_pixels = 0, dark_pixels = 0;
    double log2_mean = 0.0;
    bool available = false;
};

// Scan the complete scene-linear ProPhoto source exactly once. The caller keeps
// this result across resize, preview, strips and export; never derive tile AE.
RawDevelopmentStats raw_development_stats(const float* linear_rgb, size_t stride_px,
                                          uint32_t w, uint32_t h,
                                          const OutputCancel& cancel = {});

class RawDevelopment {
public:
    static RawDevelopment build(const RawDevelopmentMetadata& metadata,
                                const RawDevelopmentStats& stats,
                                const RawDevelopmentOptions& options);

    // Unmodified engine float cache in, developed encoded ProPhoto out, once per
    // packing operation, before Layer2/output conversion. Disabled is a bitwise
    // no-op. This is pointwise and allocates nothing; safe in output workers.
    void apply_encoded_prophoto(float rgb[3]) const noexcept;

    bool enabled() const noexcept { return enabled_; }
    bool auto_exposure() const noexcept { return auto_exposure_; }
    double effective_ev() const noexcept { return effective_ev_; }
    double auto_ev() const noexcept { return auto_ev_; }
    const char* curve_name() const noexcept { return embedded_ ? "embedded_pchip_v1" : "loglogistic_v1"; }
    const std::string& status() const noexcept { return status_; }
    static constexpr unsigned version() noexcept { return 1; }

private:
    double tone(double value) const noexcept;
    bool enabled_ = false, auto_exposure_ = false, embedded_ = false;
    double effective_ev_ = 0.0, auto_ev_ = 0.0, gain_ = 1.0;
    std::vector<double> x_, y_, slopes_;
    std::string status_ = "neutral";
};

}  // namespace spk::android
