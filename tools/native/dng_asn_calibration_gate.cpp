// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Synthetic ASN metadata interpolation gate regression.
// Co-created with GPT (OpenAI), 2026-11-11.
#include "io/raw_camera.hpp"

#include <array>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <limits>
#include <memory>
#include <string>

int main(int argc, char** argv) {
    if (argc < 2 || argc > 3) return 2;
    auto raw = std::make_unique<spk::io::detail::CameraLibRaw>();
    raw->imgdata.params.use_camera_matrix = 1;
    const int opened = raw->open_file(argv[1]);
    if (opened == LIBRAW_SUCCESS && argc == 3) {
        const std::string kind = argv[2];
        const float value = kind == "nan" ? std::numeric_limits<float>::quiet_NaN() :
            kind == "inf" ? std::numeric_limits<float>::infinity() : 1e-8f;
        // Isolate the interpolation gate from LibRaw's file-open rejection.
        for (auto& slot : raw->imgdata.color.dng_color) {
            for (int r = 0; r < 3; ++r) for (int c = 0; c < 3; ++c)
                slot.calibration[r][c] = float(r == c);
            slot.calibration[0][1] = value;
        }
    }
    const std::array<float, 4> wb{2, 1, 1.5f, 1};
    const bool ok = opened == LIBRAW_SUCCESS && raw->interpolate_dng_matrix(&wb, 6500);
    bool finite_cc = true;
    for (const auto& slot : raw->imgdata.color.dng_color)
        for (const auto& row : slot.calibration)
            for (float value : row) finite_cc &= std::isfinite(value);
    std::cout << std::setprecision(17) << "{\"open\":" << opened
              << ",\"interpolate_ok\":" << ok << ",\"finite_cc\":" << finite_cc
              << ",\"kelvin\":" << raw->dng_matrix_k << ",\"camera_xyz\":[";
    for (int r = 0; r < 3; ++r) for (int c = 0; c < 3; ++c) {
        if (r || c) std::cout << ',';
        const float value = raw->imgdata.color.cam_xyz[r][c];
        if (std::isfinite(value)) std::cout << value;
        else std::cout << "null";
    }
    std::cout << "]}\n";
}
