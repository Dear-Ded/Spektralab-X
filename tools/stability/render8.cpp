// render8.cpp -- one engine render as the 8-bit input jpeg444_compare.py reads.
//
//   render8 <resources> <raw> '<params json>' <out.rgb>
//
// Decodes the RAW (upstream's decode_raw_compatible), renders "full" and
// writes uint32 width, height, 8, then the RGB bytes (each 16-bit code
// rounded to 8 bits, as the app's pack does). The params should ask for the
// file's space (output_color_space "sRGB") and leave grain on: grain is the
// fine detail a 4:2:0 encoder smears.
#include <spektrafilm/spk_engine.h>
#include "io/raw_decoder.hpp"

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

int main(int argc, char** argv) {
    if (argc < 5) { std::fprintf(stderr, "usage: render8 <res> <raw> '<params>' <out.rgb>\n"); return 2; }
    spk::io::DecodedRaw d;
    std::string err;
    if (!spk::io::decode_raw_compatible(argv[2], d, err)) { std::fprintf(stderr, "raw: %s\n", err.c_str()); return 1; }
    spk_engine* engine = spk_engine_create(argv[1], nullptr);
    if (!engine) { std::fprintf(stderr, "engine: %s\n", spk_last_error(nullptr)); return 1; }
    const spk_image img{d.rgb.data(), d.width, d.height, 3};
    spk_session* s = spk_open(engine, &img, argv[3], nullptr);
    if (!s) { std::fprintf(stderr, "open: %s\n", spk_last_error(engine)); return 1; }
    spk_result r{};
    if (spk_render(s, "full", &r) != SPK_OK || !r.rgba16) { std::fprintf(stderr, "render: %s\n", spk_last_error(engine)); return 1; }
    std::vector<uint8_t> rgb(size_t(r.width) * r.height * 3);
    for (uint32_t y = 0; y < r.height; ++y) {
        const uint16_t* row = r.rgba16 + size_t(y) * r.row_stride_px * 4;
        for (uint32_t x = 0; x < r.width; ++x)
            for (int c = 0; c < 3; ++c)
                rgb[(size_t(y) * r.width + x) * 3 + c] = uint8_t((uint32_t(row[4 * x + c]) * 255 + 32767) / 65535);
    }
    std::FILE* f = std::fopen(argv[4], "wb");
    if (!f) return 1;
    const uint32_t head[3] = {r.width, r.height, 8};
    std::fwrite(head, 4, 3, f);
    std::fwrite(rgb.data(), 1, rgb.size(), f);
    std::fclose(f);
    std::printf("%ux%u\n", r.width, r.height);
    spk_result_free(&r);
    spk_session_release(s);
    spk_engine_destroy(engine);
    return 0;
}
