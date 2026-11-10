// Test-only Mac export oracle; co-created with GPT (OpenAI). See NOTICE.
#pragma once

#include "core/cam16.hpp"
#include "core/json.hpp"
#include "pipeline/view.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>

namespace spk::test {
namespace mac_export_detail {

inline double table(const Cam16Setup& setup, double lightness, double hue) {
    const size_t rows = setup.l_grid.size(), columns = setup.h_grid.size();
    const double row_position =
        (std::clamp(lightness, setup.l_grid.front(), setup.l_grid.back()) - setup.l_grid.front()) /
        (setup.l_grid.back() - setup.l_grid.front()) * double(rows - 1);
    const size_t row = std::min(rows - 2, size_t(std::floor(row_position)));
    const double row_fraction = row_position - double(row);
    const double column_position = (hue - setup.h_grid.front()) /
                                   (setup.h_grid[1] - setup.h_grid[0]);
    const double column_floor = std::floor(column_position);
    const double column_fraction = column_position - column_floor;
    const int n = int(columns);
    const size_t column = size_t((int(column_floor) % n + n) % n);
    const size_t next_column = (column + 1) % columns;
    const double lower = setup.c_max_table[row * columns + column] * (1 - column_fraction) +
                         setup.c_max_table[row * columns + next_column] * column_fraction;
    const double upper = setup.c_max_table[(row + 1) * columns + column] * (1 - column_fraction) +
                         setup.c_max_table[(row + 1) * columns + next_column] * column_fraction;
    return lower * (1 - row_fraction) + upper * row_fraction;
}

inline uint16_t read16(const uint8_t* source) {
    return uint16_t(uint16_t(source[0]) | (uint16_t(source[1]) << 8));
}

inline double decode_prophoto(uint16_t value) {
    const double encoded = double(value) / 65535;
    return encoded < 16.0 / 512 ? encoded / 16 : std::pow(encoded, 1.8);
}

inline double encode_srgb(double linear) {
    return linear <= .0031308 ? 12.92 * linear : 1.055 * std::pow(linear, 1 / 2.4) - .055;
}

inline void write(uint8_t* destination, double encoded, bool bits16) {
    if (!std::isfinite(encoded)) throw std::runtime_error("Mac oracle produced a nonfinite code");
    const unsigned maximum = bits16 ? 65535u : 255u;
    const unsigned code = unsigned(std::floor(std::clamp(encoded, 0.0, 1.0) * maximum + .5));
    destination[0] = uint8_t(code & 255u);
    if (bits16) destination[1] = uint8_t(code >> 8);
}

}  // namespace mac_export_detail

// The input has already completed GPU grading in encoded ProPhoto RGBA16.
// This is only the final file transform: it must not render/grade recursively.
inline view::Result mac_export_reference(spk_engine* engine, const view::Result& graded_prophoto16,
                                         const std::string& target, bool bits16) {
    if (!engine || (target != "sRGB" && target != "Display P3"))
        throw std::runtime_error("Mac oracle requires an engine and an sRGB/P3 target");
    const size_t pixels = size_t(graded_prophoto16.width) * graded_prophoto16.height;
    if (!pixels || graded_prophoto16.pixels.size() != pixels * 8)
        throw std::runtime_error("Mac oracle requires tightly packed ProPhoto RGBA16");

    char* raw_reply = nullptr;
    const float* cmax = nullptr;
    uint32_t count = 0;
    constexpr const char* specification =
        R"({"lightness_compression_active":false,"knee":[0.7,0.995,6]})";
    const auto status = spk_output_transform(engine, "ProPhoto RGB", target.c_str(), specification,
                                              &raw_reply, &cmax, &count);
    const std::unique_ptr<char, decltype(&spk_string_free)> reply_owner(raw_reply, spk_string_free);
    if (status != SPK_OK || !raw_reply)
        throw std::runtime_error(std::string("Mac oracle fetch: ") + spk_last_error(engine));
    Json reply;
    std::string error;
    if (!Json::parse(raw_reply, reply, error)) throw std::runtime_error(error);
    const auto& compression = reply.at("gamut_compress");
    const auto& constants = compression.at("k").items();
    const auto& matrix = reply.at("matrix").items();
    const auto& to_xyz = compression.at("m_to_xyz").items();
    const auto& to_rgb = compression.at("m_to_rgb").items();
    const size_t rows = size_t(compression.at("cmax_rows").as_int());
    const size_t columns = size_t(compression.at("cmax_cols").as_int());
    if (reply.at("source_cctf_mode").as_int() != 1 || reply.at("target_cctf_mode").as_int() != 0 ||
        compression.at("algorithm").as_string() != "cam16ucs" ||
        compression.at("lightness_compression_active").as_bool() ||
        constants.size() != 22 || matrix.size() != 9 || to_xyz.size() != 9 || to_rgb.size() != 9 ||
        rows < 2 || columns < 2 || !cmax || rows * columns != count)
        throw std::runtime_error("Mac oracle received a different transform contract");
    const auto k = [&](size_t i) { return constants[i].as_double(); };
    if (std::abs(k(12) - .7) > 1e-12 || std::abs(k(13) - .995) > 1e-12 || k(14) != 6)
        throw std::runtime_error("Mac oracle knee override was not honored");

    Cam16Setup setup;
    setup.F_L = k(0); setup.N_bb = k(1); setup.N_cb = k(2); setup.n = k(3);
    setup.z = k(4); setup.A_w = k(5); setup.c = k(6); setup.N_c = k(7);
    setup.white_Jp = k(18);
    for (size_t c = 0; c < 3; ++c) setup.D_rgb[c] = k(19 + c);
    setup.l_grid.resize(rows);
    for (size_t i = 0; i < rows; ++i) setup.l_grid[i] = k(8) + (k(9) - k(8)) * double(i) / double(rows - 1);
    setup.h_grid.resize(columns);
    for (size_t i = 0; i < columns; ++i) setup.h_grid[i] = k(10) + k(11) * double(i);
    setup.c_max_table.assign(cmax, cmax + count);
    double source_matrix[9], xyz_matrix[9], rgb_matrix[9];
    for (size_t i = 0; i < 9; ++i) {
        source_matrix[i] = matrix[i].as_double();
        xyz_matrix[i] = to_xyz[i].as_double();
        rgb_matrix[i] = to_rgb[i].as_double();
    }
    const Mat3 source_to_target = Mat3::from_row_major(source_matrix);
    setup.m_to_xyz = Mat3::from_row_major(xyz_matrix);
    setup.m_to_rgb = Mat3::from_row_major(rgb_matrix);

    view::Result result = graded_prophoto16;
    const size_t component_bytes = bits16 ? 2 : 1;
    result.pixels.resize(pixels * 4 * component_bytes);
    for (size_t i = 0; i < pixels; ++i) {
        const uint8_t* source = graded_prophoto16.pixels.data() + i * 8;
        double linear[3], target_linear[3], xyz[3], jab[3];
        for (size_t c = 0; c < 3; ++c)
            linear[c] = mac_export_detail::decode_prophoto(mac_export_detail::read16(source + c * 2));
        source_to_target.apply(linear, target_linear);
        setup.m_to_xyz.apply(target_linear, xyz);
        xyz_to_cam16ucs(setup, xyz, jab);
        const double chroma = std::hypot(jab[1], jab[2]);
        const double maximum = std::max(1e-9, mac_export_detail::table(setup, jab[0], std::atan2(jab[2], jab[1])));
        const double distance = chroma / maximum;
        const double ratio = chroma ? reinhard_knee(distance, .7, .995, 6) / distance : 1;
        jab[1] *= ratio; jab[2] *= ratio;
        cam16ucs_to_xyz(setup, jab, xyz);
        setup.m_to_rgb.apply(xyz, target_linear);
        uint8_t* destination = result.pixels.data() + i * 4 * component_bytes;
        for (size_t c = 0; c < 3; ++c)
            mac_export_detail::write(destination + c * component_bytes,
                                     mac_export_detail::encode_srgb(target_linear[c]), bits16);
        mac_export_detail::write(destination + 3 * component_bytes, 1, bits16);
    }
    return result;
}

}  // namespace spk::test
