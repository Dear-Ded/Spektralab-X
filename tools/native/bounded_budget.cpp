// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Synthetic host-payload admission and warm-pool GPU regressions.
// Co-created with GPT (OpenAI), 2026-11-11.
#include "pipeline/bounded_print_run.hpp"
#include <cstring>
#include <filesystem>
#include <iostream>
#include <memory>

namespace {
unsigned checks = 0;
std::string error;
void check(bool ok, const std::string& message) {
    ++checks;
    if (!ok) throw std::runtime_error(message);
}
template<class F> void rejects(F&& f, const char* message) {
    bool rejected = false;
    try { f(); } catch (const std::exception&) { rejected = true; }
    check(rejected, message);
}

#ifndef SPK_BUDGET_BEFORE
void host_checks(const std::string& scratch) {
    using namespace spk;
    constexpr uint32_t w = 5, h = 17, rows = 3, halo = 2;
    const uint64_t fixed = ((h + rows - 1) / rows) * sizeof(StripSpan) + 2 * h;
    const uint64_t per = uint64_t(w) * (rows + 2 * halo + rows) * 12;
    const auto p = plan_external_bands(w, h, rows, halo, 4, fixed + 2 * per);
    check(p.workers == 2 && p.host_payload_bytes == fixed + 2 * per, "host workers were not budgeted");
    check(p.input_floats == 105 && p.output_floats == 45 && p.bands == 6, "payload geometry differs");
    rejects([&] { plan_external_bands(w, h, rows, halo, 4, fixed + per - 1); }, "undersized host budget admitted");
    rejects([&] { plan_external_bands(0, h, rows, halo, 1, UINT64_MAX); }, "zero width admitted");
    rejects([&] { plan_external_bands(UINT32_MAX, 2, 2, 0, 1, UINT64_MAX); }, "kernel pixel overflow admitted");
    constexpr uint32_t too_wide = UINT32_MAX / 3 + 1;
    rejects([&] { plan_external_bands(too_wide, 1, 1, 0, 1, UINT64_MAX); }, "RGB element overflow admitted");
    rejects([&] { choose_band_rows(1, too_wide, UINT64_MAX, UINT64_MAX, 1); }, "point chooser admitted RGB overflow");
    rejects([&] { choose_window_rows(1, too_wide, 0, UINT64_MAX, UINT64_MAX, 1); }, "print chooser admitted RGB overflow");
    float pixel[3]{};
    PointChainTables empty_point;
    PrintRunTables empty_print;
    check(!point_chain_band(nullptr, empty_point, pixel, too_wide, 1, pixel, error), "point kernel admitted RGB overflow");
    check(!print_run_window(nullptr, empty_print, pixel, too_wide, 1, 0, 1, pixel, error), "print kernel admitted RGB overflow");
    const auto big_halo = plan_external_bands(w, h, rows, UINT32_MAX, 1, UINT64_MAX);
    check(big_halo.input_floats == size_t(w) * h * 3, "halo addition overflow");
    const auto big_rows = plan_external_bands(w, h, UINT32_MAX, 0, 0, UINT64_MAX);
    check(big_rows.bands == 1 && big_rows.workers == 1, "large band or zero worker normalization");
    std::vector<float> source(size_t(w) * h * 3), actual(source.size());
    for (size_t i = 0; i < source.size(); ++i) source[i] = float(i) / 17;
    ExternalPlane input(scratch, w, h, 3);
    input.write_rows(0, h, source.data()); input.seal();
    for (auto order : {BandOrder::Forward, BandOrder::Reverse, BandOrder::Shuffled}) {
        ExternalPlane output(scratch, w, h, 3);
        PointChainStats stats;
        auto copy = [](const float* in, uint32_t width, uint32_t, uint32_t offset,
                       uint32_t count, float* out, std::string&) {
            std::memcpy(out, in + size_t(offset) * width * 3, size_t(count) * width * 12);
            return true;
        };
        check(!run_external_bands(input, output, rows, halo, order, 4, &stats, error, {}, copy,
                                  fixed + per - 1), "execution bypassed host admission");
        check(!output.ready() && !stats.bands && !stats.host_payload_bytes, "rejected job published stats/output");
        check(run_external_bands(input, output, rows, halo, order, 4, &stats, error, {}, copy,
                                 fixed + 2 * per), error);
        output.read_rows(0, h, actual.data());
        check(std::memcmp(source.data(), actual.data(), source.size() * 4) == 0, "budgeted halo rows changed");
        check(stats.worker_slots == 2 && stats.host_payload_bytes == fixed + 2 * per, "execution ledger differs");
    }
    std::cout << "host_payload_admission=pass budget=" << fixed + 2 * per << " workers=2/4\n";
}
#endif

void gpu_checks(spk::gpu::Gpu* gpu) {
    using namespace spk;
    constexpr uint32_t w = 17, h = 23;
    const size_t count = size_t(w) * h * 3, bytes = count * sizeof(float);
    std::vector<float> source(count), expected(count), actual(count);
    for (size_t i = 0; i < count; ++i) source[i] = float(int(i % 97) - 5) / 83;
    PrintRunTables t;
    const float identity[] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    t.point.xyz_to_rgb = gpu->upload_persistent(identity, sizeof identity, error);
    check(bool(t.point.xyz_to_rgb), error);
    t.point.gamut_compress = false; t.point.cctf = false;
    for (bool sharpen : {false, true}) {
        t.unsharp_sigma = sharpen ? 0.7 : 0.0;
        t.unsharp_amount = sharpen ? 0.7 : 0.0;
        bool carried = false;
        t.halo = print_run_halo(t.unsharp_sigma, t.unsharp_amount, carried);
        // Independent production FIR + linear-combination sequence.
        {
            Image img, blurred, sharp;
            img.w = w; img.h = h; img.c = 3;
            img.buf = gpu->upload(source.data(), bytes, error);
            check(bool(img.buf), error);
            if (sharpen) {
                Blur blur(gpu);
                const double sigma[] = {.7, .7, .7}, a[] = {1.7, 1.7, 1.7}, b[] = {-.7, -.7, -.7};
                check(blur.gaussian(img, sigma, blurred, error), error);
                check(blur.lincomb(img, blurred, a, b, sharp, error), error);
                check(gpu->flush(error), error);
                check(gpu->read(sharp.buf.get(), 0, expected.data(), bytes, error), error);
            } else expected = source;
        }
        check(gpu->flush(error), error);
        gpu->trim_pool(0);
        // Deliberately leave only large free buffers for a small window.
        {
            std::vector<gpu::BufferRef> warm;
            for (int i = 0; i < 6; ++i) warm.push_back(gpu->alloc(size_t(1) << 20, error));
            for (const auto& buffer : warm) check(bool(buffer), error);
        }
        const auto before = gpu->pool_stats().audit;
        check(print_run_window(gpu, t, source.data(), w, h, 0, h, actual.data(), error), error);
        const auto after = gpu->pool_stats().audit;
        check(std::memcmp(expected.data(), actual.data(), bytes) == 0, "bounded FIR changed float bytes");
        check(after.reuse_bytes_taken - before.reuse_bytes_taken ==
              after.reuse_bytes_requested - before.reuse_bytes_requested, "small window borrowed oversized pool buffers");
        std::cout << "warm_pool_exact=pass sharpen=" << sharpen << " window_bytes=" << bytes << '\n';
    }
}
}

int main(int argc, char** argv) {
    try {
        check(argc == 3, "usage: bounded_budget SHADER_DIR SCRATCH_DIR");
        std::filesystem::create_directories(argv[2]);
#ifndef SPK_BUDGET_BEFORE
        host_checks(argv[2]);
#endif
        std::unique_ptr<spk::gpu::Gpu> gpu(spk::gpu::Gpu::create_vulkan(argv[1], error));
        check(bool(gpu), error);
        std::cout << "device=" << gpu->device_name() << '\n';
        gpu_checks(gpu.get());
        std::cout << "BOUNDED_BUDGET_PASS checks=" << checks << '\n';
    } catch (const std::exception& e) { std::cerr << "FAIL: " << e.what() << '\n'; return 1; }
}
