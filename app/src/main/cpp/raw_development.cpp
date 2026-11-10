// Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
#include "raw_development.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace spk::android {
namespace {
constexpr double kGrey = 0.1845;
constexpr double kLogMin = -24.0, kLogRange = 48.0;
constexpr double kContrast = 1.5;
const double kLogSigmoidA = std::log(std::pow(kGrey, kContrast) * (1.0 / kGrey - 1.0));

double endpoint_slope(double ha, double hb, double da, double db) {
    const double value = ((2.0 * ha + hb) * da - ha * db) / (ha + hb);
    if (value * da <= 0.0) return 0.0;
    return std::copysign(std::min(std::abs(value), 3.0 * std::abs(da)), da);
}

double positive_decode(float value) noexcept {
    // Engine ProPhoto has a linear toe, including all negative values. Clipping
    // negatives belongs only to this SDR display boundary, never RAW storage.
    if (value <= 0.0f) return 0.0;
    return value < 16.0f / 512.0f ? double(value) / 16.0 : std::pow(double(value), 1.8);
}
}  // namespace

RawDevelopmentStats raw_development_stats(const float* rgb, size_t stride,
                                          uint32_t w, uint32_t h,
                                          const OutputCancel& cancel) {
    cancel.check();
    if (!rgb || !w || !h || stride < w ||
        stride > std::numeric_limits<size_t>::max() / h / 3 / sizeof(float))
        throw std::invalid_argument("invalid RAW statistics source");
    RawDevelopmentStats stats;
    for (uint32_t y = 0; y < h; ++y) {
        cancel.check();
        const float* row = rgb + size_t(y) * stride * 3;
        for (uint32_t x = 0; x < w; ++x) {
            const float* c = row + size_t(x) * 3;
            if (!std::isfinite(c[0]) || !std::isfinite(c[1]) || !std::isfinite(c[2])) {
                ++stats.invalid_pixels;
                continue;
            }
            const double luminance = 0.2880402 * c[0] + 0.7118741 * c[1] + 0.0000857 * c[2];
            if (luminance <= std::exp2(kLogMin)) {
                ++stats.dark_pixels;
                continue;
            }
            const double coordinate = (std::log2(luminance) - kLogMin) * 4096.0 / kLogRange;
            const size_t bin = size_t(std::clamp(coordinate, 0.0, 4095.0));
            ++stats.histogram[bin];
            ++stats.valid_pixels;
        }
    }
    if (stats.valid_pixels) {
        const double low = double(stats.valid_pixels) * 0.01;
        const double high = double(stats.valid_pixels) * 0.99;
        uint64_t cumulative = 0;
        double weighted = 0.0, weight_sum = 0.0;
        for (size_t i = 0; i < stats.histogram.size(); ++i) {
            const uint64_t before = cumulative;
            cumulative += stats.histogram[i];
            const double weight = std::max(0.0, std::min(double(cumulative), high) -
                                                std::max(double(before), low));
            weighted += weight * (kLogMin + (double(i) + 0.5) * kLogRange / 4096.0);
            weight_sum += weight;
        }
        stats.log2_mean = weighted / weight_sum;
        stats.available = std::isfinite(stats.log2_mean);
    }
    cancel.check();
    return stats;
}

bool prepare_raw_development_stats(const RawDevelopmentOptions& options, bool no_film,
                                   const float* rgb, size_t stride,
                                   uint32_t w, uint32_t h, RawDevelopmentStats& out,
                                   const OutputCancel& cancel) {
    if (!options.enabled || !no_film) {
        out = {};
        return false;
    }
    out = raw_development_stats(rgb, stride, w, h, cancel);
    return true;
}

