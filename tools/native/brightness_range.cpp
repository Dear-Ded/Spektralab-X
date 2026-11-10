// Co-created with GPT (OpenAI), 2026-11-09. See NOTICE for project terms.
#include "layer2.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

using namespace spk::android;

namespace {
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
int code(double value) { return int(std::lround(std::clamp(value, 0.0, 1.0) * 65535)); }

// Emit actual bytes for a same-toolchain comparison against the unmodified
// implementation. Do not hardcode a libm-specific hash as a portable oracle.
void original_range(const char* snapshot) {
    std::FILE* file = snapshot ? std::fopen(snapshot, "wb") : nullptr;
    require(!snapshot || file, "cannot open range snapshot");
    uint64_t hash = 14695981039346656037ull;
    size_t samples = 0;
    for (float brightness : {-50.f, -25.f, -1.f, 1.f, 10.f, 25.f, 50.f}) {
        std::vector<float> values(kLookSize, 0); values[kBrightness] = brightness;
        Layer2 look(values);
        for (uint32_t i = 0; i <= 65536; ++i) {
            float r = float(i) / 32768.f, g = r, b = r;
            look.apply(r, g, b, .5f, .5f);
            require(std::isfinite(r) && r == g && g == b, "range output is not finite neutral RGB");
            const double expected = std::pow(double(i) / 32768, 1.0 / (1.0 + double(brightness) * .008));
            // The existing uniform table approximates the steep near-black
            // curve. This change preserves that separate approximation policy.
            require(std::abs(double(r) - expected) < .0006, "original-range interpolation drift");
            const uint32_t bits = std::bit_cast<uint32_t>(r);
            for (unsigned shift = 0; shift < 32; shift += 8) {
                hash ^= (bits >> shift) & 255u; hash *= 1099511628211ull;
            }
            if (file) require(std::fwrite(&r, sizeof r, 1, file) == 1, "cannot write range snapshot");
            ++samples;
        }
    }
    if (file) require(std::fclose(file) == 0, "cannot close range snapshot");
    std::printf("brightness original range: %zu float32 samples, fnv1a=%016llx\n",
        samples, static_cast<unsigned long long>(hash));
}

void outside_range() {
    size_t cases = 0;
    // All inputs are legitimate RGBA16 codes; the controls are within the UI
    // bounds. Desaturation makes bright channel values observable below white.
    for (float exposure : {2.25f, 2.75f, 3.f}) {
        for (float brightness : {-50.f, -25.f, -1.f, 1.f, 25.f, 50.f}) {
            for (unsigned channel : {0u, 1u, 2u}) {
                std::vector<float> values(kLookSize, 0);
                values[kExposure] = exposure; values[kBrightness] = brightness;
                values[kSaturation] = -100;
                Layer2 look(values);
                std::array<float, 3> rgb{}; rgb[channel] = 1;
                look.apply(rgb[0], rgb[1], rgb[2], .5f, .5f);
                const double weights[] = {.2126, .7152, .0722};
                const double lit = std::pow(std::pow(1.0, 2.2) * std::pow(2.0, exposure), 1.0 / 2.2);
                const double expected = std::pow(lit, 1.0 / (1.0 + brightness / 100.0 * .8)) * weights[channel];
                for (float value : rgb) {
                    require(std::isfinite(value), "out-of-range brightness became non-finite");
                    require(std::abs(code(value) - code(expected)) <= 2, "brightness clipped a recoverable channel above two");
                }
                ++cases;
            }
        }
    }
    for (float brightness : {-50.f, 50.f}) {
        std::vector<float> values(kLookSize, 0); values[kBrightness] = brightness;
        Layer2 look(values);
        float previous = 0;
        for (float input : {std::nextafter(2.f, 0.f), 2.f, std::nextafter(2.f, 3.f), 2.5f, 8.f}) {
            float r = input, g = input, b = input; look.apply(r, g, b, .5f, .5f);
            const double expected = std::pow(double(input), 1.0 / (1.0 + brightness / 100.0 * .8));
            require(std::isfinite(r) && r >= previous, "brightness is discontinuous at the table boundary");
            require(std::abs(double(r) - expected) <= std::max(1.0, expected) * 3e-6,
                "outside brightness disagrees with independent power oracle");
            previous = r; ++cases;
        }
    }
    std::printf("brightness outside range: %zu legal-control/threshold cases passed\n", cases);
}
}

int main(int argc, char** argv) {
    try {
        bool range_only = false; const char* snapshot = nullptr;
        for (int i = 1; i < argc; ++i) {
            if (std::strcmp(argv[i], "--range-only") == 0) range_only = true;
            else if (std::strcmp(argv[i], "--snapshot") == 0 && i + 1 < argc) snapshot = argv[++i];
            else throw std::runtime_error("usage: brightness_range [--range-only] [--snapshot PATH]");
        }
        original_range(snapshot);
        if (!range_only) outside_range();
        return 0;
    } catch (const std::exception& e) { std::fprintf(stderr, "%s\n", e.what()); return 1; }
}
