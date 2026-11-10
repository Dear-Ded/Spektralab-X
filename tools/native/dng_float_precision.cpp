// Co-created with GPT (OpenAI), 2026-11-09. Float unpack versus production decode.
#include "io/raw_decoder.hpp"
#include <libraw/libraw.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

namespace {
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
void emit_text(const std::string& text) {
    std::cout << '"';
    for (unsigned char c : text) {
        if (c == '"' || c == '\\') std::cout << '\\' << c;
        else std::cout << (c < 32 ? '?' : char(c));
    }
    std::cout << '"';
}
void unpack(const char* path, bool preserve) {
    auto raw = std::make_unique<LibRaw>();
    if (preserve) raw->imgdata.rawparams.options &= ~LIBRAW_RAWOPTIONS_CONVERTFLOAT_TO_INT;
    raw->imgdata.params.use_camera_wb = 1;
    raw->imgdata.params.use_auto_wb = 0;
    const int opened = raw->open_file(path);
    const unsigned white_before = raw->imgdata.color.maximum;
    const unsigned black_before = raw->imgdata.color.black;
    const float dng_black_before = raw->imgdata.color.dng_levels.dng_fblack;
    std::array<unsigned, 8> cblack_before{};
    std::copy_n(raw->imgdata.color.cblack, 8, cblack_before.begin());
    const int status = opened == LIBRAW_SUCCESS ? raw->unpack() : opened;
    const auto& data = raw->imgdata;
    std::vector<double> values;
    if (status == LIBRAW_SUCCESS) {
        for (unsigned x = 4; x < data.sizes.raw_width; x += 8) {
            if (data.rawdata.float_image) values.push_back(data.rawdata.float_image[x]);
            else if (data.rawdata.float3_image) values.push_back(data.rawdata.float3_image[x][0]);
            else if (data.rawdata.raw_image) values.push_back(data.rawdata.raw_image[x]);
            else if (data.rawdata.color3_image) values.push_back(data.rawdata.color3_image[x][0]);
            else if (data.rawdata.color4_image) values.push_back(data.rawdata.color4_image[x][0]);
        }
    }
    std::cout << "{\"open\":" << opened << ",\"unpack\":" << status
              << ",\"floating_file\":" << raw->is_floating_point()
              << ",\"have_float\":" << raw->have_fpdata()
              << ",\"white_before\":" << white_before << ",\"black_before\":" << black_before
              << ",\"dng_fblack_before\":" << dng_black_before << ",\"cblack_before\":";
    array(cblack_before);
    std::cout
              << ",\"white_after\":" << data.color.maximum << ",\"black_after\":" << data.color.black
              << ",\"fmaximum\":" << data.color.fmaximum << ",\"fnorm\":" << data.color.fnorm
              << ",\"values\":";
    array(values);
    std::cout << ",\"decoder\":"; emit_text(raw->unpack_function_name() ? raw->unpack_function_name() : "");
    // Clearing the conversion bit is a diagnostic, not an implemented float pipeline.
    const int process = preserve && status == LIBRAW_SUCCESS ? raw->dcraw_process() : -999;
    std::cout << ",\"preserved_process_status\":" << process << '}';
}
void production_decode(const char* path, bool headroom) {
    spk::io::DecodedRaw decoded;
    decoded.width = 7; decoded.height = 11; decoded.rgb = {.125f, -.25f, .5f};
    std::string error;
    const bool ok = headroom ? spk::io::decode_raw_headroom(path, decoded, error)
                             : spk::io::decode_raw_compatible(path, decoded, error);
    const bool unchanged = decoded.width == 7 && decoded.height == 11 &&
        decoded.rgb == std::vector<float>({.125f, -.25f, .5f});
    std::vector<double> roi(3, 0), values;
    bool finite = true;
    for (float value : decoded.rgb) finite &= std::isfinite(value);
    if (ok) {
        for (unsigned y = 12; y < 36; ++y) for (unsigned x = 18; x < 22; ++x)
            for (unsigned c = 0; c < 3; ++c) roi[c] += decoded.rgb[(size_t(y)*decoded.width+x)*3+c]/96.;
        for (unsigned x = 4; x < decoded.width; x += 8)
            for (unsigned c = 0; c < 3; ++c) values.push_back(decoded.rgb[(size_t(24)*decoded.width+x)*3+c]);
    }
    std::cout << "{\"ok\":" << ok << ",\"unchanged\":" << unchanged
              << ",\"finite\":" << finite << ",\"width\":" << decoded.width
              << ",\"height\":" << decoded.height << ",\"source_white\":" << decoded.metadata.white_level
              << ",\"roi\":"; array(roi);
    std::cout << ",\"values_rgb\":"; array(values);
    std::cout << ",\"error\":"; emit_text(error);
    std::cout << '}';
}
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::cout << std::setprecision(17) << "{\"default_unpack\":";
    unpack(argv[1], false);
    std::cout << ",\"preserved_unpack\":"; unpack(argv[1], true);
    std::cout << ",\"compatible\":"; production_decode(argv[1], false);
    std::cout << ",\"headroom\":"; production_decode(argv[1], true);
    std::cout << "}\n";
}
