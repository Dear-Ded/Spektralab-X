// spk_android: render one input the way SpektraLab for Android does, for a
// pixel comparison against upstream's own Linux host (host_ref.py).
//
//   spk_android <resources> <kind> <input> <outdir> '<params json>' ['<look csv>']
//   spk_android prep <raw> <long_edge> <out.f32>
//
// kind   f32   linear ProPhoto floats (w, h as uint32, then w*h*3 float), the
//              same numbers host_ref.py hands the host as a float TIFF
//        raw   a RAW file, decoded full size by LibRaw as openRaw does
//        srgb  an 8-bit JPEG/PNG, opened as openBitmap opens an sRGB photo
//        p3    the same for a Display P3 photo
//
// The engine opens with the app's parameters (Edit.openingParams: encoded
// ProPhoto out, product defaults), renders the full tier once, and the print
// leaves ProPhoto through the app's own code, for each destination:
//
//   export_<t>.rgb  pack_rgba16 with the export transform (CAM16 into the file)
//   view_<t>.rgb    pack_rgba8 with the display transform (matrix and clip)
//   print.rgb       the engine's encoded ProPhoto, 16-bit (for diagnosis)
//
// with <t> srgb and p3, and Layer 2 when a look (Look.kt's 50 values) is given.
// Each .rgb is w, h, bits (uint32) then w*h*3 samples, uint8 or uint16 LE.
// `prep` decodes a RAW and area-resizes it as openRaw does (resize.cpp), so a
// whole scene matrix can run at a size lavapipe renders quickly.
#include "spektrafilm/spk_engine.h"
#include "io/raw_decoder.hpp"
#include "layer2.hpp"
#include "output_transform.hpp"
#include "resize.hpp"

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

namespace {

bool write_rgb(const std::string& path, uint32_t w, uint32_t h, uint32_t bits, const void* data) {
    std::FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    const uint32_t head[3] = {w, h, bits};
    std::fwrite(head, 4, 3, f);
    std::fwrite(data, bits / 8, size_t(w) * h * 3, f);
    return std::fclose(f) == 0;
}

template <typename T>
std::vector<T> drop_alpha(const T* rgba, size_t stride_px, uint32_t w, uint32_t h) {
    std::vector<T> out(size_t(w) * h * 3);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c) out[(size_t(y) * w + x) * 3 + c] = rgba[(size_t(y) * stride_px + x) * 4 + c];
    return out;
}

bool read_f32(const std::string& path, std::vector<float>& rgb, uint32_t& w, uint32_t& h) {
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    uint32_t head[2];
    bool ok = std::fread(head, 4, 2, f) == 2;
    if (ok) {
        w = head[0]; h = head[1];
        rgb.resize(size_t(w) * h * 3);
        ok = std::fread(rgb.data(), 4, rgb.size(), f) == rgb.size();
    }
    std::fclose(f);
    return ok;
}

