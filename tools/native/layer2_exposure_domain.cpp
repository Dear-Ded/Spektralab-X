// Co-created with GPT (OpenAI), 2026-11-10. See NOTICE.
// Synthetic diagnostic of the EV label against the encoded-ProPhoto contract.
// Build: c++ -std=c++20 -O2 -pthread -Iapp/src/main/cpp \
//   tools/native/layer2_exposure_domain.cpp app/src/main/cpp/layer2.cpp -o probe
// The default characterizes current behavior. --require-linear makes violations
// of a linear-light exposure invariant fail, independently of upstream parity.
#include "layer2.hpp"
#include "layer2_local.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <string_view>
#include <vector>

namespace {
double encode(double value) {
    return value < 1.0 / 512.0 ? value * 16.0 : std::pow(value, 1.0 / 1.8);
}
double decode(double value) {
    return value < 16.0 / 512.0 ? value / 16.0 : std::pow(value, 1.8);
}
}

int main(int argc, char** argv) {
    using namespace spk::android;
    const bool require_linear = argc == 2 && std::string_view(argv[1]) == "--require-linear";
    unsigned violations = 0, legacy_failures = 0, parity_failures = 0, neutral_failures = 0;
    unsigned ratio_failures = 0, roundtrip_failures = 0;
    unsigned sweep_failures = 0, sweep_cases = 0;
    std::cout << std::setprecision(10);
    for (bool unquantised : {false, true}) {
        for (float ev : {-2.0f, 0.0f, 2.0f}) {
            std::vector<float> values(kLookSize, 0.0f);
            values[kExposure] = ev;
            Layer2 global(values);
            const float parameters[10] = {ev, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            local::Tone mask(parameters);
            for (double linear : {-4.0, -0.001, 0.0, 0.0001, 0.001, 1.0 / 512.0, 0.002, 0.01, 0.18, 1.0, 4.0}) {
                const float encoded = float(encode(linear));
                std::array<float, 3> output{encoded, encoded, encoded};
                std::array<float, 3> local_output = output;
                global.apply(output[0], output[1], output[2], .5f, .5f, {}, unquantised);
                mask.apply(local_output.data(), unquantised);
                const double measured = decode(output[0]);
                const double expected = linear * std::exp2(double(ev));
                const double tolerance = 2e-7 * std::max(1.0, std::abs(expected));
                const bool correct = std::abs(measured - expected) <= tolerance;
                if (unquantised && !correct) ++violations;
                const float legacy = ev == 0 ? encoded
                    : std::max(0.0f, encoded) * std::pow(2.0f, ev / 2.2f);
                if (!unquantised && output[0] != legacy) ++legacy_failures;
                if (std::abs(output[0] - local_output[0]) > 2e-6) ++parity_failures;
                if (ev == 0 && output[0] != encoded) ++neutral_failures;
                std::cout << "float=" << unquantised << " ev=" << ev << " linear_in=" << linear
                          << " linear_out=" << measured << " expected=" << expected
                          << " effective_ev=" << (linear > 0 ? std::log2(measured / linear) : 0)
                          << " correct=" << correct << '\n';
            }
        }
    }
    for (float ev : {-2.0f, 2.0f}) {
        const std::array<double, 3> linear{0.0001, 0.001, 0.01};
        std::array<float, 3> output;
        std::transform(linear.begin(), linear.end(), output.begin(), [](double v) { return float(encode(v)); });
        std::vector<float> values(kLookSize, 0.0f);
        values[kExposure] = ev;
        Layer2(values).apply(output[0], output[1], output[2], .5f, .5f, {}, true);
        const double g_over_r = decode(output[1]) / decode(output[0]);
        const double b_over_g = decode(output[2]) / decode(output[1]);
        if (std::abs(g_over_r - 10.0) > 2e-5 || std::abs(b_over_g - 10.0) > 2e-5) ++ratio_failures;
        std::cout << "mixed_toe ev=" << ev << " input_g_over_r=" << linear[1] / linear[0]
                  << " output_g_over_r=" << g_over_r
                  << " input_b_over_g=" << linear[2] / linear[1]
                  << " output_b_over_g=" << b_over_g << '\n';
        values[kExposure] = -ev;
        Layer2(values).apply(output[0], output[1], output[2], .5f, .5f, {}, true);
        for (size_t i = 0; i < 3; ++i)
            if (std::abs(decode(output[i]) - linear[i]) > 1e-8) ++roundtrip_failures;
    }
    // A dense signed/HDR ramp straddles both toe boundaries over the complete
    // user EV range. The oracle always decodes and encodes independently.
    for (int half_ev = -16; half_ev <= 16; ++half_ev) {
        const float ev = float(half_ev) / 2;
        std::vector<float> values(kLookSize, 0);
        values[kExposure] = ev;
        const Layer2 global(values);
        const float parameters[10] = {ev, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        const local::Tone mask(parameters);
        for (int i = -4096; i <= 4096; ++i) {
            const float encoded = std::copysign(float(std::exp2(std::abs(i) / 256.0 - 12.0)), float(i));
            std::array<float, 3> c{encoded, encoded, encoded}, masked = c;
            global.apply(c[0], c[1], c[2], .5f, .5f, {}, true);
            mask.apply(masked.data(), true);
            const double expected = encode(decode(encoded) * std::exp2(double(ev)));
            if (std::abs(double(c[0]) - expected) > 2e-7 * std::max(1.0, std::abs(expected)) ||
                c != masked) ++sweep_failures;
            ++sweep_cases;
        }
    }
    std::cout << "linear_exposure_violations=" << violations << " legacy_failures=" << legacy_failures << " global_local_parity_failures="
              << parity_failures << " neutral_failures=" << neutral_failures << " ratio_failures=" << ratio_failures
              << " roundtrip_failures=" << roundtrip_failures << " dense_sweep_cases=" << sweep_cases
              << " dense_sweep_failures=" << sweep_failures << '\n';
    return parity_failures || neutral_failures || legacy_failures ||
        (require_linear && (violations || ratio_failures || roundtrip_failures || sweep_failures)) ? 1 : 0;
}
