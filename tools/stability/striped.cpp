// striped.cpp -- does the engine's striped executor give the whole-frame picture?
//
//   striped <resources> <raw> <long_edge|0> '<params json>' <rows> [<rows>...]
//
// Decodes the RAW once (upstream's decode_raw_compatible), fits it to
// long_edge (0 = the sensor's own size), and renders "full" whole-frame, then
// once per strip height with settings.striped on. Each render is in a fresh
// session on a fresh engine, so one run's pool or cached negative cannot help
// the next. Prints, per render: the FNV-1a of the visible RGBA16 pixels, the
// time, the GPU pool's frame high-water mark (spk_memory_report) and VmHWM.
// "SAME" when a striped render equals the whole-frame one bit for bit.
#include <spektrafilm/spk_engine.h>
#include "io/raw_decoder.hpp"
#include "resize.hpp"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

namespace {

uint64_t digest(const spk_result& r) {
    uint64_t h = 1469598103934665603ull;
    for (uint32_t y = 0; y < r.height; ++y) {
        const auto* row = reinterpret_cast<const uint8_t*>(r.rgba16 + size_t(y) * r.row_stride_px * 4);
        for (size_t i = 0; i < size_t(r.width) * 8; ++i) { h ^= row[i]; h *= 1099511628211ull; }
    }
    return h;
}

long status_kb(const char* key) {
    std::ifstream f("/proc/self/status");
    std::string line;
    while (std::getline(f, line))
        if (line.rfind(key, 0) == 0) return std::atol(line.c_str() + std::string(key).size());
    return -1;
}

// "frame_high_water_bytes":<n> from the memory report, in MB.
double pool_peak_mb(spk_engine* e) {
    const std::string r = spk_memory_report(e);
    const auto k = r.find("\"frame_high_water_bytes\":");
    return k == std::string::npos ? -1 : std::atof(r.c_str() + k + 25) / 1048576.0;
}

struct Run { uint64_t hash = 0; double ms = 0, pool_mb = 0; std::string error; uint32_t w = 0, h = 0; };

Run render(const std::string& res, const std::vector<float>& rgb, uint32_t w, uint32_t h,
           const std::string& params) {
    Run out;
    spk_engine* e = spk_engine_create(res.c_str(), nullptr);
    if (!e) { out.error = std::string("engine: ") + spk_last_error(nullptr); return out; }
    const spk_image img{rgb.data(), w, h, 3};
    spk_session* s = spk_open(e, &img, params.c_str(), nullptr);
    if (!s) { out.error = std::string("open: ") + spk_last_error(e); spk_engine_destroy(e); return out; }
    spk_result r{};
    const auto t0 = std::chrono::steady_clock::now();
    if (spk_render(s, "full", &r) != SPK_OK || !r.rgba16) out.error = std::string("render: ") + spk_last_error(e);
    out.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    if (out.error.empty()) { out.hash = digest(r); out.w = r.width; out.h = r.height; }
    out.pool_mb = pool_peak_mb(e);
    spk_result_free(&r);
    spk_session_release(s);
    spk_engine_destroy(e);
    return out;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 6) { std::fprintf(stderr, "usage: striped <res> <raw> <long_edge> '<params>' <rows>...\n"); return 2; }
    const std::string res = argv[1], params = argv[4];
    spk::io::DecodedRaw d;
    std::string err;
    if (!spk::io::decode_raw_compatible(argv[2], d, err)) { std::fprintf(stderr, "raw: %s\n", err.c_str()); return 1; }
    uint32_t w = d.width, h = d.height;
    const uint32_t edge = uint32_t(std::atoi(argv[3]));
    std::vector<float> rgb;
    if (edge && std::max(w, h) > edge) {
        const double sc = double(edge) / std::max(w, h);
        const uint32_t nw = std::max(1u, uint32_t(w * sc)), nh = std::max(1u, uint32_t(h * sc));
        spk::android::resize_area_rgb(d.rgb, w, h, nw, nh, rgb);
        w = nw; h = nh;
    } else {
        rgb.swap(d.rgb);
    }
    std::vector<float>().swap(d.rgb);
    std::printf("input %ux%u (%.1f MP)\n", w, h, double(w) * h / 1e6);

    // The whole-frame reference: the params as given, striped off.
    const std::string base = params.substr(0, params.rfind('}'));
    const Run ref = render(res, rgb, w, h, base + ",\"striped\":false}");
    if (!ref.error.empty()) { std::fprintf(stderr, "whole %s\n", ref.error.c_str()); return 1; }
    std::printf("whole            %016llx %ux%u %8.0f ms  pool %7.0f MB  VmHWM %ld MB\n",
                (unsigned long long)ref.hash, ref.w, ref.h, ref.ms, ref.pool_mb, status_kb("VmHWM:") / 1024);
    int bad = 0;
    for (int i = 5; i < argc; ++i) {
        const int rows = std::atoi(argv[i]);
        const Run s = render(res, rgb, w, h, base + ",\"striped\":true,\"strip_rows\":" + std::to_string(rows) + "}");
        if (!s.error.empty()) { std::printf("rows %-6d FAIL %s\n", rows, s.error.c_str()); ++bad; continue; }
        const bool same = s.hash == ref.hash;
        bad += !same;
        std::printf("rows %-6d     %016llx %ux%u %8.0f ms  pool %7.0f MB  %s\n", rows,
                    (unsigned long long)s.hash, s.w, s.h, s.ms, s.pool_mb, same ? "SAME" : "DIFFERENT");
    }
    std::printf(bad ? "STRIPED_BAD %d\n" : "STRIPED_OK\n", bad);
    return bad ? 1 : 0;
}
