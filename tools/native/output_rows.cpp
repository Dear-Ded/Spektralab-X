// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
#include "output_transform.hpp"
#include "tiff16.hpp"

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <sys/resource.h>

using namespace spk::android;

void require(bool pass, const char* message) {
    if (!pass) { std::fprintf(stderr, "%s\n", message); std::abort(); }
}

std::vector<float> settings(bool local) {
    std::vector<float> values(50, 0);
    values[kExposure] = 0.3f; values[kContrast] = 17; values[kBrightness] = 9;
    values[kVignette] = -37; values[kVignetteMid] = 43; values[kSaturation] = 11;
    for (int row = 0; row < 5; ++row)
        values.insert(values.end(), {4, 0, 0, 0.25f, 0.18f, 0.75f, 0.86f, 1, 1});
    if (local) {
        values.insert(values.end(), {9157, 1, 55, 17, 30, 60, 35, 23891, 3});
        for (int kind = 0; kind < 3; ++kind) {
            std::array<float, 22> mask{};
            mask[0] = float(kind); mask[1] = float(kind == 1); mask[2] = 0.7f;
            mask[3] = 0.4f; mask[4] = 11; mask[6] = -17;
            mask[13] = 0.21f; mask[14] = 0.19f; mask[15] = 0.78f; mask[16] = 0.83f;
            mask[17] = 0.33f; mask[18] = 0.17f; mask[19] = 23; mask[20] = 0.6f;
            mask[21] = kind == 2 ? 3.0f : 0.0f;
            values.insert(values.end(), mask.begin(), mask.end());
            if (kind == 2) values.insert(values.end(), {
                0.23f, 0.29f, 0.2f, 0.5f, 0.7f,
                0.47f, 0.53f, 0.21f, 0.6f, 0.8f,
                0.51f, 0.49f, 0.11f, 0.4f, -0.6f});
        }
    }
    return values;
}

std::vector<uint8_t> bytes(std::FILE* file) {
    require(std::fseek(file, 0, SEEK_END) == 0, "seek TIFF length");
    const long n = std::ftell(file);
    require(n >= 0, "TIFF length");
    std::vector<uint8_t> result(size_t(n), 0);
    std::rewind(file);
    require(std::fread(result.data(), 1, result.size(), file) == result.size(), "read TIFF");
    return result;
}

void regression(const std::vector<OutputTransform>& transforms) {
    const std::vector<uint8_t> icc{1, 2, 3, 4, 5, 6, 7};
    const Layer2 tone(settings(false)), local(settings(true));
    require(local.frame(73, 97).placed != nullptr, "local fixture must include masks and dust");
    size_t cases = 0;
    for (const auto shape : {std::array<uint32_t, 2>{1, 1}, {7, 31}, {9, 32}, {7, 33}, {73, 97}, {97, 73}}) {
        const auto [w, h] = shape;
        const uint32_t stride = w + 7;
        std::vector<uint16_t> src(size_t(stride) * h * 4);
        for (size_t i = 0; i < src.size(); ++i) src[i] = uint16_t(i * 63001u + i / 7);
        const auto saved = src;
        for (const Layer2* look : {static_cast<const Layer2*>(nullptr), &tone, &local}) {
            for (size_t t = 0; t <= transforms.size(); ++t) {
                const auto* xf = t ? &transforms[t - 1] : nullptr;
                std::vector<uint16_t> expected;
                size_t expected_stride = w;
                // The previous production route, including its full-frame copy.
                if (xf) pack_rgba16(src.data(), stride, w, h, expected, look, *xf);
                else {
                    expected = src; expected_stride = stride;
                    if (look) apply_rgba16_in_place(*look, expected.data(), stride, w, h);
                }
                const Rgba16Rows rows(src.data(), stride, w, h, look, xf);
                for (uint32_t step : {1u, 3u, 32u, 65u, h}) {
                    std::vector<uint16_t> strip;
                    for (uint32_t y0 = 0; y0 < h; y0 += step) {
                        const uint32_t count = std::min(step, h - y0);
                        rows.pack(y0, count, strip);
                        require(strip.size() == size_t(w) * count * 4, "row pack size");
                        for (uint32_t y = 0; y < count; ++y)
                            for (uint32_t x = 0; x < w; ++x)
                                require(std::memcmp(strip.data() + (size_t(y) * w + x) * 4,
                                    expected.data() + (size_t(y0 + y) * expected_stride + x) * 4, 6) == 0,
                                    "row conversion changed RGB16 bytes");
                    }
                }
                std::unique_ptr<std::FILE, decltype(&std::fclose)> before(std::tmpfile(), &std::fclose),
                    after(std::tmpfile(), &std::fclose);
                std::string error;
                require(write_tiff16(before.get(), expected.data(), w, h, uint32_t(expected_stride), icc, error), "full TIFF");
                require(write_tiff16(after.get(), w, h, icc,
                    [&](uint32_t y0, uint32_t count, std::vector<uint16_t>& scratch) {
                        rows.pack(y0, count, scratch); return TiffRowView{scratch.data(), w};
                    }, error), "stream TIFF");
                require(bytes(before.get()) == bytes(after.get()), "TIFF file bytes changed");
                require(src == saved, "source image changed");
                ++cases;
            }
        }
    }
    std::unique_ptr<std::FILE, decltype(&std::fclose)> broken(std::tmpfile(), &std::fclose);
    std::string error;
    require(!write_tiff16(broken.get(), 73, 97, icc,
        [](uint32_t, uint32_t, std::vector<uint16_t>&) -> TiffRowView { throw std::bad_alloc(); }, error),
        "provider failure must fail the TIFF");
    require(bytes(broken.get()).size() == 8, "failed TIFF must have no final directory");
    std::printf("output rows: %zu cases x 5 strip heights; RGB16 and complete TIFF byte-identical; provider failure passed\n", cases);
}

