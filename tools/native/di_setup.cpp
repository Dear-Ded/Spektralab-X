// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Co-created with GPT (OpenAI), 2026-11-09.
#include "core/digital_intermediate.hpp"
#include "core/hanatos.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

#ifdef DI_SOURCE
// Including the unedited production/reference TU exposes its own anonymous
// FilmHost to this boundary probe. No copy of its interpolation is substituted.
#include DI_SOURCE
namespace spk {
Vec DI_CURVE_PROBE(const Params& params) {
    const Vec unused;
    const FilmHost host(params, unused, 0, Mat3{});
    Vec output;
    const double infinity = std::numeric_limits<double>::infinity();
    for (int c = 0; c < 3; ++c) {
        Vec queries;
        for (size_t i = 0; i < params.film.data.n_exposure; ++i) {
            const double knot = host.x[3 * i + size_t(c)];
            queries.push_back(std::nextafter(knot, -infinity));
            queries.push_back(knot);
            queries.push_back(std::nextafter(knot, infinity));
        }
        queries.push_back(host.x[size_t(c)] - 1.0);
        queries.push_back(host.x[3 * (params.film.data.n_exposure - 1) + size_t(c)] + 1.0);
        for (double query : queries) {
            output.push_back(host.curve(params.film.normalized_curves, c, query));
            if (host.dir_on) output.push_back(host.curve(host.curves0, c, query));
        }
    }
    for (double value : {-1.0, -0.0, 0.0, 1e-12, 1e-10, 0.184, 1.0, 4.0, 1000.0}) {
        const double raw[3] = {value, 0.184, value * 0.5};
        double density[3];
        host.develop(raw, density);
        output.insert(output.end(), density, density + 3);
    }
    return output;
}
}
#else
namespace spk {
bool baseline_di_constants(const Colour&, const Blob&, const Params&, const Vec&, size_t,
                           const Mat3&, const Vec&, DiConstants&, std::string&);
void baseline_di_live_offsets(const Params&, double[3]);
Vec baseline_di_curve_probe(const Params&);
Vec production_di_curve_probe(const Params&);
}

namespace {
using Clock = std::chrono::steady_clock;
size_t compared_doubles = 0;

void exact(const std::string& where, const double* expected, const double* actual, size_t n,
           bool require_finite = true) {
    for (size_t i = 0; require_finite && i < n; ++i) {
        if (!std::isfinite(expected[i]) || !std::isfinite(actual[i]))
            throw std::runtime_error(where + ": nonfinite value at " + std::to_string(i));
    }
    compared_doubles += n;
    if (std::memcmp(expected, actual, n * sizeof(double)) == 0) return;
    for (size_t i = 0; i < n; ++i) {
        if (std::bit_cast<uint64_t>(expected[i]) == std::bit_cast<uint64_t>(actual[i])) continue;
        std::cerr << where << '[' << i << "] expected=" << std::setprecision(17) << expected[i]
                  << " actual=" << actual[i] << " bits=0x" << std::hex
                  << std::bit_cast<uint64_t>(expected[i]) << "/0x"
                  << std::bit_cast<uint64_t>(actual[i]) << std::dec << '\n';
        throw std::runtime_error("double bytes differ");
    }
}

void exact_vec(const std::string& where, const spk::Vec& expected, const spk::Vec& actual,
               bool require_finite = true) {
    if (expected.size() != actual.size()) throw std::runtime_error(where + ": size differs");
    if (!expected.empty()) exact(where, expected.data(), actual.data(), expected.size(), require_finite);
}

void exact_constants(const std::string& label, const spk::DiConstants& a, const spk::DiConstants& b,
                     bool require_finite) {
    if (a.paper != b.paper || a.curve.k != b.curve.k)
        throw std::runtime_error(label + ": paper or curve dimensions differ");
    exact_vec(label + ".chd", a.chd, b.chd, require_finite);
    exact_vec(label + ".ixs", a.ixs, b.ixs, require_finite);
    exact_vec(label + ".curve.x", a.curve.x, b.curve.x, require_finite);
    exact_vec(label + ".curve.inv", a.curve.inv, b.curve.inv, require_finite);
    exact_vec(label + ".curve.y", a.curve.y, b.curve.y, require_finite);
    exact(label + ".matrix", a.matrix, b.matrix, 9, require_finite);
    exact(label + ".to_lms", a.to_lms, b.to_lms, 9, require_finite);
    exact(label + ".from_lms", a.from_lms, b.from_lms, 9, require_finite);
    const double scalar_a[] = {a.matrix_fit_rms, a.positive_floor, a.gamma_green,
                               a.density_green_mid, a.blue_delta_deg, a.blue_kappa};
    const double scalar_b[] = {b.matrix_fit_rms, b.positive_floor, b.gamma_green,
                               b.density_green_mid, b.blue_delta_deg, b.blue_kappa};
    exact(label + ".scalars", scalar_a, scalar_b, 6, require_finite);
}

struct Scenario {
    bool dir;
    double amount, gamma;
    const char* space;
};
constexpr Scenario scenarios[] = {
    {false, 1.0, 1.0, "ProPhoto RGB"},
    {true, 0.0, 0.7, "ProPhoto RGB"},
    {true, 1.0, 1.0, "ProPhoto RGB"},
    {true, 2.0, 1.4, "ProPhoto RGB"},
    {true, 1.0, 0.7, "sRGB"},
    {true, 1.0, 1.4, "Display P3"},
};
}

