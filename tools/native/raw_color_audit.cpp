// Co-created with GPT (OpenAI). Observe the production linear RAW input.
#include "raw_precision_input.hpp"
#include <bit>
#include <charconv>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>

namespace {
void require(bool value, const std::string& error) {
    if (!value) throw std::runtime_error(error);
}
void text(std::ostream& out, const std::string& value) {
    out << '"';
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') out << '\\';
        if (c < 32) out << "\\u00" << "0123456789abcdef"[c >> 4] << "0123456789abcdef"[c & 15];
        else out << char(c);
    }
    out << '"';
}
template<class T> void array(std::ostream& out, const T& values) {
    out << '[';
    bool comma = false;
    for (const auto value : values) {
        if (comma) out << ',';
        out << value;
        comma = true;
    }
    out << ']';
}
template<class T> void field(std::ostream& out, const char* key, const T& value) {
    out << ",\n  "; text(out, key); out << ':' << value;
}
void field(std::ostream& out, const char* key, const std::string& value) {
    out << ",\n  "; text(out, key); out << ':'; text(out, value);
}
template<class T> void list(std::ostream& out, const char* key, const T& values) {
    out << ",\n  "; text(out, key); out << ':'; array(out, values);
}

int audit(const std::filesystem::path& source, const std::filesystem::path& prefix,
          std::uint64_t half_budget) {
    using namespace spk;
    static_assert(std::endian::native == std::endian::little, "f32 output is little endian");
    auto raw = std::make_unique<io::detail::CameraLibRaw>();
    const int status = raw->open_file(source.c_str());
    require(status == LIBRAW_SUCCESS, std::string("open: ") + LibRaw::strerror(status));
    const auto plan = android::inspect_raw_precision(source, *raw, false);
    const auto sizes = raw->imgdata.sizes;
    // Inspect calibration on this separate instance; decode uses its own state.
    raw->imgdata.params.output_color = 4;
    raw->imgdata.params.use_camera_matrix = 1;
    const bool calibration = raw->interpolate_dng_matrix(nullptr);
    io::set_raw_half_size_budget(half_budget);
    io::DecodedRaw decoded;
    std::string error;
    android::FloatDngMetadata float_source;
    require(android::decode_raw_precision(source, plan, nullptr, 0, decoded, error, &float_source), error);
    require(decoded.rgb.size() == std::uint64_t(decoded.width) * decoded.height * 3, "pixel size mismatch");
    require(std::all_of(decoded.rgb.begin(), decoded.rgb.end(),
        [](float value) { return std::isfinite(value); }), "non-finite output");
    auto pixel_path = prefix, report_path = prefix;
    pixel_path += ".f32"; report_path += ".json";
    std::ofstream pixels(pixel_path, std::ios::binary);
    pixels.write(reinterpret_cast<const char*>(decoded.rgb.data()), decoded.rgb.size() * sizeof(float));
    pixels.close();
    require(bool(pixels), "cannot write pixels");
    std::ofstream out(report_path);
    out << std::setprecision(std::numeric_limits<double>::max_digits10) << std::boolalpha;
    const auto& m = decoded.metadata;
    out << "{\n  \"schema\":1";
    field(out, "mode", plan.mode); field(out, "note", plan.note);
    field(out, "width", decoded.width); field(out, "height", decoded.height);
    field(out, "half_size_budget", half_budget);
    field(out, "libraw_version", m.libraw_version);
    field(out, "make", m.make); field(out, "model", m.model); field(out, "decoder", m.decoder_name);
    field(out, "raw_flip", m.raw_flip);
    list(out, "source_geometry", std::array<unsigned,8>{sizes.raw_width, sizes.raw_height,
        sizes.width, sizes.height, sizes.iwidth, sizes.iheight, sizes.left_margin, sizes.top_margin});
    for (int i = 0; i < 2; ++i) {
        const auto& crop = sizes.raw_inset_crops[i];
        list(out, i == 0 ? "inset_crop_0" : "inset_crop_1", std::array<unsigned,4>{
            crop.cleft, crop.ctop, crop.cwidth, crop.cheight});
    }
    field(out, "camera_wb_valid", m.camera_whitebalance_valid);
    list(out, "camera_wb", m.camera_whitebalance); list(out, "applied_wb", m.post_scale_pre_mul);
    field(out, "black", m.black); list(out, "black_per_channel", m.black_level_per_channel);
    list(out, "black_pattern_shape", std::array<unsigned,2>{m.black_pattern_rows, m.black_pattern_cols});
    list(out, "black_pattern", m.black_pattern);
    field(out, "white", m.white_level); list(out, "white_per_channel", m.camera_white_level_per_channel);
    field(out, "postprocess_black", m.postprocess_black);
    field(out, "postprocess_maximum", m.postprocess_maximum);
    field(out, "process_warnings", m.process_warnings);
    list(out, "camera_from_xyz", m.camera_from_xyz); list(out, "camera_to_output", m.camera_to_output);
    list(out, "rgb_cam", m.rgb_cam);
    if (plan.mode == "float_linear_v1") {
        list(out, "float_black", float_source.black); list(out, "float_white", float_source.white);
        list(out, "float_neutral", float_source.neutral);
    }
    field(out, "calibration_prepared", calibration); field(out, "matrix_kelvin", raw->dng_matrix_k);
    for (int slot = 0; slot < 2; ++slot) {
        const auto& c = raw->imgdata.color.dng_color[slot];
        std::array<float,9> cm{}, fm{};
        for (int r = 0; r < 3; ++r) for (int k = 0; k < 3; ++k) {
            cm[r*3+k] = c.colormatrix[r][k]; fm[r*3+k] = c.forwardmatrix[r][k];
        }
        field(out, slot == 0 ? "illuminant_0" : "illuminant_1", c.illuminant);
        list(out, slot == 0 ? "color_matrix_0" : "color_matrix_1", cm);
        list(out, slot == 0 ? "forward_matrix_0" : "forward_matrix_1", fm);
    }
    field(out, "headroom_enabled", m.headroom_enabled);
    field(out, "baseline_exposure_present", m.dng_render.baseline_exposure_present);
    field(out, "baseline_exposure", m.dng_render.baseline_exposure);
    field(out, "profile_tone_curve_points", m.dng_render.profile_tone_curve.size());
    out << ",\n  \"profile_tone_curve\":[";
    bool comma = false;
    for (const auto& point : m.dng_render.profile_tone_curve) {
        if (comma) out << ',';
        array(out, point); comma = true;
    }
    out << ']';
    field(out, "openmp_enabled", decoded.timings.openmp_enabled);
    field(out, "workers", decoded.timings.workers);
    field(out, "total_ms", decoded.timings.total_ms);
    out << "\n}\n";
    out.close();
    require(bool(out), "cannot write report");
    std::cout << plan.mode << ' ' << decoded.width << 'x' << decoded.height << " PASS\n";
    return 0;
}
template<class Char> int entry(int argc, Char** argv) {
    try {
        require(argc == 3 || argc == 4, "usage: raw_color_audit INPUT OUTPUT_PREFIX [HALF_SIZE_PIXEL_BUDGET]");
        std::uint64_t budget = 0;
        if (argc == 4) {
            const auto option = std::filesystem::path(argv[3]).string();
            const auto parsed = std::from_chars(option.data(), option.data() + option.size(), budget);
            require(parsed.ec == std::errc{} && parsed.ptr == option.data() + option.size(), "invalid budget");
        }
        return audit(std::filesystem::path(argv[1]), std::filesystem::path(argv[2]), budget);
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n'; return 1;
    }
}
}
#ifdef _WIN32
int wmain(int argc, wchar_t** argv) { return entry(argc, argv); }
#else
int main(int argc, char** argv) { return entry(argc, argv); }
#endif
