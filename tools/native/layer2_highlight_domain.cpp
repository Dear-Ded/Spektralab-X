// Co-created with GPT (OpenAI), 2026-11-10. See NOTICE.
// Synthetic monotonicity diagnostic for HDR values reaching SDR tone weights.
#include "layer2.hpp"
#include "layer2_local.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <string_view>
#include <vector>

int main(int argc, char** argv) {
    using namespace spk::android;
    const bool require_monotonic = argc == 2 && std::string_view(argv[1]) == "--require-monotonic";
    unsigned reversals = 0, negative_highlights = 0, parity_failures = 0;
    unsigned legacy_failures = 0, sdr_zero_ev_failures = 0, hdr_shadow_failures = 0;
    unsigned signed_highlight_failures = 0, channel_ratio_failures = 0;
    std::cout << std::setprecision(10);
    for (float ev : {0.0f, 3.0f}) {
        std::vector<float> values(kLookSize, 0.0f);
        values[kExposure] = ev;
        values[kHighlights] = -100.0f;
        Layer2 global(values);
        const float params[10] = {ev, 0, 0, 0, -100, 0, 0, 0, 0, 0};
        local::Tone local_tone(params);
        float previous = -1.0f;
        for (double linear : {0.0, 0.0625, 0.125, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0}) {
            const float encoded = float(linear < 1.0 / 512 ? 16 * linear : std::pow(linear, 1.0 / 1.8));
            std::array<float, 3> c{encoded, encoded, encoded}, masked = c;
            global.apply(c[0], c[1], c[2], .5f, .5f, {}, true);
            local_tone.apply(masked.data(), true);
            float legacy = ev == 0 ? encoded : std::max(0.0f, encoded) * std::pow(2.0f, ev / 2.2f);
            const float legacy_luma = .2126f * legacy + .7152f * legacy + .0722f * legacy;
            const float position = legacy_luma <= .3857f ? .5f * legacy_luma / .3857f
                : .5f + .5f * (legacy_luma - .3857f) / (1.0f - .3857f);
            legacy += -.25f * (position * position) * legacy;
            std::array<float, 3> old{encoded, encoded, encoded}, old_mask = old;
            global.apply(old[0], old[1], old[2], .5f, .5f, {}, false);
            local_tone.apply(old_mask.data(), false);
            if (old[0] != legacy || old_mask[0] != legacy) ++legacy_failures;
            if (ev == 0 && linear <= 1 && c[0] != legacy) ++sdr_zero_ev_failures;
            const float packed = std::clamp(c[0], 0.0f, 1.0f);
            if (packed + 1e-6 < previous) ++reversals;
            if (c[0] < 0.0f) ++negative_highlights;
            if (std::abs(c[0] - masked[0]) > 1e-5f) ++parity_failures;
            previous = packed;
            std::cout << "ev=" << ev << " highlights=-100 linear_in=" << linear
                      << " encoded_out=" << c[0] << " after_pack_clamp=" << packed << '\n';
        }
    }
    for (float shadows : {-100.0f, 100.0f}) {
        std::vector<float> values(kLookSize, 0.0f);
        values[kShadows] = shadows;
        Layer2 global(values);
        const float params[10] = {0, 0, 0, 0, 0, shadows, 0, 0, 0, 0};
        local::Tone local_tone(params);
        for (float encoded : {1.0f, 2.0f, 4.0f, 8.0f}) {
            std::array<float, 3> c{encoded, encoded, encoded}, masked = c;
            global.apply(c[0], c[1], c[2], .5f, .5f, {}, true);
            local_tone.apply(masked.data(), true);
            if (c[0] != encoded || masked[0] != encoded) ++hdr_shadow_failures;
        }
    }
    {
        std::vector<float> values(kLookSize, 0.0f);
        values[kHighlights] = -100;
        Layer2 global(values);
        const float params[10] = {0, 0, 0, 0, -100, 0, 0, 0, 0, 0};
        local::Tone local_tone(params);
        for (const auto& input : {std::array<float, 3>{-.16f, -.032f, -.016f},
                                  std::array<float, 3>{-.016f, .2f, 4.0f},
                                  std::array<float, 3>{-.016f, 1.5f, 4.0f}}) {
            auto c = input, masked = input;
            global.apply(c[0], c[1], c[2], .5f, .5f, {}, true);
            local_tone.apply(masked.data(), true);
            const float gain = c[0] / input[0];
            for (size_t i = 0; i < c.size(); ++i) {
                if (c[i] * input[i] <= 0) ++signed_highlight_failures;
                if (std::abs(c[i] / input[i] - gain) > 2e-6f) ++channel_ratio_failures;
                if (std::abs(c[i] - masked[i]) > 2e-6f) ++parity_failures;
            }
            if (input[1] < 0 && c != input) ++signed_highlight_failures;
        }
    }
    std::cout << "ramp_reversals=" << reversals << " negative_highlights=" << negative_highlights
              << " global_local_parity_failures=" << parity_failures << " legacy_failures=" << legacy_failures
              << " sdr_zero_ev_failures=" << sdr_zero_ev_failures << " hdr_shadow_failures=" << hdr_shadow_failures
              << " signed_highlight_failures=" << signed_highlight_failures << " channel_ratio_failures=" << channel_ratio_failures << '\n';
    return parity_failures || legacy_failures || sdr_zero_ev_failures ||
        (require_monotonic && (reversals || negative_highlights || hdr_shadow_failures ||
                              signed_highlight_failures || channel_ratio_failures)) ? 1 : 0;
}