int main(int argc, char** argv) {
    try {
        if (argc != 2) throw std::runtime_error("usage: di_setup STAGED_RESOURCES_DIRECTORY");
        const std::filesystem::path resources(argv[1]);
        spk::Blob blob;
        spk::Colour colour;
        spk::Json filters;
        std::string error;
        std::ifstream file(resources / "neutral_print_filters.json");
        const std::string filter_text{std::istreambuf_iterator<char>(file), {}};
        if (!file || !spk::Json::parse(filter_text, filters, error))
            throw std::runtime_error("neutral filters: " + error);
        if (!blob.open((resources / "spektrafilm_constants.bin").string(), error) ||
            !colour.init(blob, error)) throw std::runtime_error(error);

        std::vector<std::string> stocks;
        size_t excluded = 0;
        for (const auto& entry : std::filesystem::directory_iterator(resources / "profiles")) {
            if (entry.path().extension() != ".json") continue;
            const std::string stock = entry.path().stem().string();
            spk::Profile profile;
            if (!spk::load_profile(resources.string(), stock, profile, error))
                throw std::runtime_error(stock + ": " + error);
            // Match the Android product's DI eligibility in Model.kt.
            if (profile.info.support != "film" || profile.info.is_positive() ||
                profile.info.channel_model == "bw") { ++excluded; continue; }
            stocks.push_back(stock);
        }
        std::sort(stocks.begin(), stocks.end());
        if (stocks.empty() || std::find(stocks.begin(), stocks.end(), "kodak_portra_400") == stocks.end())
            throw std::runtime_error("no complete DI film catalog staged");
        size_t cases = 0, successful_setups = 0, matched_rejections = 0, boundary_doubles = 0;
        double baseline_ms = 0, production_ms = 0;
        for (const std::string& stock : stocks) {
            spk::Params original;
            if (!spk::init_params(resources.string(), stock, "kodak_portra_endura", filters, original, error))
                throw std::runtime_error(stock + ": " + error);
            original.io.input_color_space = "ProPhoto RGB";
            spk::Vec sensitivity, lut;
            size_t side = 0;
            spk::Mat3 tc_b;
            if (!spk::film_sensitivity(colour, blob, original.film, original.camera, sensitivity, error) ||
                !spk::build_tc_lut(colour, blob, original.film, original.settings,
                                  original.io.input_gamut_compress, sensitivity, lut, side, error) ||
                !spk::tc_b_matrix(colour, blob, original.io.input_color_space,
                                 original.film.info.reference_illuminant, tc_b, error))
                throw std::runtime_error(stock + ": " + error);
            size_t scenario_index = 0, stock_successes = 0, stock_rejections = 0;
            for (const Scenario& scenario : scenarios) {
                spk::Params params = original;
                params.film_render.dir_couplers.active = scenario.dir;
                params.film_render.dir_couplers.amount = scenario.amount;
                params.film_render.density_curve_gamma = scenario.gamma;
                params.io.output_color_space = scenario.space;
                params.enlarger.print_exposure = std::pow(2.0, double(scenario_index) - 2.0);
                params.enlarger.m_filter_shift = double(scenario_index) - 2.0;
                params.enlarger.y_filter_shift = 2.0 - double(scenario_index);
                const std::string label = stock + "/" + std::to_string(scenario_index++);
                spk::DiConstants baseline, current;
                std::string baseline_error, current_error;
                const auto t0 = Clock::now();
                const bool baseline_ok = spk::baseline_di_constants(
                    colour, blob, params, lut, side, tc_b, sensitivity, baseline, baseline_error);
                const auto t1 = Clock::now();
                const bool current_ok = spk::di_constants(
                    colour, blob, params, lut, side, tc_b, sensitivity, current, current_error);
                const auto t2 = Clock::now();
                if (baseline_ok != current_ok)
                    throw std::runtime_error(label + ": setup status differs, baseline=" +
                                             std::to_string(baseline_ok) + " current=" +
                                             std::to_string(current_ok) + " baseline_error=" +
                                             baseline_error + " current_error=" + current_error);
                if (baseline_error != current_error)
                    throw std::runtime_error(label + ": setup diagnostics differ, baseline=" +
                                             baseline_error + " current=" + current_error);
                // Rejected controls are not supported successful configurations.
                // Compare every partially built field, including nonfinite bits,
                // but require finite values for every successful setup.
                exact_constants(label, baseline, current, baseline_ok);
                if (baseline_ok) {
                    ++successful_setups;
                    ++stock_successes;
                } else {
                    ++matched_rejections;
                    ++stock_rejections;
                    std::cout << "matched_rejection=" << label << " diagnostic=" << baseline_error << '\n';
                }
                const auto a = spk::baseline_di_curve_probe(params);
                const auto b = spk::production_di_curve_probe(params);
                exact_vec(label + ".curve_boundaries_and_develop", a, b);
                boundary_doubles += a.size();
                double offsets_a[3], offsets_b[3];
                spk::baseline_di_live_offsets(params, offsets_a);
                spk::di_live_offsets(params, offsets_b);
                exact(label + ".live_offsets", offsets_a, offsets_b, 3);
                baseline_ms += std::chrono::duration<double, std::milli>(t1 - t0).count();
                production_ms += std::chrono::duration<double, std::milli>(t2 - t1).count();
                ++cases;
            }
            std::cout << "stock=" << stock << " cases=6 successful_setups=" << stock_successes
                      << " matched_rejections=" << stock_rejections
                      << " constants_and_boundaries_memcmp_equal=true\n";
        }
        std::cout << "PASS stocks=" << stocks.size() << " excluded_non_DI_profiles=" << excluded
                  << " cases=" << cases << " successful_setups=" << successful_setups
                  << " matched_rejections=" << matched_rejections
                  << " compared_doubles=" << compared_doubles
                  << " boundary_and_develop_doubles=" << boundary_doubles
                  << " baseline_ms=" << baseline_ms << " production_ms=" << production_ms
                  << " timing_diagnostic_only=true\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << "FAIL: " << e.what() << '\n';
        return 1;
    }
}
#endif
