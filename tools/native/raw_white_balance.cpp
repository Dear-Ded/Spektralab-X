// Co-created with GPT (OpenAI). DNG white-balance metadata/decode regression.
#include "io/raw_camera.hpp"
#include "raw_white_balance.hpp"
#include "white_balance.hpp"
#ifdef SPK_RAW_DECODE_TEST
#include "io/raw_decoder.hpp"
#endif

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <memory>
#include <string>

#ifdef SPK_RAW_DECODE_TEST
static bool decode_target(const char* path, spk::io::detail::CameraLibRaw& calibration,
                          const std::array<float, 4>& selected, double target_k, double target_tint) {
    // Exercise the product's optional Bayer binning to keep this regression
    // bounded. The calibration and output conversion are shared with full size.
    spk::io::set_raw_half_size_budget(1);
    spk::io::DecodedRaw decoded;
    std::string error;
    const bool ok = spk::io::decode_raw_compatible(path, &selected, decoded, error, target_k);
    spk::io::set_raw_half_size_budget(0);
    if (!ok || decoded.rgb.empty()) {
        std::fprintf(stderr, "%s: actual decode %.0f/%+.0f failed: %s\n", path, target_k, target_tint, error.c_str());
        return false;
    }
    for (float value : decoded.rgb) if (!std::isfinite(value)) {
        std::fprintf(stderr, "%s: nonfinite actual decode pixel\n", path);
        return false;
    }
    // Recover the chosen illuminant from the matrix actually reported by the
    // decoder, not from the metadata-only matrix used to construct the input.
    for (int r = 0; r < 3; ++r) for (int c = 0; c < 3; ++c)
        calibration.imgdata.color.cam_xyz[r][c] = decoded.metadata.camera_from_xyz[r * 3 + c];
    double actual_k = 0, actual_tint = 0;
    if (!spkhost::as_shot_temperature_tint(calibration, actual_k, actual_tint) ||
        !std::isfinite(actual_k) || !std::isfinite(actual_tint) ||
        std::abs(actual_k - target_k) > 4 || std::abs(actual_tint - target_tint) > .1) {
        std::fprintf(stderr, "%s: actual decode target %.0f/%+.0f became %.6f/%+.6f\n",
                     path, target_k, target_tint, actual_k, actual_tint);
        return false;
    }
    calibration.imgdata.params.output_color = 4;
    std::copy(decoded.metadata.post_scale_pre_mul.begin(), decoded.metadata.post_scale_pre_mul.end(),
              calibration.imgdata.color.pre_mul);
    float expected[3][4] = {};
    calibration.apply_dng_forward_matrix(expected);
    double amplitude = 0, matrix_error = 0;
    for (int r = 0; r < 3; ++r) for (int c = 0; c < 4; ++c) {
        if (!std::isfinite(decoded.metadata.camera_to_output[r * 4 + c])) {
            std::fprintf(stderr, "%s: nonfinite actual decode matrix\n", path);
            return false;
        }
        amplitude += std::abs(expected[r][c]);
        matrix_error = std::max(matrix_error,
            std::abs(double(expected[r][c]) - decoded.metadata.camera_to_output[r * 4 + c]));
    }
    const bool forward = amplitude > 0;
    if (forward && matrix_error > 1e-6) {
        std::fprintf(stderr, "%s: actual decode did not use the selected FM (error %.9g)\n", path, matrix_error);
        return false;
    }
    std::printf("  actual decode %.0f/%+.0f -> %.6f/%+.6f, %ux%u, FM %s, finite pixels\n",
                target_k, target_tint, actual_k, actual_tint, decoded.width, decoded.height,
                forward ? "matched" : "not applicable");
    return true;
}
#endif

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: raw_white_balance <raw> [raw ...]\n");
        return 2;
    }
    for (int i = 1; i < argc; ++i) {
        auto raw = std::make_unique<spk::io::detail::CameraLibRaw>();
        raw->imgdata.params.use_camera_matrix = 1;
        const int status = raw->open_file(argv[i]);
        if (status != LIBRAW_SUCCESS) {
            std::fprintf(stderr, "%s: %s\n", argv[i], LibRaw::strerror(status));
            return 1;
        }
        raw->interpolate_dng_matrix(nullptr);
        double temperature = 0, tint = 0;
        std::array<float, 4> multipliers{};
        std::string error;
        if (!spkhost::as_shot_temperature_tint(*raw, temperature, tint) ||
            !std::isfinite(temperature) || temperature <= 0 || !std::isfinite(tint) ||
            !spkhost::multipliers_for(*raw, temperature, tint, multipliers, error)) {
            std::fprintf(stderr, "%s: cannot derive RAW white balance: %s\n", argv[i], error.c_str());
            return 1;
        }
        double worst = 0;
        for (int c = 0; c < 3; ++c) {
            const double expected = raw->imgdata.color.cam_mul[c] / raw->imgdata.color.cam_mul[1];
            if (!std::isfinite(expected) || expected <= 0) return 1;
            worst = std::max(worst, std::abs(multipliers[c] / expected - 1.0));
        }
        // Robertson's table interpolates temperature/tint; it is not a lossless
        // coordinate representation. This catches missing/mismatched matrices.
        if (!std::isfinite(worst) || worst > 0.005) {
            std::fprintf(stderr, "%s: white-balance round trip error %.9g\n", argv[i], worst);
            return 1;
        }
        std::printf("%s: %.2f K tint %.4f, multiplier relative error %.9g\n",
                    argv[i], temperature, tint, worst);
        // Use the same helper as JNI. Reopen the metadata for every control
        // setting, then let the actual decoder calibration run on those WB
        // multipliers. Its inverse must recover the selected UI values.
        unsigned accepted = 0, rejected = 0;
        double worst_k = 0, worst_tint = 0;
        for (double target_k : {2000., 2850., 4000., 6500., 12000.}) {
            for (double target_tint : {-150., 0., 150.}) {
                auto custom = std::make_unique<spk::io::detail::CameraLibRaw>();
                custom->imgdata.params.use_camera_matrix = 1;
                if (custom->open_file(argv[i]) != LIBRAW_SUCCESS) return 1;
                custom->interpolate_dng_matrix(nullptr);
                std::array<float, 4> selected{};
                if (!spk::android::raw_multipliers_for(*custom, target_k, target_tint, selected, error)) {
                    if (error != "white balance outside what this camera can neutralise") {
                        std::fprintf(stderr, "%s: UI WB %.0f/%+.0f failed: %s\n",
                                     argv[i], target_k, target_tint, error.c_str());
                        return 1;
                    }
                    ++rejected;
                    continue;
                }
                custom->interpolate_dng_matrix(&selected, target_k);
                std::copy(selected.begin(), selected.end(), custom->imgdata.color.cam_mul);
                double actual_k = 0, actual_tint = 0;
                if (!spkhost::as_shot_temperature_tint(*custom, actual_k, actual_tint)) return 1;
                worst_k = std::max(worst_k, std::abs(actual_k - target_k));
                worst_tint = std::max(worst_tint, std::abs(actual_tint - target_tint));
                if (!std::isfinite(actual_k) || !std::isfinite(actual_tint) ||
                    std::abs(actual_k - target_k) > 4 || std::abs(actual_tint - target_tint) > .1) {
                    std::fprintf(stderr, "%s: UI WB %.0f/%+.0f decoded as %.4f/%+.4f\n",
                                 argv[i], target_k, target_tint, actual_k, actual_tint);
                    return 1;
                }
#ifdef SPK_RAW_DECODE_TEST
                if (((target_k == 2850 || target_k == 12000) && target_tint == 0) ||
                    ((target_k == 4000 || target_k == 6500) && target_tint == -150)) {
                    if (!decode_target(argv[i], *custom, selected, target_k, target_tint)) return 1;
                }
#endif
                ++accepted;
            }
        }
        if (accepted < 5) return 1;
        std::printf("  custom WB: %u matched, %u outside camera range (K %.6g, tint %.6g)\n",
                    accepted, rejected, worst_k, worst_tint);
    }
    return 0;
}