void memory(const OutputTransform& xf, bool full) {
    constexpr uint32_t w = 4097, h = 3073;
    std::vector<uint16_t> src(size_t(w) * h * 4);
    for (size_t i = 0; i < src.size(); ++i) src[i] = uint16_t(i * 97u + i / 17);
    Layer2 look(settings(false));
    std::unique_ptr<std::FILE, decltype(&std::fclose)> file(std::tmpfile(), &std::fclose);
    std::string error;
    std::vector<uint16_t> adjusted;
    if (full) {
        pack_rgba16(src.data(), w, w, h, adjusted, &look, xf);
        require(write_tiff16(file.get(), adjusted.data(), w, h, w, {}, error), "memory full TIFF");
    } else {
        const Rgba16Rows rows(src.data(), w, w, h, &look, &xf);
        require(write_tiff16(file.get(), w, h, {},
            [&](uint32_t y0, uint32_t count, std::vector<uint16_t>& scratch) {
                rows.pack(y0, count, scratch); return TiffRowView{scratch.data(), w};
            }, error), "memory stream TIFF");
    }
    const auto payload = bytes(file.get());
    uint64_t hash = 0xcbf29ce484222325ull;
    for (uint8_t value : payload) { hash ^= value; hash *= 0x100000001b3ull; }
    rusage usage{}; getrusage(RUSAGE_SELF, &usage);
    std::printf("TIFF memory: mode=%s peak_rss_kib=%ld bytes=%zu fnv1a=%016llx\n", full ? "full" : "rows",
                usage.ru_maxrss, payload.size(), static_cast<unsigned long long>(hash));
}

int main(int argc, char** argv) {
    require(argc >= 2, "usage: output_rows RESOURCES [--memory-full|--memory-rows]");
    std::unique_ptr<spk_engine, decltype(&spk_engine_destroy)> engine(spk_engine_create(argv[1], nullptr), &spk_engine_destroy);
    require(bool(engine), "create output test engine");
    std::vector<OutputTransform> transforms;
    for (const char* target : {"sRGB", "Display P3"})
        for (bool exporting : {false, true})
            for (bool exact : {false, true}) {
                OutputTransform xf; std::string error;
                if (!xf.fetch(engine.get(), target, exporting, error)) { std::fputs(error.c_str(), stderr); std::abort(); }
                xf.set_exact(exact); transforms.push_back(std::move(xf));
            }
    if (argc == 3) { memory(transforms[0], std::string(argv[2]) == "--memory-full"); return 0; }
    regression(transforms);
}
