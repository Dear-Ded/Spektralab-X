// jpeg444_main.cpp -- write_jpeg444 (app/src/main/cpp/jpeg444.cpp) outside the app.
//
//   jpeg444 <in.rgb> <out.jpg> <quality> [<icc>]
//
// <in.rgb> is the parity harness's format (spk_android write_rgb): uint32
// width, height, bits (8), then width*height*3 bytes. The pixels go through
// the same RGBA rows and the same call the app makes from Bitmap pixels.
#include "jpeg444.hpp"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

int main(int argc, char** argv) {
    if (argc < 4) { std::fprintf(stderr, "usage: jpeg444 <in.rgb> <out.jpg> <quality> [icc]\n"); return 2; }
    std::FILE* f = std::fopen(argv[1], "rb");
    if (!f) { std::fprintf(stderr, "cannot open %s\n", argv[1]); return 1; }
    uint32_t head[3] = {0, 0, 0};
    if (std::fread(head, 4, 3, f) != 3 || head[2] != 8) { std::fprintf(stderr, "not an 8-bit .rgb\n"); return 1; }
    const uint32_t w = head[0], h = head[1];
    std::vector<uint8_t> rgb(size_t(w) * h * 3);
    if (std::fread(rgb.data(), 1, rgb.size(), f) != rgb.size()) { std::fprintf(stderr, "short read\n"); return 1; }
    std::fclose(f);
    std::vector<uint8_t> rgba(size_t(w) * h * 4);
    for (size_t i = 0; i < size_t(w) * h; ++i) {
        rgba[4 * i] = rgb[3 * i]; rgba[4 * i + 1] = rgb[3 * i + 1]; rgba[4 * i + 2] = rgb[3 * i + 2]; rgba[4 * i + 3] = 255;
    }
    std::vector<uint8_t> icc;
    if (argc > 4) {
        std::ifstream in(argv[4], std::ios::binary);
        icc.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    }
    std::string error;
    if (!spk::android::write_jpeg444(argv[2], rgba.data(), w, h, size_t(w) * 4, std::atoi(argv[3]), icc, error)) {
        std::fprintf(stderr, "write_jpeg444: %s\n", error.c_str());
        return 1;
    }
    std::printf("wrote %s %ux%u\n", argv[2], w, h);
    return 0;
}
