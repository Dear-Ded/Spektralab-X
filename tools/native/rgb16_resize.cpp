// Co-created with GPT (OpenAI). Frozen area oracle, exhaustive code values,
// cancellation atomicity and separate-process conversion-stage RSS.
#include "resize.hpp"
#include <array>
#include <bit>
#include <chrono>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <sys/resource.h>

void resize_area_rgb_reference(const std::vector<float>&, uint32_t, uint32_t,
                               uint32_t, uint32_t, std::vector<float>&);
namespace {
void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}
uint32_t random_bits(uint32_t& state) {
    state ^= state << 13; state ^= state >> 17; state ^= state << 5; return state;
}
bool same(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0;
}
std::vector<uint8_t> source(uint32_t w, uint32_t h, int pattern, std::vector<float>* floats = nullptr) {
    const size_t count = size_t(w) * h * 3;
    std::vector<uint8_t> bytes(count * 2 + 1);
    if (floats) floats->resize(count);
    uint32_t state = 0x791357af;
    for (size_t i = 0; i < count; ++i) {
        uint16_t value = uint16_t(random_bits(state));
        if (pattern == 1) value = uint16_t(i);
        if (pattern == 2) value = i % 2 ? 65535 : 0;
        if (pattern == 3) value = i < 3 || i + 3 >= count ? 65535 : 0;
        if (pattern == 4) value = 32768;
        std::memcpy(bytes.data() + 1 + i * 2, &value, 2);
        if (floats) (*floats)[i] = float(double(value) / 65535.0);
    }
    return bytes;
}
void correctness() {
    std::vector<std::array<uint32_t, 4>> shapes{{1,1,1,1}, {1,513,1,117}, {997,1,37,1},
        {65536,1,65536,1}, {19,23,19,23}, {512,257,1,1}, {997,541,257,251}, {1025,769,1000,750}};
    uint32_t state = 0x10203040;
    for (int i = 0; i < 96; ++i) {
        const uint32_t w = 1 + random_bits(state) % 151, h = 1 + random_bits(state) % 157;
        shapes.push_back({w, h, 1 + random_bits(state) % w, 1 + random_bits(state) % h});
    }
    size_t cases = 0, samples = 0;
    for (const auto& s : shapes) for (int pattern = 0; pattern < 5; ++pattern) {
        std::vector<float> input, expected, actual, float_actual;
        const auto bytes = source(s[0], s[1], pattern, &input);
        resize_area_rgb_reference(input, s[0], s[1], s[2], s[3], expected);
        spk::android::resize_area_rgb(input, s[0], s[1], s[2], s[3], float_actual);
        require(spk::android::resize_area_rgb16(bytes.data() + 1, bytes.size() - 1,
            s[0], s[1], s[2], s[3], actual), "uncancelled RGB16 resize");
        require(same(actual, expected) && same(float_actual, expected), "frozen area oracle differs");
        ++cases; samples += actual.size();
    }
    const auto bytes = source(513, 257, 0);
    const std::vector<float> sentinel{-17, 0.25f, 9};
    for (int at : {1, 3, 9, 33}) {
        auto result = sentinel;
        int polls = 0;
        require(!spk::android::resize_area_rgb16(bytes.data() + 1, bytes.size() - 1,
            513, 257, 311, 129, result, [&] { return ++polls == at; }) &&
            polls == at && same(result, sentinel), "cancellation must join and preserve output");
    }
    auto result = sentinel;
    require(!spk::android::resize_area_rgb16(bytes.data() + 1, bytes.size() - 1,
        513, 257, 311, 129, result, []() -> bool { throw std::runtime_error("poll"); }) &&
        same(result, sentinel), "throwing cancellation poll");
    int rejected = 0;
    for (int bad = 0; bad < 5; ++bad) {
        result = sentinel;
        try {
            spk::android::resize_area_rgb16(bad == 0 ? nullptr : bytes.data() + 1,
                bytes.size() - 1 - (bad == 1), bad == 2 ? 0 : 513, 257,
                bad == 3 ? 514 : 311, bad == 4 ? 258 : 129, result);
        } catch (const std::invalid_argument&) { ++rejected; }
        require(same(result, sentinel), "invalid input changed destination");
    }
    require(rejected == 5, "invalid layout not rejected");
    std::cout << "PASS RGB16: cases=" << cases << " samples=" << samples
              << " exact_bits; all_65536_codes; unaligned_input; cancel=5; invalid=5\n";
}
void memory(bool fused) {
    constexpr uint32_t w = 6000, h = 4000, nw = 1500, nh = 1000;
    auto bytes = source(w, h, 0);
    std::vector<float> output, full;
    const auto start = std::chrono::steady_clock::now();
    if (fused) {
        require(spk::android::resize_area_rgb16(bytes.data() + 1, bytes.size() - 1, w, h, nw, nh, output), "resize");
        std::vector<uint8_t>().swap(bytes);
    } else {
        full.resize(size_t(w) * h * 3);
        for (size_t i = 0; i < full.size(); ++i) {
            uint16_t value;
            std::memcpy(&value, bytes.data() + 1 + i * 2, 2);
            full[i] = float(double(value) / 65535.0);
        }
        // Production releases the RGB16 bitmap when decode returns, before
        // the old Android path allocates its resized float destination.
        std::vector<uint8_t>().swap(bytes);
        spk::android::resize_area_rgb(full, w, h, nw, nh, output);
        std::vector<float>().swap(full);
    }
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    uint64_t hash = 0xcbf29ce484222325ull;
    for (float value : output) {
        const uint32_t bits = std::bit_cast<uint32_t>(value);
        for (int k = 0; k < 32; k += 8) { hash ^= (bits >> k) & 255; hash *= 0x100000001b3ull; }
    }
    rusage usage{}; getrusage(RUSAGE_SELF, &usage);
    std::cout << "mode=" << (fused ? "fused" : "full") << ",shape=6000x4000->1500x1000,peak_rss_kib="
        << usage.ru_maxrss << ",ms=" << ms << ",fnv1a=" << std::hex << hash << '\n';
}
}
int main(int argc, char** argv) {
    try {
        if (argc == 2) memory(std::string(argv[1]) == "--memory-fused");
        else correctness();
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
