// Phase-two verification of halo-band external-plane execution (patch 0040).
// Synthetic data only. Co-created with Claude (Anthropic), 2026-11-10. See NOTICE.
#include "gpu/gpu.hpp"
#include "core/blob.hpp"
#include "core/cam16.hpp"
#include "core/colour.hpp"
#include "pipeline/bounded_print_run.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <iostream>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

using namespace spk;

namespace {
std::string error;
size_t cases = 0, compared = 0;
constexpr uint64_t kRange = uint64_t(128) << 20;

void require(bool ok, const std::string& what) {
    if (!ok) throw std::runtime_error(what.empty() ? error : what);
}

uint32_t cctf_mode(const std::string& cs) {
    if (cs == "sRGB" || cs == "Display P3") return 0;
    if (cs == "ProPhoto RGB") return 1;
    if (cs == "Adobe RGB (1998)") return 2;
    if (cs == "ITU-R BT.709" || cs == "ITU-R BT.2020") return 3;
    return 4;
}

gpu::BufferRef up(gpu::Gpu* g, const double* d, size_t n) {
    auto r = g->upload_persistent_f32(d, n, error);
    require(bool(r), error);
    return r;
}

PrintRunTables make_tables(gpu::Gpu* g, const Colour& colour, const std::string& space,
                           size_t knee_index, bool lightness, double sigma, double amount) {
    PrintRunTables t;
    PointChainTables& p = t.point;
    Cam16Setup s;
    require(cam16_setup_for(colour, space, s, error), error);
    double x[9], r[9], conv[9], out[9];
    s.m_to_xyz.to_row_major(x);
    s.m_to_rgb.to_row_major(r);
    Mat3 m, o;
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
    p.m2x = up(g, x, 9);
    p.m2r = up(g, r, 9);
    p.cmax = up(g, s.c_max_table.data(), s.c_max_table.size());
    p.k = up(g, k, 22);
    p.xyz_to_rgb = up(g, conv, 9);
    p.output_matrix = up(g, out, 9);
    p.cam16_nl = uint32_t(s.l_grid.size());
    p.cam16_nh = uint32_t(s.h_grid.size());
    p.cam16_lightness = lightness;
    p.cctf_mode = cctf_mode(space);
    t.unsharp_sigma = sigma;
    t.unsharp_amount = amount;
    bool carried = false;
    t.halo = print_run_halo(sigma, amount, carried);
    require(!carried, "unexpected carried demand");
    return t;
}

void fill(ExternalPlane& p, size_t seed) {
    static const float values[] = {-4, -.1f, -.0001f, -0.0f, 0, 1e-8f, .003f, .18f, .5f, .9f, 1, 1.01f, 2, 4, 16};
    const size_t rowf = size_t(p.width()) * 3;
    std::vector<float> row(rowf);
    for (uint32_t y = 0; y < p.height(); ++y) {
        for (size_t i = 0; i < rowf; ++i) {
            const size_t g = size_t(y) * rowf + i;
            float v = values[(g * 7 + g / 3 + g / 211 + seed) % std::size(values)];
            if (g % 4 == 0) v = std::nextafter(v, std::numeric_limits<float>::infinity());
            row[i] = v;
        }
        p.write_rows(y, 1, row.data());
    }
}

// Independent whole-frame reference: the Pipeline nodes written out again.
void whole_reference(gpu::Gpu* g, const PrintRunTables& t, const ExternalPlane& in, ExternalPlane& out) {
    const uint32_t w = in.width(), h = in.height();
    const size_t px = size_t(w) * h, bytes = px * 3 * sizeof(float);
    const PointChainTables& p = t.point;
    std::vector<float> src(px * 3), dst(px * 3);
    in.read_rows(0, h, src.data());
    auto a = g->upload(src.data(), bytes, error);
    require(bool(a), error);
    const uint32_t n[1] = {uint32_t(px)};
    auto b = g->alloc(bytes, error);
    require(bool(b), error);
    require(g->dispatch("spk_matmul3", {gpu::Arg::buf(a), gpu::Arg::buf(p.xyz_to_rgb), gpu::Arg::inline_bytes(n, 1), gpu::Arg::buf(b)}, px, error), error);
    require(g->flush(error), error);
    auto c = g->alloc(bytes, error);
    require(bool(c), error);
    const uint32_t m4[4] = {uint32_t(px), p.cam16_nl, p.cam16_nh, p.cam16_lightness ? 1u : 0u};
    require(g->dispatch("spk_cam16ucs_compress", {gpu::Arg::buf(b), gpu::Arg::buf(p.m2x), gpu::Arg::buf(p.m2r), gpu::Arg::buf(p.cmax), gpu::Arg::buf(p.k), gpu::Arg::inline_bytes(m4, 4), gpu::Arg::buf(c)}, px, error), error);
    require(g->flush(error), error);
    if (t.unsharp_sigma > 0.0 && t.unsharp_amount > 0.0) {
        Blur blur(g);
        Image cur, blurred, sharp;
        cur.h = h; cur.w = w; cur.c = 3; cur.buf = std::move(c);
        const double s[3] = {t.unsharp_sigma, t.unsharp_sigma, t.unsharp_sigma};
        const double ka[3] = {1.0 + t.unsharp_amount, 1.0 + t.unsharp_amount, 1.0 + t.unsharp_amount};
        const double kb[3] = {-t.unsharp_amount, -t.unsharp_amount, -t.unsharp_amount};
        require(blur.gaussian(cur, s, blurred, error), error);
        require(blur.lincomb(cur, blurred, ka, kb, sharp, error), error);
        c = std::move(sharp.buf);
        require(g->flush(error), error);
    }
    auto d = g->alloc(bytes, error);
    require(bool(d), error);
    const uint32_t m2[2] = {uint32_t(px), p.cctf_mode};
    require(g->dispatch("spk_cctf_encode_matrix", {gpu::Arg::buf(c), gpu::Arg::buf(p.output_matrix), gpu::Arg::inline_bytes(m2, 2), gpu::Arg::buf(d)}, px, error), error);
    require(g->flush(error), error);
    require(g->read(d.get(), 0, dst.data(), bytes, error), error);
    out.write_rows(0, h, dst.data());
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

void halo_checks() {
    bool carried = true;
    require(print_run_halo(0.0, 0.7, carried) == 0 && !carried, "zero sigma halo");
    require(print_run_halo(0.7, 0.0, carried) == 0 && !carried, "zero amount halo");
    require(print_run_halo(0.7, 0.7, carried) == 2 && !carried, "sigma 0.7 halo");
    require(print_run_halo(1.5, 0.7, carried) == 5 && !carried, "sigma 1.5 halo");
    require(print_run_halo(3.5, 0.7, carried) == 0 && carried, "sigma 3.5 must be carried");
    size_t n = 0;
    for (uint32_t w : {1u, 7u, 4097u, 65500u}) for (uint32_t halo : {0u, 2u, 5u})
        for (uint64_t budget : {uint64_t(1) << 20, uint64_t(5) << 20, uint64_t(96) << 20, uint64_t(1) << 40})
            for (uint64_t range : {uint64_t(1) << 20, kRange}) for (uint32_t req : {0u, 1u, 33u}) {
                const uint64_t minimum = (1ull + 2ull * halo) * w * 12;
                const bool fits = minimum <= range && 5 * minimum <= budget;
                try {
                    const uint32_t rows = choose_window_rows(3073, w, halo, range, budget, req);
                    require(fits && rows >= 1 && rows <= 3073, "window rows out of range");
                    require((uint64_t(rows) + 2ull * halo) * w * 12 <= range, "window exceeds storage range");
                    require(5 * (uint64_t(rows) + 2ull * halo) * w * 12 <= budget, "window exceeds byte budget");
                    if (req) require(rows <= req, "rows exceed request");
                } catch (const std::invalid_argument&) {
                    require(!fits, "valid one-row halo budget was rejected");
                }
                ++n;
            }
    std::cout << "halo_checks=" << n << std::endl;
}

void matrix(gpu::Gpu* g, const Colour& colour, const std::string& scratch) {
    const char* spaces[] = {"sRGB", "Display P3", "ProPhoto RGB"};
    const std::array<std::array<uint32_t, 2>, 5> sizes{{{1, 1}, {5, 3}, {67, 49}, {255, 131}, {64, 300}}};
    const std::array<std::array<double, 2>, 5> configs{{{0.7, 0.7}, {0.3, 1.2}, {1.5, 0.7}, {0.7, 0.0}, {0.0, 0.7}}};
    size_t ci = 0;
    for (const char* space : spaces) {
        for (auto [sigma, amount] : configs) {
            const auto t = make_tables(g, colour, space, ci % 3, ci % 2 == 1, sigma, amount);
            ++ci;
            for (auto [w, h] : sizes) {
                ExternalPlane in(scratch, w, h, 3), ref(scratch, w, h, 3);
                fill(in, ci * 13 + w);
                whole_reference(g, t, in, ref);
                for (uint32_t rows : {1u, 3u, 32u, 65u, h}) for (BandOrder order : {BandOrder::Forward, BandOrder::Reverse, BandOrder::Shuffled})
                    for (unsigned workers : {1u, 4u}) {
                        ExternalPlane out(scratch, w, h, 3);
                        PointChainStats st;
                        require(run_print_run(g, t, in, out, rows, order, workers, &st, error), error);
                        size_t floats = 0;
                        require(same(out, ref, floats), std::string("halo bands differ from whole-frame bytes: ") + space + " sigma=" + std::to_string(sigma) + " amount=" + std::to_string(amount) + " " + std::to_string(w) + "x" + std::to_string(h) + " rows=" + std::to_string(rows));
                        require(st.bands == (h + rows - 1) / rows, "band count");
                        ++cases;
                        compared += floats;
                    }
            }
        }
        std::cout << "space=" << space << " passed" << std::endl;
    }
}

void range_case(gpu::Gpu* g, const Colour& colour, const std::string& scratch) {
    constexpr uint32_t w = 4097, h = 3073;
    const auto t = make_tables(g, colour, "sRGB", 2, true, 0.7, 0.7);
    ExternalPlane in(scratch, w, h, 3);
    fill(in, 5);
    {
        std::vector<float> src(size_t(w) * h * 3), dst(src.size());
        in.read_rows(0, h, src.data());
        std::string message;
        const bool ok = print_run_window(g, t, src.data(), w, h, 0, h, dst.data(), message);
        require(!ok && message.find("maxStorageBufferRange") != std::string::npos,
                "expected a maxStorageBufferRange failure for the whole plane, got: " + message);
        std::cout << "whole_plane_rejected=" << message << std::endl;
    }
    const uint32_t rows_a = choose_window_rows(h, w, t.halo, kRange, uint64_t(96) << 20, 0);
    ExternalPlane a(scratch, w, h, 3), b(scratch, w, h, 3);
    PointChainStats sa, sb;
    require(run_print_run(g, t, in, a, rows_a, BandOrder::Shuffled, 4, &sa, error), error);
    require(run_print_run(g, t, in, b, 97, BandOrder::Reverse, 1, &sb, error), error);
    require(sa.max_band_bytes <= kRange, "window exceeds storage range");
    size_t floats = 0;
    require(same(a, b, floats), "large plane differs between band heights");
    std::cout << "large_plane bands_a=" << sa.bands << " rows_a=" << rows_a << " halo=" << t.halo
              << " max_window_bytes=" << sa.max_band_bytes << " bands_b=" << sb.bands << " bytes_equal=true" << std::endl;
    ++cases;
}
}  // namespace

int main(int argc, char** argv) {
    try {
        require(argc >= 4, "usage: bounded_print_run SHADER_DIR RESOURCES_DIR SCRATCH_DIR");
        const std::string scratch = argv[3];
        std::filesystem::create_directories(scratch);
        std::unique_ptr<gpu::Gpu> g(gpu::Gpu::create_vulkan(argv[1], error));
        require(bool(g), error);
        std::string math;
        require(g->check_math_mode(math), math);
        const char* strict = std::getenv("SPEKTRAFILM_VULKAN_STRICT_LIMITS");
        const bool is_strict = strict && *strict == '1';
        std::cout << "backend=" << g->device_name() << " strict_limits=" << is_strict << std::endl;
        Blob blob;
        Colour colour;
        require(blob.open(std::string(argv[2]) + "/spektrafilm_constants.bin", error) && colour.init(blob, error), error);
        halo_checks();
        matrix(g.get(), colour, scratch);
        if (is_strict) range_case(g.get(), colour, scratch);
        std::cout << "BOUNDED_PRINT_RUN_PASS cases=" << cases << " float32_values=" << compared
                  << " strict_range_case=" << is_strict << " workers=1,4 orders=forward,reverse,shuffled" << std::endl;
        return 0;
    } catch (const std::exception& e) {
        std::cerr << "FAIL: " << e.what() << std::endl;
        return 1;
    }
}
