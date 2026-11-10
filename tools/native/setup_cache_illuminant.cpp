// Co-created with GPT (OpenAI), 2026-11-10. See NOTICE.
// Real LUT builder, synthetic film and RGB samples; no image files are read.
#include "core/setup_cache.hpp"
#include "core/printing.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <stdexcept>

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    using namespace spk;
    std::string error;
    const auto check = [](bool ok, const std::string& message) {
        if (!ok) throw std::runtime_error(message);
    };
    try {
        Blob blob; check(blob.open(std::string(argv[1]) + "/spektrafilm_constants.bin", error), error);
        Colour colour; check(colour.init(blob, error), error);
        Profile film;
        film.info.stock = "synthetic_illuminant_cache";
        film.info.reference_illuminant = "D55";
        film.data.adaptation_window_params = {420, 20, 680, 25};
        Vec sensitivity(kNumWavelengths * 3);
        for (size_t i = 0; i < kNumWavelengths; ++i)
            for (int c = 0; c < 3; ++c) {
                const double distance = (colour.wavelengths()[i] - (620 - 80 * c)) / 45;
                sensitivity[i * 3 + c] = std::exp(-.5 * distance * distance);
            }
        SettingsParams settings;
        GamutCompressSpec compress;
        SetupCache cache;
        size_t side = 0;
        std::shared_ptr<const Vec> d55, repeated, d65, returned;
        check(cache.tc_lut(colour, blob, film, settings, compress, sensitivity, d55, side, error), error);
        check(cache.tc_lut(colour, blob, film, settings, compress, sensitivity, repeated, side, error), error);
        check(d55 == repeated, "unchanged illuminant did not reuse its LUT");
        film.info.reference_illuminant = "D65";
        check(cache.tc_lut(colour, blob, film, settings, compress, sensitivity, d65, side, error), error);
        Vec fresh;
        check(build_tc_lut(colour, blob, film, settings, compress, sensitivity, fresh, side, error), error);
        double baseline_delta = 0, cache_error = 0;
        for (size_t i = 0; i < fresh.size(); ++i) {
            baseline_delta = std::max(baseline_delta, std::abs((*d55)[i] - fresh[i]));
            cache_error = std::max(cache_error, std::abs((*d65)[i] - fresh[i]));
        }
        std::cout << "changed_illuminant_lut_delta=" << baseline_delta
                  << " cached_fresh_max_error=" << cache_error << '\n';
        check(baseline_delta > 1e-5, "synthetic fixture does not distinguish illuminants");
        check(*d65 == fresh, "changed illuminant reused a stale LUT");
        Mat3 matrix;
        check(tc_b_matrix(colour, blob, "ProPhoto RGB", "D65", matrix, error), error);
        for (const auto& rgb : {std::array<double,3>{.18,.18,.18}, {.01,.03,.3}, {.2,.02,.02}}) {
            double xyz[3], tc[2], cached_raw[3], fresh_raw[3];
            matrix.apply(rgb.data(), xyz);
            const double total = xyz[0] + xyz[1] + xyz[2];
            const double xy[2] = {xyz[0] / total, xyz[1] / total};
            tri2quad(xy, tc);
            lut2d_cubic_host(*d65, side, tc, cached_raw);
            lut2d_cubic_host(fresh, side, tc, fresh_raw);
            for (int c = 0; c < 3; ++c)
                check(cached_raw[c] * total == fresh_raw[c] * total, "cached film exposure differs from fresh");
        }
        film.info.reference_illuminant = "D55";
        check(cache.tc_lut(colour, blob, film, settings, compress, sensitivity, returned, side, error), error);
        check(returned == d55, "returning to the original illuminant missed its cached LUT");
        std::cout << "same_illuminant_reuse=true changed_illuminant_fresh_equal=true"
                     " synthetic_exposures_equal=true original_illuminant_reuse=true\n";
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
