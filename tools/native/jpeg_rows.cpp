// Co-created with GPT (OpenAI). See NOTICE for project terms.
#include "jpeg444.hpp"
#include "output_transform.hpp"
#include "rgba8_spool.hpp"

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <memory>
#include <stdexcept>
#include <sys/resource.h>

using namespace spk::android;
#if SPK_JPEG_BASELINE
namespace spk::android {
bool write_jpeg444_baseline(const std::string&, const uint8_t*, uint32_t, uint32_t,
                            size_t, int, const std::vector<uint8_t>&, std::string&);
}
#endif

void check(bool pass, const std::string& error) { if (!pass) throw std::runtime_error(error); }
size_t status_kib(const std::string& name) {
    std::ifstream status("/proc/self/status");
    std::string line;
    while (std::getline(status, line))
        if (line.starts_with(name + ":")) return std::stoull(line.substr(name.size() + 1));
    throw std::runtime_error("missing process memory counter: " + name);
}
std::vector<uint8_t> bytes(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    check(bool(file), "missing JPEG");
    return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}

std::vector<float> settings(bool local) {
    std::vector<float> p(50, 0);
    p[kExposure] = -0.7f; p[kContrast] = 17; p[kBrightness] = 9;
    p[kVignette] = -37; p[kVignetteMid] = 43; p[kSaturation] = 11;
    for (int row = 0; row < 5; ++row)
        p.insert(p.end(), {4, 0, 0, .25f, .18f, .75f, .86f, 1, 1});
    if (local) {
        p.insert(p.end(), {9157, 1, 55, 17, 30, 60, 35, 23891, 3});
        for (int kind = 0; kind < 3; ++kind) {
            std::array<float, 22> mask{};
            mask[0] = float(kind); mask[1] = float(kind == 1); mask[2] = .7f;
            mask[3] = .4f; mask[4] = 11; mask[6] = -17;
            mask[13] = .21f; mask[14] = .19f; mask[15] = .78f; mask[16] = .83f;
            mask[17] = .33f; mask[18] = .17f; mask[19] = 23; mask[20] = .6f;
            mask[21] = kind == 2 ? 1.f : 0.f;
            p.insert(p.end(), mask.begin(), mask.end());
            if (kind == 2) p.insert(p.end(), {.23f, .29f, .2f, .5f, .7f});
        }
    }
    return p;
}