int prep(int argc, char** argv) {
    if (argc < 5) { std::fprintf(stderr, "usage: spk_android prep <raw> <long_edge> <out.f32>\n"); return 2; }
    spk::io::DecodedRaw d;
    std::string err;
    if (!spk::io::decode_raw_compatible(argv[2], d, err)) { std::fprintf(stderr, "raw: %s\n", err.c_str()); return 1; }
    const uint32_t edge = uint32_t(std::atoi(argv[3]));
    uint32_t w = d.width, h = d.height;
    std::vector<float> rgb;
    if (edge && std::max(w, h) > edge) {
        // EditorViewModel's fit(): floor of the scaled size.
        const double s = double(edge) / std::max(w, h);
        const uint32_t nw = std::max(1u, uint32_t(w * s)), nh = std::max(1u, uint32_t(h * s));
        spk::android::resize_area_rgb(d.rgb, w, h, nw, nh, rgb);
        w = nw; h = nh;
    } else {
        rgb.swap(d.rgb);
    }
    std::FILE* f = std::fopen(argv[4], "wb");
    if (!f) return 1;
    std::fwrite(&w, 4, 1, f); std::fwrite(&h, 4, 1, f);
    std::fwrite(rgb.data(), 4, rgb.size(), f);
    std::fclose(f);
    std::printf("%s %ux%u\n", argv[2], w, h);
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc >= 2 && std::string(argv[1]) == "prep") return prep(argc, argv);
    if (argc < 6) { std::fprintf(stderr, "usage: spk_android <res> <kind> <input> <outdir> <params> [look]\n"); return 2; }
    const std::string res = argv[1], kind = argv[2], in = argv[3], out = argv[4], extra = argv[5];

    std::vector<float> rgb;
    uint32_t w = 0, h = 0;
    std::string input;
    if (kind == "f32" || kind == "raw") {
        if (kind == "f32") {
            if (!read_f32(in, rgb, w, h)) { std::fprintf(stderr, "cannot read %s\n", in.c_str()); return 1; }
        } else {
            spk::io::DecodedRaw d;
            std::string err;
            if (!spk::io::decode_raw_compatible(in, d, err)) { std::fprintf(stderr, "raw: %s\n", err.c_str()); return 1; }
            rgb.swap(d.rgb); w = d.width; h = d.height;
        }
        input = R"("input_color_space":"ProPhoto RGB","input_cctf_decoding":false)";
    } else if (kind == "srgb" || kind == "p3") {
        // openBitmap with no highlight recovery: the codes / 255, the engine decodes.
        int iw, ih, n;
        unsigned char* p = stbi_load(in.c_str(), &iw, &ih, &n, 3);
        if (!p) { std::fprintf(stderr, "decode failed: %s\n", in.c_str()); return 1; }
        w = uint32_t(iw); h = uint32_t(ih);
        rgb.resize(size_t(w) * h * 3);
        for (size_t i = 0; i < rgb.size(); ++i) rgb[i] = p[i] * (1.0f / 255.0f);
        stbi_image_free(p);
        input = kind == "p3" ? R"("input_color_space":"Display P3","input_cctf_decoding":true)"
                             : R"("input_color_space":"sRGB","input_cctf_decoding":true)";
    } else if (kind == "srgb_icc" || kind == "p3_icc") {
        // Diagnosis: the host's own JPEG path (image_io.cpp encoded_to_linear_prophoto):
        // the sRGB curve, then the profile's D50 colorants into linear ProPhoto.
        int iw, ih, n;
        unsigned char* p = stbi_load(in.c_str(), &iw, &ih, &n, 3);
        if (!p) { std::fprintf(stderr, "decode failed: %s\n", in.c_str()); return 1; }
        w = uint32_t(iw); h = uint32_t(ih);
        const double srgb[9] = {0.4360747, 0.3850649, 0.1430804, 0.2225045, 0.7168786, 0.0606169,
                                0.0139322, 0.0971045, 0.7141733};
        const double p3[9] = {0.5151215, 0.2919769, 0.1571045, 0.2411957, 0.6922455, 0.0665588,
                              -0.0010529, 0.0418854, 0.7840729};
        // inverse of the ProPhoto colorants (linear_prophoto_space)
        const double a = 0.7976749, b = 0.1351917, c = 0.0313534, d = 0.2880402, e = 0.7118741, f = 0.0000857,
                     g = 0.0, hh = 0.0, k = 0.8252100;
        const double det = a * (e * k - f * hh) - b * (d * k - f * g) + c * (d * hh - e * g);
        const double inv[9] = {(e * k - f * hh) / det, (c * hh - b * k) / det, (b * f - c * e) / det,
                               (f * g - d * k) / det, (a * k - c * g) / det, (c * d - a * f) / det,
                               (d * hh - e * g) / det, (b * g - a * hh) / det, (a * e - b * d) / det};
        const double* s = kind == "p3_icc" ? p3 : srgb;
        double m[9] = {};
        for (int i = 0; i < 3; ++i)
            for (int j = 0; j < 3; ++j)
                for (int q = 0; q < 3; ++q) m[i * 3 + j] += inv[i * 3 + q] * s[q * 3 + j];
        float lut[256];
        for (int v = 0; v < 256; ++v) {
            const double x = v / 255.0;
            lut[v] = float(x < 0.04045 ? x / 12.92 : std::pow((x + 0.055) / 1.055, 2.4));
        }
        rgb.resize(size_t(w) * h * 3);
        for (size_t px = 0; px < size_t(w) * h; ++px) {
            const double l[3] = {lut[p[px * 3]], lut[p[px * 3 + 1]], lut[p[px * 3 + 2]]};
            for (int r = 0; r < 3; ++r) rgb[px * 3 + r] = float(m[r * 3] * l[0] + m[r * 3 + 1] * l[1] + m[r * 3 + 2] * l[2]);
        }
        stbi_image_free(p);
        input = R"("input_color_space":"ProPhoto RGB","input_cctf_decoding":false)";
    } else {
        std::fprintf(stderr, "unknown kind %s\n", kind.c_str());
        return 2;
    }

    // Edit.openingParams, with the case's own fields after it.
    const std::string tail = extra.size() > 2 ? "," + extra.substr(1) : "}";
    const std::string params = "{" + input +
        R"(,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"extended_dynamic_range":false,)"
        R"("product_defaults":true,"preview_long_edge":1024)" + tail;

    std::unique_ptr<spk::android::Layer2> look;
    if (argc > 6 && argv[6][0]) {
        std::vector<float> v;
        std::stringstream ss(argv[6]);
        std::string item;
        while (std::getline(ss, item, ',')) v.push_back(std::stof(item));
        look = std::make_unique<spk::android::Layer2>(v);
        if (look->identity()) look.reset();
    }

    spk_engine* engine = spk_engine_create(res.c_str(), nullptr);
    if (!engine) { std::fprintf(stderr, "engine: %s\n", spk_last_error(nullptr)); return 1; }
    const spk_image img{rgb.data(), w, h, 3};
    spk_session* s = spk_open(engine, &img, params.c_str(), nullptr);
    if (!s) { std::fprintf(stderr, "open: %s\n", spk_last_error(engine)); return 1; }
    spk_result r{};
    if (spk_render(s, "full", &r) != SPK_OK || !r.rgba16) {
        std::fprintf(stderr, "render: %s\n", spk_last_error(engine));
        return 1;
    }
    {
        const auto print = drop_alpha(r.rgba16, r.row_stride_px, r.width, r.height);
        if (!write_rgb(out + "/print.rgb", r.width, r.height, 16, print.data())) return 1;
    }
    std::string err;
    for (const auto& [target, tag] : {std::pair{"sRGB", "srgb"}, std::pair{"Display P3", "p3"}}) {
        spk::android::OutputTransform xf;
        if (!xf.fetch(engine, target, true, err)) { std::fprintf(stderr, "transform: %s\n", err.c_str()); return 1; }
        std::vector<uint16_t> file;
        spk::android::pack_rgba16(r.rgba16, r.row_stride_px, r.width, r.height, file, look.get(), xf);
        const auto e = drop_alpha(file.data(), r.width, r.width, r.height);
        if (!write_rgb(out + "/export_" + tag + ".rgb", r.width, r.height, 16, e.data())) return 1;

        spk::android::OutputTransform view;
        if (!view.fetch(engine, target, false, err)) { std::fprintf(stderr, "transform: %s\n", err.c_str()); return 1; }
        std::vector<uint8_t> screen(size_t(r.width) * r.height * 4);
        spk::android::pack_rgba8(r.rgba16, r.row_stride_px, r.width, r.height, screen.data(), size_t(r.width) * 4,
                                 look.get(), view);
        const auto v = drop_alpha(screen.data(), r.width, r.width, r.height);
        if (!write_rgb(out + "/view_" + tag + ".rgb", r.width, r.height, 8, v.data())) return 1;
    }
    spk_result_free(&r);
    spk_session_release(s);
    spk_engine_destroy(engine);
    std::printf("%s %ux%u ok\n", in.c_str(), w, h);
    return 0;
}
