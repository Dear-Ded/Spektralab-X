// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Co-created with GPT (OpenAI), 2026-11-12.
// CPU parameter-domain gate. Compile with -fno-access-control; no GPU or images.
#include "core/json.hpp"
#include "core/params.hpp"
#include "pipeline/pipeline.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using spk::Json;
using Components = std::vector<spk::Blur::Component>;
constexpr std::array<const char*, 2> kStocks{"kodak_portra_400", "cinestill_800t"};
constexpr std::array<double, 3> kFormats{4, 35, 36};
constexpr std::array<uint32_t, 8> kEdges{1600, 6000, 7902, 7903, 8000, 8127, 8128, 8192};
constexpr std::array<double, 2> kAmounts{1, 2};
constexpr double kLensProbeUm = 20.0;
constexpr size_t kPlannedCases = kStocks.size() * kFormats.size() * kAmounts.size()
                               * (kEdges.size() + 2);

bool near(double a, double b) {
    return std::isfinite(a) && std::isfinite(b)
        && std::abs(a - b) <= 2e-12 * std::max({1.0, std::abs(a), std::abs(b)});
}

struct Checks {
    Json entries = Json::array();
    size_t count = 0;
    bool passed = true;
    void add(const std::string& name, bool ok) {
        Json item = Json::object();
        item.set("name", Json(name));
        item.set("passed", Json(ok));
        entries.push(std::move(item));
        ++count;
        passed = passed && ok;
    }
};

double maximum(const Components& comps) {
    double value = 0.0;
    for (const auto& comp : comps)
        for (double sigma : comp.sigma) value = std::max(value, sigma);
    return value;
}

bool finite(const Components& comps) {
    for (const auto& comp : comps)
        for (int c = 0; c < 3; ++c)
            if (!std::isfinite(comp.sigma[c]) || !std::isfinite(comp.weight[c])) return false;
    return true;
}

bool scaled(const Components& a, const Components& b, double ratio) {
    if (a.size() != b.size()) return false;
    for (size_t k = 0; k < a.size(); ++k)
        for (int c = 0; c < 3; ++c)
            if (!near(a[k].sigma[c], b[k].sigma[c] * ratio)
                || !near(a[k].weight[c], b[k].weight[c])) return false;
    return true;
}

Json components_json(const Components& comps) {
    Json out = Json::array();
    for (const auto& comp : comps) {
        Json item = Json::object(), sigma = Json::array(), weight = Json::array();
        for (int c = 0; c < 3; ++c) {
            sigma.push(Json(comp.sigma[c]));
            weight.push(Json(comp.weight[c]));
        }
        item.set("sigma", std::move(sigma));
        item.set("weight", std::move(weight));
        out.push(std::move(item));
    }
    return out;
}

struct Measurement {
    double pitch = 0, ratio = 0, lens = 0, lens_probe = 0, unsharp = 0, glare = 0;
    Components scatter, bounces, dir;
};

Measurement measure(spk::Pipeline& pipeline, uint32_t L, uint32_t F) {
    // Shape metadata only. These calls do not allocate a pixel buffer or bake setup.
    pipeline.set_source_shape(L, std::max(1u, L * 2 / 3), F, std::max(1u, F * 2 / 3));
    Measurement out;
    out.pitch = pipeline.pixel_size_um();
    out.ratio = pipeline.tier_ratio();
    const auto hal = pipeline.halation_blurs();
    out.scatter = hal.scatter;
    out.bounces = hal.bounces;
    out.dir = pipeline.dir_coupler_diffusion();
    out.lens = pipeline.lens_blur_sigma();
    out.unsharp = pipeline.unsharp_sigma();
    // There is no glare sigma accessor. This is node_glare's expression,
    // using its real parameters and the actual tier_ratio(), without dispatch.
    const auto& glare = pipeline.params_.print_render.glare;
    out.glare = glare.blur * out.ratio * std::sqrt(std::max(glare.amount, 0.0));
    const double saved = pipeline.params_.camera.lens_blur_um;
    pipeline.params_.camera.lens_blur_um = kLensProbeUm;
    out.lens_probe = pipeline.lens_blur_sigma();
    pipeline.params_.camera.lens_blur_um = saved;
    return out;
}

