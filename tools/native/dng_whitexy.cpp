#include "io/raw_camera.hpp"
#include "io/raw_decoder.hpp"
#include "raw_white_balance.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <string>

#ifndef SPK_DNG_WHITE_XY
#define SPK_DNG_WHITE_XY 1
#endif

namespace {
void emit_string(const std::string& value) {
    std::cout << '"';
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') std::cout << '\\' << c;
        else if (c < 32) std::cout << '?';
        else std::cout << c;
    }
    std::cout << '"';
}
template<class T> void array(const T& values) {
    std::cout << '[';
    bool comma = false;
    for (auto value : values) {
        if (comma) std::cout << ',';
        std::cout << value;
        comma = true;
    }
    std::cout << ']';
}
}

int main(int argc, char** argv) {
    if (argc < 4 || argc > 5) return 2;
    const bool headroom = std::string(argv[2]) == "headroom";
    const bool custom = std::string(argv[3]) == "custom";
    if (const char* value = std::getenv("SPK_TEST_RAW_BUDGET"))
        spk::io::set_raw_half_size_budget(std::strtoull(value, nullptr, 10));
    auto raw = std::make_unique<spk::io::detail::CameraLibRaw>();
    raw->imgdata.params.use_camera_matrix = 1;
    const int opened = raw->open_file(argv[1]);
    std::array<float, 4> selected{};
    std::string error;
    bool selected_ok = true;
    bool matrix_ok = false;
    double source_temperature = 0, source_tint = 0;
    bool source_temperature_valid = false;
    if (opened == LIBRAW_SUCCESS) {
        matrix_ok = raw->interpolate_dng_matrix(nullptr);
        source_temperature_valid = spkhost::as_shot_temperature_tint(*raw, source_temperature, source_tint);
        if (custom) {
            selected_ok = spk::android::raw_multipliers_for(*raw, 6500, 0, selected, error);
            if (!selected_ok) {
                selected = {2, 1, 1.5f, 1};
                selected_ok = true;
                error.clear();
            }
        }
    }
    spk::io::DecodedRaw out;
    out.width = 7; out.height = 11; out.rgb = {.125f, -.25f, .5f};
    out.metadata.model = "unchanged";
    const auto* wb = custom ? &selected : nullptr;
    bool ok = false;
    if (selected_ok) {
        ok = headroom ? spk::io::decode_raw_headroom(argv[1], wb, out, error, custom ? 6500 : 0)
                      : spk::io::decode_raw_compatible(argv[1], wb, out, error, custom ? 6500 : 0);
    }
    const bool unchanged = out.width == 7 && out.height == 11 &&
        out.rgb == std::vector<float>({.125f, -.25f, .5f}) && out.metadata.model == "unchanged";
    bool finite = true;
    for (float value : out.rgb) finite &= std::isfinite(value);
    std::array<double, 3> mean{};
    double maximum_pixel_spread = 0;
    if (ok) {
        for (std::size_t i = 0; i < out.rgb.size(); ++i) mean[i % 3] += out.rgb[i];
        for (double& value : mean) value /= std::size_t(out.width) * out.height;
        for (std::size_t i = 0; i < out.rgb.size(); i += 3) {
            const auto values = {out.rgb[i], out.rgb[i+1], out.rgb[i+2]};
            maximum_pixel_spread = std::max(maximum_pixel_spread,
                double(*std::max_element(values.begin(), values.end())-
                       *std::min_element(values.begin(), values.end())));
        }
    }
    if (ok && argc == 5) {
        std::ofstream file(argv[4], std::ios::binary);
        file.write(reinterpret_cast<const char*>(out.rgb.data()), out.rgb.size() * sizeof(float));
        if (!file) return 3;
    }
    std::cout << std::setprecision(17) << "{\"open\":" << opened << ",\"ok\":" << ok
              << ",\"unchanged\":" << unchanged << ",\"matrix_ok\":" << matrix_ok
              << ",\"finite\":" << finite << ",\"width\":" << out.width
              << ",\"height\":" << out.height << ",\"kelvin\":" << raw->dng_matrix_k
              << ",\"source_valid\":" << out.metadata.camera_whitebalance_valid
              << ",\"temperature_valid\":" << source_temperature_valid
              << ",\"source_temperature\":" << source_temperature << ",\"source_tint\":" << source_tint;
#if SPK_DNG_WHITE_XY
    std::cout << ",\"derived\":" << raw->dng_white_xy_derived
              << ",\"from_xy\":" << out.metadata.camera_whitebalance_from_xy << ",\"xy\":";
    array(out.metadata.camera_white_xy);
#endif
    std::cout << ",\"source_wb\":"; array(out.metadata.camera_whitebalance);
    std::cout << ",\"selected_wb\":"; array(selected);
    std::cout << ",\"applied_wb\":"; array(out.metadata.post_scale_pre_mul);
    std::cout << ",\"camera_to_output\":"; array(out.metadata.camera_to_output);
    std::cout << ",\"camera_xyz\":";
    std::array<float, 9> camera{};
    for (int r = 0; r < 3; ++r) for (int c = 0; c < 3; ++c) camera[r * 3 + c] = raw->imgdata.color.cam_xyz[r][c];
    array(camera);
    std::cout << ",\"mean\":"; array(mean);
    std::cout << ",\"maximum_pixel_spread\":" << maximum_pixel_spread;
    std::cout << ",\"error\":"; emit_string(error);
    std::cout << "}\n";
    return 0;
}
