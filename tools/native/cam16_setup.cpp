// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Co-created with GPT (OpenAI), 2026-11-09.
#include "core/cam16.hpp"

#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

// The runner compiles the pinned, unedited baseline with only symbol renames.
namespace spk {
void baseline_cam16_setup(const double[3], double, double, Cam16Setup&);
void baseline_xyz_to_cam16ucs(const Cam16Setup&, const double[3], double[3]);
void baseline_cam16ucs_to_xyz(const Cam16Setup&, const double[3], double[3]);
bool baseline_cam16_setup_for(const Colour&, const std::string&, Cam16Setup&, std::string&);
double baseline_reinhard_knee(double, double, double, double);
}

namespace {
using Clock = std::chrono::steady_clock;
size_t inverse_cases = 0;
size_t forward_cases = 0;
size_t knee_cases = 0;

void exact(const std::string& where, const double* expected, const double* actual, size_t n) {
    if (std::memcmp(expected, actual, n * sizeof(double)) == 0) return;
    for (size_t i = 0; i < n; ++i) {
        if (std::bit_cast<uint64_t>(expected[i]) == std::bit_cast<uint64_t>(actual[i])) continue;
        std::cerr << where << '[' << i << "] first difference: expected="
                  << std::setprecision(17) << expected[i] << " actual=" << actual[i]
                  << " bits=0x" << std::hex << std::bit_cast<uint64_t>(expected[i])
                  << "/0x" << std::bit_cast<uint64_t>(actual[i]) << std::dec << '\n';
        throw std::runtime_error("double bytes differ");
    }
}

void exact_vec(const std::string& where, const spk::Vec& expected, const spk::Vec& actual) {
    if (expected.size() != actual.size()) throw std::runtime_error(where + ": size differs");
    if (!expected.empty()) exact(where, expected.data(), actual.data(), expected.size());
}

std::array<double, 30> metadata(const spk::Cam16Setup& st) {
    std::array<double, 30> out{st.n, st.F_L, st.N_bb, st.N_cb, st.z, st.A_w,
                               st.c, st.N_c, st.D_rgb[0], st.D_rgb[1], st.D_rgb[2]};
    st.m_to_xyz.to_row_major(out.data() + 11);
    st.m_to_rgb.to_row_major(out.data() + 20);
    out[29] = st.white_Jp;
    return out;
}

void exact_setup(const std::string& where, const spk::Cam16Setup& expected,
                 const spk::Cam16Setup& actual) {
    const auto a = metadata(expected), b = metadata(actual);
    exact(where + ".metadata", a.data(), b.data(), a.size());
    exact_vec(where + ".l_grid", expected.l_grid, actual.l_grid);
    exact_vec(where + ".h_grid", expected.h_grid, actual.h_grid);
    exact_vec(where + ".c_max_table", expected.c_max_table, actual.c_max_table);
}

void compare_inverse(const std::string& where, const spk::Cam16Setup& base,
                     const spk::Cam16Setup& current, const std::array<double, 3>& jab) {
    double expected[3], actual[3];
    spk::baseline_cam16ucs_to_xyz(base, jab.data(), expected);
    spk::cam16ucs_to_xyz(current, jab.data(), actual);
    exact(where + ".inverse.case" + std::to_string(inverse_cases), expected, actual, 3);
    ++inverse_cases;
}

void public_transforms(const std::string& where, const spk::Cam16Setup& base,
                       const spk::Cam16Setup& current) {
    const double inf = std::numeric_limits<double>::infinity();
    std::vector<double> lightness{-100.0, -20.0, -0.0, 0.0, 0.5, 125.0, 160.0, 220.0, 250.0};
    // Every table row and its immediate double neighbours exercise the public
    // inverse independently of the hoisted, internal table-building entry.
    for (double row : base.l_grid) {
        lightness.push_back(std::nextafter(row, -inf));
        lightness.push_back(row);
        lightness.push_back(std::nextafter(row, inf));
    }
    constexpr std::array<std::array<double, 2>, 8> ab{{
        {0.0, 0.0}, {-0.0, -0.0}, {1.0, -1.0}, {-1.0, 1.0},
        {30.0, 40.0}, {-40.0, 30.0}, {150.0, 0.0}, {0.0, -150.0}}};
    for (double j : lightness)
        for (const auto& c : ab) compare_inverse(where, base, current, {j, c[0], c[1]});

    constexpr std::array<std::array<double, 3>, 12> xyz{{
        {0.0, 0.0, 0.0}, {-0.0, 0.0, -0.0}, {0.18, 0.18, 0.18},
        {0.95047, 1.0, 1.08883}, {4.0, 4.0, 4.0}, {16.0, 2.0, 0.01},
        {-0.2, -0.2, -0.2}, {-0.1, 0.2, 0.3}, {0.3, -0.1, 0.2},
        {0.2, 0.3, -0.1}, {1e-12, 2e-12, 3e-12}, {1.2, 0.1, 3.0}}};
    for (const auto& value : xyz) {
        double expected[3], actual[3];
        spk::baseline_xyz_to_cam16ucs(base, value.data(), expected);
        spk::xyz_to_cam16ucs(current, value.data(), actual);
        exact(where + ".forward.case" + std::to_string(forward_cases), expected, actual, 3);
        ++forward_cases;
    }
}

void viewing_conditions() {
    constexpr std::array<std::array<double, 3>, 2> whites{{
        {0.95047, 1.0, 1.08883}, {0.96422, 1.0, 0.82521}}};
    for (const auto& white : whites) {
        for (double la : {1.0, 20.0, 64.0, 318.31}) {
            spk::Cam16Setup a, b;
            spk::baseline_cam16_setup(white.data(), la, 20.0, a);
            spk::cam16_setup(white.data(), la, 20.0, b);
            exact_setup("viewing", a, b);
            public_transforms("viewing", a, b);
        }
    }
}
}