struct Result {
    std::string stock;
    double M = 0, amount = 0, max_bounce = 0;
    uint32_t L = 0, F = 0;
    bool back_reflection = false, domain_ok = false;
};

Json run_case(spk::Pipeline& pipeline, const std::string& stock, double M,
              double amount, uint32_t L, uint32_t F, Result& result, Checks& total) {
    pipeline.params_.camera.film_format_mm = M;
    pipeline.params_.film_render.halation.halation_amount = amount;
    const auto same_source = measure(pipeline, L, L);
    const auto full = measure(pipeline, F, F);
    const auto value = measure(pipeline, L, F);
    const double ratio = double(L) / double(F);
    const auto& params = pipeline.params_;
    Checks checks;
    checks.add("pixel_pitch_M_times_1000_over_L", near(value.pitch, M * 1000.0 / L));
    checks.add("source_over_full_ratio", near(value.ratio, ratio));
    checks.add("finite_components", finite(value.scatter) && finite(value.bounces) && finite(value.dir));
    checks.add("lens_uses_current_pitch", near(value.lens, params.camera.lens_blur_um / value.pitch));
    checks.add("nonzero_lens_probe_uses_current_pitch", near(value.lens_probe, kLensProbeUm / value.pitch));
    checks.add("unsharp_uses_L_over_F", near(value.unsharp, params.scanner.unsharp_mask[0] * ratio));
    checks.add("glare_uses_L_over_F", near(value.glare, same_source.glare * ratio));
    checks.add("F_does_not_change_physical_sigmas",
               scaled(value.scatter, same_source.scatter, 1.0)
               && scaled(value.bounces, same_source.bounces, 1.0)
               && scaled(value.dir, same_source.dir, 1.0)
               && near(value.lens_probe, same_source.lens_probe));
    checks.add("current_vs_full_physical_sigma_ratio",
               scaled(value.scatter, full.scatter, ratio)
               && scaled(value.bounces, full.bounces, ratio)
               && scaled(value.dir, full.dir, ratio)
               && near(value.lens_probe, full.lens_probe * ratio));
    checks.add("unsharp_current_vs_full", near(value.unsharp, full.unsharp * ratio));
    checks.add("glare_current_vs_full", near(value.glare, full.glare * ratio));

    const double max_bounce = maximum(value.bounces);
    const double max_sigma = std::max({max_bounce, maximum(value.scatter), maximum(value.dir),
                                       value.lens, value.unsharp, value.glare});
    // This is the corrected IIR's sigma contract, not an attempted GPU run.
    const bool domain_ok = finite(value.scatter) && finite(value.bounces) && finite(value.dir)
        && std::isfinite(value.lens) && std::isfinite(value.unsharp) && std::isfinite(value.glare)
        && max_sigma <= 256.0;
    Json out = Json::object();
    out.set("stock", Json(stock));
    out.set("M", Json(M)); out.set("L", Json(double(L))); out.set("F", Json(double(F)));
    out.set("amount", Json(amount));
    out.set("actual_back_reflection", Json(params.film_render.halation.back_reflection));
    out.set("pixel_size_um", Json(value.pitch)); out.set("source_full_ratio", Json(value.ratio));
    out.set("max_bounce_sigma", Json(max_bounce)); out.set("above256", Json(max_bounce > 256.0));
    out.set("max_scatter_sigma", Json(maximum(value.scatter)));
    out.set("max_dir_sigma", Json(maximum(value.dir)));
    out.set("any_sigma_above256", Json(max_sigma > 256.0));
    out.set("accepts_0052_sigma_domain", Json(domain_ok));
    out.set("bounce_uses_iir", Json(spk::Blur::demand(value.bounces).carried));
    out.set("scatter_uses_iir", Json(spk::Blur::demand(value.scatter).carried));
    out.set("dir_uses_iir", Json(spk::Blur::demand(value.dir).carried));
    out.set("lensSigma", Json(value.lens)); out.set("unsharpSigma", Json(value.unsharp));
    out.set("glareSigma", Json(value.glare)); out.set("lens_probe_um", Json(kLensProbeUm));
    out.set("lensProbeSigma", Json(value.lens_probe));
    out.set("scatter", components_json(value.scatter));
    out.set("bounces", components_json(value.bounces));
    out.set("dir", components_json(value.dir));
    out.set("passed", Json(checks.passed)); out.set("checks", std::move(checks.entries));
    total.count += checks.count; total.passed = total.passed && checks.passed;
    result = {stock, M, amount, max_bounce, L, F,
              params.film_render.halation.back_reflection, domain_ok};
    return out;
}

