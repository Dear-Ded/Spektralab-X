// spk_render: render one image through the SpektraLab engine, for the README
// sample comparisons. Same C API and opening parameters the Android app uses.
//
//   spk_render <resources> <input> <output.png> <long_edge> '<params json>'
//
// RAW goes through spk::io::decode_raw_compatible (linear ProPhoto); anything
// else through stb_image (encoded sRGB).
#include "spektrafilm/spk_engine.h"
#include "io/raw_decoder.hpp"

#define STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image.h"
#include "stb_image_write.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static void area_resize(const std::vector<float>& src, uint32_t w, uint32_t h, uint32_t nw, uint32_t nh,
                        std::vector<float>& dst) {
    dst.assign(size_t(nw) * nh * 3, 0.0f);
    const double sx = double(w) / nw, sy = double(h) / nh;
    for (uint32_t y = 0; y < nh; ++y) {
        const uint32_t y0 = uint32_t(y * sy), y1 = std::max(y0 + 1, std::min(h, uint32_t(std::ceil((y + 1) * sy))));
        for (uint32_t x = 0; x < nw; ++x) {
            const uint32_t x0 = uint32_t(x * sx), x1 = std::max(x0 + 1, std::min(w, uint32_t(std::ceil((x + 1) * sx))));
            double acc[3] = {0, 0, 0};
            for (uint32_t yy = y0; yy < y1; ++yy)
                for (uint32_t xx = x0; xx < x1; ++xx)
                    for (int c = 0; c < 3; ++c) acc[c] += src[(size_t(yy) * w + xx) * 3 + c];
            const double n = double(y1 - y0) * (x1 - x0);
            for (int c = 0; c < 3; ++c) dst[(size_t(y) * nw + x) * 3 + c] = float(acc[c] / n);
        }
    }
}

int main(int argc, char** argv) {
    if (argc < 6) { std::fprintf(stderr, "usage: %s res in out.png long_edge params\n", argv[0]); return 2; }
    const std::string res = argv[1], in = argv[2], out = argv[3];
    const uint32_t edge = uint32_t(std::atoi(argv[4]));
    std::string params = argv[5];

    std::vector<float> rgb;
    uint32_t w = 0, h = 0;
    std::string ext = in.substr(in.find_last_of('.') + 1);
    for (auto& c : ext) c = char(std::tolower(c));
    const bool raw = ext != "jpg" && ext != "jpeg" && ext != "png";
    if (raw) {
        spk::io::DecodedRaw d;
        std::string err;
        if (!spk::io::decode_raw_compatible(in, d, err)) { std::fprintf(stderr, "raw: %s\n", err.c_str()); return 1; }
        rgb.swap(d.rgb); w = d.width; h = d.height;
    } else {
        int iw, ih, n;
        unsigned char* p = stbi_load(in.c_str(), &iw, &ih, &n, 3);
        if (!p) { std::fprintf(stderr, "decode failed\n"); return 1; }
        w = iw; h = ih;
        rgb.resize(size_t(w) * h * 3);
        for (size_t i = 0; i < rgb.size(); ++i) rgb[i] = p[i] / 255.0f;
        stbi_image_free(p);
    }
    if (edge && std::max(w, h) > edge) {
        const double s = double(edge) / std::max(w, h);
        const uint32_t nw = std::max(1u, uint32_t(w * s)), nh = std::max(1u, uint32_t(h * s));
        std::vector<float> small;
        area_resize(rgb, w, h, nw, nh, small);
        rgb.swap(small); w = nw; h = nh;
    }
    // "none": the neutral decode, no film -- the "before" of a comparison.
    if (params == "none") {
        static const float m[9] = {2.0341926f, -0.7274198f, -0.3067728f, -0.2288151f, 1.2317584f, -0.0029433f,
                                   -0.0085497f, -0.1532550f, 1.1618047f};
        auto enc = [](float v) { v = std::min(1.0f, std::max(0.0f, v));
            return v <= 0.0031308f ? 12.92f * v : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f; };
        // Expose so the median luminance lands near middle grey, like the meter.
        std::vector<float> lum; lum.reserve(rgb.size() / 3 / 16 + 1);
        for (size_t i = 0; i < rgb.size() / 3; i += 16) lum.push_back(0.29f * rgb[3*i] + 0.62f * rgb[3*i+1] + 0.09f * rgb[3*i+2]);
        std::nth_element(lum.begin(), lum.begin() + lum.size() / 2, lum.end());
        const float gain = raw ? 0.18f / std::max(1e-4f, lum[lum.size() / 2]) : 1.0f;
        std::vector<unsigned char> px(size_t(w) * h * 3);
        for (size_t i = 0; i < size_t(w) * h; ++i) {
            float r = rgb[3*i] * gain, g = rgb[3*i+1] * gain, b = rgb[3*i+2] * gain;
            float o[3] = {r, g, b};
            if (raw) { o[0] = m[0]*r + m[1]*g + m[2]*b; o[1] = m[3]*r + m[4]*g + m[5]*b; o[2] = m[6]*r + m[7]*g + m[8]*b;
                       for (float& v : o) v = enc(v); }
            for (int c = 0; c < 3; ++c) px[3*i+c] = (unsigned char)std::lround(std::min(1.0f, std::max(0.0f, o[c])) * 255.0f);
        }
        const int ok = stbi_write_png(out.c_str(), int(w), int(h), 3, px.data(), int(w) * 3);
        std::printf("%s %ux%u -> %s (neutral)\n", in.c_str(), w, h, ok ? out.c_str() : "WRITE FAILED");
        return ok ? 0 : 1;
    }
    // Input description, as the app sends it.
    const std::string input = raw
        ? R"("input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"auto_exposure":true)"
        : R"("input_color_space":"sRGB","input_cctf_decoding":true,"auto_exposure":false)";
    params = "{" + input +
             R"(,"output_color_space":"sRGB","output_cctf_encoding":true,"extended_dynamic_range":false,"product_defaults":true)" +
             (params.size() > 2 ? "," + params.substr(1) : "}");

    spk_engine* engine = spk_engine_create(res.c_str(), nullptr);
    if (!engine) { std::fprintf(stderr, "engine: %s\n", spk_last_error(nullptr)); return 1; }
    const spk_image img{rgb.data(), w, h, 3};
    spk_session* s = spk_open(engine, &img, params.c_str(), nullptr);
    if (!s) { std::fprintf(stderr, "open: %s\n", spk_last_error(engine)); return 1; }
    spk_result r;
    std::memset(&r, 0, sizeof r);
    if (spk_render(s, "full", &r) != SPK_OK || !r.rgba16) { std::fprintf(stderr, "render: %s\n", spk_last_error(engine)); return 1; }
    std::vector<unsigned char> px(size_t(r.width) * r.height * 3);
    for (uint32_t y = 0; y < r.height; ++y)
        for (uint32_t x = 0; x < r.width; ++x)
            for (int c = 0; c < 3; ++c)
                px[(size_t(y) * r.width + x) * 3 + c] =
                    (unsigned char)((uint32_t(r.rgba16[(size_t(y) * r.row_stride_px + x) * 4 + c]) + 128u) / 257u);
    const int ok = stbi_write_png(out.c_str(), int(r.width), int(r.height), 3, px.data(), int(r.width) * 3);
    spk_result_free(&r);
    spk_session_release(s);
    spk_engine_destroy(engine);
    std::printf("%s %ux%u -> %s\n", in.c_str(), w, h, ok ? out.c_str() : "WRITE FAILED");
    return ok ? 0 : 1;
}
