// Co-created with GPT (OpenAI), 2026-11-08. See NOTICE for project terms.
// Compare row scratch against the frozen two-dispatch GPU implementation.
#include "pipeline/blur.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <vector>

using spk::gpu::Arg;
using spk::gpu::BufferRef;

void require(bool ok, const std::string& error) {
    if (!ok) { std::fprintf(stderr, "FIR row regression: %s\n", error.c_str()); std::exit(1); }
}

struct Tables { BufferRef kernel, weight; uint32_t radius; };
Tables tables(spk::gpu::Gpu* gpu, const double sigma[3], const double weight[3]) {
    std::string error;
    spk::Vec kernels[3];
    size_t radii[3];
    for (int c = 0; c < 3; ++c) radii[c] = spk::gaussian_kernel_1d(sigma[c], 3.0, kernels[c]);
    const size_t radius = std::max({radii[0], radii[1], radii[2]});
    std::vector<float> values(3 * (2 * radius + 1), 0);
    float weights[3];
    for (int c = 0; c < 3; ++c) {
        for (size_t k = 0; k < kernels[c].size(); ++k)
            values[size_t(c) * (2 * radius + 1) + radius - radii[c] + k] = float(kernels[c][k]);
        weights[c] = float(weight[c]);
    }
    Tables t{gpu->upload(values.data(), values.size() * sizeof(float), error),
             gpu->upload(weights, sizeof weights, error), uint32_t(radius)};
    require(bool(t.kernel) && bool(t.weight), error);
    return t;
}

std::vector<float> run(spk::gpu::Gpu* gpu, const spk::Image& input, const spk::Image* acc,
                       const Tables& t, uint32_t rows, bool mirror) {
    std::string error;
    const size_t row_bytes = size_t(input.w) * 3 * sizeof(float);
    auto tmp = gpu->alloc_bounded(row_bytes * std::min(rows, input.h), error);
    auto out = gpu->alloc(input.bytes(), error);
    require(bool(tmp) && bool(out), error);
    const bool legacy = rows == input.h;
    for (uint32_t y = 0; y < input.h; y += rows) {
        const uint32_t nrows = std::min(rows, input.h - y);
        for (uint32_t axis = 0; axis < 2; ++axis) {
            const uint32_t meta[8] = {input.h, input.w, t.radius, axis, acc && axis ? 1u : 0u,
                                      mirror ? 1u : 0u, y, nrows};
            const auto& src = axis ? tmp : input.buf;
            const auto& dst = axis ? out : tmp;
            require(gpu->dispatch(legacy ? "spk_sep_fir_acc" : "spk_sep_fir_rows",
                {Arg::buf(src), Arg::buf(t.kernel), Arg::buf(acc ? acc->buf : input.buf),
                 Arg::buf(t.weight), Arg::inline_bytes(meta, legacy ? 6 : 8), Arg::buf(dst)},
                size_t(input.w) * nrows, error), error);
        }
        require(gpu->flush(error), error);
    }
    std::vector<float> output(input.elements());
    require(gpu->read(out.get(), 0, output.data(), output.size() * sizeof(float), error), error);
    return output;
}

size_t compared = 0;
void equal(const std::vector<float>& a, const std::vector<float>& b) {
    require(a.size() == b.size(), "result length changed");
    for (size_t i = 0; i < a.size(); ++i) {
        if (std::memcmp(&a[i], &b[i], sizeof(float))) {
            std::fprintf(stderr, "first different float at %zu: %.9g %.9g\n", i, a[i], b[i]);
            std::exit(1);
        }
    }
    compared += a.size();
}

spk::Image image(spk::gpu::Gpu* gpu, uint32_t w, uint32_t h, uint32_t salt) {
    std::vector<float> data(size_t(w) * h * 3);
    uint32_t state = salt;
    for (float& v : data) {
        state = state * 1664525u + 1013904223u;
        // Signed HDR samples are exact multiples of a power of two.
        v = (int32_t(state >> 8) - 4194304) / 1048576.0f;
    }
    std::string error;
    spk::Image out; out.h = h; out.w = w; out.c = 3;
    out.buf = gpu->upload(data.data(), data.size() * sizeof(float), error);
    require(bool(out.buf), error);
    return out;
}

