// Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
#include "io/raw_decoder.hpp"

#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>

#ifndef SPK_DNG_RENDER_METADATA
#define SPK_DNG_RENDER_METADATA 1
#endif

int main(int argc, char** argv) {
    if (argc < 2 || argc > 3) return 2;
    spk::io::DecodedRaw decoded;
    std::string error;
    if (!spk::io::decode_raw_compatible(argv[1], decoded, error)) {
        std::cerr << error << '\n';
        return 1;
    }
    uint64_t hash = 14695981039346656037ull;
    for (float value : decoded.rgb) {
        uint32_t bits;
        std::memcpy(&bits, &value, sizeof bits);
        for (unsigned shift = 0; shift < 32; shift += 8) {
            hash ^= (bits >> shift) & 255u;
            hash *= 1099511628211ull;
        }
    }
    if (argc == 3) {
        std::ofstream out(argv[2], std::ios::binary);
        out.write(reinterpret_cast<const char*>(decoded.rgb.data()),
                  std::streamsize(decoded.rgb.size() * sizeof(float)));
        if (!out) return 3;
    }
    std::cout << std::setprecision(17) << "{\"width\":" << decoded.width
        << ",\"height\":" << decoded.height << ",\"pixel_fnv64\":\"" << std::hex << hash << std::dec << '"';
#if SPK_DNG_RENDER_METADATA
    const auto& m = decoded.metadata.dng_render;
    std::cout << std::boolalpha << ",\"is_dng\":" << m.is_dng
        << ",\"baseline_present\":" << m.baseline_exposure_present
        << ",\"baseline_invalid\":" << m.baseline_exposure_invalid
        << ",\"baseline\":" << m.baseline_exposure
        << ",\"offset_present\":" << m.baseline_exposure_offset_present
        << ",\"offset_invalid\":" << m.baseline_exposure_offset_invalid
        << ",\"offset\":" << m.baseline_exposure_offset
        << ",\"black_present\":" << m.default_black_render_present
        << ",\"black_invalid\":" << m.default_black_render_invalid
        << ",\"black\":" << m.default_black_render
        << ",\"tone_present\":" << m.profile_tone_curve_present
        << ",\"tone_invalid\":" << m.profile_tone_curve_invalid
        << ",\"extra_profiles\":" << m.extra_camera_profiles
        << ",\"profile_mismatch\":" << m.profile_source_mismatch << ",\"tone\":[";
    for (size_t i = 0; i < m.profile_tone_curve.size(); ++i) {
        if (i) std::cout << ',';
        std::cout << '[' << m.profile_tone_curve[i][0] << ',' << m.profile_tone_curve[i][1] << ']';
    }
    std::cout << ']';
#endif
    std::cout << "}\n";
}
