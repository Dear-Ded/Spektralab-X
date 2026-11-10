// Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
#include "layer2.hpp"
#include "layer2_local.hpp"

#include <array>
#include <cmath>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <vector>

namespace {
using namespace spk::android;
void require(bool ok, const char* what) { if (!ok) throw std::runtime_error(what); }

std::vector<float> masked(const std::array<float, 10>& controls) {
    std::vector<float> values(kLookSize, 0);
    values[kVignetteMid] = 50;
    for (int row = 0; row < 5; ++row) values.insert(values.end(), {2, 0, 0, 1, 1});
    values.insert(values.end(), {9157, 1, 0, 0, 0, 0, 0, 1, 1});
    std::array<float, 22> mask{};
    mask[0] = 0; mask[2] = 1;
    std::copy(controls.begin(), controls.end(), mask.begin() + 3);
    mask[13] = 0; mask[14] = 0; mask[15] = .1f; mask[16] = 0;
    mask[17] = .2f; mask[18] = .2f; mask[20] = .5f;
    values.insert(values.end(), mask.begin(), mask.end());
    return values;
}

void recovered_channels() {
    for (const auto& item : {std::array<float, 5>{4, .2f, .2f, 50, 0},
                             std::array<float, 5>{-.2f, .2f, .2f, 0, 0},
                             std::array<float, 5>{4, 2, .8f, 0, -3}}) {
        std::array<float, 10> controls{};
        controls[0] = item[4]; controls[2] = item[3]; controls[3] = -100;
        const Layer2 layer(masked(controls));
        const auto frame = layer.frame(17, 13);
        require(bool(frame.placed), "mask fixture did not reach production parser");
        const double weights[] = {.2126, .7152, .0722};
        for (bool unquantised : {false, true}) {
            double expected = 0;
            for (int k = 0; k < 3; ++k) {
                double c = item[k];
                if (item[4] != 0) {
                    if (unquantised) {
                        const double linear = c < 1.0 / 32.0 ? c / 16.0 : std::pow(c, 1.8);
                        const double exposed = linear * std::exp2(double(item[4]));
                        c = exposed < 1.0 / 512.0 ? 16.0 * exposed : std::pow(exposed, 1.0 / 1.8);
                    } else {
                        c = std::pow(std::pow(std::max(0.0, c), 2.2) * std::pow(2.0, item[4]), 1.0 / 2.2);
                    }
                }
                if (item[3] != 0) c = std::pow(std::max(0.0, c), 1.0 / (1 + item[3] * .008));
                expected += c * weights[k];
            }
            float r = item[0], g = item[1], b = item[2];
            layer.apply(r, g, b, .5f, .5f, frame, unquantised);
            for (float c : {r, g, b}) require(std::isfinite(c) && std::abs(c - expected) < 2e-6, "mask clipped recoverable RGB");
        }
    }
    std::cout << "local recovered channels: 6 masked HDR/signed cases passed\n";
}

void preserved_domain() {
    size_t cases = 0;
    // These inputs remain in the old nonnegative <=2 brightness domain.
    for (float ev : {-3.f, 0.f, 3.f}) for (float brightness : {-50.f, 0.f, 50.f})
        for (int i = 0; i <= 4096; ++i) {
            std::array<float, 10> controls{};
            controls[0] = ev; controls[2] = brightness;
            local::Tone tone(controls.data());
            float got[3] = {i / 4096.f * .125f, .03f, .1f};
            float old[3]; std::memcpy(old, got, sizeof old);
            for (float& c : old) c = std::max(0.0f, c) * std::pow(2.f, ev / 2.2f);
            if (brightness != 0) for (float& c : old)
                c = std::pow(std::min(2.f, std::max(0.f, c)), 1.f / (1.f + brightness / 100.f * .8f));
            tone.apply(got);
            require(std::memcmp(got, old, sizeof old) == 0, "ordinary mask arithmetic changed");
            ++cases;
        }
    std::cout << "local ordinary domain: " << cases << " RGB triplets byte-identical\n";
}

void bounded_mask_weights() {
    size_t cases = 0;
    // Exercise the complete mask parser and full-coverage blend, not just
    // local::Tone. All inputs are legal RGBA16 codes before positive exposure.
    for (bool unquantised : {false, true}) {
        std::array<float, 10> controls{};
        controls[0] = 3;
        const Layer2 exposure_only(masked(controls));
        const auto frame = exposure_only.frame(17, 13);
        require(bool(frame.placed), "tone-weight fixture missed mask parser");
        controls[4] = -100;
        const Layer2 highlights(masked(controls));
        float previous = -1;
        for (unsigned code = 0; code <= 65535; code += 257) {
            const float input = float(code) / 65535;
            float r = input, g = input, b = input;
            highlights.apply(r, g, b, .5f, .5f, frame, unquantised);
            require(std::isfinite(r) && r >= 0 && r + 1e-6f >= previous,
                    "legal mask exposure/highlights reversed bright light");
            require(std::abs(r - g) < 2e-6f && std::abs(r - b) < 2e-6f,
                    "mask highlights introduced a gray hue");
            previous = r;
            ++cases;
        }
        require(previous >= .75f, "mask turned exposed white dark");
        controls[4] = 0;
        for (float shadows : {-100.f, 100.f}) {
            controls[5] = shadows;
            const Layer2 shadow_layer(masked(controls));
            for (float input : {.5f, .75f, 1.f}) {
                std::array<float, 3> expected{input, input, input}, got = expected;
                exposure_only.apply(expected[0], expected[1], expected[2], .5f, .5f, frame, unquantised);
                shadow_layer.apply(got[0], got[1], got[2], .5f, .5f, frame, unquantised);
                require(expected[0] > 1 && got == expected, "mask shadow control changed already-white light");
                ++cases;
            }
        }
    }
    std::cout << "local bounded weights: " << cases << " parsed integer/float mask cases passed\n";
}
}

int main() {
    try { recovered_channels(); preserved_domain(); bounded_mask_weights(); return 0; }
    catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
