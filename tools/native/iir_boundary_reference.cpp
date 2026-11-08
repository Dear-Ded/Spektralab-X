// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Research only: constant-extension terminal states for the upstream YvV
// coefficients. This does not replace the production GPU/Metal recurrence.
#include "numeric.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using State = std::array<double, 3>;

double step(double x, State& s, const double b[4]) {
    const double y = b[0] * x + b[1] * s[0] + b[2] * s[1] + b[3] * s[2];
    s = {y, s[0], s[1]};
    return y;
}

// The first row of Triggs/Sdika's M, with the input gain distributed into
// each sweep, followed by M*A, M*A^2, M*A^3 for states beyond the last pixel.
// See their equations (3), (10), (14). Long double here diagnoses the
// boundary model; a GPU port needs its own conditioning/precision validation.
State terminal(const State& last, double endpoint, const double b[4]) {
    const long double a1 = b[1], a2 = b[2], a3 = b[3];
    const long double dc = 1 - a1 - a2 - a3;
    const long double gain = b[0];
    const long double scale = gain / ((1 + a1 - a2 + a3) * dc * (1 + a2 + (a1 - a3) * a3));
    std::array<long double, 3> row{
        (1 - a2 - a3 * a1 - a3 * a3) * scale,
        (a3 + a1) * (a2 + a3 * a1) * scale,
        a3 * (a1 + a3 * a2) * scale};
    const long double forward_dc = endpoint * gain / dc;
    const long double reverse_dc = forward_dc * gain / dc;
    State result{};
    for (int i = 0; i < 3; ++i) {
        row = {row[0] * a1 + row[1], row[0] * a2 + row[2], row[0] * a3};
        long double value = reverse_dc;
        for (int j = 0; j < 3; ++j) value += row[j] * (last[j] - forward_dc);
        result[i] = double(value);
    }
    return result;
}

std::vector<double> filter(const std::vector<double>& input, const double b[4], bool corrected, size_t chunk) {
    std::vector<double> out(input.size());
    State state{input.front(), input.front(), input.front()};
    for (size_t start = 0; start < input.size(); start += chunk)
        for (size_t i = start; i < std::min(input.size(), start + chunk); ++i) out[i] = step(input[i], state, b);
    state = corrected ? terminal(state, input.back(), b) : State{out.back(), out.back(), out.back()};
    for (size_t end = input.size(); end;) {
        const size_t start = end > chunk ? end - chunk : 0;
        while (end > start) { --end; out[end] = step(out[end], state, b); }
    }
    return out;
}

// Independent tail oracle: explicitly continue the forward recurrence far
// beyond the frame, then reverse it. No matrix or terminal() is used here.
std::vector<double> extended(const std::vector<double>& input, const double b[4], size_t pad) {
    std::vector<long double> values(input.size() + pad);
    std::array<long double, 3> state{input.front(), input.front(), input.front()};
    const auto sweep = [&](long double x) {
        const long double y = b[0] * x + b[1] * state[0] + b[2] * state[1] + b[3] * state[2];
        state = {y, state[0], state[1]};
        return y;
    };
    for (size_t i = 0; i < values.size(); ++i) values[i] = sweep(i < input.size() ? input[i] : input.back());
    const long double dc = 1.0L - b[1] - b[2] - b[3];
    const long double settled = input.back() * (b[0] / dc) * (b[0] / dc);
    state = {settled, settled, settled};
    for (size_t i = values.size(); i-- > 0;) values[i] = sweep(values[i]);
    return {values.begin(), values.begin() + input.size()};
}

double difference(const std::vector<double>& a, const std::vector<double>& b) {
    double error = 0;
    for (size_t i = 0; i < a.size(); ++i) {
        if (!std::isfinite(a[i]) || !std::isfinite(b[i])) std::abort();
        error = std::max(error, std::abs(a[i] - b[i]));
    }
    return error;
}

int main() {
    std::puts("length,sigma,signal,legacy_tail_max_abs,candidate_tail_max_abs,candidate_reversal_max_abs,chunk_bytes_equal");
    size_t cases = 0;
    double worst_tail = 0, worst_reversal = 0;
    for (size_t n : {1u, 2u, 3u, 17u, 513u})
        for (double sigma : {3.0, 6.0, 24.0, 96.0})
            for (int pattern = 0; pattern < 6; ++pattern) {
                const char* names[] = {"constant", "ramp", "right_step", "right_impulse", "centre_impulse", "hdr_alternating"};
                std::vector<double> input(n);
                for (size_t i = 0; i < n; ++i) {
                    if (pattern == 0) input[i] = 0.5;
                    if (pattern == 1) input[i] = double(i) / std::max(size_t(1), n - 1);
                    if (pattern == 2) input[i] = i >= n - std::min(n, size_t(8)) ? 1.0 : 0.0;
                    if (pattern == 3) input[i] = i == n - 1 ? 1.0 : 0.0;
                    if (pattern == 4) input[i] = i == n / 2 ? 1.0 : 0.0;
                    if (pattern == 5) input[i] = (i % 2) ? 4.0 : -0.25;
                }
                double b[4]; spk::yvv_coeffs(sigma, b);
                const auto legacy = filter(input, b, false, n);
                const auto candidate = filter(input, b, true, n);
                const auto oracle = extended(input, b, size_t(std::ceil(64 * sigma)) + 64);
                const auto longer = extended(input, b, size_t(std::ceil(128 * sigma)) + 128);
                const double oracle_delta = difference(oracle, longer);
                auto reversed = input; std::reverse(reversed.begin(), reversed.end());
                auto mirrored = filter(reversed, b, true, n); std::reverse(mirrored.begin(), mirrored.end());
                const double tail = difference(candidate, oracle), reversal = difference(candidate, mirrored);
                worst_tail = std::max(worst_tail, tail); worst_reversal = std::max(worst_reversal, reversal);
                for (size_t chunk : {1u, 2u, 7u, 32u, 257u}) {
                    const auto split = filter(input, b, true, chunk);
                    if (std::memcmp(candidate.data(), split.data(), n * sizeof(double))) std::abort();
                }
                // This is a CPU double model gate, not the GPU's output tolerance.
                if (oracle_delta > 1e-9 || tail > 2e-8 || reversal > 2e-8) {
                    std::fprintf(stderr, "boundary candidate failed: n=%zu sigma=%g pattern=%s tail=%.12g reversal=%.12g oracle_delta=%.12g\n",
                                 n, sigma, names[pattern], tail, reversal, oracle_delta);
                    return 1;
                }
                std::printf("%zu,%g,%s,%.12g,%.12g,%.12g,1\n", n, sigma, names[pattern], difference(legacy, oracle), tail, reversal);
                ++cases;
            }
    std::fprintf(stderr, "IIR CPU candidate: %zu cases; worst tail %.12g; worst reversal %.12g; five chunk sizes byte-identical\n",
                 cases, worst_tail, worst_reversal);
}