RawDevelopment RawDevelopment::build(const RawDevelopmentMetadata& meta,
                                     const RawDevelopmentStats& stats,
                                     const RawDevelopmentOptions& options) {
    RawDevelopment result;
    if (!options.enabled) return result;
    if (!std::isfinite(options.exposure_ev) || options.exposure_ev < -8.0f || options.exposure_ev > 8.0f)
        throw std::invalid_argument("RAW exposure must be finite and within -8 to 8 EV");
    if (meta.extra_camera_profiles || meta.profile_source_mismatch)
        throw std::invalid_argument("RAW standard development does not support this profile selection");
    if (meta.baseline_exposure_invalid || meta.baseline_exposure_offset_invalid ||
        meta.default_black_render_invalid || meta.profile_tone_curve_invalid)
        throw std::invalid_argument("invalid RAW display metadata");
    if (meta.default_black_render_present && meta.default_black_render > 1)
        throw std::invalid_argument("invalid RAW DefaultBlackRender");

    const double baseline = meta.baseline_exposure_present ? meta.baseline_exposure : 0.0;
    const double offset = meta.baseline_exposure_offset_present ? meta.baseline_exposure_offset : 0.0;
    if (!std::isfinite(baseline) || !std::isfinite(offset))
        throw std::invalid_argument("invalid RAW baseline exposure");
    result.enabled_ = true;
    result.auto_exposure_ = options.auto_exposure;
    result.status_ = "ok";
    if (options.auto_exposure) {
        if (stats.available && std::isfinite(stats.log2_mean)) {
            // The histogram describes unexposed decoder pixels. Subtract the
            // technical baseline before auto-normalizing so it is applied once.
            result.auto_ev_ = std::clamp(std::log2(kGrey) - stats.log2_mean - baseline - offset, -4.0, 4.0);
        } else {
            result.status_ += ";auto_exposure_no_samples";
        }
    }
    result.effective_ev_ = baseline + offset + result.auto_ev_ + options.exposure_ev;
    result.gain_ = std::exp2(result.effective_ev_);
    if (!std::isfinite(result.gain_) || result.gain_ <= 0.0 ||
        result.gain_ > std::numeric_limits<double>::max() /
                       std::pow(double(std::numeric_limits<float>::max()), 1.8))
        throw std::invalid_argument("RAW effective exposure is not representable");

    // Auto's shadow scale is not carried by the bounded metadata contract.
    // Record the unsupported rendering choice instead of inventing a black lift
    // or confusing sensor-black subtraction with display-black rendering.
    if (!meta.default_black_render_present || meta.default_black_render == 0)
        result.status_ += ";black_auto_not_applied";

    if (!meta.profile_tone_curve_present) return result;
    const auto& points = meta.profile_tone_curve;
    if (points.size() < 2 || points.size() > 256)
        throw std::invalid_argument("invalid RAW profile tone curve size");
    result.embedded_ = true;
    for (const auto& point : points) {
        if (!std::isfinite(point[0]) || !std::isfinite(point[1]) ||
            point[0] < 0.0f || point[0] > 1.0f || point[1] < 0.0f || point[1] > 1.0f ||
            (!result.x_.empty() && point[0] <= result.x_.back()))
            throw std::invalid_argument("invalid RAW profile tone curve point");
        result.x_.push_back(point[0]);
        result.y_.push_back(point[1]);
    }
    const size_t n = points.size();
    std::vector<double> h(n - 1), d(n - 1);
    for (size_t i = 0; i + 1 < n; ++i) {
        h[i] = result.x_[i + 1] - result.x_[i];
        d[i] = (result.y_[i + 1] - result.y_[i]) / h[i];
    }
    result.slopes_.assign(n, 0.0);
    if (n == 2) {
        result.slopes_[0] = result.slopes_[1] = d[0];
    } else {
        // Shape-preserving cubic Hermite interpolation (weighted harmonic
        // secants), derived from the published PCHIP method. No SDK code/table.
        result.slopes_[0] = endpoint_slope(h[0], h[1], d[0], d[1]);
        result.slopes_[n - 1] = endpoint_slope(h[n - 2], h[n - 3], d[n - 2], d[n - 3]);
        for (size_t i = 1; i + 1 < n; ++i) {
            if (d[i - 1] * d[i] <= 0.0) continue;
            const double w1 = 2.0 * h[i] + h[i - 1], w2 = h[i] + 2.0 * h[i - 1];
            result.slopes_[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i]);
        }
    }
    return result;
}

double RawDevelopment::tone(double value) const noexcept {
    if (!embedded_) {
        // Mathematical zero-skew log-logistic view transform, grey .1845 and
        // contrast 1.5; see darktable sigmoid.c, fixed revision
        // 0c23f000db413843f385e402d53a796b71d4cba0. This is not an ACR3 replica.
        if (value <= 0.0) return 0.0;
        const double u = kContrast * std::log(value) - kLogSigmoidA;
        const double z = std::exp(-std::abs(u));
        return u >= 0.0 ? 1.0 / (1.0 + z) : z / (1.0 + z);
    }
    if (value <= x_.front()) return y_.front();
    if (value >= x_.back()) return y_.back();
    const size_t i = size_t(std::upper_bound(x_.begin(), x_.end(), value) - x_.begin()) - 1;
    const double h = x_[i + 1] - x_[i], t = (value - x_[i]) / h;
    const double t2 = t * t, t3 = t2 * t;
    return std::clamp((2.0*t3 - 3.0*t2 + 1.0)*y_[i] + (t3 - 2.0*t2 + t)*h*slopes_[i] +
                      (-2.0*t3 + 3.0*t2)*y_[i + 1] + (t3 - t2)*h*slopes_[i + 1], 0.0, 1.0);
}

void RawDevelopment::apply_encoded_prophoto(float rgb[3]) const noexcept {
    if (!enabled_) return;
    // Preserve existing non-finite pixel handling at the caller boundary;
    // statistics excludes such pixels and this transform does not invent RGB.
    if (!std::isfinite(rgb[0]) || !std::isfinite(rgb[1]) || !std::isfinite(rgb[2])) return;
    double c[3];
    for (int i = 0; i < 3; ++i) c[i] = positive_decode(rgb[i]) * gain_;
    const double lo = std::min({c[0], c[1], c[2]}), hi = std::max({c[0], c[1], c[2]});
    const double low = tone(lo), high = tone(hi);
    for (int i = 0; i < 3; ++i) {
        // Map extrema, then interpolate each channel between them. This keeps
        // RGB ordering/hue ratios rather than adding a saturation multiplier.
        const double ratio = hi > lo ? (c[i] - lo) / (hi - lo) : 0.0;
        const double mapped = low + ratio * (high - low);
        const double bounded = std::clamp(mapped, 0.0, 1.0);
        rgb[i] = float(bounded < 1.0 / 512.0 ? 16.0 * bounded : std::pow(bounded, 1.0 / 1.8));
    }
}

}  // namespace spk::android
