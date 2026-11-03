// spk_parity: render one input through the engine three ways and write 16-bit
// linear-light PNG-free float dumps the comparison script grades in CIEDE2000.
//
//   spk_parity <resources> <input> <outdir> <long_edge> '<params json>'
//
//   old.f32      the pre-0.1.19 Android path: the engine asked for sRGB directly
//   export.f32   upstream's export path: ProPhoto, then spk_output_transform into
//                sRGB with the lightness shoulder off (engine.cpp's export branch),
//                through the app's OutputTransform (upstream's own arithmetic)
//   display.f32  the app's screen path: ProPhoto, matrix and clip into sRGB,
//                through the app's table-driven decode/encode
//   exact.f32    the same display path with the exact transfer curves, so
//                display vs exact measures only the tables' error
//
// Each file is w, h (uint32) then w*h*3 float encoded sRGB values in 0..1.
// Grain and glare should be off in the params for a pixel comparison.
#include "spektrafilm/spk_engine.h"
#include "io/raw_decoder.hpp"
#include "output_transform.hpp"

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

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

static bool dump(const std::string& path, uint32_t w, uint32_t h, const std::vector<float>& rgb) {
    std::FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    std::fwrite(&w, 4, 1, f); std::fwrite(&h, 4, 1, f);
    std::fwrite(rgb.data(), 4, rgb.size(), f);
    return std::fclose(f) == 0;
}

static bool render(spk_engine* engine, const std::vector<float>& rgb, uint32_t w, uint32_t h,
                   const std::string& params, spk_result& r) {
    const spk_image img{rgb.data(), w, h, 3};
    spk_session* s = spk_open(engine, &img, params.c_str(), nullptr);
    if (!s) { std::fprintf(stderr, "open: %s\n", spk_last_error(engine)); return false; }
    std::memset(&r, 0, sizeof r);
    const bool ok = spk_render(s, "full", &r) == SPK_OK && r.rgba16;
    if (!ok) std::fprintf(stderr, "render: %s\n", spk_last_error(engine));
    spk_session_release(s);   // the result owns its own texture
    return ok;
}

int main(int argc, char** argv) {
    if (argc < 6) { std::fprintf(stderr, "usage: %s res in outdir long_edge params\n", argv[0]); return 2; }
    const std::string res = argv[1], in = argv[2], out = argv[3];
    const uint32_t edge = uint32_t(std::atoi(argv[4]));
    const std::string extra = argv[5];

    std::vector<float> rgb;
    uint32_t w = 0, h = 0;
    std::string ext = in.substr(in.find_last_of('.') + 1);
    for (auto& c : ext) c = char(std::tolower(c));
    const bool raw = ext != "jpg" && ext != "jpeg" && ext != "png";
    if (raw) {
        spk::io::DecodedRaw d; std::string err;
        if (!spk::io::decode_raw_compatible(in, d, err)) { std::fprintf(stderr, "raw: %s\n", err.c_str()); return 1; }
        rgb.swap(d.rgb); w = d.width; h = d.height;
    } else {
        int iw, ih, n;
        unsigned char* p = stbi_load(in.c_str(), &iw, &ih, &n, 3);
        if (!p) { std::fprintf(stderr, "decode failed\n"); return 1; }
        w = iw; h = ih; rgb.resize(size_t(w) * h * 3);
        for (size_t i = 0; i < rgb.size(); ++i) rgb[i] = p[i] / 255.0f;
        stbi_image_free(p);
    }
    if (edge && std::max(w, h) > edge) {
        const double s = double(edge) / std::max(w, h);
        const uint32_t nw = std::max(1u, uint32_t(w * s)), nh = std::max(1u, uint32_t(h * s));
        std::vector<float> small; area_resize(rgb, w, h, nw, nh, small); rgb.swap(small); w = nw; h = nh;
    }
    const std::string input = raw
        ? R"("input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"auto_exposure":true)"
        : R"("input_color_space":"sRGB","input_cctf_decoding":true,"auto_exposure":true)";
    const std::string tail = extra.size() > 2 ? "," + extra.substr(1) : "}";
    const std::string common = "{" + input + R"(,"output_cctf_encoding":true,"extended_dynamic_range":false,"product_defaults":true)";

    spk_engine* engine = spk_engine_create(res.c_str(), nullptr);
    if (!engine) { std::fprintf(stderr, "engine: %s\n", spk_last_error(nullptr)); return 1; }

    auto to_float = [](const spk_result& r, std::vector<float>& v) {
        v.resize(size_t(r.width) * r.height * 3);
        for (uint32_t y = 0; y < r.height; ++y)
            for (uint32_t x = 0; x < r.width; ++x)
                for (int c = 0; c < 3; ++c)
                    v[(size_t(y) * r.width + x) * 3 + c] = r.rgba16[(size_t(y) * r.row_stride_px + x) * 4 + c] / 65535.0f;
    };

    // Old Android path: the engine compresses straight into sRGB.
    spk_result r{};
    if (!render(engine, rgb, w, h, common + R"(,"output_color_space":"sRGB")" + tail, r)) return 1;
    std::vector<float> v; to_float(r, v);
    if (!dump(out + "/old.f32", r.width, r.height, v)) return 1;
    spk_result_free(&r);

    // Upstream's ProPhoto render, converted three ways.
    if (!render(engine, rgb, w, h, common + tail, r)) return 1;
    std::string err;
    struct Way { const char* name; bool exporting; bool exact; };
    for (const Way way : {Way{"export", true, true}, Way{"display", false, false}, Way{"exact", false, true}}) {
        spk::android::OutputTransform xf;
        if (!xf.fetch(engine, "sRGB", way.exporting, err)) { std::fprintf(stderr, "transform: %s\n", err.c_str()); return 1; }
        xf.set_exact(way.exact);
        v.resize(size_t(r.width) * r.height * 3);
        for (uint32_t y = 0; y < r.height; ++y)
            for (uint32_t x = 0; x < r.width; ++x) {
                float c[3];
                xf.apply16(r.rgba16 + (size_t(y) * r.row_stride_px + x) * 4, c);
                for (int k = 0; k < 3; ++k) v[(size_t(y) * r.width + x) * 3 + k] = std::min(1.0f, std::max(0.0f, c[k]));
            }
        if (!dump(out + "/" + way.name + ".f32", r.width, r.height, v)) return 1;
    }
    spk_result_free(&r);
    spk_engine_destroy(engine);
    std::printf("%s %ux%u ok\n", in.c_str(), w, h);
    return 0;
}
