// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors. See NOTICE.
// Co-created with GPT (OpenAI), 2026-11-09.
#include "core/params.hpp"
#include "gpu/gpu.hpp"
#include "gpu_vulkan_read.hpp"
#include "spektrafilm/spk_engine.h"
#include "vulkan_test_hooks.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <limits>
#include <locale>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Plane = std::vector<float>;
using Controls = std::array<float, 7>;
void check(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

Plane table(size_t n, int changed_row = -1, float stops = 0.0f, bool bump = false) {
    Plane result(5 * n);
    for (size_t row = 0; row < 5; ++row) {
        for (size_t i = 0; i < n; ++i) {
            const double x = double(i) / double(n - 1);
            const double e = -8.0 + 16.0 * x;
            const double lift = row == size_t(changed_row)
                ? double(stops) * (bump ? std::exp(-0.5 * e * e) : 1.0) : 0.0;
            result[row * n + i] = float(std::clamp(x + lift / 16.0, 0.0, 1.0));
        }
    }
    return result;
}

std::string csv(const Plane& values) {
    std::ostringstream text;
    text.imbue(std::locale::classic());
    text << std::setprecision(std::numeric_limits<float>::max_digits10);
    for (size_t i = 0; i < values.size(); ++i) {
        if (i) text << ',';
        text << values[i];
    }
    return text.str();
}

void parser_tests() {
    Plane values{123.0f};
    size_t n = 123;
    std::string error;
    check(spk::parse_scene_curve("", values, n, error) && values.empty() && n == 0,
          "empty curve is not a bypass");
    for (size_t count : {size_t(2), size_t(129), size_t(4096)}) {
        const auto source = table(count, 2, 1.0f);
        check(spk::parse_scene_curve(csv(source), values, n, error), error);
        check(n == count && values == source, "CSV float samples did not round-trip");
    }
    const std::string identity = csv(table(2));
    const std::vector<std::string> malformed{
        "0.1,0.2,0.3", "nan,0", "inf,0", "-0.01,0", "1.01,0", "a,b",
        identity + ",", identity + ", ", identity + "tail", identity + std::string("\0tail", 5),
        csv(table(4097)), std::string(1024 * 1024 + 1, '0')};
    for (const auto& text : malformed) {
        values.assign(1, 123.0f);
        n = 123;
        error.clear();
        check(!spk::parse_scene_curve(text, values, n, error) && values.empty() && n == 0 &&
              error.find("scene_curve") != std::string::npos, "malformed curve accepted or stale parse output");
    }
    spk::Json delta;
    std::string param;
    check(spk::Json::parse(R"({"scene_curve":true})", delta, error), error);
    check(!spk::validate_delta(delta, error, param) && param == "scene_curve", "non-string curve accepted");
    check(spk::Json::parse(R"({"exposure_baseline_ev":1.25,"scene_latitude_active":true,"scene_curve":""})", delta, error), error);
    check(spk::validate_delta(delta, error, param), "legacy exposure/placement API was removed: " + error);
    std::cout << "parser PASS valid=4 malformed=12 wrong_type=1 legacy_fields=1\n";
}

// Independent double reference: interpolate output stops between the two
// neighbouring stop knots, then apply the difference as a linear gain.
double reference_gain(double value, const Plane& values, size_t row, const Controls& p) {
    if (!(value > 0.0) || !std::isfinite(value)) return 1.0;
    const size_t n = size_t(p[0]);
    const double half = p[1];
    const double position = std::clamp(std::log2(value / double(p[2])) + double(p[6]), -half, half);
    const double step = 2.0 * half / double(n - 1);
    const double index = (position + half) / step;
    const size_t left = std::min(size_t(index), n - 2);
    const double fraction = index - double(left);
    const double lower_stop = -half + 2.0 * half * double(values[row * n + left]);
    const double upper_stop = -half + 2.0 * half * double(values[row * n + left + 1]);
    const double mapped = (1.0 - fraction) * lower_stop + fraction * upper_stop;
    return std::exp2(std::clamp(mapped - position, -30.0, 30.0));
}

std::array<double, 3> reference_pixel(const float* input, const Plane& values, const Controls& p) {
    std::array<double, 3> c{input[0], input[1], input[2]};
    const double y = p[3] * std::max(c[0], 0.0) + p[4] * std::max(c[1], 0.0) + p[5] * std::max(c[2], 0.0);
    const double luma_gain = reference_gain(y, values, 1, p);
    for (double& v : c) v *= luma_gain;
    for (double& v : c) v *= reference_gain(v, values, 0, p);
    for (size_t channel = 0; channel < 3; ++channel)
        c[channel] *= reference_gain(c[channel], values, channel + 2, p);
    return c;
}

Plane run_kernel(spk::gpu::Gpu* gpu, const Plane& input, const Plane& values, const Controls& p,
                 bool in_place = false) {
    std::string error;
    const uint32_t pixels = uint32_t(input.size() / 3);
    constexpr size_t guards = 51;
    Plane sentinel(input.size() + guards, -123456.0f);
    Plane guarded_input = sentinel;
    std::copy(input.begin(), input.end(), guarded_input.begin());
    auto source = gpu->upload(guarded_input.data(), guarded_input.size() * sizeof(float), error);
    auto curves = gpu->upload(values.data(), values.size() * sizeof(float), error);
    auto output = in_place ? source : gpu->upload(sentinel.data(), sentinel.size() * sizeof(float), error);
    check(source && curves && output, error);
    check(gpu->dispatch("spk_scene_curve",
        {spk::gpu::Arg::buf(source), spk::gpu::Arg::inline_bytes(p.data(), p.size()),
         spk::gpu::Arg::buf(curves), spk::gpu::Arg::inline_bytes(&pixels, 1), spk::gpu::Arg::buf(output)},
        size_t(pixels) + 17, error) && gpu->flush(error), error);
    Plane result;
    check(read_test_buffer(gpu, output, result, error), error);
    for (size_t i = input.size(); i < sentinel.size(); ++i)
        check(result[i] == sentinel[i], "scene curve wrote beyond count");
    result.resize(input.size());
    return result;
}

void kernel_tests(const std::string& resources) {
    std::string error;
    std::unique_ptr<spk::gpu::Gpu> gpu(spk::gpu::Gpu::create_vulkan(resources + "/vulkan", error));
    check(bool(gpu) && gpu->check_math_mode(error), error);
    Plane input;
    for (size_t i = 0; i < 513; ++i) {
        const float value = float(0.184 * std::exp2(-16.0 + 32.0 * double(i) / 512.0));
        input.insert(input.end(), {value, i % 7 == 0 ? -value * .2f : value * .7f, value * 1.3f});
    }
    input.insert(input.end(), {0.0f, -0.0f, 0.0f, -1.0f, -2.0f, -3.0f});
    const size_t pathological_start = input.size();
    for (float v : {std::numeric_limits<float>::infinity(), std::numeric_limits<float>::quiet_NaN()})
        input.insert(input.end(), {v, v, v});
    size_t cases = 0;
    size_t dispatch_modes = 0;
    double maximum = 0.0;
    for (size_t n : {size_t(2), size_t(129), size_t(4096)}) {
        for (int row : {-1, 0, 1, 2, 3, 4, 5}) {
            Plane values = table(n, row == 5 ? 0 : row, 1.0f, row == 5);
            if (row == 5) {
                const auto luma = table(n, 1, -.5f, true);
                std::copy(luma.begin() + n, luma.begin() + 2 * n, values.begin() + n);
                const auto blue = table(n, 4, .75f);
                std::copy(blue.begin() + 4 * n, blue.end(), values.begin() + 4 * n);
            }
            for (float ev : {-2.0f, 0.0f, 1.0f}) {
                const Controls p{float(n), 8.0f, .184f, .2126f, .7152f, .0722f, ev};
                check(spk::gpu::test_limit_dispatch_groups_x(gpu.get(), 0, error), error);
                const auto output = run_kernel(gpu.get(), input, values, p);
                for (size_t i = 0; i < input.size() / 3; ++i) {
                    const auto expected = reference_pixel(input.data() + 3 * i, values, p);
                    for (size_t c = 0; c < 3; ++c) {
                        const double want = expected[c], got = output[3 * i + c];
                        if (std::isnan(want)) { check(std::isnan(got), "NaN signal replaced"); continue; }
                        if (std::isinf(want)) { check(got == want, "infinite signal replaced"); continue; }
                        check(std::isfinite(got), "finite scene signal became nonfinite");
                        const double relative = std::abs(want - got) / std::max(1.0, std::abs(want));
                        maximum = std::max(maximum, relative);
                        check(relative <= 5e-5, "curve differs from double oracle, n=" + std::to_string(n) +
                              " row=" + std::to_string(row) + " ev=" + std::to_string(ev) +
                              " sample=" + std::to_string(3 * i + c) + " relative=" + std::to_string(relative));
                        if (i * 3 >= pathological_start - 6)
                            check(std::bit_cast<uint32_t>(input[3 * i + c]) == std::bit_cast<uint32_t>(output[3 * i + c]),
                                  "nonpositive signal bits changed");
                    }
                }
                // A 517-pixel frame spans three dispatches at cap=1; the
                // 400-pixel substrip spans two. Test both distinct and aliased
                // buffers so a missing base cannot hide behind an identity row.
                const Plane band(input.begin() + 111 * 3, input.begin() + 511 * 3);
                for (uint32_t cap : {0u, 1u}) {
                    check(spk::gpu::test_limit_dispatch_groups_x(gpu.get(), cap, error), error);
                    for (bool in_place : {false, true}) {
                        const std::string mode = " cap=" + std::to_string(cap) +
                                                 " in_place=" + std::to_string(in_place);
                        const auto candidate = cap == 0 && !in_place ? output
                            : run_kernel(gpu.get(), input, values, p, in_place);
                        check(std::memcmp(candidate.data(), output.data(), output.size() * sizeof(float)) == 0,
                              "scene curve changed with dispatch mode" + mode);
                        const auto strip = run_kernel(gpu.get(), band, values, p, in_place);
                        check(std::memcmp(strip.data(), output.data() + 111 * 3, strip.size() * sizeof(float)) == 0,
                              "pointwise curve differs for a strip" + mode);
                        ++dispatch_modes;
                    }
                }
                ++cases;
            }
        }
    }
    check(spk::gpu::test_limit_dispatch_groups_x(gpu.get(), 0, error), error);
    std::cout << "kernel PASS cases=" << cases << " pixels_per_case=" << input.size() / 3
              << " dispatch_modes=" << dispatch_modes << " group_x_caps=0,1 in_place=true"
                 " whole_strip_memcmp=true guard_floats=51 max_relative=" << maximum << '\n';
}

using Engine = std::unique_ptr<spk_engine, decltype(&spk_engine_destroy)>;
using Session = std::unique_ptr<spk_session, decltype(&spk_session_release)>;
std::vector<uint16_t> render(spk_session* session, spk_engine* engine, bool* cached = nullptr, bool fresh = false) {
    spk_result result{};
    const auto status = fresh ? spk_render(session, "full", &result) : spk_reprint(session, "full", &result);
    if (status != SPK_OK) {
        spk_result_free(&result);
        throw std::runtime_error(std::string("render: ") + spk_last_error(engine));
    }
    check(result.rgba16 && result.width && result.height, "render has no pixels");
    std::vector<uint16_t> pixels(result.rgba16, result.rgba16 + size_t(result.width) * result.height * 4);
    if (cached) *cached = result.negative_was_cached != 0;
    spk_result_free(&result);
    return pixels;
}

std::string curve_delta(const std::string& curve) {
    spk::Json delta = spk::Json::object();
    delta.set("scene_curve", spk::Json(curve));
    return delta.dump();
}

std::string get_params(spk_session* session) {
    char* text = nullptr;
    check(spk_get_params(session, &text) == SPK_OK && text, "get_params failed");
    std::string result(text);
    spk_string_free(text);
    return result;
}

void api_tests(const std::string& resources) {
    Engine engine(spk_engine_create(resources.c_str(), nullptr), spk_engine_destroy);
    check(bool(engine), std::string("engine: ") + spk_last_error(nullptr));
    constexpr uint32_t w = 67, h = 31;
    Plane input(size_t(w) * h * 3);
    for (size_t y = 0; y < h; ++y) for (size_t x = 0; x < w; ++x) for (size_t c = 0; c < 3; ++c)
        input[(y * w + x) * 3 + c] = float(.184 * std::exp2(-5.0 + 10.0 * double(x) / (w - 1)) * (1.0 - .15 * c));
    spk_image image{input.data(), w, h, 3};
    const char* quiet = R"({"grain_active":false,"glare_active":false,"halation_active":false,"dir_couplers_active":false,"auto_exposure":false,"input_cctf_decoding":false,"scanner_unsharp_amount":0,"product_defaults":true})";
    Session session(spk_open(engine.get(), &image, quiet, nullptr), spk_session_release);
    check(bool(session), std::string("open: ") + spk_last_error(engine.get()));
    auto set = [&](const std::string& delta) {
        check(spk_set_params(session.get(), delta.c_str(), nullptr) == SPK_OK,
              std::string("set: ") + spk_last_error(engine.get()));
    };
    auto medium = [&]() {
        char* text = nullptr;
        check(spk_scene_latitude(session.get(), "{}", &text) == SPK_OK && text,
              std::string("scene latitude: ") + spk_last_error(engine.get()));
        spk::Json json;
        std::string error;
        const bool ok = spk::Json::parse(text, json, error);
        spk_string_free(text);
        check(ok, error);
        return json.at("medium").dump();
    };
    for (bool di : {false, true}) {
        set(di ? R"({"digital_intermediate":true})" : R"({"digital_intermediate":false})");
        const auto original = render(session.get(), engine.get());
        set(curve_delta(""));
        check(render(session.get(), engine.get()) == original, "empty curve changed old render");
        const auto medium_before = medium();
        set(curve_delta(csv(table(129, 0, 1.0f, true))));
        bool cached = true;
        const auto curved = render(session.get(), engine.get(), &cached);
        check(!cached && curved != original, "curve did not rebuild and change negative");
        check(medium() == medium_before, "curve contaminated the medium reference probe");
        set(R"({"print_exposure":1.05})");
        render(session.get(), engine.get(), &cached);
        check(cached, "PRINT edit discarded curved negative");
        set(R"({"print_exposure":1,"striped":true,"strip_rows":7})");
        check(render(session.get(), engine.get(), nullptr, true) == curved, "whole/strip scene curve render differs");
        set(R"({"striped":false,"scene_curve":""})");
        check(render(session.get(), engine.get()) == original, "clear did not restore old pixels");
    }
    set(R"({"digital_intermediate":false})");
    const std::string before = get_params(session.get());
    check(spk_set_params(session.get(), R"({"print_exposure":1.25,"scene_curve":"0,1,"})", nullptr) == SPK_ERR_USER,
          "invalid curve API request accepted");
    check(get_params(session.get()) == before, "invalid curve partially mutated params");
    const auto original = render(session.get(), engine.get());
    set(R"({"exposure_baseline_ev":1})");
    const auto baseline = render(session.get(), engine.get());
    check(baseline != original, "legacy exposure_baseline_ev no longer affects rendering");
    Plane doubled = input;
    for (float& v : doubled) v *= 2.0f;
    spk_image twice{doubled.data(), w, h, 3};
    Session doubled_session(spk_open(engine.get(), &twice, quiet, nullptr), spk_session_release);
    check(bool(doubled_session), spk_last_error(engine.get()));
    check(render(doubled_session.get(), engine.get()) == baseline, "frozen meter differs from doubled input");
    set(R"({"exposure_baseline_ev":0,"exposure_compensation_ev":1})");
    const auto ev_plain = render(session.get(), engine.get());
    const auto bump = curve_delta(csv(table(129, 0, 1.0f, true)));
    set(bump);
    const auto ev_curved = render(session.get(), engine.get());
    check(spk_set_params(doubled_session.get(), bump.c_str(), nullptr) == SPK_OK,
          "doubled input curve rejected");
    const auto doubled_curved = render(doubled_session.get(), engine.get());
    uint32_t alignment_error = 0, bump_effect = 0;
    for (size_t i = 0; i < ev_curved.size(); ++i) {
        alignment_error = std::max(alignment_error, uint32_t(std::abs(int(ev_curved[i]) - int(doubled_curved[i]))));
        bump_effect = std::max(bump_effect, uint32_t(std::abs(int(ev_curved[i]) - int(ev_plain[i]))));
    }
    // Spectral upsampling is nonlinear; this is the upstream EV alignment tolerance,
    // not an assertion that moving exposure across that stage is byte equivalent.
    check(alignment_error < 131, "local scene-curve bump is not aligned with film exposure EV");
    check(bump_effect > 3276, "local bump test did not visibly exercise the curve");
    std::cout << "api PASS paper_and_DI=2 empty_clear_exact=true shoot_print_cache=true whole_strip=true"
                 " medium_unaffected=true invalid_delta_atomic=true legacy_baseline_exact=true"
              << " ev_alignment_max_code=" << alignment_error << " bump_max_code=" << bump_effect << '\n';
}
}

int main(int argc, char** argv) {
    try {
        check(argc == 2 || argc == 3, "usage: scene_curve RESOURCES [--api]");
        parser_tests();
        if (argc == 3) {
            check(std::strcmp(argv[2], "--api") == 0, "unknown test mode");
            api_tests(argv[1]);
        } else {
            kernel_tests(argv[1]);
        }
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
