// Phase-one verification of bounded external-plane execution (patch 0039).
// Synthetic data only. Co-created with Claude (Anthropic), 2026-11-10. See NOTICE.
// Publication and GPU cancellation checks co-created with GPT (OpenAI), 2026-11-11.
#include "gpu/gpu.hpp"
#include "core/blob.hpp"
#include "core/cam16.hpp"
#include "core/colour.hpp"
#include "pipeline/bounded_point_chain.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

using spk::BandOrder;
using spk::ExternalPlane;
using spk::PointChainTables;
using spk::gpu::Arg;
using spk::gpu::Gpu;

namespace {
std::string error;
size_t cases = 0, compared = 0;
constexpr uint64_t kRange = uint64_t(128) << 20;

void require(bool ok, const std::string& what) {
    if (!ok) throw std::runtime_error(what.empty() ? error : what);
}

size_t status_kib(const char* key) {
    std::ifstream f("/proc/self/status");
    std::string line;
    const size_t n = std::strlen(key);
    while (std::getline(f, line))
        if (line.compare(0, n, key) == 0) return std::stoul(line.substr(n + 1));
    return 0;
}

uint32_t cctf_mode(const std::string& cs) {
    if (cs == "sRGB" || cs == "Display P3") return 0;
    if (cs == "ProPhoto RGB") return 1;
    if (cs == "Adobe RGB (1998)") return 2;
    if (cs == "ITU-R BT.709" || cs == "ITU-R BT.2020") return 3;
    return 4;
}

spk::gpu::BufferRef upload_double(Gpu* gpu, const double* d, size_t n) {
    auto r = gpu->upload_persistent_f32(d, n, error);
    require(bool(r), error);
    return r;
}

PointChainTables make_tables(Gpu* gpu, const spk::Colour& colour, const std::string& space,
                             size_t knee_index, bool lightness) {
    PointChainTables t;
    spk::Cam16Setup s;
    require(spk::cam16_setup_for(colour, space, s, error), error);
    double x[9], r[9], conv[9], out[9];
    s.m_to_xyz.to_row_major(x);
    s.m_to_rgb.to_row_major(r);
    spk::Mat3 m, o;
    const double d50[] = {.3457, .3585};
    require(colour.matrix_XYZ_to_RGB(space, d50, "CAT02", m, error), error);
    m.transposed().to_row_major(conv);
    require(colour.matrix_RGB_to_RGB(space, space, "", o, error), error);
    o.transposed().to_row_major(out);
    const double knees[][3] = {{.8, 1, 1.2}, {0, 1, 1}, {.99, 1.01, 4}};
    const auto& kn = knees[knee_index % 3];
    const double k[] = {s.F_L, s.N_bb, s.N_cb, s.n, s.z, s.A_w, s.c, s.N_c,
        s.l_grid.front(), s.l_grid.back(), s.h_grid.front(), s.h_grid[1] - s.h_grid[0],
        kn[0], kn[1], kn[2], lightness ? .9 : 0.0, 1.0, lightness ? 1.2 : 1.0,
        s.white_Jp, s.D_rgb[0], s.D_rgb[1], s.D_rgb[2]};
    t.m2x = upload_double(gpu, x, 9);
    t.m2r = upload_double(gpu, r, 9);
    t.cmax = upload_double(gpu, s.c_max_table.data(), s.c_max_table.size());
    t.k = upload_double(gpu, k, 22);
    t.xyz_to_rgb = upload_double(gpu, conv, 9);
    t.output_matrix = upload_double(gpu, out, 9);
    t.cam16_nl = uint32_t(s.l_grid.size());
    t.cam16_nh = uint32_t(s.h_grid.size());
    t.cam16_lightness = lightness;
    t.cctf_mode = cctf_mode(space);
    return t;
}

void fill(ExternalPlane& p, size_t seed) {
    static const float values[] = {-4, -.1f, -.0001f, -0.0f, 0, 1e-8f, .003f, .18f, .5f, .9f, 1, 1.01f, 2, 4, 16};
    const size_t rowf = size_t(p.width()) * 3;
    std::vector<float> row(rowf);
    for (uint32_t y = 0; y < p.height(); ++y) {
        for (size_t i = 0; i < rowf; ++i) {
            const size_t g = size_t(y) * rowf + i;
            float v = values[(g * 7 + g / 3 + seed) % std::size(values)];
            if (g % 4 == 0) v = std::nextafter(v, std::numeric_limits<float>::infinity());
            row[i] = v;
        }
        p.write_rows(y, 1, row.data());
    }
    p.seal();
}

// Independent whole-frame reference: the three Pipeline nodes written out again.
void whole_reference(Gpu* gpu, const PointChainTables& t, const ExternalPlane& in, ExternalPlane& out) {
    const uint32_t w = in.width(), h = in.height();
    const size_t px = size_t(w) * h, bytes = px * 3 * sizeof(float);
    std::vector<float> src(px * 3);
    in.read_rows(0, h, src.data());
    auto a = gpu->upload(src.data(), bytes, error);
    require(bool(a), error);
    const uint32_t n[1] = {uint32_t(px)};
    auto b = gpu->alloc(bytes, error);
    require(bool(b), error);
    require(gpu->dispatch("spk_matmul3", {Arg::buf(a), Arg::buf(t.xyz_to_rgb), Arg::inline_bytes(n, 1), Arg::buf(b)}, px, error), error);
    require(gpu->flush(error), error);
    auto c = gpu->alloc(bytes, error);
    require(bool(c), error);
    const uint32_t m4[4] = {uint32_t(px), t.cam16_nl, t.cam16_nh, t.cam16_lightness ? 1u : 0u};
    require(gpu->dispatch("spk_cam16ucs_compress", {Arg::buf(b), Arg::buf(t.m2x), Arg::buf(t.m2r), Arg::buf(t.cmax),
                Arg::buf(t.k), Arg::inline_bytes(m4, 4), Arg::buf(c)}, px, error), error);
    require(gpu->flush(error), error);
    auto d = gpu->alloc(bytes, error);
    require(bool(d), error);
    const uint32_t m2[2] = {uint32_t(px), t.cctf_mode};
    require(gpu->dispatch("spk_cctf_encode_matrix", {Arg::buf(c), Arg::buf(t.output_matrix), Arg::inline_bytes(m2, 2), Arg::buf(d)}, px, error), error);
    require(gpu->flush(error), error);
    std::vector<float> dst(px * 3);
    require(gpu->read(d.get(), 0, dst.data(), bytes, error), error);
    out.write_rows(0, h, dst.data());
    out.seal();
}

bool same(const ExternalPlane& a, const ExternalPlane& b, size_t& floats) {
    std::vector<float> x, y;
    floats = 0;
    for (uint32_t r = 0; r < a.height(); r += 64) {
        const uint32_t n = std::min(64u, a.height() - r);
        x.resize(size_t(n) * a.width() * 3);
        y.resize(x.size());
        a.read_rows(r, n, x.data());
        b.read_rows(r, n, y.data());
        if (std::memcmp(x.data(), y.data(), x.size() * sizeof(float)) != 0) return false;
        floats += x.size();
    }
    return true;
}

uint64_t fnv1a(const ExternalPlane& p) {
    uint64_t h = 0xcbf29ce484222325ull;
    std::vector<float> v;
    for (uint32_t r = 0; r < p.height(); r += 64) {
        const uint32_t n = std::min(64u, p.height() - r);
        v.resize(size_t(n) * p.width() * 3);
        p.read_rows(r, n, v.data());
        const auto* bytes = reinterpret_cast<const unsigned char*>(v.data());
        for (size_t i = 0; i < v.size() * sizeof(float); ++i) { h ^= bytes[i]; h *= 0x100000001b3ull; }
    }
    return h;
}

void cancellation_checks(Gpu* gpu, const spk::Colour& colour, const std::string& scratch) {
    constexpr uint32_t w = 17, h = 23;
    const auto tables = make_tables(gpu, colour, "sRGB", 2, true);
    ExternalPlane input(scratch, w, h, 3), reference(scratch, w, h, 3);
    fill(input, 81);
    whole_reference(gpu, tables, input, reference);
    std::vector<float> src(size_t(w) * h * 3), dst(src.size()), expected(src.size());
    input.read_rows(0, h, src.data());
    reference.read_rows(0, h, expected.data());
    unsigned checkpoints = 0;
    require(spk::point_chain_band(gpu, tables, src.data(), w, h, dst.data(), error,
        [&] { ++checkpoints; return false; }), error);
    require(checkpoints >= 8, "point cancellation checkpoints missing");
    for (unsigned stop = 1; stop <= checkpoints; ++stop) {
        unsigned visited = 0;
        std::string message;
        require(!spk::point_chain_band(gpu, tables, src.data(), w, h, dst.data(), message,
            [&] { return ++visited == stop; }) && message == "render cancelled", "point GPU cancel ignored");
        require(spk::point_chain_band(gpu, tables, src.data(), w, h, dst.data(), error), error);
        require(std::memcmp(dst.data(), expected.data(), dst.size() * sizeof(float)) == 0,
            "point GPU recovery differs after cancellation");
    }
    unsigned total = 0;
    ExternalPlane control(scratch, w, h, 3);
    require(spk::run_point_chain(gpu, tables, input, control, 3, BandOrder::Forward, 1, nullptr, error,
        [&] { ++total; return false; }), error);
    for (unsigned stop : {1u, total / 2, total}) {
        ExternalPlane output(scratch, w, h, 3), recovery(scratch, w, h, 3);
        unsigned visited = 0;
        spk::PointChainStats stats{99, 99, 99};
        std::string message;
        require(!spk::run_point_chain(gpu, tables, input, output, 3, BandOrder::Forward, 1, &stats, message,
            [&] { return ++visited == stop; }) && message == "render cancelled", "point run cancel ignored");
        require(!output.ready() && stats.bands == 0 && input.ready(), "point run published partial output");
        require(spk::run_point_chain(gpu, tables, input, recovery, 3, BandOrder::Shuffled, 4, nullptr, error), error);
        size_t floats = 0;
        require(same(recovery, reference, floats), "point run recovery differs after cancellation");
    }
    std::cout << "POINT_CANCEL_PASS gpu_boundaries=" << checkpoints << " run_boundaries=3 recovery_bytes_equal=true\n";
}

void band_rows_checks() {
    size_t n = 0;
    for (uint32_t w : {1u, 7u, 4097u, 65500u}) for (uint64_t budget : {uint64_t(1) << 20, uint64_t(3) << 20, uint64_t(96) << 20, uint64_t(1) << 40})
        for (uint64_t range : {uint64_t(1) << 20, kRange}) for (uint32_t req : {0u, 1u, 33u}) {
            const bool fits = uint64_t(w) * 12 <= range && 3 * uint64_t(w) * 12 <= budget;
            try {
                const uint32_t rows = spk::choose_band_rows(3073, w, range, budget, req);
                require(fits && rows >= 1 && rows <= 3073, "band rows out of range");
                require(uint64_t(rows) * w * 12 <= range, "band exceeds storage range");
                require(3 * uint64_t(rows) * w * 12 <= budget, "band exceeds byte budget");
                if (req) require(rows <= req, "band exceeds requested rows");
            } catch (const std::invalid_argument&) {
                require(!fits, "valid one-row budget was rejected");
            }
            ++n;
        }
    std::cout << "band_rows_checks=" << n << "\n";
}

void matrix(Gpu* gpu, const spk::Colour& colour, const std::string& scratch) {
    const char* spaces[] = {"sRGB", "Display P3", "ProPhoto RGB", "Adobe RGB (1998)", "ITU-R BT.709", "ITU-R BT.2020"};
    const std::array<std::array<uint32_t, 2>, 5> sizes{{{1, 1}, {5, 3}, {67, 49}, {255, 131}, {512, 300}}};
    size_t ci = 0;
    for (const char* space : spaces) {
        const auto t = make_tables(gpu, colour, space, ci % 3, ci % 2 == 1);
        ++ci;
        for (auto [w, h] : sizes) {
            ExternalPlane in(scratch, w, h, 3), ref(scratch, w, h, 3);
            fill(in, ci * 13 + w);
            whole_reference(gpu, t, in, ref);
            for (uint32_t rows : {1u, 3u, 32u, 65u, h}) for (BandOrder order : {BandOrder::Forward, BandOrder::Reverse, BandOrder::Shuffled})
                for (unsigned workers : {1u, 4u}) {
                    ExternalPlane out(scratch, w, h, 3);
                    spk::PointChainStats st;
                    require(spk::run_point_chain(gpu, t, in, out, rows, order, workers, &st, error), error);
                    size_t floats = 0;
                    require(same(out, ref, floats), std::string("bands differ from whole-frame bytes: ") + space + " " + std::to_string(w) + "x" + std::to_string(h) + " rows=" + std::to_string(rows));
                    require(st.bands == (h + rows - 1) / rows, "band count");
                    ++cases;
                    compared += floats;
                }
        }
        std::cout << "space=" << space << " passed\n";
    }
}

void range_case(Gpu* gpu, const spk::Colour& colour, const std::string& scratch) {
    constexpr uint32_t w = 4097, h = 3073;
    const auto t = make_tables(gpu, colour, "sRGB", 2, true);
    ExternalPlane in(scratch, w, h, 3);
    fill(in, 5);
    {
        ExternalPlane tmp(scratch, w, h, 3);
        std::string message;
        bool failed = false;
        try { whole_reference(gpu, t, in, tmp); } catch (const std::exception& e) { message = e.what(); failed = true; }
        require(failed && message.find("maxStorageBufferRange") != std::string::npos,
                "expected a maxStorageBufferRange failure for the whole plane, got: " + message);
        std::cout << "whole_plane_rejected=" << message << "\n";
    }
    const uint32_t rows_a = spk::choose_band_rows(h, w, kRange, uint64_t(96) << 20, 0);
    ExternalPlane a(scratch, w, h, 3), b(scratch, w, h, 3);
    spk::PointChainStats sa, sb;
    require(spk::run_point_chain(gpu, t, in, a, rows_a, BandOrder::Shuffled, 4, &sa, error), error);
    require(spk::run_point_chain(gpu, t, in, b, 17, BandOrder::Reverse, 1, &sb, error), error);
    require(sa.max_band_bytes <= kRange, "band exceeds storage range");
    size_t floats = 0;
    require(same(a, b, floats), "large plane differs between band heights");
    constexpr uint32_t k = 37;
    for (uint32_t y0 : {0u, h / 2 - 18, h - k}) {
        ExternalPlane win(scratch, w, k, 3), ref(scratch, w, k, 3);
        std::vector<float> rows(size_t(k) * w * 3), got(rows.size()), want(rows.size());
        in.read_rows(y0, k, rows.data());
        win.write_rows(0, k, rows.data());
        win.seal();
        whole_reference(gpu, t, win, ref);
        a.read_rows(y0, k, got.data());
        ref.read_rows(0, k, want.data());
        require(std::memcmp(got.data(), want.data(), got.size() * sizeof(float)) == 0, "large plane window differs from whole-frame dispatch");
    }
    std::cout << "large_plane bands_a=" << sa.bands << " rows_a=" << rows_a << " max_band_bytes=" << sa.max_band_bytes
              << " bands_b=" << sb.bands << " bytes_equal=true windows=3\n";
    ++cases;
}

void memory(Gpu* gpu, const spk::Colour& colour, const std::string& scratch, bool whole) {
    constexpr uint32_t w = 4097, h = 3073;
    const auto t = make_tables(gpu, colour, "sRGB", 2, true);
    ExternalPlane in(scratch, w, h, 3), out(scratch, w, h, 3);
    fill(in, 5);
    {
        std::ofstream reset("/proc/self/clear_refs");
        reset << "5\n";
        reset.close();
        require(bool(reset), "cannot reset VmHWM");
    }
    const size_t start_rss = status_kib("VmRSS");
    const uint64_t token = gpu->begin_memory_observation();
    uint32_t rows = h;
    if (whole) {
        std::vector<float> src(size_t(w) * h * 3), dst(src.size());
        in.read_rows(0, h, src.data());
        require(spk::point_chain_band(gpu, t, src.data(), w, h, dst.data(), error), error);
        out.write_rows(0, h, dst.data());
        out.seal();
    } else {
        rows = spk::choose_band_rows(h, w, kRange, uint64_t(48) << 20, 0);
        require(spk::run_point_chain(gpu, t, in, out, rows, BandOrder::Forward, 1, nullptr, error), error);
    }
    const auto obs = gpu->end_memory_observation(token);
    const size_t peak = status_kib("VmHWM");
    std::printf("BOUNDED_MEMORY mode=%s band_rows=%u start_rss_kib=%zu stage_peak_rss_kib=%zu gpu_live_peak=%zu gpu_resident_peak=%zu fnv1a=%016llx\n",
                whole ? "whole" : "bands", rows, start_rss, peak, obs.live_peak, obs.resident_peak, (unsigned long long)fnv1a(out));
}
}  // namespace

