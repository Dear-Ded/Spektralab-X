#pragma once

// Co-created with GPT (OpenAI). Explicit precision dispatch; legacy decoding
// remains the engine's existing compatible policy without changed defaults.
#include "float_dng_linear.hpp"
#include "io/raw_decoder.hpp"
#include <chrono>

namespace spk::android {

inline constexpr FloatDngLimits raw_float_linear_limits{16ULL * 1024 * 1024, 24ULL * 16 * 1024 * 1024};

struct RawPrecisionPlan {
    FloatDngSupport support = FloatDngSupport::NotFloat;
    FloatDngMetadata source;
    std::string mode, note;
    // These are the already-open decoder's actual, unprocessed source values.
    io::RawSourceSizes source_sizes;
    int raw_flip = 0;
    bool custom_whitebalance = false;
};

inline RawPrecisionPlan inspect_raw_precision(const std::filesystem::path& path,
                                              io::detail::CameraLibRaw& opened, bool custom_wb,
                                              const FloatDngCancel& cancelled = {}) {
    RawPrecisionPlan plan;
    plan.custom_whitebalance = custom_wb;
    plan.support = inspect_float_linear_dng(path, opened, raw_float_linear_limits,
                                           plan.source, plan.note, cancelled);
    if (plan.support == FloatDngSupport::NotFloat) {
        plan.mode = "integer_compatible16";
    } else if (plan.support == FloatDngSupport::Supported) {
        plan.mode = "float_linear_v1";
        const auto& s = opened.imgdata.sizes;
        plan.source_sizes = {s.raw_width, s.raw_height, s.width, s.height, s.iwidth, s.iheight,
                             s.top_margin, s.left_margin, s.pixel_aspect};
        plan.raw_flip = s.flip;
    } else {
        plan.mode = "float_legacy_quantized";
        plan.note += "; legacy float conversion quantizes to integer";
    }
    return plan;
}

// float_source is replaced only by a successful float_linear_v1 decode. Its
// original rational black/white values must not be truncated into RawMetadata's
// legacy integer fields. Callers expose mode/note from the corresponding plan.
// Cancellation throws FloatDngCancelled for both policies. The compatibility
// decoder's progress callback is passed through and its result is checked again
// before publication.
inline bool decode_raw_precision(const std::filesystem::path& path, const RawPrecisionPlan& plan,
                                 const std::array<float, 4>* user_mul, double white_temperature,
                                 io::DecodedRaw& out, std::string& error,
                                 FloatDngMetadata* float_source = nullptr,
                                 const FloatDngCancel& cancelled = {}) {
    error.clear();
    try {
        float_dng_detail::check_cancel(cancelled);
        float_dng_detail::require(plan.custom_whitebalance == bool(user_mul),
                                  "RAW precision plan white balance changed; inspect the source again");
        io::DecodedRaw candidate;
        if (plan.mode == "integer_compatible16" || plan.mode == "float_legacy_quantized") {
            float_dng_detail::require((plan.mode == "integer_compatible16") ==
                                      (plan.support == FloatDngSupport::NotFloat), "invalid RAW precision plan");
            if (!io::decode_raw_compatible(path, user_mul, candidate, error, white_temperature, cancelled)) return false;
            float_dng_detail::check_cancel(cancelled);
            out = std::move(candidate);
            return true;
        }
        float_dng_detail::require(plan.mode == "float_linear_v1" && plan.support == FloatDngSupport::Supported,
                                  "invalid float LinearRaw precision plan");
        const auto start = std::chrono::steady_clock::now();
        io::RawDecodeThreadScope thread_scope;
        FloatDngLinear linear;
        if (!decode_float_linear_dng(path, raw_float_linear_limits, user_mul, white_temperature,
                                    linear, error, cancelled)) return false;
        const auto& source = linear.source;
        const auto& expected = plan.source;
        float_dng_detail::require(source.width == expected.width && source.height == expected.height &&
            source.orientation == expected.orientation && source.black == expected.black &&
            source.white == expected.white && source.neutral == expected.neutral,
            "float LinearRaw source metadata changed after inspection");
        // TIFF orientations are not LibRaw flip flags: e.g. orientation 8 is
        // LibRaw flip 5. Preserve the decoder's actual flag and verify its match.
        constexpr int flips[] = {0,0,1,3,2,4,6,7,5};
        float_dng_detail::require(source.orientation <= 8 && plan.raw_flip == flips[source.orientation],
                                  "float LinearRaw source orientation mismatch");
        const auto& sizes = plan.source_sizes;
        float_dng_detail::require(sizes.raw_width == source.width && sizes.raw_height == source.height &&
            sizes.width == source.width && sizes.height == source.height &&
            sizes.top_margin == 0 && sizes.left_margin == 0 && sizes.pixel_aspect == 1,
            "float LinearRaw source geometry changed after inspection");
        candidate.width = linear.width;
        candidate.height = linear.height;
        candidate.rgb = std::move(linear.rgb);
        auto& metadata = candidate.metadata;
        // The strict v1 allowlist excludes every DNG display-rendering tag.
        // This is a DNG with no such supplied fields, not an applied display look.
        metadata.dng_render.is_dng = true;
        metadata.libraw_version = LibRaw::version();
        metadata.libraw_capabilities = LibRaw::capabilities();
        metadata.make = std::move(linear.make);
        metadata.model = std::move(linear.model);
        metadata.decoder_name = std::move(linear.decoder_name);
        metadata.sizes_before_postprocess = sizes;
        metadata.raw_flip = plan.raw_flip;
        metadata.camera_whitebalance_valid = true;
        metadata.camera_whitebalance = linear.camera_whitebalance;
        metadata.post_scale_pre_mul = linear.applied_whitebalance;
        metadata.colour_channels = 3;
        metadata.camera_from_xyz = linear.camera_from_xyz;
        metadata.camera_to_output = linear.camera_to_prophoto;
        // rgb_cam, integer black/white and postprocess fields remain unset:
        // neither dcraw_process nor its integer scaling ran on this branch.
        candidate.timings.openmp_enabled = io::raw_openmp_enabled();
        candidate.timings.workers = thread_scope.workers();
        candidate.timings.total_ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - start).count();
        float_dng_detail::check_cancel(cancelled);
        out = std::move(candidate);
        if (float_source) *float_source = source;
        return true;
    } catch (const io::RawDecodeCancelled&) {
        throw FloatDngCancelled();
    } catch (const FloatDngCancelled&) {
        throw;
    } catch (const std::exception& failure) {
        error = std::string("RAW precision dispatch: ") + failure.what();
        return false;
    }
}

} // namespace spk::android
