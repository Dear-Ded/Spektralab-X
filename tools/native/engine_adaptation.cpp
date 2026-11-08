// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
#include "gainmap.hpp"
#include "look_state.hpp"
#include "resize.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>

#include <sys/resource.h>

void resize_area_rgb_reference(const std::vector<float>&, uint32_t, uint32_t, uint32_t, uint32_t, std::vector<float>&);

namespace {
std::atomic<bool> refuse_allocation{false};
void require(bool ok, const char* what) {
    if (!ok) { std::fprintf(stderr, "FAIL: %s\n", what); std::exit(1); }
}
uint32_t random_bits(uint32_t& s) { s ^= s << 13; s ^= s >> 17; s ^= s << 5; return s; }

void resize_test(bool large) {
    using Shape = std::array<uint32_t, 4>;
    std::vector<Shape> shapes{{1, 1, 1, 1}, {1, 67, 1, 29}, {71, 1, 31, 1},
                             {19, 23, 19, 23}, {512, 257, 1, 1}, {997, 541, 257, 251}};
    uint32_t rng = 0x13579bdf;
    for (int i = 0; i < 64; ++i) {
        const uint32_t w = 1 + random_bits(rng) % 131, h = 1 + random_bits(rng) % 137;
        shapes.push_back({w, h, 1 + random_bits(rng) % w, 1 + random_bits(rng) % h});
    }
    if (large) shapes.push_back({4097, 3073, 2049, 1537});
    size_t cases = 0;
    for (const auto& s : shapes) {
        for (int pattern = 0; pattern < 5; ++pattern) {
            std::vector<float> src(size_t(s[0]) * s[1] * 3);
            for (size_t i = 0; i < src.size(); ++i) {
                const uint32_t b = random_bits(rng);
                if (pattern == 0) src[i] = std::bit_cast<float>((b & 0x807fffffu) | ((107u + ((b >> 22) % 38u)) << 23));
                if (pattern == 1) src[i] = (i & 1) ? 65536.0f : -65535.5f;
                if (pattern == 2) src[i] = i % 3 == 0 ? -0.0f : 0.18f;
                if (pattern == 3) src[i] = (i < 3 || i + 3 >= src.size()) ? 4096.0f : 0.0f;
                if (pattern == 4) src[i] = std::bit_cast<float>((b & 1u) | (b & 0x80000000u));
            }
            std::vector<float> expected, got;
            resize_area_rgb_reference(src, s[0], s[1], s[2], s[3], expected);
            spk::android::resize_area_rgb(src, s[0], s[1], s[2], s[3], got);
            require(got.size() == expected.size() && !std::memcmp(got.data(), expected.data(), got.size() * sizeof(float)),
                    "row-cache resize must match the two-pass oracle byte for byte");
            if (src.size() < 50000) {
                spk::android::resize_area_rgb(src, s[0], s[1], s[2], s[3], src);
                require(src == got, "resize with aliased source/destination");
            }
            ++cases;
        }
    }
    bool rejected = false;
    try { std::vector<float> src, dst; spk::android::resize_area_rgb(src, 0, 1, 1, 1, dst); }
    catch (const std::invalid_argument&) { rejected = true; }
    require(rejected, "zero dimensions must be refused before computing taps");
    std::printf("resize: %zu shapes/patterns byte-identical\n", cases);
}

void gainmap_test() {
    using spk::android::GainmapChannel;
    GainmapChannel c;
    require(GainmapChannel::make(1, 16, 2, 0, 0, c), "valid HDR metadata");
    const float sample = spk::android::gainmap_bilinear(0, 255, 0, 255, 0.5f, 0.5f);
    require(sample == 0.5f && c.recover(1, sample) == 2, "sample then gamma: sqrt-gamma or boost-space interpolation is wrong");
    for (float gamma : {0.5f, 1.0f, 2.0f, 3.0f}) {
        require(GainmapChannel::make(0.5f, 32.0f, gamma, 0.02f, 0.005f, c), "per-channel metadata");
        for (int i = 0; i <= 1024; ++i) {
            const float g = i / 1024.0f;
            const double l = std::log(0.5) * (1 - std::pow(double(g), gamma)) + std::log(32.0) * std::pow(double(g), gamma);
            const double expected = (0.18 + double(c.epsilon_sdr)) * std::exp(l) - double(c.epsilon_hdr);
            const double got = c.recover(0.18f, g);
            require(std::abs(got - expected) <= 2e-6 * std::max(1.0, std::abs(expected)), "Android HDR equation in natural-log double oracle");
        }
    }
    require(!GainmapChannel::make(0, 4, 1, 0, 0, c), "zero gain ratio rejected");
    require(!GainmapChannel::make(1, 4, 0, 0, 0, c), "zero gamma rejected");
    require(!GainmapChannel::make(1, 4, 1, -1, 0, c), "negative offset rejected");
    require(!GainmapChannel::make(1, 4, std::numeric_limits<float>::quiet_NaN(), 0, 0, c), "NaN metadata rejected");
    std::puts("gainmap: 4100 scalar equations and bilinear/gamma order passed");
}

struct RedTable : spk::android::ColourTable {
    void apply(float& r, float& g, float& b) const override { r = 1; g = 0; b = 0; }
};

void look_test() {
    spk::android::LookState state;
    require(!state.active(), "initial look is identity");
    auto table = std::make_shared<RedTable>();
    state.set_lut(table, 1);
    state.set_values({});
    float r = 0.2f, g = 0.3f, b = 0.4f;
    state.active()->apply(r, g, b, 0.5f, 0.5f);
    require(r == 1 && g == 0 && b == 0, "setLook must retain the LUT");
    const auto* previous = state.active();
    bool failed = false;
    refuse_allocation = true;
    try { state.set_values({}); } catch (const std::bad_alloc&) { failed = true; }
    refuse_allocation = false;
    require(failed && state.active() == previous, "setLook allocation failure must preserve the previous look");
    failed = false;
    refuse_allocation = true;
    try { state.set_lut(nullptr, 0); } catch (const std::bad_alloc&) { failed = true; }
    refuse_allocation = false;
    require(failed && state.active() == previous, "setLut allocation failure must preserve the previous look");
    state.set_lut(nullptr, 0);
    require(!state.active(), "explicit LUT clear restores identity");
    std::puts("look: LUT retention and allocation-failure transactions passed");
}

void memory_run(bool reference) {
    constexpr uint32_t w = 4097, h = 3073, nw = 2049, nh = 1537;
    std::vector<float> src(size_t(w) * h * 3), out;
    uint32_t rng = 0x2468ace1;
    for (float& v : src) v = float(random_bits(rng) % 65536) / 8192;
    const auto start = std::chrono::steady_clock::now();
    if (reference) resize_area_rgb_reference(src, w, h, nw, nh, out);
    else spk::android::resize_area_rgb(src, w, h, nw, nh, out);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    uint64_t hash = 0xcbf29ce484222325ull;
    for (float v : out) {
        const uint32_t bits = std::bit_cast<uint32_t>(v);
        for (int shift = 0; shift < 32; shift += 8) { hash ^= (bits >> shift) & 255; hash *= 0x100000001b3ull; }
    }
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    std::printf("memory: mode=%s shape=4097x3073->2049x1537 peak_rss_kib=%ld ms=%.3f output_fnv1a=%016llx\n",
                reference ? "reference" : "cache", usage.ru_maxrss, ms, static_cast<unsigned long long>(hash));
}
}  // namespace

void* operator new(std::size_t size) {
    if (refuse_allocation) throw std::bad_alloc();
    if (void* p = std::malloc(size ? size : 1)) return p;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size) { return ::operator new(size); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t) noexcept { std::free(p); }

int main(int argc, char** argv) {
    static_assert(sizeof(float) == 4 && std::numeric_limits<float>::is_iec559);
    if (argc > 1 && std::string(argv[1]) == "--memory-reference") { memory_run(true); return 0; }
    if (argc > 1 && std::string(argv[1]) == "--memory-cache") { memory_run(false); return 0; }
    resize_test(argc > 1 && std::string(argv[1]) == "--large");
    gainmap_test();
    look_test();
}