void regression(spk_engine* engine, const std::string& dir) {
    const Layer2 tone(settings(false)), local(settings(true));
    check(local.frame(73, 97).placed != nullptr, "missing spatial fixture");
    const auto a = dir + "/jpeg-full.jpg", b = dir + "/jpeg-rows.jpg";
    const std::vector<uint8_t> icc(70000, 42); // Exercise multiple APP2 chunks.
    size_t cases = 0, comparisons = 0, max_scratch = 0;
    for (const char* target : {"sRGB", "Display P3"}) {
        OutputTransform xf; std::string error;
        check(xf.fetch(engine, target, true, error), error);
        for (const auto shape : {std::array<uint32_t, 2>{1, 1}, {7, 33}, {73, 97}, {97, 73}}) {
            const auto [w, h] = shape;
            const uint32_t stride = w + 7;
            std::vector<uint16_t> src(size_t(stride) * h * 4);
            std::vector<float> floating(size_t(stride) * h * 3);
            for (size_t i = 0; i < src.size(); ++i) src[i] = uint16_t(i * 63001u + i / 7);
            for (size_t i = 0; i < floating.size(); ++i) floating[i] = float(i % 577) / 127.f - .4f;
            for (const Layer2* look : {static_cast<const Layer2*>(nullptr), &tone, &local}) {
                for (bool fp : {false, true}) {
                    std::vector<uint8_t> full(size_t(w) * h * 4);
                    if (fp) pack_float_rgba8(floating.data(), stride, w, h, full.data(), size_t(w) * 4, look, xf);
                    else pack_rgba8(src.data(), stride, w, h, full.data(), size_t(w) * 4, look, xf);
                    const Rgba8Rows rows = fp ? Rgba8Rows(floating.data(), stride, w, h, look, &xf)
                                             : Rgba8Rows(src.data(), stride, w, h, look, &xf);
                    auto spool = Rgba8Spool::create(b, w, h);
                    std::vector<uint8_t> staging;
                    for (uint32_t y = 0; y < h; y += 32) {
                        rows.pack(y, std::min(32u, h - y), staging);
                        spool->append(staging);
                    }
                    spool->seal();
#if SPK_JPEG_BASELINE
                    check(write_jpeg444_baseline(a, full.data(), w, h, size_t(w) * 4, 98, icc, error), error);
#else
                    check(write_jpeg444(a, full.data(), w, h, size_t(w) * 4, 98, icc, error), error);
#endif
                    for (uint32_t step : {1u, 3u, 32u, 65u}) {
                        check(write_jpeg444(b, w, h, 98, icc,
                            [&](uint32_t y, uint32_t count, std::vector<uint8_t>& scratch) {
                                check(count <= step, "unbounded JPEG row request");
                                rows.pack(y, count, scratch);
                                check(scratch.size() == size_t(w) * count * 4, "row allocation size");
                                max_scratch = std::max(max_scratch, scratch.size());
                                check(std::memcmp(scratch.data(), full.data() + size_t(y) * w * 4,
                                                  scratch.size()) == 0, "RGBA8 rows differ from full pack");
                                return JpegRowView{scratch.data(), size_t(w) * 4};
                            }, error, step), error);
                        check(bytes(a) == bytes(b), "complete JPEG bytes differ");
                        ++comparisons;
                        check(write_jpeg444(b, w, h, 98, icc,
                            [&](uint32_t y, uint32_t count, std::vector<uint8_t>& scratch) {
                                return spool->read(y, count, scratch);
                            }, error, step), error);
                        check(bytes(a) == bytes(b), "spooled JPEG bytes differ");
                        ++comparisons;
                    }
                    ++cases;
                }
            }
        }
    }
    std::string error;
    for (bool throws : {false, true}) {
        check(!write_jpeg444(b, 73, 97, 98, {},
            [&](uint32_t, uint32_t, std::vector<uint8_t>&) -> JpegRowView {
                if (throws) throw std::bad_alloc();
                return {};
            }, error), "bad source should fail");
        check(!std::filesystem::exists(b), "failed JPEG was left on disk");
    }
    check(!write_jpeg444(b, 73, 97, 98, {}, {}, error), "empty source accepted");
    bool short_failed = false;
    try { Rgba8Spool::create(b, 73, 97)->seal(); }
    catch (const std::exception&) { short_failed = true; }
    check(short_failed, "incomplete spool accepted");
    std::remove(a.c_str());
    std::printf("JPEG_ROWS_PASS cases=%zu file_comparisons=%zu max_scratch_bytes=%zu baseline=%d cleanup=passed\n",
                cases, comparisons, max_scratch, int(SPK_JPEG_BASELINE));
}

