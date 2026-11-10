// Co-created with GPT (OpenAI). Production dispatcher parity and atomicity probe.
#include "raw_precision_input.hpp"
#include <cstring>
#include <iomanip>
#include <iostream>

namespace {
void string(const std::string& value) {
    std::cout << '"';
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') std::cout << '\\';
        std::cout << (c < 32 ? '?' : char(c));
    }
    std::cout << '"';
}
template<class T> void array(const T& values) {
    std::cout << '[';
    bool comma = false;
    for (auto value : values) { if (comma) std::cout << ','; std::cout << value; comma = true; }
    std::cout << ']';
}
bool same(const spk::android::FloatDngMetadata& a, const spk::android::FloatDngMetadata& b) {
    return a.width == b.width && a.height == b.height && a.orientation == b.orientation &&
        a.black == b.black && a.white == b.white && a.neutral == b.neutral;
}
bool same(const spk::io::RawSourceSizes& a, const spk::io::RawSourceSizes& b) {
    return a.raw_width == b.raw_width && a.raw_height == b.raw_height && a.width == b.width &&
        a.height == b.height && a.iwidth == b.iwidth && a.iheight == b.iheight &&
        a.top_margin == b.top_margin && a.left_margin == b.left_margin && a.pixel_aspect == b.pixel_aspect;
}
bool same(const spk::io::RawDngRenderMetadata& a, const spk::io::RawDngRenderMetadata& b) {
    return a.is_dng == b.is_dng && a.baseline_exposure_present == b.baseline_exposure_present &&
        a.baseline_exposure_invalid == b.baseline_exposure_invalid && a.baseline_exposure == b.baseline_exposure &&
        a.baseline_exposure_offset_present == b.baseline_exposure_offset_present &&
        a.baseline_exposure_offset_invalid == b.baseline_exposure_offset_invalid &&
        a.baseline_exposure_offset == b.baseline_exposure_offset &&
        a.default_black_render_present == b.default_black_render_present &&
        a.default_black_render_invalid == b.default_black_render_invalid && a.default_black_render == b.default_black_render &&
        a.profile_tone_curve_present == b.profile_tone_curve_present &&
        a.profile_tone_curve_invalid == b.profile_tone_curve_invalid && a.profile_tone_curve == b.profile_tone_curve &&
        a.extra_camera_profiles == b.extra_camera_profiles && a.profile_source_mismatch == b.profile_source_mismatch;
}
bool same(const spk::io::RawMetadata& a, const spk::io::RawMetadata& b) {
    return same(a.dng_render,b.dng_render) && a.libraw_version == b.libraw_version &&
        a.libraw_capabilities == b.libraw_capabilities && a.make == b.make && a.model == b.model &&
        a.decoder_name == b.decoder_name && same(a.sizes_before_postprocess,b.sizes_before_postprocess) &&
        a.raw_flip == b.raw_flip && a.camera_whitebalance_valid == b.camera_whitebalance_valid &&
        a.camera_whitebalance == b.camera_whitebalance && a.camera_whitebalance_from_xy == b.camera_whitebalance_from_xy &&
        a.camera_white_xy == b.camera_white_xy && a.post_scale_pre_mul == b.post_scale_pre_mul &&
        a.as_shot_wb_applied == b.as_shot_wb_applied && a.black == b.black &&
        a.black_level_per_channel == b.black_level_per_channel && a.black_pattern_rows == b.black_pattern_rows &&
        a.black_pattern_cols == b.black_pattern_cols && a.black_pattern == b.black_pattern &&
        a.white_level == b.white_level && a.camera_white_level_per_channel == b.camera_white_level_per_channel &&
        a.postprocess_black == b.postprocess_black && a.postprocess_maximum == b.postprocess_maximum &&
        a.process_warnings == b.process_warnings && a.rgb_cam == b.rgb_cam && a.colour_channels == b.colour_channels &&
        a.camera_from_xyz == b.camera_from_xyz && a.headroom_enabled == b.headroom_enabled &&
        a.white_balance_exposure_restore == b.white_balance_exposure_restore && a.camera_to_output == b.camera_to_output;
}
bool same_pixels(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() && (a.empty() || std::memcmp(a.data(),b.data(),a.size()*sizeof(float)) == 0);
}
}

