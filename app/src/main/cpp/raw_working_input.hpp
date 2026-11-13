#pragma once

// Co-created with GPT (OpenAI). Preserve decode policy and only shorten the
// lifetime of the integer RGB16-to-working-float conversion.
#include "raw_precision_input.hpp"
#include "resize.hpp"

namespace spk::android {

inline io::RawRgb16Converter raw_working_converter(const RawPrecisionPlan& plan,
        bool full_source_statistics, uint32_t max_edge, uint64_t max_pixels,
        uint32_t& source_width, uint32_t& source_height) {
    if (plan.mode != "integer_compatible16" || full_source_statistics || (!max_edge && !max_pixels)) return {};
    return [max_edge, max_pixels, &source_width, &source_height](const io::RawRgb16View& source,
            io::RawFloatImage& output, const io::RawDecodeCancel& cancelled) {
        uint32_t w, h;
        fit_rgb_size(source.width, source.height, max_edge, max_pixels, w, h);
        if (w == source.width && h == source.height) return false;
        if (!resize_area_rgb16(source.data, source.bytes, source.width, source.height,
                               w, h, output.rgb, cancelled)) return false;
        output.width = w;
        output.height = h;
        source_width = source.width;
        source_height = source.height;
        return true;
    };
}

} // namespace spk::android
