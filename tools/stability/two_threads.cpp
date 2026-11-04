// two_threads.cpp -- engine patch 0005's acceptance test.
//
//   two_threads <resources> [iterations] [film] [print]
//
// The app renders an export on ExportThread while the editor renders on
// EngineThread. Each iteration here makes a fresh engine (so the print-LUT
// caches are empty), opens one session per thread on the same synthetic
// frame and stock pair, and has both threads render "full" and then the LUT
// preview at the same moment -- the first use of the stock, which is what
// patch 0005 locks. Every result must equal, bit for bit, the one a single
// thread produces. Grain and glare are off: both are stochastic.
#include <spektrafilm/spk_engine.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr uint32_t kW = 640, kH = 427;

// A smooth gradient with a few hard edges: enough structure that a wrong
// table or a torn buffer changes the hash.
std::vector<float> frame() {
    std::vector<float> rgb(size_t(kW) * kH * 3);
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x) {
            float* p = &rgb[(size_t(y) * kW + x) * 3];
            const float u = float(x) / kW, v = float(y) / kH;
            p[0] = 0.02f + 0.8f * u * u;
            p[1] = 0.02f + 0.6f * v;
            p[2] = 0.05f + 0.4f * (((x / 40 + y / 40) & 1) ? 1.0f - u : u);
        }
    return rgb;
}

// FNV-1a over the visible pixels (row padding excluded).
uint64_t digest(const spk_result& r) {
    uint64_t h = 1469598103934665603ull;
    for (uint32_t y = 0; y < r.height; ++y) {
        const auto* row = reinterpret_cast<const uint8_t*>(r.rgba16 + size_t(y) * r.row_stride_px * 4);
        for (size_t i = 0; i < size_t(r.width) * 8; ++i) { h ^= row[i]; h *= 1099511628211ull; }
    }
    return h;
}

struct Out { uint64_t full = 0, lut = 0; std::string error; };

Out run_session(spk_engine* engine, const std::vector<float>& rgb, const std::string& params,
                std::atomic<int>* gate) {
    Out out;
    const spk_image img{rgb.data(), kW, kH, 3};
    spk_session* s = spk_open(engine, &img, params.c_str(), nullptr);
    if (!s) { out.error = std::string("open: ") + spk_last_error(engine); return out; }
    if (gate) { gate->fetch_sub(1); while (gate->load() > 0) std::this_thread::yield(); }
    spk_result r{};
    if (spk_render(s, "full", &r) != SPK_OK || !r.rgba16) out.error = std::string("render: ") + spk_last_error(engine);
    else out.full = digest(r);
    spk_result_free(&r);
    if (out.error.empty()) {
        spk_result l{};
        char* reply = nullptr;
        if (spk_preview_stock_lut(s, nullptr, "full", &l, &reply) != SPK_OK || !l.rgba16)
            out.error = std::string("lut: ") + spk_last_error(engine);
        else out.lut = digest(l);
        if (reply) spk_string_free(reply);
        spk_result_free(&l);
    }
    spk_session_release(s);
    return out;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: two_threads <resources> [iterations] [film] [print]\n"); return 2; }
    const std::string res = argv[1];
    const int iterations = argc > 2 ? std::atoi(argv[2]) : 30;
    const std::string film = argc > 3 ? argv[3] : "kodak_portra_400";
    const std::string print = argc > 4 ? argv[4] : "kodak_portra_endura";
    const std::string params = "{\"film_stock\":\"" + film + "\",\"print_stock\":\"" + print +
                               "\",\"grain_active\":false,\"glare_active\":false}";
    const std::vector<float> rgb = frame();

    // The reference: one thread, its own engine.
    spk_engine* engine = spk_engine_create(res.c_str(), nullptr);
    if (!engine) { std::fprintf(stderr, "engine: %s\n", spk_last_error(nullptr)); return 1; }
    const Out ref = run_session(engine, rgb, params, nullptr);
    spk_engine_destroy(engine);
    if (!ref.error.empty()) { std::fprintf(stderr, "reference %s\n", ref.error.c_str()); return 1; }
    std::printf("reference full %016llx lut %016llx\n", (unsigned long long)ref.full, (unsigned long long)ref.lut);

    int bad = 0;
    for (int i = 0; i < iterations; ++i) {
        engine = spk_engine_create(res.c_str(), nullptr);
        if (!engine) { std::fprintf(stderr, "engine: %s\n", spk_last_error(nullptr)); return 1; }
        std::atomic<int> gate{2};
        Out a, b;
        std::thread ta([&] { a = run_session(engine, rgb, params, &gate); });
        std::thread tb([&] { b = run_session(engine, rgb, params, &gate); });
        ta.join();
        tb.join();
        spk_engine_destroy(engine);
        for (const Out* o : {&a, &b}) {
            if (!o->error.empty()) { std::printf("iteration %d: %s\n", i, o->error.c_str()); ++bad; }
            else if (o->full != ref.full || o->lut != ref.lut) {
                std::printf("iteration %d: differs (full %016llx lut %016llx)\n", i,
                            (unsigned long long)o->full, (unsigned long long)o->lut);
                ++bad;
            }
        }
    }
    std::printf("%d iterations, %d mismatches\n", iterations, bad);
    std::printf(bad ? "TWO_THREADS_FAIL\n" : "TWO_THREADS_OK\n");
    return bad ? 1 : 0;
}
