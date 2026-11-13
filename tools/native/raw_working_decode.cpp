// Co-created with GPT (OpenAI). Real LibRaw/precision dispatch, public synthetic
// fixtures only. No platform shims or replacement decoder.
#include "raw_working_input.hpp"
#include <chrono>
#include <cstring>
#include <iostream>
#include <sys/resource.h>

namespace {
void require(bool value, const std::string& message) {
    if (!value) throw std::runtime_error(message);
}
bool same(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0;
}
spk::android::RawPrecisionPlan inspect(const char* file, bool custom = false) {
    auto raw = std::make_unique<spk::io::detail::CameraLibRaw>();
    require(raw->open_file(file) == LIBRAW_SUCCESS, "open fixture metadata");
    return spk::android::inspect_raw_precision(file, *raw, custom);
}
void invalid_converters(const char* file) {
    using namespace spk::io;
    DecodedRaw out; out.width = 77; out.height = 88; out.rgb = {-9, 2, 11}; out.metadata.make = "previous";
    const auto original = out;
    std::string error;
    for (int bad = 0; bad < 4; ++bad) {
        const RawRgb16Converter convert = [bad](const RawRgb16View& source, RawFloatImage& result, const RawDecodeCancel&) {
            result.width = bad == 1 ? source.width + 1 : 1;
            result.height = 1;
            result.rgb = {1, 2, 3};
            if (bad == 0) result.width = 0;
            if (bad == 2) result.rgb.pop_back();
            if (bad == 3) throw std::runtime_error("converter test failure");
            return true;
        };
        require(!decode_raw_compatible(file, nullptr, out, error, 0, {}, convert) &&
            out.width == original.width && out.height == original.height && same(out.rgb, original.rgb) &&
            out.metadata.make == original.metadata.make, "invalid converter published partial output");
    }
    bool reached = false, cancelled = false;
    try {
        decode_raw_compatible(file, nullptr, out, error, 0, [&] { return reached; },
            [&](const RawRgb16View&, RawFloatImage&, const RawDecodeCancel& poll) -> bool {
                reached = true;
                require(poll(), "converter cancellation poll");
                throw std::runtime_error("cancellation takes precedence");
            });
    } catch (const RawDecodeCancelled&) { cancelled = true; }
    require(reached && cancelled && same(out.rgb, original.rgb) && out.width == 77, "late cancellation atomicity");
    std::cout << "PASS converter boundary: invalid=4; cancellation_precedence; unchanged_output\n";
}
void fixture(const char* file) {
    using namespace spk::android;
    const auto plan = inspect(file);
    spk::io::DecodedRaw full;
    std::string error;
    require(decode_raw_precision(file, plan, nullptr, 0, full, error), error);
    size_t cases = 0;
    for (const bool statistics : {false, true}) for (int cap = 0; cap < 4; ++cap) {
        uint32_t source_w = 0, source_h = 0;
        const uint32_t edge = cap == 0 ? 0 : cap == 1 ? std::max(full.width, full.height) :
            std::max(1u, std::max(full.width, full.height) * 3 / 4);
        const uint64_t pixels = cap == 3 ? uint64_t(full.width) * full.height / 3 : 0;
        const auto convert = raw_working_converter(plan, statistics, edge, pixels, source_w, source_h);
        require(bool(convert) == (plan.mode == "integer_compatible16" && !statistics && (edge || pixels)), "conversion gate");
        spk::io::DecodedRaw result;
        require(decode_raw_precision(file, plan, nullptr, 0, result, error, nullptr, {}, convert), error);
        uint32_t w, h;
        fit_rgb_size(full.width, full.height, edge, pixels, w, h);
        const bool shortened = bool(convert) && (w != full.width || h != full.height);
        std::vector<float> expected;
        if (shortened) resize_area_rgb(full.rgb, full.width, full.height, w, h, expected);
        else expected = full.rgb;
        require(same(result.rgb, expected) && result.width == (shortened ? w : full.width) &&
            result.height == (shortened ? h : full.height), "decoded float bits or dimensions changed");
        require(source_w == (shortened ? full.width : 0) && source_h == (shortened ? full.height : 0), "source provenance");
        require(result.metadata.camera_to_output == full.metadata.camera_to_output && result.metadata.raw_flip == full.metadata.raw_flip &&
            result.metadata.camera_whitebalance == full.metadata.camera_whitebalance && result.metadata.post_scale_pre_mul == full.metadata.post_scale_pre_mul &&
            result.metadata.make == full.metadata.make && result.metadata.model == full.metadata.model &&
            result.metadata.postprocess_black == full.metadata.postprocess_black && result.metadata.postprocess_maximum == full.metadata.postprocess_maximum,
            "camera metadata changed");
        ++cases;
    }
    if (plan.mode == "integer_compatible16") {
        const auto custom_plan = inspect(file, true);
        const std::array<float, 4> multipliers{2, 1, 1.5f, 1};
        spk::io::DecodedRaw custom_full, custom_working;
        require(decode_raw_precision(file, custom_plan, &multipliers, 0, custom_full, error), error);
        uint32_t sw = 0, sh = 0, w, h;
        const uint32_t edge = std::max(1u, std::max(custom_full.width, custom_full.height) * 3 / 4);
        const auto converter = raw_working_converter(custom_plan, false, edge, 0, sw, sh);
        require(decode_raw_precision(file, custom_plan, &multipliers, 0, custom_working, error, nullptr, {}, converter), error);
        fit_rgb_size(custom_full.width, custom_full.height, edge, 0, w, h);
        std::vector<float> expected;
        resize_area_rgb(custom_full.rgb, custom_full.width, custom_full.height, w, h, expected);
        require(same(custom_working.rgb, expected) && custom_working.width == w && custom_working.height == h &&
            sw == custom_full.width && sh == custom_full.height &&
            custom_working.metadata.post_scale_pre_mul == custom_full.metadata.post_scale_pre_mul &&
            custom_working.metadata.camera_to_output == custom_full.metadata.camera_to_output &&
            !custom_working.metadata.as_shot_wb_applied, "custom WB working conversion");
        ++cases;
    }
    std::cout << "PASS " << file << ": mode=" << plan.mode << ",flip=" << full.metadata.raw_flip
              << ",cases=" << cases << ",exact_bits; full_statistics_gate; original_dimensions\n";
    if (plan.mode == "integer_compatible16") invalid_converters(file);
}
void memory(const char* file, bool fused) {
    using namespace spk::android;
    const auto plan = inspect(file);
    require(plan.mode == "integer_compatible16", "memory fixture must use integer route");
    uint32_t source_w = 0, source_h = 0;
    const auto converter = fused ? raw_working_converter(plan, false, 1500, 1500000, source_w, source_h) : spk::io::RawRgb16Converter{};
    const auto start = std::chrono::steady_clock::now();
    spk::io::DecodedRaw decoded;
    std::string error;
    require(decode_raw_precision(file, plan, nullptr, 0, decoded, error, nullptr, {}, converter), error);
    uint32_t w, h;
    fit_rgb_size(decoded.width, decoded.height, 1500, 1500000, w, h);
    if (w != decoded.width || h != decoded.height) resize_area_rgb(decoded.rgb, decoded.width, decoded.height, w, h, decoded.rgb);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    uint64_t hash = 0xcbf29ce484222325ull;
    const auto* bytes = reinterpret_cast<const uint8_t*>(decoded.rgb.data());
    for (size_t i = 0; i < decoded.rgb.size() * sizeof(float); ++i) { hash ^= bytes[i]; hash *= 0x100000001b3ull; }
    rusage usage{}; getrusage(RUSAGE_SELF, &usage);
    std::cout << "decode_mode=" << (fused ? "fused" : "full") << ",peak_rss_kib=" << usage.ru_maxrss
        << ",ms=" << ms << ",fnv1a=" << std::hex << hash << '\n';
}
}
int main(int argc, char** argv) {
    try {
        if (argc == 3 && std::string(argv[1]).starts_with("--memory-")) memory(argv[2], std::string(argv[1]) == "--memory-fused");
        else { require(argc > 1, "synthetic DNG fixtures required"); for (int i = 1; i < argc; ++i) fixture(argv[i]); }
    } catch (const std::exception& failure) { std::cerr << failure.what() << '\n'; return 1; }
}