const Result* find(const std::vector<Result>& results, double M, uint32_t L, uint32_t F, double amount) {
    for (const auto& row : results)
        if (row.stock == "cinestill_800t" && row.M == M && row.L == L
            && row.F == F && row.amount == amount) return &row;
    return nullptr;
}

Json boundary_check(const std::vector<Result>& rows, double M, uint32_t low, uint32_t high,
                    Checks& total) {
    const Result* a = find(rows, M, low, low, 1);
    const Result* b = find(rows, M, high, high, 1);
    const bool applicable = a && b && a->back_reflection && b->back_reflection;
    const bool ok = applicable && a->max_bounce <= 256.0 && b->max_bounce > 256.0
        && a->domain_ok && !b->domain_ok;
    Json out = Json::object();
    out.set("M", Json(M)); out.set("low_L", Json(double(low))); out.set("high_L", Json(double(high)));
    out.set("applicable", Json(applicable)); out.set("passed", Json(ok));
    if (a) out.set("low_max_bounce_sigma", Json(a->max_bounce));
    if (b) out.set("high_max_bounce_sigma", Json(b->max_bounce));
    if (!applicable) out.set("reason", Json(std::string("actual CineStill back-reflection model unavailable")));
    total.add("back_reflection_cutover_" + std::to_string(int(M)) + "mm", ok);
    return out;
}

Json preview_check(const std::vector<Result>& rows, double M, Checks& total) {
    const Result* full = find(rows, M, 6000, 6000, 2);
    const Result* preview = find(rows, M, 1600, 6000, 2);
    const bool applicable = full && preview && full->back_reflection && preview->back_reflection;
    const bool ok = applicable && full->max_bounce > 256.0 && !full->domain_ok
        && preview->max_bounce <= 256.0 && preview->domain_ok;
    Json out = Json::object();
    out.set("M", Json(M)); out.set("amount", Json(2.0));
    out.set("applicable", Json(applicable)); out.set("passed", Json(ok));
    if (full) out.set("full6000_max_bounce_sigma", Json(full->max_bounce));
    if (preview) out.set("preview1600_max_bounce_sigma", Json(preview->max_bounce));
    if (!applicable) out.set("reason", Json(std::string("actual CineStill back-reflection model unavailable")));
    total.add("full6000_rejects_preview1600_accepts_amount2_" + std::to_string(int(M)) + "mm", ok);
    return out;
}
}  // namespace

