// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Co-created with GPT (OpenAI), 2026-11-12.
// Direct single-pass alias gate; the candidate's coefficients are the contract.
#include "gpu/gpu.hpp"
#include "numeric.hpp"
#include "pipeline/iir_boundary.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <locale>
#include <memory>
#include <string>
#include <vector>

namespace {
using spk::gpu::Arg;
using spk::gpu::BufferRef;
using spk::gpu::Gpu;

struct Shape { uint32_t height, width; };
constexpr std::array<Shape, 8> kShapes{{
    {1, 2}, {2, 1}, {1, 3}, {3, 1}, {2, 3}, {3, 2}, {17, 19}, {257, 259}}};
constexpr std::array<double, 5> kSigmas{3, 6, 24, 96, 256};
constexpr std::array<std::array<uint32_t, 3>, 4> kActive{{
    {1, 1, 1}, {1, 0, 1}, {0, 1, 0}, {0, 0, 0}}};
constexpr std::array<float, 3> kWeights{0.0f, -0.5f, 1.25f};
constexpr size_t kPlannedCases = kShapes.size() * kSigmas.size() * 2 * 2 * kActive.size();

struct CaseResult {
    Shape shape{};
    double sigma = 0;
    uint32_t axis = 0, accumulate = 0;
    std::array<uint32_t, 3> active{};
    std::array<float, 3> weights{};
    size_t values = 0, different_values = 0, different_bytes = 0, nonfinite_values = 0;
    size_t first_difference = 0;
    uint32_t separate_bits = 0, alias_bits = 0;
    double max_abs = 0;
    bool readback_complete = false, bytes_equal = false, passed = false;
    std::string error;
};

std::string json_string(const std::string& text) {
    std::string out = "\"";
    const char hex[] = "0123456789abcdef";
    for (unsigned char c : text) {
        if (c == '"' || c == '\\') { out += '\\'; out += char(c); }
        else if (c < 0x20) {
            out += "\\u00";
            out += hex[c >> 4]; out += hex[c & 15];
        } else out += char(c);
    }
    return out + '"';
}

void make_images(Shape shape, std::vector<float>& input, std::vector<float>& accumulator) {
    input.resize(size_t(shape.height) * shape.width * 3);
    accumulator.resize(input.size());
    const float base[3] = {-2.5f, 1.75f, 4.5f};
    for (uint32_t y = 0; y < shape.height; ++y)
        for (uint32_t x = 0; x < shape.width; ++x)
            for (uint32_t c = 0; c < 3; ++c) {
                const size_t i = (size_t(y) * shape.width + x) * 3 + c;
                const int saw = int((17 * x + 29 * y + 13 * c) % 23) - 11;
                float value = base[c] + 0.03125f * float(x) - 0.046875f * float(y)
                              + 0.0625f * float(saw);
                if (x == 0) value -= 0.5f * float(c + 1);
                if (x + 1 == shape.width) value += 1.25f + 0.25f * float(c);
                if (y == 0) value -= 0.375f;
                if (y + 1 == shape.height) value += 2.0f - 0.5f * float(c);
                input[i] = value;
                accumulator[i] = 0.5f + float((11 * x + 7 * y + 5 * c) % 29) * 0.03125f;
            }
}

bool make_coefficients(const CaseResult& test, std::array<float, 90>& coefficients) {
    coefficients.fill(0);
    for (size_t c = 0; c < 3; ++c) {
        if (!test.active[c]) continue;
        double b[4];
        spk::yvv_coeffs(test.sigma, b);
        if (!spk::detail::iir_boundary_coeffs(b, coefficients.data() + 24 + 22 * c)) return false;
        for (size_t j = 0; j < 4; ++j) {
            const float hi = float(b[j]);
            coefficients[8 * c + 2 * j] = hi;
            coefficients[8 * c + 2 * j + 1] = float(b[j] - double(hi));
        }
    }
    return true;
}

bool dispatch_pass(Gpu* gpu, const CaseResult& test, const BufferRef& source,
                   const BufferRef& output, const BufferRef& coefficients,
                   const BufferRef& active, const BufferRef& accumulator,
                   const BufferRef& weights, std::string& error) {
    const uint32_t threads = (test.axis == 0 ? test.shape.width : test.shape.height) * 3;
    // Match Blur::iir's 256-line launches, including the final partial launch.
    constexpr uint32_t step = 256 * 3;
    for (uint32_t first = 0; first < threads; first += step) {
        const uint32_t end = std::min(threads, first + step);
        const uint32_t meta[6] = {
            test.shape.height, test.shape.width, test.accumulate, test.axis, first, end};
        if (!gpu->dispatch("spk_iir_boundary_df_acc",
                {Arg::buf(source), Arg::buf(coefficients), Arg::buf(active),
                 Arg::buf(accumulator), Arg::buf(weights), Arg::inline_bytes(meta, 6),
                 Arg::buf(output)}, end - first, error)) return false;
    }
    return gpu->flush(error);
}

void run_case(Gpu* gpu, const std::vector<float>& input,
              const std::vector<float>& accumulator_values, CaseResult& test) {
    std::array<float, 90> coefficient_values{};
    if (!make_coefficients(test, coefficient_values)) {
        test.error = "candidate boundary coefficient construction failed";
        return;
    }
    const size_t bytes = input.size() * sizeof(float);
    std::vector<float> poison(input.size(), std::numeric_limits<float>::quiet_NaN());
    auto source = gpu->upload(input.data(), bytes, test.error);
    auto separate = gpu->upload(poison.data(), bytes, test.error);
    auto aliased = gpu->upload(input.data(), bytes, test.error);
    auto accumulator = gpu->upload(accumulator_values.data(), bytes, test.error);
    auto coefficients = gpu->upload(coefficient_values.data(), sizeof coefficient_values, test.error);
    auto active = gpu->upload_u32(test.active.data(), test.active.size(), test.error);
    auto weights = gpu->upload(test.weights.data(), sizeof test.weights, test.error);
    if (!source || !separate || !aliased || !accumulator || !coefficients || !active || !weights) return;
    if (source.get() == separate.get() || source.get() == aliased.get() ||
        separate.get() == aliased.get() || accumulator.get() == source.get() ||
        accumulator.get() == separate.get() || accumulator.get() == aliased.get()) {
        test.error = "live source/output/accumulator buffers are not independent";
        return;
    }
    if (!dispatch_pass(gpu, test, source, separate, coefficients, active, accumulator, weights, test.error) ||
        !dispatch_pass(gpu, test, aliased, aliased, coefficients, active, accumulator, weights, test.error)) return;

    std::vector<float> separate_values(input.size()), alias_values(input.size());
    const bool separate_read = gpu->read(separate.get(), 0, separate_values.data(), bytes, test.error);
    std::string alias_error;
    const bool alias_read = gpu->read(aliased.get(), 0, alias_values.data(), bytes, alias_error);
    if (!alias_read) test.error += (test.error.empty() ? "" : "; ") + alias_error;
    if (!separate_read || !alias_read) return;
    test.readback_complete = true;
    test.values = input.size();
    test.bytes_equal = std::memcmp(separate_values.data(), alias_values.data(), bytes) == 0;
    const auto* separate_bytes = reinterpret_cast<const unsigned char*>(separate_values.data());
    const auto* alias_bytes = reinterpret_cast<const unsigned char*>(alias_values.data());
    for (size_t i = 0; i < bytes; ++i) test.different_bytes += separate_bytes[i] != alias_bytes[i];
    for (size_t i = 0; i < input.size(); ++i) {
        const uint32_t a = std::bit_cast<uint32_t>(separate_values[i]);
        const uint32_t b = std::bit_cast<uint32_t>(alias_values[i]);
        if (a != b) {
            if (!test.different_values) {
                test.first_difference = i; test.separate_bits = a; test.alias_bits = b;
            }
            ++test.different_values;
        }
        if (!std::isfinite(separate_values[i]) || !std::isfinite(alias_values[i])) ++test.nonfinite_values;
        else test.max_abs = std::max(test.max_abs, std::abs(double(separate_values[i]) - alias_values[i]));
    }
    test.passed = test.bytes_equal && test.nonfinite_values == 0;
    if (!test.passed) test.error = test.nonfinite_values ? "nonfinite output" : "alias byte mismatch";
}

bool write_report(const char* path, const std::string& device, const std::string& math_mode,
                  const std::vector<CaseResult>& results, const std::string& error) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    out.imbue(std::locale::classic());
    out << std::boolalpha << std::setprecision(17);
    size_t completed = 0, passed = 0, values = 0, different_bytes = 0, nonfinite_values = 0;
    for (const auto& test : results) {
        completed += test.readback_complete;
        passed += test.passed; values += test.values;
        different_bytes += test.different_bytes; nonfinite_values += test.nonfinite_values;
    }
    const bool success = error.empty() && results.size() == kPlannedCases && passed == kPlannedCases;
    out << "{\n  \"schema\":\"iir-boundary-alias-v1\",\n"
        << "  \"kernel\":\"spk_iir_boundary_df_acc\",\n"
        << "  \"input\":\"public_synthetic_asymmetric_signed_hdr\",\n"
        << "  \"shape_order\":\"height,width\",\n"
        << "  \"axis_contract\":\"0=vertical,1=horizontal\",\n"
        << "  \"device\":" << json_string(device) << ",\n"
        << "  \"math_mode\":" << json_string(math_mode) << ",\n"
        << "  \"planned_cases\":" << kPlannedCases << ",\n"
        << "  \"attempted_cases\":" << results.size() << ",\n"
        << "  \"completed_cases\":" << completed << ",\n"
        << "  \"passed_cases\":" << passed << ",\n"
        << "  \"compared_float32_values\":" << values << ",\n"
        << "  \"compared_bytes_per_output\":" << values * sizeof(float) << ",\n"
        << "  \"readback_bytes_both_outputs\":" << values * sizeof(float) * 2 << ",\n"
        << "  \"different_bytes\":" << different_bytes << ",\n"
        << "  \"nonfinite_value_pairs\":" << nonfinite_values << ",\n"
        << "  \"passed\":" << success << ",\n  \"error\":" << json_string(error) << ",\n"
        << "  \"cases\":[\n";
    for (size_t i = 0; i < results.size(); ++i) {
        const auto& test = results[i];
        out << (i ? ",\n" : "") << "    {\"height\":" << test.shape.height
            << ",\"width\":" << test.shape.width << ",\"sigma\":" << test.sigma
            << ",\"axis\":" << test.axis << ",\"accumulate\":" << test.accumulate
            << ",\"active\":[" << test.active[0] << ',' << test.active[1] << ',' << test.active[2]
            << "],\"weights\":[" << test.weights[0] << ',' << test.weights[1] << ',' << test.weights[2]
            << "],\"readback_complete\":" << test.readback_complete
            << ",\"compared_float32_values\":" << test.values
            << ",\"bytes_equal\":" << test.bytes_equal
            << ",\"different_values\":" << test.different_values
            << ",\"different_bytes\":" << test.different_bytes
            << ",\"nonfinite_value_pairs\":" << test.nonfinite_values
            << ",\"max_abs\":" << test.max_abs << ",\"first_difference\":";
        if (test.different_values)
            out << "{\"index\":" << test.first_difference << ",\"separate_bits\":"
                << test.separate_bits << ",\"alias_bits\":" << test.alias_bits << '}';
        else out << "null";
        out << ",\"passed\":" << test.passed << ",\"error\":" << json_string(test.error) << '}';
    }
    out << "\n  ]\n}\n";
    out.close();
    return bool(out);
}
}  // namespace