int main(int argc, char** argv) {
    try {
        if (argc != 2) throw std::runtime_error("usage: cam16_setup CONSTANTS_BLOB");
        spk::Blob blob;
        spk::Colour colour;
        std::string error;
        if (!blob.open(argv[1], error) || !colour.init(blob, error)) throw std::runtime_error(error);
        constexpr std::array<const char*, 6> spaces{
            "sRGB", "Display P3", "ProPhoto RGB", "Adobe RGB (1998)",
            "ITU-R BT.709", "ITU-R BT.2020"};
        size_t cells = 0;
        double total_baseline_ms = 0, total_current_ms = 0;
        for (const char* space : spaces) {
            spk::Cam16Setup baseline, current;
            auto t0 = Clock::now();
            if (!spk::baseline_cam16_setup_for(colour, space, baseline, error))
                throw std::runtime_error(error);
            auto t1 = Clock::now();
            if (!spk::cam16_setup_for(colour, space, current, error))
                throw std::runtime_error(error);
            auto t2 = Clock::now();
            if (baseline.c_max_table.size() != spk::kCmaxNL * spk::kCmaxNH)
                throw std::runtime_error("unexpected Cmax dimensions");
            exact_setup(space, baseline, current);
            public_transforms(space, baseline, current);
            cells += baseline.c_max_table.size();
            const double baseline_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
            const double current_ms = std::chrono::duration<double, std::milli>(t2 - t1).count();
            total_baseline_ms += baseline_ms;
            total_current_ms += current_ms;
            std::cout << "space=" << space << " cells=" << baseline.c_max_table.size()
                      << " metadata_doubles=30 grids=784 memcmp_equal=true baseline_ms="
                      << baseline_ms << " current_ms=" << current_ms << '\n';
        }
        if (cells != 276480) throw std::runtime_error("incomplete table coverage");
        viewing_conditions();
        for (double threshold : {0.0, 0.8}) {
            for (double d : {-1.0, 0.0, 0.8, std::nextafter(0.8, 1.0), 1.0, 4.0, 100.0}) {
                const double a = spk::baseline_reinhard_knee(d, threshold, 1.2, 1.5);
                const double b = spk::reinhard_knee(d, threshold, 1.2, 1.5);
                exact("reinhard_knee", &a, &b, 1);
                ++knee_cases;
            }
        }
        std::cout << "PASS spaces=6 cmax_cells=" << cells << " setup_metadata_doubles=180"
                  << " inverse_cases=" << inverse_cases << " forward_cases=" << forward_cases
                  << " viewing_setups=8 knee_cases=" << knee_cases
                  << " baseline_total_ms=" << total_baseline_ms
                  << " current_total_ms=" << total_current_ms << " timing_diagnostic_only=true\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << "FAIL: " << e.what() << '\n';
        return 1;
    }
}