int main(int argc, char** argv) {
    const bool large = argc == 3 && std::strcmp(argv[2], "--large") == 0;
    if (argc != 2 && !large) { std::fputs("usage: fir_rows SHADER_DIRECTORY [--large]\n", stderr); return 2; }
    std::string error;
    std::unique_ptr<spk::gpu::Gpu> gpu(spk::gpu::Gpu::create_vulkan(argv[1], error));
    require(bool(gpu), error);
    std::fprintf(stderr, "device: %s\n", gpu->device_name().c_str());
    // An idle oversized plane must not defeat the scratch bound.
    {
        auto oversized = gpu->alloc(size_t(16) << 20, error);
        require(bool(oversized), error); oversized.reset();
        for (size_t bytes : {size_t(12), size_t(32), size_t(4096), size_t(65532)}) {
            auto constant = gpu->alloc(bytes, error);
            require(bool(constant) && gpu->size_bytes(constant.get()) <= std::max(bytes * 2, size_t(4096)),
                    "small constant retained an idle image plane");
        }
        auto bounded = gpu->alloc_bounded(size_t(8) << 20, error);
        require(bool(bounded) && gpu->size_bytes(bounded.get()) == (size_t(8) << 20), "bounded pool reused oversized storage");
    }
    for (bool initially_bounded : {false, true}) {
        gpu->trim_pool(0);
        auto block = initially_bounded ? gpu->alloc_bounded(size_t(8) << 20, error)
                                       : gpu->alloc(size_t(8) << 20, error);
        require(bool(block), error);
        auto* scratch = block.get();
        block.reset();
        if (!initially_bounded) {
            auto promoted = gpu->alloc_bounded(size_t(8) << 20, error);
            require(bool(promoted) && promoted.get() == scratch, "bounded request did not reuse equal-sized ordinary storage");
        }
        // This request is above the small-constant policy and would otherwise
        // borrow the 8 MiB block. It independently tests the bounded marker.
        auto smaller = gpu->alloc(size_t(4) << 20, error);
        require(bool(smaller) && smaller.get() != scratch && gpu->size_bytes(smaller.get()) == (size_t(4) << 20),
                "ordinary request retained oversized bounded scratch");
        auto exact = gpu->alloc(size_t(8) << 20, error);
        require(bool(exact) && exact.get() == scratch, "equal-sized ordinary request did not reuse scratch");
    }
    gpu->trim_pool(0);
    size_t cases = 0;
    const double mix[3] = {0.0, -1.25, 1.3};
    const double sigmas[][3] = {{0, 0.5, 2.999}, {0.25, 1.0, 2.0}, {2.999, 2.999, 2.999}};
    for (const auto shape : {std::array<uint32_t, 2>{1, 1}, {1, 17}, {17, 1}, {3, 2}, {47, 31}, {513, 257}}) {
        const auto src = image(gpu.get(), shape[0], shape[1], 123);
        const auto acc = image(gpu.get(), shape[0], shape[1], 987);
        for (const auto& sigma : sigmas) for (bool accumulate : {false, true}) for (bool mirror : {false, true}) {
            const auto t = tables(gpu.get(), sigma, mix);
            const auto expected = run(gpu.get(), src, accumulate ? &acc : nullptr, t, src.h, mirror);
            for (uint32_t rows : {1u, 2u, 7u, 32u, 113u}) {
                equal(expected, run(gpu.get(), src, accumulate ? &acc : nullptr, t, std::min(rows, src.h), mirror));
                ++cases;
            }
        }
    }
    gpu->trim_pool(0);
    std::puts("width,height,accumulate,legacy_live_bytes,row_live_bytes,legacy_ms,row_ms,bytes_equal");
    std::vector<std::array<uint32_t, 2>> shapes{{2049, 1025}, {1025, 2049}, {65537, 33}, {1000003, 2}};
    if (large) shapes.push_back({6001, 4001});
    for (const auto shape : shapes) {
        const auto src = image(gpu.get(), shape[0], shape[1], 123);
        const auto acc = image(gpu.get(), shape[0], shape[1], 987);
        for (bool accumulate : {false, true}) {
            const auto t = tables(gpu.get(), sigmas[0], mix);
            gpu->trim_pool(0);
            const auto before = std::chrono::steady_clock::now();
            const auto observation = gpu->begin_memory_observation();
            const auto expected = run(gpu.get(), src, accumulate ? &acc : nullptr, t, src.h, false);
            const auto legacy_memory = gpu->end_memory_observation(observation);
            const auto middle = std::chrono::steady_clock::now();
            gpu->trim_pool(0);
            const auto candidate_observation = gpu->begin_memory_observation();
            spk::Image output;
            spk::Blur filter(gpu.get());
            require(filter.gaussian(src, sigmas[0], output, error, 3.0, accumulate ? &acc : nullptr, mix), error);
            const auto row_memory = gpu->end_memory_observation(candidate_observation);
            std::vector<float> actual(src.elements());
            require(gpu->read(output.buf.get(), 0, actual.data(), actual.size() * sizeof(float), error), error);
            const auto end = std::chrono::steady_clock::now();
            equal(expected, actual); ++cases;
            require(row_memory.live_peak < legacy_memory.live_peak, "production FIR did not reduce live bytes");
            std::printf("%u,%u,%u,%zu,%zu,%.3f,%.3f,1\n", src.w, src.h, unsigned(accumulate),
                        legacy_memory.live_peak, row_memory.live_peak,
                        std::chrono::duration<double, std::milli>(middle - before).count(),
                        std::chrono::duration<double, std::milli>(end - middle).count());
        }
    }
    std::fprintf(stderr, "FIR rows: %zu cases; %zu float32 values byte-identical; exact scratch and small-request pool limits verified\n", cases, compared);
}