int main(int argc, char** argv) {
    if (argc != 3) {
        std::fputs("usage: iir_boundary_alias SHADERS OUTPUT_JSON\n", stderr);
        return 2;
    }
    std::string error, device, math_mode;
    std::vector<CaseResult> results;
    results.reserve(kPlannedCases);
    std::unique_ptr<Gpu> gpu(Gpu::create_vulkan(argv[1], error));
    if (gpu) {
        device = gpu->device_name();
        const bool math_ok = gpu->check_math_mode(math_mode);
        std::fprintf(stderr, "IIR alias device: %s\nIIR alias math mode: %s\n",
                     device.c_str(), math_mode.c_str());
        if (!math_ok) error = math_mode;
        else {
            error.clear();
            for (Shape shape : kShapes) {
                std::vector<float> input, accumulator;
                make_images(shape, input, accumulator);
                for (double sigma : kSigmas)
                    for (uint32_t axis : {0u, 1u})
                        for (uint32_t accumulate : {0u, 1u})
                            for (size_t mask = 0; mask < kActive.size(); ++mask) {
                                CaseResult test;
                                test.shape = shape; test.sigma = sigma;
                                test.axis = axis; test.accumulate = accumulate; test.active = kActive[mask];
                                for (size_t c = 0; c < 3; ++c) test.weights[c] = kWeights[(c + mask) % 3];
                                run_case(gpu.get(), input, accumulator, test);
                                results.push_back(test);
                                if (!test.readback_complete) {
                                    error = test.error.empty() ? "incomplete GPU case" : test.error;
                                    goto finished;
                                }
                            }
            }
        }
    } else if (error.empty()) error = "Vulkan device creation failed";
finished:
    size_t passed = 0;
    for (const auto& test : results) passed += test.passed;
    const bool success = error.empty() && results.size() == kPlannedCases && passed == kPlannedCases;
    if (!write_report(argv[2], device, math_mode, results, error)) {
        std::fprintf(stderr, "cannot write alias report: %s\n", argv[2]);
        return 2;
    }
    std::fprintf(stderr, "IIR boundary alias: %zu/%zu passed, %zu planned; %s\n",
                 passed, results.size(), kPlannedCases, device.c_str());
    if (!error.empty()) std::fprintf(stderr, "%s\n", error.c_str());
    return success ? 0 : 1;
}