void memory(spk_engine* engine, const std::string& dir, bool full, bool fp) {
    constexpr uint32_t w = 4097, h = 3073;
    OutputTransform xf; std::string error;
    check(xf.fetch(engine, "sRGB", true, error), error);
    rusage startup{}; getrusage(RUSAGE_SELF, &startup);
    // Engine/LUT startup has its own peak. Measure the complete output stage
    // separately, while retaining the process-lifetime getrusage peak below.
    {
        std::ofstream reset("/proc/self/clear_refs");
        reset << "5\n";
        reset.close();
        check(bool(reset), "cannot reset VmHWM");
    }
    const size_t start_rss = status_kib("VmRSS");
    std::vector<uint16_t> src;
    std::vector<float> floating;
    if (fp) {
        floating.resize(size_t(w) * h * 3);
        for (size_t i = 0; i < floating.size(); ++i) floating[i] = float(i % 577) / 127.f - .4f;
    } else {
        src.resize(size_t(w) * h * 4);
        for (size_t i = 0; i < src.size(); ++i) src[i] = uint16_t(i * 97u + i / 17);
    }
    const auto path = dir + "/jpeg-memory.jpg";
    size_t scratch_bytes = 0;
    if (full) {
        std::vector<uint8_t> rgba(size_t(w) * h * 4);
        if (fp) pack_float_rgba8(floating.data(), w, w, h, rgba.data(), size_t(w) * 4, nullptr, xf);
        else pack_rgba8(src.data(), w, w, h, rgba.data(), size_t(w) * 4, nullptr, xf);
        std::vector<uint16_t>().swap(src); // exportBitmap frees ResultGuard here.
        std::vector<float>().swap(floating);
        check(write_jpeg444(path, rgba.data(), w, h, size_t(w) * 4, 98, {}, error), error);
        scratch_bytes = rgba.size();
    } else {
        auto spool = Rgba8Spool::create(path, w, h);
        {
            const Rgba8Rows rows = fp ? Rgba8Rows(floating.data(), w, w, h, nullptr, &xf)
                                     : Rgba8Rows(src.data(), w, w, h, nullptr, &xf);
            std::vector<uint8_t> staging;
            for (uint32_t y = 0; y < h; y += 32) {
                rows.pack(y, std::min(32u, h - y), staging);
                scratch_bytes = std::max(scratch_bytes, staging.size());
                spool->append(staging);
            }
            spool->seal();
        }
        std::vector<uint16_t>().swap(src);
        std::vector<float>().swap(floating);
        check(write_jpeg444(path, w, h, 98, {},
            [&](uint32_t y, uint32_t count, std::vector<uint8_t>& scratch) {
                return spool->read(y, count, scratch);
            }, error), error);
    }
    rusage usage{}; getrusage(RUSAGE_SELF, &usage);
    const size_t stage_peak_rss = status_kib("VmHWM");
    const auto payload = bytes(path);
    uint64_t hash = 0xcbf29ce484222325ull;
    for (uint8_t byte : payload) { hash ^= byte; hash *= 0x100000001b3ull; }
    std::printf("JPEG_MEMORY mode=%s input=%s startup_peak_rss_kib=%ld lifetime_peak_rss_kib=%ld stage_start_rss_kib=%zu stage_peak_rss_kib=%zu conversion_bytes=%zu file_bytes=%zu fnv1a=%016llx\n",
                full ? "full" : "rows", fp ? "float" : "rgba16", startup.ru_maxrss,
                std::max(startup.ru_maxrss, usage.ru_maxrss), start_rss, stage_peak_rss,
                scratch_bytes, payload.size(), (unsigned long long)hash);
    std::remove(path.c_str());
}

int main(int argc, char** argv) {
    try {
        check(argc >= 3, "usage: jpeg_rows RESOURCES OUTPUT_DIR [--memory-full|--memory-rows|--memory-full-float|--memory-rows-float]");
        std::filesystem::create_directories(argv[2]);
        std::unique_ptr<spk_engine, decltype(&spk_engine_destroy)> engine(spk_engine_create(argv[1], nullptr), spk_engine_destroy);
        check(bool(engine), "engine create failed");
        if (argc == 4) {
            const std::string mode = argv[3];
            check(mode == "--memory-full" || mode == "--memory-rows" ||
                  mode == "--memory-full-float" || mode == "--memory-rows-float", "unknown memory mode");
            memory(engine.get(), argv[2], mode == "--memory-full" || mode == "--memory-full-float",
                   mode.ends_with("-float"));
        } else regression(engine.get(), argv[2]);
        return 0;
    } catch (const std::exception& e) { std::fprintf(stderr, "FAIL: %s\n", e.what()); return 1; }
}