int main(int argc, char** argv) {
    using namespace spk::android;
    if (argc < 3) return 2;
    bool custom = false, bad_plan = false, wrong_wb = false;
    int cancel_at = 0;
    for (int i = 3; i < argc; ++i) {
        const std::string option = argv[i];
        if (option == "--custom") custom = true;
        else if (option == "--bad-plan") bad_plan = true;
        else if (option == "--wrong-wb") wrong_wb = true;
        else if (option == "--cancel" && i + 1 < argc) cancel_at = std::stoi(argv[++i]);
        else return 2;
    }
    auto raw = std::make_unique<spk::io::detail::CameraLibRaw>();
    const int opened = raw->open_file(argv[1]);
    if (opened != LIBRAW_SUCCESS) { std::cout << "{\"open_status\":" << opened << "}\n"; return 0; }
    std::vector<unsigned char> snapshot(sizeof(raw->imgdata));
    std::memcpy(snapshot.data(), &raw->imgdata, snapshot.size());
    auto plan = inspect_raw_precision(argv[1], *raw, custom);
    if (bad_plan) plan.mode = "invalid-test-plan";
    spk::io::DecodedRaw output;
    output.width = 77; output.height = 88; output.rgb = {-9,-3,5};
    output.metadata.make = "unchanged"; output.timings.total_ms = -1;
    const auto previous = output;
    FloatDngMetadata source{77,88,8,{1,2,3},{4,5,6},{7,8,9}};
    const auto previous_source = source;
    const std::array<float,4> custom_mul{2,1,1.5,1};
    const auto* user_mul = custom || wrong_wb ? &custom_mul : nullptr;
    const double temperature = user_mul ? 6500 : 0;
    std::string error;
    bool ok = false, cancelled = false;
    int polls = 0;
    try {
        ok = decode_raw_precision(argv[1], plan, user_mul, temperature, output, error, &source,
                                 [&] { return ++polls == cancel_at; });
    } catch (const FloatDngCancelled&) { cancelled = true; }
    const bool unchanged = output.width == previous.width && output.height == previous.height &&
        same_pixels(output.rgb,previous.rgb) && same(output.metadata,previous.metadata) && output.timings.total_ms == -1;
    const bool source_unchanged = same(source,previous_source);
    const bool raw_unchanged = std::memcmp(snapshot.data(), &raw->imgdata, snapshot.size()) == 0;
    bool exact_pixels = false, exact_metadata = false, reference_ok = false;
    std::string reference_error;
    if (!cancel_at && !bad_plan && !wrong_wb) {
        if (plan.mode == "float_linear_v1") {
            FloatDngLinear reference;
            reference_ok = decode_float_linear_dng(argv[1],raw_float_linear_limits,reference,reference_error);
            exact_pixels = reference_ok == ok && (!ok || (output.width == reference.width && output.height == reference.height &&
                same_pixels(output.rgb,reference.rgb)));
            if (ok) {
                spk::io::RawDngRenderMetadata expected_render;
                expected_render.is_dng = true;
                exact_metadata = same(source,reference.source) && output.metadata.raw_flip == raw->imgdata.sizes.flip &&
                    same(output.metadata.sizes_before_postprocess,plan.source_sizes) &&
                    output.metadata.make == reference.make && output.metadata.model == reference.model &&
                    output.metadata.decoder_name == reference.decoder_name &&
                    output.metadata.camera_whitebalance_valid && output.metadata.camera_whitebalance == reference.camera_whitebalance &&
                    output.metadata.post_scale_pre_mul == reference.applied_whitebalance &&
                    output.metadata.camera_from_xyz == reference.camera_from_xyz &&
                    output.metadata.camera_to_output == reference.camera_to_prophoto &&
                    output.metadata.colour_channels == 3 && same(output.metadata.dng_render,expected_render) &&
                    !output.metadata.as_shot_wb_applied && !output.metadata.headroom_enabled &&
                    output.metadata.black == 0 && output.metadata.black_level_per_channel == std::array<std::uint32_t,4>{} &&
                    output.metadata.white_level == 0 && output.metadata.camera_white_level_per_channel == std::array<std::uint32_t,4>{} &&
                    output.metadata.postprocess_black == 0 && output.metadata.postprocess_maximum == 0 &&
                    output.metadata.rgb_cam == std::array<float,12>{};
            } else exact_metadata = source_unchanged;
        } else {
            spk::io::DecodedRaw reference;
            reference_ok = spk::io::decode_raw_compatible(argv[1],user_mul,reference,reference_error,temperature);
            exact_pixels = reference_ok == ok && (!ok || (output.width == reference.width && output.height == reference.height &&
                same_pixels(output.rgb,reference.rgb)));
            exact_metadata = source_unchanged && (!ok || same(output.metadata,reference.metadata));
        }
    }
    if (ok) {
        std::ofstream file(argv[2],std::ios::binary);
        file.write(reinterpret_cast<const char*>(output.rgb.data()),output.rgb.size()*sizeof(float));
        if (!file) return 3;
    }
    std::cout << std::setprecision(17) << std::boolalpha << "{\"open_status\":0,\"mode\":"; string(plan.mode);
    std::cout << ",\"note\":"; string(plan.note);
    std::cout << ",\"ok\":" << ok << ",\"cancelled\":" << cancelled << ",\"polls\":" << polls
        << ",\"raw_unchanged\":" << raw_unchanged << ",\"unchanged\":" << unchanged
        << ",\"float_metadata_unchanged\":" << source_unchanged << ",\"reference_ok\":" << reference_ok
        << ",\"exact_pixels\":" << exact_pixels << ",\"exact_metadata\":" << exact_metadata
        << ",\"width\":" << output.width << ",\"height\":" << output.height
        << ",\"raw_flip\":" << output.metadata.raw_flip << ",\"error\":"; string(error);
    std::cout << ",\"float_black\":"; array(source.black);
    std::cout << ",\"float_white\":"; array(source.white);
    std::cout << "}\n";
    return raw_unchanged && (ok || (unchanged && source_unchanged)) ? 0 : 4;
}
