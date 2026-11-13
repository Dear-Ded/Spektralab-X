// Co-created with GPT (OpenAI). Actual meter/scene code and a sorted-rank oracle.
#include "pipeline/pipeline.hpp"
#include "core/latitude_fit.hpp"
#if SPK_SELECT_CANDIDATE
#include "core/order_statistics.hpp"
#endif
#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
uint64_t bits(double value) { return std::bit_cast<uint64_t>(value); }
void mix(uint64_t& hash, double value) { hash = (hash ^ bits(value)) * 0x100000001b3ull; }
std::vector<double> input(size_t n, int pattern) {
    std::vector<double> values(n);
    uint64_t state = 0x243f6a8885a308d3ull;
    for (size_t i = 0; i < n; ++i) {
        state ^= state << 13; state ^= state >> 7; state ^= state << 17;
        const double ramp = (double(i) + 1.0) / double(n);
        switch (pattern) {
        case 0: values[i] = 0.184; break;
        case 1: values[i] = std::exp2(double(int(state % 65537) - 32768) / 1024.0); break;
        case 2: values[i] = ramp; break;
        case 3: values[i] = 1.0 - ramp; break;
        case 4: values[i] = double(std::min(i, n - 1 - i)) / double(n); break;
        case 5: values[i] = double((i + n / 2) % n) / double(n); break;
        case 6: values[i] = i % 3 ? 0.184 : 8.0; break;
        case 7: values[i] = i % 1000 == 0 ? 4096.0 : (i % 100 < 3 ? 0.0 : ramp); break;
        case 8: values[i] = double(int(state % 31) - 15) / 8.0; break;
        default: values[i] = i % 2 ? -0.0 : 0.0; break;
        }
    }
    return values;
}
bool ambiguous(const std::vector<double>& values) {
    bool a = false, b = false;
    for (double value : values) {
        if (std::isnan(value)) return true;
        if (value == 0.0) (std::signbit(value) ? a : b) = true;
    }
    return a && b;
}
template<size_t N>
std::array<double, N> rank_oracle(const std::vector<double>& source,
                                 const std::array<size_t, N>& ranks) {
    auto values = source;
    std::array<double, N> result{};
    if (ambiguous(values)) {
        for (size_t i = 0; i < N; ++i) {
            std::nth_element(values.begin(), values.begin() + ranks[i], values.end());
            result[i] = values[ranks[i]];
        }
    } else {
        std::sort(values.begin(), values.end());
        for (size_t i = 0; i < N; ++i) result[i] = values[ranks[i]];
    }
    return result;
}
void helper_oracle(const std::vector<double>& source) {
#if SPK_SELECT_CANDIDATE
    const size_t end = source.size() - 1;
    const std::array<size_t, 7> ranks = {0, 0, end / 10, end / 2, end / 2, end, end};
    auto values = source;
    const auto actual = spk::detail::select_ranks(values, ranks);
    const auto expected = rank_oracle(source, ranks);
    require(std::memcmp(actual.data(), expected.data(), sizeof actual) == 0, "rank oracle");
    values = source;
    const double median = spk::detail::median_in_place(values);
    values = source;
    std::sort(values.begin(), values.end());
    const size_t n = values.size(), m = n / 2;
    const double sorted_median = n % 2 ? values[m] : 0.5 * (values[m - 1] + values[m]);
    require(bits(median) == bits(sorted_median), "median sorted oracle");
#else
    (void)source;
#endif
}
uint64_t scene_hash(const spk::slm::SceneStats& scene) {
    uint64_t hash = 0xcbf29ce484222325ull;
    for (double value : scene.histogram) mix(hash, value);
    mix(hash, scene.p01); mix(hash, scene.p1); mix(hash, scene.p50);
    mix(hash, scene.p99); mix(hash, scene.p999);
    return hash;
}
std::vector<double> scene_input(const std::vector<double>& raw) {
    auto values = raw;
    for (double& value : values) {
        if (value > 0.0) value = std::log2(value);
        else if (value < 0.0) value = -std::log2(1.0 - value);
    }
    return values;
}
void fixture(const std::vector<double>& raw, int pattern) {
    helper_oracle(raw);
    const size_t n = raw.size();
    size_t h = size_t(std::sqrt(double(n)));
    while (n % h) --h;
    const auto ev = spk::Pipeline::exposure_evs_from(raw, uint32_t(h), uint32_t(n / h));
    const auto stops = scene_input(raw);
    const auto scene = spk::slm::scene_stats(stops);
    const std::array<size_t, 5> ranks = {10 * (n - 1) / 10000, 100 * (n - 1) / 10000,
        5000 * (n - 1) / 10000, 9900 * (n - 1) / 10000, 9990 * (n - 1) / 10000};
    const auto expected = rank_oracle(stops, ranks);
    const std::array<double, 5> actual = {scene.p01, scene.p1, scene.p50, scene.p99, scene.p999};
    require(std::memcmp(actual.data(), expected.data(), sizeof actual) == 0, "production scene ranks");
    require(scene.samples == n && scene.histogram.size() == 128, "scene shape");
    std::cout << n << ',' << pattern << ',' << std::hex << bits(ev.balanced) << ',' << bits(ev.center)
        << ',' << bits(ev.protect_highlights) << ',' << bits(ev.protect_shadows) << ',' << scene_hash(scene);
    for (const char* method : {"median", "average", "center_weighted"}) {
        double result = 0.0; std::string error;
        require(spk::Pipeline::legacy_exposure_ev(raw, uint32_t(h), uint32_t(n / h), method, result, error),
            "legacy meter");
        std::cout << ',' << bits(result);
    }
    std::cout << std::dec << '\n';
}
void regression() {
    std::vector<size_t> sizes;
    for (size_t n = 1; n <= 128; ++n) sizes.push_back(n);
    for (size_t n : {129, 255, 256, 257, 511, 512, 513, 997, 1000, 1001,
                     1023, 1024, 1025, 8191, 8192, 8193, 65025, 65536}) sizes.push_back(n);
    std::cout << "samples,pattern,balanced,center,protect_highlights,protect_shadows,scene,median,average,center_weighted\n";
    for (size_t n : sizes) for (int pattern = 0; pattern < 10; ++pattern) fixture(input(n, pattern), pattern);
    require(spk::slm::scene_stats({}).samples == 0, "empty scene");
    const auto empty = spk::Pipeline::exposure_evs_from({}, 0, 0);
    require(bits(empty.balanced) == bits(0.0), "empty meter");
#if SPK_SELECT_CANDIDATE
    helper_oracle({-0.0, 0.0, 0.0, -0.0});
    helper_oracle({-INFINITY, -1.0, 0.0, 1.0, INFINITY});
    helper_oracle({0.0, std::nan("7"), 1.0, -1.0, 4.0});
#endif
    std::cerr << "PASS production_vectors=" << sizes.size() * 10
        << "; scene_sorted_oracle; median_sorted_oracle; duplicate_ranks; signed_zero; empty\n";
}
void benchmark(const std::string& mode, int pattern, int iterations) {
    auto raw = input(65536, pattern);
    if (mode == "scene") raw = scene_input(raw);
    uint64_t hash = 0xcbf29ce484222325ull;
    const auto start = std::chrono::steady_clock::now();
    for (int i = 0; i < iterations; ++i) {
        if (mode == "scene") hash = (hash ^ scene_hash(spk::slm::scene_stats(raw))) * 0x100000001b3ull;
        else if (mode == "meter") {
            const auto ev = spk::Pipeline::exposure_evs_from(raw, 256, 256);
            mix(hash, ev.balanced); mix(hash, ev.center);
            mix(hash, ev.protect_highlights); mix(hash, ev.protect_shadows);
        } else {
            require(mode == "median", "benchmark mode");
            double ev = 0.0; std::string error;
            require(spk::Pipeline::legacy_exposure_ev(raw, 256, 256, "median", ev, error), "median benchmark");
            mix(hash, ev);
        }
    }
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    std::cout << mode << ',' << pattern << ",65536," << iterations << ',' << ms << ',' << std::hex << hash << '\n';
}
}
int main(int argc, char** argv) {
    try {
        if (argc == 5 && std::string(argv[1]) == "--benchmark") benchmark(argv[2], std::stoi(argv[3]), std::stoi(argv[4]));
        else { require(argc == 1, "arguments"); regression(); }
        return 0;
    } catch (const std::exception& error) { std::cerr << "FAIL " << error.what() << '\n'; return 1; }
}
