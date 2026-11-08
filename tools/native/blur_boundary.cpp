// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// A diagnostic of the actual GPU blur, not a compatibility pass/fail oracle.
#include "pipeline/blur.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <vector>

std::vector<float> blur(spk::gpu::Gpu* gpu, const std::vector<float>& signal, double sigma) {
    spk::Image input, output;
    input.h = 1; input.w = uint32_t(signal.size()); input.c = 3;
    std::vector<float> rgb(signal.size() * 3);
    for (size_t i = 0; i < signal.size(); ++i) std::fill_n(rgb.data() + i * 3, 3, signal[i]);
    std::string error;
    input.buf = gpu->upload(rgb.data(), rgb.size() * sizeof(float), error);
    const double sigmas[3] = {sigma, sigma, sigma};
    spk::Blur filter(gpu);
    if (!input.buf || !filter.gaussian(input, sigmas, output, error) ||
        !gpu->read(output.buf.get(), 0, rgb.data(), rgb.size() * sizeof(float), error)) {
        std::fprintf(stderr, "%s\n", error.c_str()); std::exit(1);
    }
    std::vector<float> result(signal.size());
    for (size_t i = 0; i < signal.size(); ++i) {
        result[i] = rgb[3 * i];
        if (!std::isfinite(result[i])) { std::fputs("nonfinite blur output\n", stderr); std::exit(1); }
    }
    return result;
}

void kernel_study(spk::gpu::Gpu* gpu) {
    std::puts("sigma,method,samples,mass,centroid_offset,effective_sigma,l1_to_gaussian,max_abs_to_gaussian");
    for (double sigma : {0.5, 1.0, 2.999, 3.0, 6.0, 24.0, 96.0}) {
        const size_t radius = size_t(std::ceil(32 * sigma)) + 16, n = radius * 2 + 1;
        std::vector<float> impulse(n, 0); impulse[radius] = 1;
        const auto actual = blur(gpu, impulse, sigma);
        std::vector<double> gaussian(n);
        double normalizer = 0;
        for (size_t i = 0; i < n; ++i) {
            const double x = (double(i) - double(radius)) / sigma;
            gaussian[i] = std::exp(-0.5 * x * x); normalizer += gaussian[i];
        }
        double mass = 0, first = 0, second = 0, l1 = 0, max_error = 0;
        for (size_t i = 0; i < n; ++i) {
            const double x = double(i) - double(radius);
            const double delta = std::abs(actual[i] - gaussian[i] / normalizer);
            mass += actual[i]; first += x * actual[i]; second += x * x * actual[i];
            l1 += delta; max_error = std::max(max_error, delta);
        }
        const double centroid = first / mass;
        const double width = std::sqrt(std::max(0.0, second / mass - centroid * centroid));
        std::printf("%.6g,%s,%zu,%.12g,%.12g,%.12g,%.12g,%.12g\n", sigma,
                    spk::Blur::is_fir(sigma) ? "FIR" : "IIR", n, mass, centroid, width, l1, max_error);
    }
}

int main(int argc, char** argv) {
    if (argc < 2 || argc > 3 || (argc == 3 && std::strcmp(argv[2], "--kernel"))) {
        std::fputs("usage: blur_boundary VULKAN_SHADER_DIRECTORY [--kernel]\n", stderr); return 2;
    }
    std::string error;
    std::unique_ptr<spk::gpu::Gpu> gpu(spk::gpu::Gpu::create_vulkan(argv[1], error));
    if (!gpu) { std::fprintf(stderr, "%s\n", error.c_str()); return 1; }
    std::fprintf(stderr, "device: %s\n", gpu->device_name().c_str());
    if (argc == 3) { kernel_study(gpu.get()); return 0; }
    std::puts("sigma,signal,padded_crop_max_abs,reversal_max_abs,interior_padded_max_abs,interior_samples,finite");
    constexpr size_t n = 513;
    for (double sigma : {1.0, 2.999, 3.0, 6.0, 24.0, 96.0}) {
        for (int pattern = 0; pattern < 5; ++pattern) {
            const char* names[] = {"constant", "ramp", "right_step", "right_impulse", "centre_impulse"};
            std::vector<float> input(n);
            for (size_t i = 0; i < n; ++i) {
                if (pattern == 0) input[i] = 0.5f;
                if (pattern == 1) input[i] = float(i) / float(n - 1);
                if (pattern == 2) input[i] = i >= n - 8 ? 1.0f : 0.0f;
                if (pattern == 3) input[i] = i == n - 1 ? 1.0f : 0.0f;
                if (pattern == 4) input[i] = i == n / 2 ? 1.0f : 0.0f;
            }
            const auto actual = blur(gpu.get(), input, sigma);
            auto flipped = input; std::reverse(flipped.begin(), flipped.end());
            const auto reverse = blur(gpu.get(), flipped, sigma);
            // Constant input extension isolates terminal-state handling, using
            // the same kernel and coefficients with distant boundaries.
            const size_t pad = size_t(std::ceil(16 * sigma)) + 16;
            std::vector<float> padded(n + 2 * pad, input.back());
            std::fill_n(padded.begin(), pad, input.front());
            std::copy(input.begin(), input.end(), padded.begin() + pad);
            const auto extended = blur(gpu.get(), padded, sigma);
            double crop_error = 0, reversal_error = 0, interior_error = 0;
            size_t interior_samples = 0;
            const size_t margin = size_t(std::ceil(4 * sigma));
            for (size_t i = 0; i < n; ++i) {
                const double delta = std::abs(double(actual[i]) - extended[pad + i]);
                crop_error = std::max(crop_error, delta);
                reversal_error = std::max(reversal_error, std::abs(double(actual[i]) - reverse[n - 1 - i]));
                if (i >= margin && i + margin < n) {
                    interior_error = std::max(interior_error, delta);
                    ++interior_samples;
                }
            }
            if (!interior_samples) interior_error = std::nan("");
            std::printf("%.6g,%s,%.12g,%.12g,%.12g,%zu,1\n", sigma, names[pattern], crop_error,
                        reversal_error, interior_error, interior_samples);
        }
    }
}