int main(int argc, char** argv) {
    if (argc != 3) {
        std::cerr << "usage: iir_pipeline_domain RESOURCES OUTPUT_JSON\n";
        return 2;
    }
    Json report = Json::object(), stocks = Json::array(), cases = Json::array(), boundaries = Json::array();
    Checks total;
    std::vector<Result> results;
    report.set("kind", Json(std::string("CPU pipeline parameter-domain check")));
    report.set("gpu_created", Json(false)); report.set("image_pixels_allocated", Json(0.0));
    report.set("init_includes_digest", Json(true));
    report.set("planned_cases", Json(double(kPlannedCases)));
    report.set("amount_semantics", Json(std::string("halation_amount; glare.amount remains initialized value")));
    report.set("sigma_domain", Json(std::string("triggs-v1: IIR sigma in [3,256]; smaller positive sigma uses FIR")));
    report.set("glareSigma_method", Json(std::string("node_glare expression with actual tier_ratio; no node dispatch")));
    try {
        std::ifstream filters_file(std::string(argv[1]) + "/neutral_print_filters.json", std::ios::binary);
        if (!filters_file) throw std::runtime_error("cannot open neutral_print_filters.json");
        const std::string text{std::istreambuf_iterator<char>(filters_file), {}};
        Json filters;
        std::string error;
        if (!Json::parse(text, filters, error)) throw std::runtime_error(error);
        for (const char* stock : kStocks) {
            spk::Params params;
            error.clear();
            const bool loaded = spk::init_params(argv[1], stock, "kodak_portra_endura", filters, params, error);
            Json info = Json::object();
            info.set("stock", Json(std::string(stock))); info.set("initialized_and_digested", Json(loaded));
            total.add(std::string(stock) + "_initialized_and_digested", loaded);
            if (!loaded) {
                info.set("error", Json(error)); stocks.push(std::move(info)); continue;
            }
            const auto& hal = params.film_render.halation;
            const bool expected_back_reflection = std::string(stock) == "cinestill_800t";
            info.set("resolved_stock", Json(params.film_stock));
            info.set("profile_antihalation", Json(params.film.info.antihalation));
            info.set("actual_back_reflection", Json(hal.back_reflection));
            info.set("expected_back_reflection", Json(expected_back_reflection));
            info.set("base_thickness_um", Json(hal.base_thickness_um));
            info.set("halation_spatial_scale", Json(hal.halation_spatial_scale));
            info.set("lens_blur_um", Json(params.camera.lens_blur_um));
            info.set("unsharp_full_tier_sigma", Json(params.scanner.unsharp_mask[0]));
            info.set("glare_full_tier_blur", Json(params.print_render.glare.blur));
            info.set("glare_amount", Json(params.print_render.glare.amount));
            total.add(std::string(stock) + "_actual_back_reflection", hal.back_reflection == expected_back_reflection);
            const bool ordinary_frame = !params.film_render.overscan.active && !params.film_render.date_imprint.active;
            total.add(std::string(stock) + "_ordinary_frame_geometry", ordinary_frame);
            if (!ordinary_frame) {
                info.set("error", Json(std::string("initialized geometry requires overscan/date-specific pitch; not overridden")));
                stocks.push(std::move(info)); continue;
            }
            std::cerr << stock << " actual_back_reflection=" << std::boolalpha << hal.back_reflection
                      << " profile_antihalation=" << params.film.info.antihalation << '\n';
            stocks.push(std::move(info));
            spk::Pipeline pipeline(nullptr, nullptr, nullptr, nullptr);
            pipeline.params_ = params;
            for (double M : kFormats) for (double amount : kAmounts) for (uint32_t L : kEdges) {
                const auto append = [&](uint32_t F) {
                    Result result;
                    cases.push(run_case(pipeline, stock, M, amount, L, F, result, total));
                    results.push_back(std::move(result));
                };
                append(L);
                if (L == 1600) { append(6000); append(8192); }
            }
        }
        boundaries.push(boundary_check(results, 35, 7902, 7903, total));
        boundaries.push(boundary_check(results, 36, 8127, 8128, total));
        boundaries.push(preview_check(results, 35, total));
        boundaries.push(preview_check(results, 36, total));
        total.add("all_planned_cases_completed", results.size() == kPlannedCases);
    } catch (const std::exception& e) {
        report.set("error", Json(std::string(e.what())));
        total.add("completed_without_exception", false);
    }
    report.set("stocks", std::move(stocks)); report.set("cases", std::move(cases));
    report.set("boundary_checks", std::move(boundaries));
    report.set("completed_cases", Json(double(results.size())));
    report.set("assertions", Json(double(total.count))); report.set("passed", Json(total.passed));
    report.set("checks", std::move(total.entries));
    std::ofstream output(argv[2], std::ios::binary);
    if (!output || !(output << report.dump() << '\n')) {
        std::cerr << "cannot write output JSON: " << argv[2] << '\n';
        return 2;
    }
    std::cerr << "parameter_cases=" << results.size() << '/' << kPlannedCases
              << " assertions=" << total.count << " passed=" << std::boolalpha << total.passed << '\n';
    return total.passed ? 0 : 1;
}