int main(int argc, char** argv) {
    try {
        require(argc >= 4, "usage: bounded_point_chain SHADER_DIR RESOURCES_DIR SCRATCH_DIR [--memory-whole|--memory-bands]");
        const std::string scratch = argv[3];
        std::filesystem::create_directories(scratch);
        std::unique_ptr<Gpu> gpu(Gpu::create_vulkan(argv[1], error));
        require(bool(gpu), error);
        std::string math;
        require(gpu->check_math_mode(math), math);
        const char* strict = std::getenv("SPEKTRAFILM_VULKAN_STRICT_LIMITS");
        const bool is_strict = strict && *strict == '1';
        std::cout << "backend=" << gpu->device_name() << " strict_limits=" << is_strict << "\n";
        spk::Blob blob;
        spk::Colour colour;
        require(blob.open(std::string(argv[2]) + "/spektrafilm_constants.bin", error) && colour.init(blob, error), error);
        if (argc >= 5) {
            const std::string mode = argv[4];
            require(mode == "--memory-whole" || mode == "--memory-bands", "unknown memory mode");
            memory(gpu.get(), colour, scratch, mode == "--memory-whole");
            return 0;
        }
        band_rows_checks();
        cancellation_checks(gpu.get(), colour, scratch);
        matrix(gpu.get(), colour, scratch);
        if (is_strict) range_case(gpu.get(), colour, scratch);
        std::cout << "BOUNDED_POINT_CHAIN_PASS cases=" << cases << " float32_values=" << compared
                  << " strict_range_case=" << is_strict << " workers=1,4 orders=forward,reverse,shuffled\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << "FAIL: " << e.what() << "\n";
        return 1;
    }
}
